import { createSocket } from "node:dgram";
import { once } from "node:events";
import { RTCPeerConnection as NativePeer, MediaStreamTrack as NativeTrack, RTCRtpCodecParameters,
  Message, parseMessage, methods, classes } from "werift";
import { chromium, expect, type Browser, type Page } from "@playwright/test";

export const CAMERA_ORIGIN = "https://127.0.0.1:8443";
const deviceId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const token = "m".repeat(43);

declare global {
  interface Window {
    lanternSourcePeer: globalThis.RTCPeerConnection;
    lanternSourceTrack: globalThis.MediaStreamTrack;
    lanternSourceTimer: ReturnType<typeof setInterval>;
    lanternTestMicrophone: { requests: number; tracks: globalThis.MediaStreamTrack[] };
    lanternCaptureStub: MediaDevices["getUserMedia"];
    lanternAudioContexts: AudioContext[];
    lanternViewerPeer?: globalThis.RTCPeerConnection;
    lanternPublisherCandidate(candidate: RTCIceCandidateInit): Promise<void>;
  }
}

const config = {
  iceServers: [],
  iceUseIpv4: true,
  iceUseIpv6: false,
  iceAdditionalHostAddresses: ["127.0.0.1"],
  iceLite: false,
  codecs: {
    video: [new RTCRtpCodecParameters({ mimeType: "video/VP8", clockRate: 90_000, payloadType: 96 })],
    audio: [new RTCRtpCodecParameters({ mimeType: "audio/opus", clockRate: 48_000, channels: 2, payloadType: 111 })],
  },
};

function disablePublicDiscovery(peer: NativePeer) {
  // Werift otherwise substitutes Google's STUN server even when iceServers is empty.
  peer.iceTransports.forEach((transport) => { transport.connection.stunServer = undefined; });
}

export class ReferenceCamera {
  private readonly publisher = new NativePeer({
    ...config, iceInterfaceAddresses: { udp4: "127.0.0.1" }, iceLite: true,
  });
  private readonly sessions = new Map<string, { peer: NativePeer; video: NativeTrack; audio?: NativeTrack }>();
  private readonly initialPackets: { video: Buffer[]; audio: Buffer[] } = { video: [], audio: [] };
  private readonly discovery = createSocket("udp4");
  private discoveryBound = false;
  private closed = false;
  private sourceBrowser?: Browser;
  private sourcePage?: Page;
  audioPackets = 0;
  private failures: string[] = [];
  get sessionCount() { return this.sessions.size; }

  async diagnostics() {
    const viewers = await this.page.evaluate(async () => {
      const peer = window.lanternViewerPeer;
      if (!peer) return null;
      return {
        connection: peer.connectionState, ice: peer.iceConnectionState,
        microphone: {
          requests: window.lanternTestMicrophone.requests,
          tracks: window.lanternTestMicrophone.tracks.map((track) => track.readyState),
          contexts: window.lanternAudioContexts.map((context) => context.state),
          senders: peer.getSenders().map((sender) => sender.track?.kind ?? null),
        },
        stats: Array.from((await peer.getStats()).values()),
      };
    });
    return { viewers, peers: await Promise.all([...this.sessions.values()].map(async ({ peer }) => ({
      connection: peer.connectionState, ice: peer.iceConnectionState,
      stats: Array.from((await peer.getStats()).values()),
    }))) };
  }

  constructor(private readonly page: Page) {
    this.publisher.onTrack.subscribe((track) => {
      const kind = track.kind;
      if (kind !== "video" && kind !== "audio") return;
      track.onReceiveRtp.subscribe((packet) => {
        if (this.initialPackets[kind].length < 200) this.initialPackets[kind].push(packet.serialize());
        this.sessions.forEach(({ peer, video, audio }) => {
          const output = kind === "video" ? video : audio;
          if (peer.connectionState === "connected") output?.writeRtp(packet.serialize());
        });
      });
    });
  }

