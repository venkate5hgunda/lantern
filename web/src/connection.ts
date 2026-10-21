import { PeerApi, isUnauthorized } from "./api";
import { describeError, type CameraEvent, type PeerInfo } from "./protocol";

export interface PeerConnectionPort {
  readonly connectionState: RTCPeerConnectionState;
  readonly localDescription: RTCSessionDescriptionInit | null;
  addTransceiver(kind: "video" | "audio", direction: RTCRtpTransceiverDirection): {
    replaceTrack(track: MediaStreamTrack | null): Promise<void>;
  };
  createOffer(): Promise<RTCSessionDescriptionInit>;
  setLocalDescription(offer: RTCSessionDescriptionInit): Promise<void>;
  setRemoteDescription(answer: RTCSessionDescriptionInit): Promise<void>;
  onTrack(listener: (track: MediaStreamTrack) => void): void;
  onStateChange(listener: () => void): void;
  onCandidate(listener: (candidate: RTCIceCandidateInit) => void): void;
  close(): void;
}

function browserPeer(iceServers: RTCIceServer[]): PeerConnectionPort {
  if (!globalThis.RTCPeerConnection) throw new Error("This browser does not support WebRTC.");
  const pc = new RTCPeerConnection({ iceServers });
  return {
    get connectionState() { return pc.connectionState; },
    get localDescription() { return pc.localDescription; },
    addTransceiver(kind, direction) {
      const transceiver = pc.addTransceiver(kind, { direction });
      return { replaceTrack: (track) => transceiver.sender.replaceTrack(track) };
    },
    createOffer: () => pc.createOffer(),
    setLocalDescription: (description) => pc.setLocalDescription(description),
    setRemoteDescription: (description) => pc.setRemoteDescription(description),
    onTrack: (listener) => { pc.ontrack = (event) => listener(event.track); },
    onStateChange: (listener) => {
      pc.addEventListener("connectionstatechange", listener);
      pc.addEventListener("iceconnectionstatechange", listener);
    },
    onCandidate: (listener) => {
      pc.onicecandidate = (event) => { if (event.candidate?.candidate) listener(event.candidate.toJSON()); };
    },
    close: () => pc.close(),
  };
}

export function abortable<T>(operation: Promise<T>, signal: AbortSignal): Promise<T> {
  if (signal.aborted) return Promise.reject(signal.reason);
  return new Promise<T>((resolve, reject) => {
    const abort = () => reject(signal.reason);
    signal.addEventListener("abort", abort, { once: true });
    operation.then(resolve, reject).finally(() => signal.removeEventListener("abort", abort));
  });
}

type Transport = Pick<PeerApi,
  "origin" | "info" | "events" | "capture" | "snapshot" | "createSession" | "heartbeat" | "closeSession" | "addCandidate"
>;
export interface ConnectionDependencies {
  createPeer(iceServers: RTCIceServer[]): PeerConnectionPort;
  createStream(): MediaStream;
  getMicrophone(): Promise<MediaStream>;
}

const browserDependencies: ConnectionDependencies = {
  createPeer: browserPeer,
  createStream: () => new MediaStream(),
  getMicrophone: () => {
    if (!navigator.mediaDevices?.getUserMedia) {
      return Promise.reject(new Error("Microphone access requires a supported browser and a trusted HTTPS page."));
    }
    return navigator.mediaDevices.getUserMedia({
      video: false,
      audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
    });
  },
};

export interface CameraState {
  connection: "idle" | "connecting" | "connected" | "error";
  info: PeerInfo | null;
  stream: MediaStream | null;
  audio: boolean;
  microphone: boolean;
  microphonePending: boolean;
  loadingEvents: boolean;
  snapshotting: boolean;
  events: CameraEvent[];
  error: string | null;
}

