import { afterEach, describe, expect, it, vi } from "vitest";
import { CameraApiError } from "./api";
import { CameraConnection, type ConnectionDependencies, type PeerConnectionPort } from "./connection";

const id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const sessionId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
const otherSession = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
const info = {
  apiVersion: 1 as const, deviceId: id, name: "Door", armed: true,
  capabilities: { video: true, audio: true, snapshots: true, personDetection: false },
};
const event = {
  id, deviceId: id, createdAt: "2026-10-04T12:00:00Z", trigger: "manual" as const,
  confidence: null, snapshotPath: `/api/v1/events/${id}/snapshot`,
};
const session = {
  sessionId, answer: { type: "answer" as const, sdp: "v=0\r\n" },
  expiresAt: "2099-01-01T00:00:00Z",
};

class FakePeer implements PeerConnectionPort {
  connectionState: RTCPeerConnectionState = "new";
  iceGatheringState: RTCIceGatheringState = "complete";
  localDescription: RTCSessionDescriptionInit | null = null;
  readonly sender = { replaceTrack: vi.fn().mockResolvedValue(undefined) };
  readonly addTransceiver = vi.fn((_kind: string, _direction: string) => this.sender);
  readonly createOffer = vi.fn().mockResolvedValue({ type: "offer", sdp: "v=0\r\n" });
  readonly setLocalDescription = vi.fn(async (description: RTCSessionDescriptionInit) => {
    this.localDescription = description;
  });
  readonly setRemoteDescription = vi.fn(async () => {
    this.connectionState = "connected"; this.stateListener?.();
  });
  readonly close = vi.fn(() => { this.connectionState = "closed"; this.stateListener?.(); });
  private stateListener: (() => void) | null = null;
  private trackListener: ((track: MediaStreamTrack) => void) | null = null;
  private candidateListener: ((candidate: RTCIceCandidateInit) => void) | null = null;
  onTrack(listener: (track: MediaStreamTrack) => void) { this.trackListener = listener; }
  onStateChange(listener: () => void) { this.stateListener = listener; }
  onCandidate(listener: (candidate: RTCIceCandidateInit) => void) { this.candidateListener = listener; }
  emitCandidate(candidate: RTCIceCandidateInit) { this.candidateListener?.(candidate); }
  emitTrack(track: MediaStreamTrack) { this.trackListener?.(track); }
  fail() { this.connectionState = "failed"; this.stateListener?.(); }
}

class FakeTrack extends EventTarget implements MediaStreamTrack {
  contentHint = "";
  enabled = true;
  id = "test-track";
  label = "Test track";
  muted = false;
  readyState: MediaStreamTrackState = "live";
  onended: MediaStreamTrack["onended"] = null;
  onmute: MediaStreamTrack["onmute"] = null;
  onunmute: MediaStreamTrack["onunmute"] = null;
  constructor(readonly kind: string) { super(); }
  stop = vi.fn(() => { this.readyState = "ended"; });
  clone() { return new FakeTrack(this.kind); }
  applyConstraints() { return Promise.resolve(); }
  getCapabilities() { return {}; }
  getConstraints() { return {}; }
  getSettings() { return {}; }
}

class FakeStream extends EventTarget implements MediaStream {
  readonly id = "test-stream";
  readonly active = true;
  onaddtrack: MediaStream["onaddtrack"] = null;
  onremovetrack: MediaStream["onremovetrack"] = null;
  private tracks: MediaStreamTrack[];
  constructor(track?: MediaStreamTrack) { super(); this.tracks = track ? [track] : []; }
  getTracks() { return [...this.tracks]; }
  getAudioTracks() { return this.tracks.filter((track) => track.kind === "audio"); }
  getVideoTracks() { return this.tracks.filter((track) => track.kind === "video"); }
  getTrackById(id: string) { return this.tracks.find((track) => track.id === id) ?? null; }
  addTrack(track: MediaStreamTrack) { this.tracks.push(track); }
  removeTrack(track: MediaStreamTrack) { this.tracks = this.tracks.filter((item) => item !== track); }
  clone() { const copy = new FakeStream(); this.tracks.forEach((track) => copy.addTrack(track.clone())); return copy; }
}

