import { PeerApi } from "./api";
import { describeError, type SavedPeer } from "./protocol";
import { activeWorker, decodeApplicationKey } from "./pwa";
const pending = new Set<string>();
const disabled = new Set<string>();
export function notificationSupport(): boolean {
  return globalThis.isSecureContext && "Notification" in globalThis && "serviceWorker" in navigator && "PushManager" in globalThis;
}
const status = (deviceId: string, message: string) => window.dispatchEvent(new CustomEvent("lantern:notifications", { detail: { deviceId, message } }));
export async function enableNotifications(peer: SavedPeer, api: PeerApi, automatic = false): Promise<void> {
  if (automatic && disabled.has(peer.deviceId)) return;
  if (!automatic) disabled.delete(peer.deviceId);
  try {
    if (automatic && localStorage.getItem(`lantern:alerts-disabled:${peer.deviceId}`) === "1") return;
    if (!automatic) localStorage.removeItem(`lantern:alerts-disabled:${peer.deviceId}`);
  } catch { /* Restricted storage still permits session notification controls. */ }
  if (pending.has(peer.deviceId)) return;
  pending.add(peer.deviceId);
  try {
    if (!notificationSupport()) throw new Error("Push notifications need a supported HTTPS browser. On iPhone, install Lantern on the Home Screen first.");
    // Call before awaiting anything: browsers require a viewing-session gesture.
    const permission = Notification.permission === "default" ? await Notification.requestPermission() : Notification.permission;
    if (permission !== "granted") throw new Error(permission === "denied" ? "Notifications are blocked. Allow them in this site's browser settings to receive event alerts." : "Notifications were not enabled. You can enable alerts later.");
    const config = await api.notificationConfig();
    const scope = new URL(`push/${peer.deviceId}/`, document.baseURI);
    const registration = await navigator.serviceWorker.register(new URL("sw.js", document.baseURI), { scope: scope.pathname });
    await activeWorker(registration);
    let subscription = await registration.pushManager.getSubscription();
    const publicKey = decodeApplicationKey(config.publicKey);
    const existingKey = subscription?.options.applicationServerKey;
    if (subscription && (!existingKey || new Uint8Array(existingKey).some((value, index) => value !== publicKey[index]) || existingKey.byteLength !== publicKey.byteLength)) {
      await subscription.unsubscribe(); subscription = null;
    }
    subscription ??= await registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: publicKey });
    await api.subscribeNotifications(subscription.toJSON());
    status(peer.deviceId, "Event alerts enabled, including while this viewer is closed. Delivery depends on your browser, OS and internet connection.");
  } catch (cause) { status(peer.deviceId, describeError(cause)); }
  finally { pending.delete(peer.deviceId); }
}
export async function disableNotifications(peer: SavedPeer, api: PeerApi): Promise<void> {
  disabled.add(peer.deviceId);
  try { localStorage.setItem(`lantern:alerts-disabled:${peer.deviceId}`, "1"); } catch { /* Session cancellation still works. */ }
  let remoteError: unknown = null;
  try { await api.unsubscribeNotifications(); } catch (cause) { remoteError = cause; }
  try {
    // A disconnected camera must not prevent local subscription cancellation.
    if ("serviceWorker" in navigator) {
      const scope = new URL(`push/${peer.deviceId}/`, document.baseURI);
      const registration = await navigator.serviceWorker.getRegistration(scope);
      if (registration?.scope === scope.href) {
        await (await registration.pushManager.getSubscription())?.unsubscribe();
        await registration.unregister();
      }
    }
    status(peer.deviceId, remoteError ? "Alerts disabled on this browser. Camera-side removal could not be confirmed; expired endpoints will be cleaned up on delivery." : "Event alerts disabled for this viewer and camera.");
  } catch (cause) { status(peer.deviceId, describeError(cause)); }
}