export class CameraConnection {
  private state: CameraState = {
    connection: "idle", info: null, stream: null, audio: false,
    microphone: false, microphonePending: false, loadingEvents: false,
    snapshotting: false, events: [], error: null,
  };
  private readonly listeners = new Set<() => void>();
  private peer: PeerConnectionPort | null = null;
  private remote: MediaStream | null = null;
  private microphone: MediaStream | null = null;
  private audioSender: ReturnType<PeerConnectionPort["addTransceiver"]> | null = null;
  private sessionId: string | null = null;
  private operation: AbortController | null = null;
  private refreshOperation: AbortController | null = null;
  private captureOperation: AbortController | null = null;
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  private generation = 0;
  private microphoneGeneration = 0;
  private heartbeatPending = false;

  constructor(
    private readonly api: Transport,
    private readonly deviceId: string,
    private readonly unauthorized: () => void,
    private readonly backgroundError: (message: string) => void,
    private readonly dependencies: ConnectionDependencies = browserDependencies,
  ) {}

  getSnapshot = (): CameraState => this.state;
  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  private update(patch: Partial<CameraState>) {
    this.state = { ...this.state, ...patch };
    this.listeners.forEach((listener) => listener());
  }

  private report(error: unknown) {
    if (isUnauthorized(error)) this.unauthorized();
    this.update({ error: describeError(error) });
  }

  clearError() { this.update({ error: null }); }

  async refresh(): Promise<void> {
    this.refreshOperation?.abort();
    const operation = new AbortController();
    this.refreshOperation = operation;
    this.update({ loadingEvents: true });
    try {
      const info = await this.api.info(operation.signal);
      if (info.deviceId !== this.deviceId) throw new Error("This address now belongs to a different device. Remove it and pair again.");
      if (operation.signal.aborted) return;
      this.update({ info });
      const { events } = await this.api.events(operation.signal);
      if (events.some((event) => event.deviceId !== this.deviceId)) {
        throw new Error("Camera returned events belonging to a different device.");
      }
      if (!operation.signal.aborted) this.update({ events });
    } catch (error) {
      if (!operation.signal.aborted) this.report(error);
    } finally {
      if (this.refreshOperation === operation) {
        this.refreshOperation = null;
        this.update({ loadingEvents: false });
      }
    }
  }

  private cleanupLocal(): string | null {
    this.operation?.abort();
    this.operation = null;
    if (this.heartbeatTimer) clearInterval(this.heartbeatTimer);
    this.heartbeatTimer = null;
    this.heartbeatPending = false;
    ++this.microphoneGeneration;
    this.microphone?.getTracks().forEach((track) => track.stop());
    this.microphone = null;
    this.audioSender = null;
    const peer = this.peer;
    this.peer = null;
    peer?.close();
    this.remote?.getTracks().forEach((track) => track.stop());
    this.remote = null;
    const id = this.sessionId;
    this.sessionId = null;
    return id;
  }

  private async releaseSession(id: string | null): Promise<void> {
    if (!id) return;
    try { await this.api.closeSession(id); }
    catch (error) {
      if (isUnauthorized(error)) this.unauthorized();
      this.backgroundError(`Could not confirm camera session closure: ${describeError(error)} Abandoned sessions expire after 30 seconds.`);
    }
  }

  async disconnect(): Promise<void> {
    ++this.generation;
    const id = this.cleanupLocal();
    this.update({
      connection: "idle", stream: null, audio: false,
      microphone: false, microphonePending: false,
    });
    await this.releaseSession(id);
  }

  async stop(): Promise<void> {
    this.refreshOperation?.abort();
    this.captureOperation?.abort();
    this.refreshOperation = null;
    this.captureOperation = null;
    this.update({ loadingEvents: false, snapshotting: false });
    await this.disconnect();
  }

