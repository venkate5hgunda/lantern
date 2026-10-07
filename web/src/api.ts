import { z } from "zod";
import {
  ErrorSchema, EventSchema, EventsSchema, HeartbeatSchema, InfoSchema,
  PairSchema, SessionSchema, normalizeOrigin,
} from "./protocol";

export class CameraApiError extends Error {
  constructor(message: string, public readonly status: number, public readonly code: string) {
    super(message);
    this.name = "CameraApiError";
  }
}

export function isUnauthorized(error: unknown): boolean {
  return error instanceof CameraApiError && error.status === 401;
}

export async function readBoundedBody(response: Response, limit: number): Promise<Uint8Array> {
  const declared = response.headers.get("content-length");
  if (declared && Number(declared) > limit) {
    await response.body?.cancel();
    throw new Error("Camera response exceeds the supported size.");
  }
  if (!response.body) return new Uint8Array();
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > limit) {
        await reader.cancel();
        throw new Error("Camera response exceeds the supported size.");
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const result = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) { result.set(chunk, offset); offset += chunk.byteLength; }
  return result;
}

export class PeerApi {
  readonly origin: string;

  constructor(origin: string, private readonly token: string | null = null) {
    this.origin = normalizeOrigin(origin);
  }

  private async request<T>(
    path: string, schema: z.ZodType<T>, options: RequestInit = {},
    signal?: AbortSignal, timeoutMs = 15_000,
  ): Promise<T> {
    const abort = new AbortController();
    const forwardAbort = () => abort.abort(signal?.reason);
    if (signal?.aborted) forwardAbort();
    else signal?.addEventListener("abort", forwardAbort, { once: true });
    const timer = setTimeout(
      () => abort.abort(new Error("The camera did not respond before the timeout.")),
      timeoutMs,
    );
    try {
      let response: Response;
      try {
        response = await fetch(`${this.origin}${path}`, {
          ...options,
          headers: {
            ...(this.token ? { Authorization: `Bearer ${this.token}` } : {}),
            ...(options.body ? { "Content-Type": "application/json" } : {}),
          },
          credentials: "omit",
          cache: "no-store",
          redirect: "error",
          referrerPolicy: "no-referrer",
          signal: abort.signal,
        });
      } catch (error) {
        if (abort.signal.aborted) throw abort.signal.reason;
        throw new Error(
          "Cannot reach the camera. Check its HTTPS certificate trust, allowed viewer origin, " +
          "address and LAN/Meshnet connection.",
          { cause: error },
        );
      }
      if (response.status === 204) return schema.parse(undefined);
      const bytes = await readBoundedBody(response, 1_048_576);
      let json: unknown;
      try { json = JSON.parse(new TextDecoder().decode(bytes)); }
      catch { throw new Error(`Camera returned an invalid JSON response (HTTP ${response.status}).`); }
      if (!response.ok) {
        const parsed = ErrorSchema.safeParse(json);
        if (!parsed.success) throw new Error(`Camera returned an invalid error response (HTTP ${response.status}).`);
        throw new CameraApiError(parsed.data.error.message, response.status, parsed.data.error.code);
      }
      const parsed = schema.safeParse(json);
      if (!parsed.success) throw new Error("Camera returned data incompatible with Lantern protocol v1.");
      return parsed.data;
    } finally {
      clearTimeout(timer);
      signal?.removeEventListener("abort", forwardAbort);
    }
  }

  info(signal?: AbortSignal) { return this.request("/api/v1/info", InfoSchema, {}, signal); }

  pair(code: string, viewerName: string, signal?: AbortSignal) {
    const cleanName = viewerName.trim();
    if (!/^\d{6}$/.test(code)) throw new Error("Enter the six-digit pairing code shown on the camera.");
    if (!cleanName || cleanName.length > 80) throw new Error("Viewer name must contain 1 to 80 characters.");
    return this.request("/api/v1/pair", PairSchema, {
      method: "POST", body: JSON.stringify({ code, viewerName: cleanName }),
    }, signal);
  }

