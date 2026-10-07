import { afterEach, describe, expect, it, vi } from "vitest";
import { PeerApi, readBoundedBody } from "./api";

const origin = "https://door.nord:8443";
const id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const info = { apiVersion: 1, deviceId: id, name: "Door", armed: true,
  capabilities: { video: true, audio: true, snapshots: true, personDetection: false } };
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), {
  status, headers: { "Content-Type": "application/json" },
});

afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); });

describe("authenticated peer API", () => {
  it("uses bearer headers, no credentials/caching and no redirects", async () => {
    const fetch = vi.fn().mockResolvedValue(json(info));
    vi.stubGlobal("fetch", fetch);
    await new PeerApi(origin, "a".repeat(43)).info();
    expect(fetch).toHaveBeenCalledWith(`${origin}/api/v1/info`, expect.objectContaining({
      headers: { Authorization: `Bearer ${"a".repeat(43)}` },
      cache: "no-store", credentials: "omit", redirect: "error",
    }));
    expect(fetch.mock.calls[0]?.[0]).not.toContain("token=");
  });
  it("rejects invalid input before sending pairing requests", () => {
    const fetch = vi.fn();
    vi.stubGlobal("fetch", fetch);
    expect(() => new PeerApi(origin).pair("123", "Viewer")).toThrow("six-digit");
    expect(() => new PeerApi(origin).pair("123456", "  ")).toThrow("Viewer name");
    expect(fetch).not.toHaveBeenCalled();
  });
  it("preserves camera authorization errors", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json(
      { error: { code: "unauthorized", message: "Grant was revoked." } }, 401,
    )));
    await expect(new PeerApi(origin).events()).rejects.toMatchObject({
      status: 401, code: "unauthorized", message: "Grant was revoked.",
    });
  });
  it("rejects incompatible successful replies", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({ ...info, apiVersion: 2 })));
    await expect(new PeerApi(origin).info()).rejects.toThrow("incompatible");
  });
  it("never follows an untrusted snapshot path", async () => {
    const fetch = vi.fn();
    vi.stubGlobal("fetch", fetch);
    await expect(new PeerApi(origin).snapshot("../secret")).rejects.toThrow();
    expect(fetch).not.toHaveBeenCalled();
  });
  it("rejects disguised/non-JPEG snapshot data", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("not a jpeg", {
      headers: { "Content-Type": "image/jpeg" },
    })));
    await expect(new PeerApi(origin).snapshot(id)).rejects.toThrow("invalid JPEG");
  });
  it("supports already-closed sessions but exposes other closure failures", async () => {
    const fetch = vi.fn()
      .mockResolvedValueOnce(json({ error: { code: "not_found", message: "Not found" } }, 404))
      .mockResolvedValueOnce(json({ error: { code: "unauthorized", message: "Revoked" } }, 401));
    vi.stubGlobal("fetch", fetch);
    await expect(new PeerApi(origin).closeSession(id)).resolves.toBeUndefined();
    await expect(new PeerApi(origin).closeSession(id)).rejects.toMatchObject({ status: 401 });
  });
  it("explains a network failure without claiming its cause is diagnosed", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("Failed to fetch")));
    await expect(new PeerApi(origin).info()).rejects.toThrow("certificate trust");
  });
  it("aborts on deadlines and preserves explicit cancellation", async () => {
    vi.useFakeTimers();
    vi.stubGlobal("fetch", vi.fn((_url: string, options: RequestInit) => new Promise(
      (_resolve, reject) => options.signal?.addEventListener("abort", () => reject(options.signal?.reason)),
    )));
    const request = new PeerApi(origin).info();
    const failure = expect(request).rejects.toThrow("timeout");
    await vi.advanceTimersByTimeAsync(15_000);
    await failure;
    const abort = new AbortController();
    const cancelled = new PeerApi(origin).info(abort.signal);
    const cancelledFailure = expect(cancelled).rejects.toMatchObject({ name: "AbortError" });
    abort.abort();
    await cancelledFailure;
  });
});

describe("bounded response parsing", () => {
  it("bounds a stream even when content-length is absent", async () => {
    const stream = new ReadableStream<Uint8Array>({
      start(controller) { controller.enqueue(new Uint8Array(3)); controller.enqueue(new Uint8Array(3)); controller.close(); },
    });
    await expect(readBoundedBody(new Response(stream), 5)).rejects.toThrow("size");
  });
  it("rejects oversized declared responses without reading them", async () => {
    await expect(readBoundedBody(new Response("small", {
      headers: { "Content-Length": "100000" },
    }), 20)).rejects.toThrow("size");
  });
});