function setup() {
  const peer = new FakePeer();
  const api = {
    origin: "https://door.nord:8443",
    info: vi.fn().mockResolvedValue(info),
    events: vi.fn().mockResolvedValue({ events: [] }),
    capture: vi.fn().mockResolvedValue(event),
    snapshot: vi.fn().mockResolvedValue(new Blob()),
    createSession: vi.fn().mockResolvedValue(session),
    heartbeat: vi.fn().mockResolvedValue({ expiresAt: session.expiresAt }),
    closeSession: vi.fn().mockResolvedValue(undefined),
    addCandidate: vi.fn().mockResolvedValue(undefined),
  };
  const dependencies: ConnectionDependencies = {
    createPeer: vi.fn(() => peer),
    createStream: () => new FakeStream(),
    getMicrophone: vi.fn().mockRejectedValue(new Error("No microphone")),
  };
  const unauthorized = vi.fn();
  const backgroundError = vi.fn();
  const connection = new CameraConnection(api, id, unauthorized, backgroundError, dependencies);
  return { peer, api, dependencies, unauthorized, backgroundError, connection };
}

afterEach(() => vi.useRealTimers());

describe("direct camera session lifecycle", () => {
  it("negotiates a video-only offer without obtaining a microphone", async () => {
    const { connection, peer, api, dependencies } = setup();
    await connection.connect();
    expect(connection.getSnapshot().connection).toBe("connected");
    expect(peer.addTransceiver).toHaveBeenCalledWith("video", "recvonly");
    expect(api.createSession).toHaveBeenCalledWith(
      { type: "offer", sdp: "v=0\r\n" }, false,
    );
    expect(dependencies.getMicrophone).not.toHaveBeenCalled();
    await connection.stop();
  });
  it("closes local media and the owned server session on disconnect", async () => {
    const { connection, peer, api } = setup();
    await connection.connect();
    const remoteTrack = new FakeTrack("video");
    peer.emitTrack(remoteTrack);
    await connection.disconnect();
    expect(remoteTrack.stop).toHaveBeenCalledOnce();
    expect(peer.close).toHaveBeenCalledOnce();
    expect(api.closeSession).toHaveBeenCalledWith(sessionId);
    expect(connection.getSnapshot().stream).toBeNull();
  });
  it("rejects disarmed cameras and changed device identities before creating media", async () => {
    const { connection, api, dependencies } = setup();
    api.info.mockResolvedValueOnce({ ...info, armed: false });
    await connection.connect();
    expect(connection.getSnapshot().error).toContain("not armed");
    api.info.mockResolvedValueOnce({ ...info, deviceId: otherSession });
    await connection.connect();
    expect(connection.getSnapshot().error).toContain("identity changed");
    expect(dependencies.createPeer).not.toHaveBeenCalled();
  });
  it("releases a session when answer negotiation fails", async () => {
    const { connection, peer, api } = setup();
    peer.setRemoteDescription.mockRejectedValueOnce(new Error("Invalid answer"));
    await connection.connect();
    expect(connection.getSnapshot().connection).toBe("error");
    expect(connection.getSnapshot().error).toBe("Invalid answer");
    expect(api.closeSession).toHaveBeenCalledWith(sessionId);
    expect(peer.close).toHaveBeenCalledOnce();
  });
  it("cleans up a late server response after a superseding disconnect", async () => {
    const { connection, api, peer } = setup();
    let resolve: (value: typeof session) => void = () => {};
    api.createSession.mockReturnValueOnce(new Promise((done) => { resolve = done; }));
    const connecting = connection.connect();
    await vi.waitFor(() => expect(api.createSession).toHaveBeenCalledOnce());
    await connection.disconnect();
    resolve(session);
    await connecting;
    expect(api.createSession.mock.calls[0]).toHaveLength(2);
    expect(api.closeSession).toHaveBeenCalledWith(sessionId);
    expect(peer.close).toHaveBeenCalledOnce();
    expect(connection.getSnapshot().connection).toBe("idle");
  });
  it("reports cleanup failures with the expiry safety net", async () => {
    const { connection, api, backgroundError } = setup();
    await connection.connect();
    api.closeSession.mockRejectedValueOnce(new Error("Offline"));
    await connection.disconnect();
    expect(backgroundError).toHaveBeenCalledWith(expect.stringContaining("30 seconds"));
    expect(connection.getSnapshot().connection).toBe("idle");
  });
  it("supports being stopped and reused by React StrictMode", async () => {
    const { connection, api } = setup();
    await connection.refresh();
    await connection.stop();
    await connection.refresh();
    expect(api.events).toHaveBeenCalledTimes(2);
    expect(connection.getSnapshot().info).toEqual(info);
  });
  it("heartbeats only active sessions and clears authorization after revocation", async () => {
    vi.useFakeTimers();
    const { connection, api, unauthorized } = setup();
    await connection.connect();
    await vi.advanceTimersByTimeAsync(10_000);
    expect(api.heartbeat).toHaveBeenCalledTimes(1);
    api.heartbeat.mockRejectedValueOnce(new CameraApiError("Revoked", 401, "unauthorized"));
    await vi.advanceTimersByTimeAsync(10_000);
    expect(unauthorized).toHaveBeenCalledOnce();
    expect(connection.getSnapshot().connection).toBe("idle");
    await vi.advanceTimersByTimeAsync(30_000);
    expect(api.heartbeat).toHaveBeenCalledTimes(2);
  });
  it("exposes media failures and closes the failed connection", async () => {
    const { connection, peer, api } = setup();
    await connection.connect();
    peer.fail();
    await vi.waitFor(() => expect(api.closeSession).toHaveBeenCalledWith(sessionId));
    expect(connection.getSnapshot().error).toContain("interrupted");
    expect(peer.close).toHaveBeenCalledOnce();
  });
});