  private startHeartbeat(generation: number, id: string, signal: AbortSignal) {
    this.heartbeatTimer = setInterval(() => {
      if (generation !== this.generation || this.heartbeatPending) return;
      this.heartbeatPending = true;
      void this.api.heartbeat(id, signal).catch((error: unknown) => {
        if (generation === this.generation && !signal.aborted) {
          this.report(error);
          void this.disconnect();
        }
      }).finally(() => {
        if (generation === this.generation) this.heartbeatPending = false;
      });
    }, 10_000);
  }

  async connect(audio = false): Promise<void> {
    const generation = ++this.generation;
    const oldSession = this.cleanupLocal();
    this.update({
      connection: "connecting", stream: null, audio: false, microphone: false,
      microphonePending: false, error: null,
    });
    await this.releaseSession(oldSession);
    if (generation !== this.generation) return;
    const operation = new AbortController();
    this.operation = operation;
    const timeout = setTimeout(() => operation.abort(new Error("Camera connection timed out.")), 45_000);
    try {
      const info = await this.api.info(operation.signal);
      if (info.deviceId !== this.deviceId) throw new Error("Camera identity changed. Remove this endpoint and pair again.");
      if (!info.armed || !info.capabilities.video) throw new Error("This camera is not armed for video.");
      if (audio && !info.capabilities.audio) throw new Error("This camera does not support audio.");
      if (generation !== this.generation) return;
      if (operation.signal.aborted) throw operation.signal.reason;
      this.update({ info });
      const iceServers: RTCIceServer[] = info.localStunPort === undefined ? [] : [{
        urls: `stun:${new URL(this.api.origin).hostname}:${info.localStunPort}`,
      }];
      const peer = this.dependencies.createPeer(iceServers);
      this.peer = peer;
      const remote = this.dependencies.createStream();
      this.remote = remote;
      peer.onTrack((track) => {
        if (generation !== this.generation) { track.stop(); return; }
        if (!remote.getTracks().includes(track)) remote.addTrack(track);
        this.update({ stream: remote });
      });
      peer.addTransceiver("video", "recvonly");
      if (audio) this.audioSender = peer.addTransceiver("audio", "sendrecv");
      const candidates: RTCIceCandidateInit[] = [];
      let flushingCandidates = false;
      const flushCandidates = async () => {
        if (flushingCandidates || !this.sessionId || generation !== this.generation) return;
        flushingCandidates = true;
        try {
          while (candidates.length && generation === this.generation && this.sessionId) {
            const candidate = candidates.shift();
            if (candidate) await this.api.addCandidate(this.sessionId, candidate, operation.signal);
          }
        } catch (error) {
          if (generation === this.generation && !operation.signal.aborted) {
            this.report(error);
            await this.disconnect();
          }
        } finally { flushingCandidates = false; }
      };
      peer.onCandidate((candidate) => {
        if (generation !== this.generation || operation.signal.aborted) return;
        if (candidates.length >= 64) {
          this.report(new Error("Browser generated too many pending media candidates."));
          void this.disconnect();
          return;
        }
        candidates.push(candidate);
        void flushCandidates();
      });
      let resolveConnected: () => void = () => {};
      let rejectConnected: (error: Error) => void = () => {};
      const connected = new Promise<void>((resolve, reject) => {
        resolveConnected = resolve; rejectConnected = reject;
      });
      // Observe immediately so a failed connection cannot reject an unhandled promise during negotiation.
      const observedConnection = connected.then(() => ({ error: null }), (error: Error) => ({ error }));
      peer.onStateChange(() => {
        if (generation !== this.generation) return;
        if (peer.connectionState === "connected") resolveConnected();
        if (["failed", "disconnected", "closed"].includes(peer.connectionState)) {
          const error = new Error("The camera media connection was interrupted. Reconnect when it is reachable.");
          rejectConnected(error);
          if (this.state.connection === "connected") {
            this.report(error);
            void this.disconnect();
          }
        }
      });
      const offer = await abortable(peer.createOffer(), operation.signal);
      await abortable(peer.setLocalDescription(offer), operation.signal);
      const gatheredOffer = peer.localDescription;
      if (!gatheredOffer?.sdp) throw new Error("Browser did not gather a usable media address.");
      // Observe the bounded creation response even after cancellation so its session can be closed.
      const response = await this.api.createSession(gatheredOffer, audio);
      if (generation !== this.generation || operation.signal.aborted) {
        await this.releaseSession(response.sessionId);
        if (generation === this.generation) throw operation.signal.reason;
        return;
      }
      this.sessionId = response.sessionId;
      this.startHeartbeat(generation, response.sessionId, operation.signal);
      await abortable(peer.setRemoteDescription(response.answer), operation.signal);
      void flushCandidates();
      if (generation !== this.generation) return;
      if (peer.connectionState === "connected") resolveConnected();
      const result = await abortable(observedConnection, operation.signal);
      if (result.error) throw result.error;
      if (generation === this.generation) this.update({ connection: "connected", audio });
    } catch (error) {
      if (generation !== this.generation) return;
      const id = this.cleanupLocal();
      this.report(error);
      this.update({
        connection: "error", stream: null, audio: false,
        microphone: false, microphonePending: false,
      });
      await this.releaseSession(id);
    } finally {
      clearTimeout(timeout);
    }
  }