  createSession(offer: RTCSessionDescriptionInit, audio: boolean, signal?: AbortSignal) {
    if (offer.type !== "offer" || !offer.sdp || offer.sdp.length > 65_536) {
      throw new Error("Browser generated an unsupported WebRTC offer.");
    }
    return this.request("/api/v1/sessions", SessionSchema, {
      method: "POST", body: JSON.stringify({ offer, audio }),
    }, signal, 20_000);
  }

  heartbeat(id: string, signal?: AbortSignal) {
    return this.request(`/api/v1/sessions/${z.string().uuid().parse(id)}`, HeartbeatSchema,
      { method: "PUT" }, signal);
  }

  addCandidate(id: string, candidate: RTCIceCandidateInit, signal?: AbortSignal) {
    const parsed = z.object({
      candidate: z.string().min(1).max(4096),
      sdpMid: z.string().max(64).nullable(),
      sdpMLineIndex: z.number().int().min(0).max(1),
    }).parse(candidate);
    return this.request(`/api/v1/sessions/${z.string().uuid().parse(id)}/ice`, z.void(),
      { method: "POST", body: JSON.stringify(parsed) }, signal);
  }

  async closeSession(id: string): Promise<void> {
    try {
      await this.request(`/api/v1/sessions/${z.string().uuid().parse(id)}`, z.void(),
        { method: "DELETE", keepalive: true }, undefined, 5_000);
    } catch (error) {
      if (error instanceof CameraApiError && error.status === 404) return;
      throw error;
    }
  }

  events(signal?: AbortSignal) { return this.request("/api/v1/events", EventsSchema, {}, signal); }

  capture(signal?: AbortSignal) {
    return this.request("/api/v1/events", EventSchema, { method: "POST" }, signal);
  }

  async snapshot(id: string, signal?: AbortSignal): Promise<Blob> {
    const abort = new AbortController();
    const forwardAbort = () => abort.abort(signal?.reason);
    if (signal?.aborted) forwardAbort();
    else signal?.addEventListener("abort", forwardAbort, { once: true });
    const timer = setTimeout(() => abort.abort(new Error("Snapshot retrieval timed out.")), 15_000);
    try {
      let response: Response;
      try {
        response = await fetch(`${this.origin}/api/v1/events/${z.string().uuid().parse(id)}/snapshot`, {
          headers: this.token ? { Authorization: `Bearer ${this.token}` } : {},
          credentials: "omit", cache: "no-store", redirect: "error",
          referrerPolicy: "no-referrer", signal: abort.signal,
        });
      } catch (error) {
        if (abort.signal.aborted) throw abort.signal.reason;
        throw new Error("Could not retrieve the snapshot. Check camera connectivity and certificate trust.", { cause: error });
      }
      if (!response.ok) {
        const bytes = await readBoundedBody(response, 8_192);
        const parsed = ErrorSchema.safeParse(JSON.parse(new TextDecoder().decode(bytes)));
        if (!parsed.success) throw new Error(`Invalid snapshot error response (HTTP ${response.status}).`);
        throw new CameraApiError(parsed.data.error.message, response.status, parsed.data.error.code);
      }
      if (response.headers.get("content-type")?.split(";")[0] !== "image/jpeg") {
        await response.body?.cancel();
        throw new Error("Camera returned an unsupported snapshot format.");
      }
      const bytes = await readBoundedBody(response, 5_242_880);
      if (bytes.length < 3 || bytes[0] !== 0xff || bytes[1] !== 0xd8 || bytes[2] !== 0xff) {
        throw new Error("Camera returned invalid JPEG data.");
      }
      return new Blob([new Uint8Array(bytes)], { type: "image/jpeg" });
    } finally {
      clearTimeout(timer);
      signal?.removeEventListener("abort", forwardAbort);
    }
  }
}