describe("microphone privacy", () => {
  it("only requests the mic on explicit action and releases it when disabled", async () => {
    const { connection, dependencies, peer } = setup();
    const track = new FakeTrack("audio");
    vi.mocked(dependencies.getMicrophone).mockResolvedValue(new FakeStream(track));
    await connection.connect(true);
    expect(dependencies.getMicrophone).not.toHaveBeenCalled();
    await connection.setMicrophone(true);
    expect(connection.getSnapshot().microphone).toBe(true);
    expect(peer.sender.replaceTrack).toHaveBeenCalledWith(track);
    await connection.setMicrophone(false);
    expect(track.stop).toHaveBeenCalledOnce();
    expect(peer.sender.replaceTrack).toHaveBeenLastCalledWith(null);
    expect(connection.getSnapshot().microphone).toBe(false);
    await connection.stop();
  });
  it("stops a microphone returned after the connection was closed", async () => {
    const { connection, dependencies } = setup();
    const track = new FakeTrack("audio");
    let resolve: (value: MediaStream) => void = () => {};
    vi.mocked(dependencies.getMicrophone).mockReturnValue(new Promise((done) => { resolve = done; }));
    await connection.connect(true);
    const enabling = connection.setMicrophone(true);
    await connection.disconnect();
    resolve(new FakeStream(track));
    await enabling;
    expect(track.stop).toHaveBeenCalledOnce();
    expect(connection.getSnapshot().microphone).toBe(false);
  });
  it("reports microphone denial and never reports transmission", async () => {
    const { connection } = setup();
    await connection.connect(true);
    await connection.setMicrophone(true);
    expect(connection.getSnapshot().error).toBe("No microphone");
    expect(connection.getSnapshot().microphone).toBe(false);
    expect(connection.getSnapshot().microphonePending).toBe(false);
    await connection.stop();
  });
});

