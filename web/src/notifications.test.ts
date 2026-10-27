import { expect, it, vi } from "vitest";
import { enableNotifications, disableNotifications } from "./notifications";
import type { PeerApi } from "./api";
import type { SavedPeer } from "./protocol";

it("opt-out during an in-flight camera subscription removes the late registration", async () => {
  localStorage.clear();
  vi.stubGlobal("isSecureContext", true);
  vi.stubGlobal("Notification", { permission: "granted" });
  vi.stubGlobal("PushManager", class {});
  let resolvePost!: () => void;
  const post = new Promise<void>((resolve) => { resolvePost = resolve; });
  const subscription = { options: { applicationServerKey: new Uint8Array([1]).buffer }, toJSON: () => ({}), unsubscribe: vi.fn().mockResolvedValue(true) };
  const scope = new URL("push/camera-race/", document.baseURI).href;
  const registration = { scope, active: {}, pushManager: { getSubscription: vi.fn().mockResolvedValue(subscription) }, unregister: vi.fn().mockResolvedValue(true) };
  Object.defineProperty(navigator, "serviceWorker", { configurable: true, value: { register: vi.fn().mockResolvedValue(registration), getRegistration: vi.fn().mockResolvedValue(registration) } });
  const api = { notificationConfig: vi.fn().mockResolvedValue({ publicKey: "AQ" }), subscribeNotifications: vi.fn(() => post), unsubscribeNotifications: vi.fn().mockResolvedValue(undefined) };
  const peer = { deviceId: "camera-race" } as SavedPeer;
  try {
    const enabling = enableNotifications(peer, api as unknown as PeerApi);
    await vi.waitFor(() => expect(api.subscribeNotifications).toHaveBeenCalledOnce());
    await disableNotifications(peer, api as unknown as PeerApi);
    resolvePost();
    await enabling;
    expect(api.unsubscribeNotifications).toHaveBeenCalledTimes(2);
    expect(subscription.unsubscribe).toHaveBeenCalledTimes(2);
    expect(registration.unregister).toHaveBeenCalledTimes(2);
    expect(localStorage.getItem("lantern:alerts-disabled:camera-race")).toBe("1");
  } finally { vi.unstubAllGlobals(); delete (navigator as unknown as Record<string, unknown>).serviceWorker; }
});