  async install() {
    this.discovery.on("error", (error) => { this.failures.push(error.message); });
    this.discovery.on("message", (packet, source) => {
      if (this.closed || packet.length > 1200) return;
      const request = parseMessage(packet);
      if (!request || request.messageMethod !== methods.BINDING || request.messageClass !== classes.REQUEST) return;
      const response = new Message(methods.BINDING, classes.RESPONSE, request.transactionId, [
        ["XOR-MAPPED-ADDRESS", [source.address, source.port]],
      ]).addFingerprint();
      this.discovery.send(response.bytes, source.port, source.address, (error) => {
        if (error) this.failures.push(error.message);
      });
    });
    const listening = once(this.discovery, "listening");
    this.discovery.bind(0, "127.0.0.1");
    await listening;
    this.discoveryBound = true;
    const address = this.discovery.address();
    if (typeof address === "string") throw new Error("Reference discovery did not bind UDP.");
    await this.page.addInitScript(() => {
      const NativeConnection = RTCPeerConnection;
      window.RTCPeerConnection = class extends NativeConnection {
        constructor(configuration?: RTCConfiguration) {
          super(configuration);
          window.lanternViewerPeer = this;
        }
      };
      window.lanternTestMicrophone = { requests: 0, tracks: [] };
      window.lanternAudioContexts = [];
      window.lanternCaptureStub = async () => {
        window.lanternTestMicrophone.requests++;
        const audio = new AudioContext();
        window.lanternAudioContexts.push(audio);
        const oscillator = audio.createOscillator();
        const destination = audio.createMediaStreamDestination();
        oscillator.connect(destination); oscillator.start();
        await audio.resume();
        window.lanternTestMicrophone.tracks.push(...destination.stream.getTracks());
        return destination.stream;
      };
      Object.defineProperty(Object.getPrototypeOf(navigator.mediaDevices), "getUserMedia", {
        configurable: true, value: window.lanternCaptureStub,
      });
    });
    // The camera encoder is fixed; only the actual viewer follows the platform under test.
    this.sourceBrowser = await chromium.launch({ args: ["--autoplay-policy=no-user-gesture-required"] });
    const sourcePage = await this.sourceBrowser.newPage();
    this.sourcePage = sourcePage;
    await sourcePage.exposeFunction("lanternPublisherCandidate", async (candidate: RTCIceCandidateInit) => {
      try { await this.publisher.addIceCandidate(candidate); }
      catch (error) { this.failures.push(error instanceof Error ? error.message : String(error)); }
    });
    await this.page.route(`${CAMERA_ORIGIN}/**`, async (route) => {
      const request = route.request();
      const path = new URL(request.url()).pathname;
      const headers = {
        "Access-Control-Allow-Origin": "http://127.0.0.1:4173",
        "Access-Control-Allow-Headers": "Authorization, Content-Type",
        "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
        "Cache-Control": "no-store",
      };
      if (request.method() === "OPTIONS") {
        await route.fulfill({ status: 204, headers }); return;
      }
      let body: unknown;
      let status = 200;
      if (path === "/api/v1/info") {
        body = { apiVersion: 1, deviceId, name: "Media camera", armed: true, localStunPort: address.port,
          capabilities: { video: true, audio: true, snapshots: true, personDetection: false } };
      } else if (path === "/api/v1/pair") {
        body = { deviceId, token, expiresAt: "2099-01-01T00:00:00Z" }; status = 201;
      } else {
        expect(request.headers().authorization).toBe(`Bearer ${token}`);
        if (path === "/api/v1/events") body = { events: [] };
        else if (path === "/api/v1/sessions" && request.method() === "POST") {
          const payload: { offer: { type: "offer"; sdp: string }; audio: boolean } = request.postDataJSON();
          const peer = new NativePeer(config);
          const video = new NativeTrack({ kind: "video" });
          const audio = payload.audio ? new NativeTrack({ kind: "audio" }) : undefined;
          peer.addTrack(video);
          if (audio) peer.addTrack(audio);
          peer.onTrack.subscribe((track) => {
            if (track.kind === "audio") track.onReceiveRtp.subscribe(() => this.audioPackets++);
          });
          peer.connectionStateChange.subscribe((state) => {
            if (state === "connected") {
              this.initialPackets.video.forEach((packet) => video.writeRtp(packet));
              this.initialPackets.audio.forEach((packet) => audio?.writeRtp(packet));
            }
          });
          await peer.setRemoteDescription(payload.offer);
          disablePublicDiscovery(peer);
          await peer.setLocalDescription(await peer.createAnswer());
          if (!peer.localDescription) throw new Error("Reference camera did not create an answer.");
          const sessionId = crypto.randomUUID();
          this.sessions.set(sessionId, { peer, video, audio });
          body = { sessionId, answer: peer.localDescription, expiresAt: "2099-01-01T00:00:00Z" };
          status = 201;
        } else if (path.startsWith("/api/v1/sessions/")) {
          const segments = path.split("/");
          const id = segments[4];
          const session = id ? this.sessions.get(id) : undefined;
          if (!session) { status = 404; body = { error: { code: "not_found", message: "Session not found" } }; }
          else if (segments[5] === "ice" && request.method() === "POST") {
            await session.peer.addIceCandidate(request.postDataJSON());
            await route.fulfill({ status: 204, headers }); return;
          } else if (request.method() === "DELETE") {
            await session.peer.close(); session.video.stop(); session.audio?.stop(); this.sessions.delete(id!);
            await route.fulfill({ status: 204, headers }); return;
          } else body = { expiresAt: "2099-01-01T00:00:00Z" };
        } else { status = 404; body = { error: { code: "not_found", message: "Unknown test endpoint" } }; }
      }
      await route.fulfill({ status, headers, contentType: "application/json", body: JSON.stringify(body) });
    });
    await this.page.goto("/");
    expect(await this.page.evaluate(() => (
      navigator.mediaDevices.getUserMedia === window.lanternCaptureStub
    ))).toBe(true);
    await sourcePage.goto("http://127.0.0.1:4173");
    const offer = await sourcePage.evaluate(async () => {
      const peer = new RTCPeerConnection({ iceServers: [] });
      window.lanternSourcePeer = peer;
      peer.onicecandidate = (event) => {
        if (event.candidate) void window.lanternPublisherCandidate(event.candidate.toJSON());
      };
      const canvas = document.createElement("canvas");
      canvas.width = 320; canvas.height = 180;
      const drawing = canvas.getContext("2d");
      if (!drawing) throw new Error("Test source canvas is unavailable");
      drawing.fillStyle = "rgb(30,150,80)"; drawing.fillRect(0, 0, 320, 180);
      const stream = canvas.captureStream(5);
      const track = stream.getVideoTracks()[0];
      if (!track) throw new Error("Test source video is unavailable");
      window.lanternSourceTrack = track;
      window.lanternSourceTimer = setInterval(() => {
        drawing.fillStyle = `rgb(30,${100 + Math.floor(Date.now() / 100) % 100},80)`;
        drawing.fillRect(0, 0, 320, 180);
      }, 200);
      peer.addTrack(track, stream);
      const audio = new AudioContext();
      window.lanternAudioContexts = [audio];
      const oscillator = audio.createOscillator();
      const destination = audio.createMediaStreamDestination();
      oscillator.connect(destination); oscillator.start();
      await audio.resume();
      const audioTrack = destination.stream.getAudioTracks()[0];
      if (!audioTrack) throw new Error("Test source audio is unavailable");
      peer.addTrack(audioTrack, destination.stream);
      await peer.setLocalDescription(await peer.createOffer());
      if (!peer.localDescription) throw new Error("No publisher offer.");
      return peer.localDescription.toJSON();
    });
    if (offer.type !== "offer" || !offer.sdp) throw new Error("Invalid publisher offer.");
    await this.publisher.setRemoteDescription({ type: "offer", sdp: offer.sdp });
    disablePublicDiscovery(this.publisher);
    await this.publisher.setLocalDescription(await this.publisher.createAnswer());
    const answer = this.publisher.localDescription;
    if (!answer) throw new Error("Reference receiver did not create an answer.");
    await sourcePage.evaluate((answer) => window.lanternSourcePeer.setRemoteDescription(answer), answer);
    await expect.poll(() => this.initialPackets.video.length, { timeout: 15_000 }).toBeGreaterThan(0);
    await expect.poll(() => this.initialPackets.audio.length, { timeout: 15_000 }).toBeGreaterThan(0);
    expect(this.failures).toEqual([]);
    await this.page.getByRole("button", { name: "Pair your first camera" }).click();
    await this.page.getByLabel("Camera HTTPS address").fill(CAMERA_ORIGIN);
    await this.page.getByLabel("Six-digit pairing code").fill("123456");
    await this.page.getByRole("button", { name: "Pair camera", exact: true }).click();
    await expect(this.page.getByRole("article", { name: "Media camera camera" })).toBeVisible();
  }

  async close() {
    this.closed = true;
    if (this.discoveryBound) this.discovery.close();
    await Promise.all([...this.sessions.values()].map(async ({ peer, video, audio }) => {
      await peer.close(); video.stop(); audio?.stop();
    }));
    this.sessions.clear();
    await this.publisher.close();
    if (this.sourcePage && !this.sourcePage.isClosed()) {
      await this.sourcePage.evaluate(async () => {
        window.lanternSourcePeer?.close();
        window.lanternSourcePeer?.getSenders().forEach((sender) => sender.track?.stop());
        window.lanternSourceTrack?.stop();
        clearInterval(window.lanternSourceTimer);
        await Promise.all(window.lanternAudioContexts.map((context) => context.close()));
      });
    }
    await this.sourceBrowser?.close();
    if (!this.page.isClosed()) {
      await this.page.evaluate(async () => {
        window.lanternTestMicrophone.tracks.forEach((track) => track.stop());
        await Promise.all(window.lanternAudioContexts.map((context) => context.close()));
      });
    }
    expect(this.failures).toEqual([]);
  }
}