  async setMicrophone(enabled: boolean): Promise<void> {
    const generation = this.generation;
    const microphoneGeneration = ++this.microphoneGeneration;
    const sender = this.audioSender;
    this.microphone?.getTracks().forEach((track) => track.stop());
    this.microphone = null;
    this.update({ microphone: false, microphonePending: enabled });
    if (!enabled) {
      try { await sender?.replaceTrack(null); }
      catch (error) { if (generation === this.generation) this.report(error); }
      return;
    }
    if (this.state.connection !== "connected" || !this.state.audio || !sender) {
      this.update({ microphonePending: false, error: "Connect an audio session before enabling your microphone." });
      return;
    }
    let captured: MediaStream | null = null;
    try {
      captured = await this.dependencies.getMicrophone();
      if (generation !== this.generation || microphoneGeneration !== this.microphoneGeneration) {
        captured.getTracks().forEach((track) => track.stop());
        return;
      }
      const track = captured.getAudioTracks()[0];
      if (!track) throw new Error("No usable microphone was returned by the browser.");
      this.microphone = captured;
      await sender.replaceTrack(track);
      if (generation === this.generation && microphoneGeneration === this.microphoneGeneration) {
        this.update({ microphone: true });
      } else {
        captured.getTracks().forEach((item) => item.stop());
      }
    } catch (error) {
      captured?.getTracks().forEach((track) => track.stop());
      if (this.microphone === captured) this.microphone = null;
      if (generation === this.generation && microphoneGeneration === this.microphoneGeneration) this.report(error);
    } finally {
      if (generation === this.generation && microphoneGeneration === this.microphoneGeneration) {
        this.update({ microphonePending: false });
      }
    }
  }

  async capture(): Promise<void> {
    if (this.captureOperation) return;
    const operation = new AbortController();
    this.captureOperation = operation;
    this.update({ snapshotting: true });
    try {
      const event = await this.api.capture(operation.signal);
      if (event.deviceId !== this.deviceId) throw new Error("Snapshot came from a different device.");
      if (!operation.signal.aborted) {
        const events = [event, ...this.state.events.filter((item) => item.id !== event.id)].slice(0, 50);
        this.update({ events });
      }
    } catch (error) {
      if (!operation.signal.aborted) this.report(error);
    } finally {
      if (this.captureOperation === operation) {
        this.captureOperation = null;
        this.update({ snapshotting: false });
      }
    }
  }

  async snapshot(id: string, signal: AbortSignal): Promise<Blob> {
    if (!this.state.events.some((event) => event.id === id)) throw new Error("Unknown event.");
    try { return await this.api.snapshot(id, signal); }
    catch (error) {
      if (!signal.aborted) this.report(error);
      throw error;
    }
  }
}