describe("event integrity", () => {
  it("persists a returned event without duplicating it on repeated capture", async () => {
    const { connection } = setup();
    await connection.capture();
    await connection.capture();
    expect(connection.getSnapshot().events).toEqual([event]);
  });
  it("rejects cross-device histories and snapshot events", async () => {
    const { connection, api } = setup();
    api.events.mockResolvedValueOnce({ events: [{ ...event, deviceId: otherSession }] });
    await connection.refresh();
    expect(connection.getSnapshot().events).toEqual([]);
    expect(connection.getSnapshot().error).toContain("different device");
    api.capture.mockResolvedValueOnce({ ...event, deviceId: otherSession });
    await connection.capture();
    expect(connection.getSnapshot().events).toEqual([]);
  });
});

describe("ICE interoperability", () => {
  it("uses only the paired camera's advertised local discovery port", async () => {
    const { connection, api, dependencies } = setup();
    api.info.mockResolvedValueOnce({ ...info, localStunPort: 8444, iceServers: ["stun:other.invalid"] });
    await connection.connect();
    expect(dependencies.createPeer).toHaveBeenCalledWith([{ urls: "stun:door.nord:8444" }]);
    await connection.stop();
  });
  it("preserves IPv6 brackets in the paired camera's discovery URL", async () => {
    const { connection, api, dependencies } = setup();
    api.origin = "https://[fd00::1]:8443";
    api.info.mockResolvedValueOnce({ ...info, localStunPort: 8444 });
    await connection.connect();
    expect(dependencies.createPeer).toHaveBeenCalledWith([{ urls: "stun:[fd00::1]:8444" }]);
    await connection.stop();
  });
  it("does not substitute a public discovery server for older peers", async () => {
    const { connection, dependencies } = setup();
    await connection.connect();
    expect(dependencies.createPeer).toHaveBeenCalledWith([]);
    await connection.stop();
  });
  it("sends the offer without waiting for gathering to complete", async () => {
    const { connection, peer, api } = setup();
    peer.iceGatheringState = "gathering";
    await connection.connect();
    expect(api.createSession).toHaveBeenCalledOnce();
    expect(connection.getSnapshot().connection).toBe("connected");
    await connection.stop();
  });
  it("forwards candidates only to the active owned session", async () => {
    const { connection, peer, api } = setup();
    await connection.connect();
    const candidate = { candidate: "candidate:1 1 udp 1 127.0.0.1 9999 typ host", sdpMid: "0", sdpMLineIndex: 0 };
    peer.emitCandidate(candidate);
    await vi.waitFor(() => expect(api.addCandidate).toHaveBeenCalledWith(sessionId, candidate, expect.any(AbortSignal)));
    await connection.disconnect();
    peer.emitCandidate(candidate);
    expect(api.addCandidate).toHaveBeenCalledOnce();
  });
  it("reports connected media without waiting for every trickled candidate acknowledgment", async () => {
    const { connection, peer, api } = setup();
    let acknowledge: () => void = () => {};
    api.addCandidate.mockReturnValueOnce(new Promise<void>((resolve) => { acknowledge = resolve; }));
    peer.setLocalDescription.mockImplementationOnce(async (description) => {
      peer.localDescription = description;
      peer.emitCandidate({
        candidate: "candidate:1 1 udp 1 127.0.0.1 9999 typ host", sdpMid: "0", sdpMLineIndex: 0,
      });
    });
    await connection.connect();
    expect(connection.getSnapshot().connection).toBe("connected");
    expect(api.addCandidate).toHaveBeenCalledOnce();
    await connection.stop();
    acknowledge();
  });
  it("bounds a connection that never establishes media", async () => {
    vi.useFakeTimers();
    const { connection, peer, api } = setup();
    peer.setRemoteDescription.mockImplementationOnce(async () => {});
    const pending = connection.connect();
    await vi.advanceTimersByTimeAsync(45_000);
    await pending;
    expect(connection.getSnapshot().error).toContain("timed out");
    expect(peer.close).toHaveBeenCalledOnce();
    expect(api.closeSession).toHaveBeenCalledWith(sessionId);
  });
});
