export async function registerShell(): Promise<ServiceWorkerRegistration | null> {
  if (!globalThis.isSecureContext || !("serviceWorker" in navigator)) return null;
  const scope = new URL("./", document.baseURI);
  const existing = await navigator.serviceWorker.getRegistration(scope).catch(() => undefined);
  if (!navigator.onLine && existing?.scope === scope.href && existing.active) return existing;
  try { const registration = await navigator.serviceWorker.register(new URL("sw.js", document.baseURI), { scope: new URL("./", document.baseURI).pathname }); await activeWorker(registration); return registration; }
  catch { return existing?.scope === scope.href && existing.active ? existing : null; }
}
export function decodeApplicationKey(value: string): Uint8Array<ArrayBuffer> {
  const decoded = atob(value.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(value.length / 4) * 4, "="));
  return Uint8Array.from(decoded, (character) => character.charCodeAt(0));
}
export async function activeWorker(registration: ServiceWorkerRegistration): Promise<void> {
  if (registration.active) return;
  const worker = registration.installing || registration.waiting;
  if (!worker) throw new Error("Notification worker could not start.");
  await new Promise<void>((resolve, reject) => {
    const timer = setTimeout(() => { worker.removeEventListener("statechange", changed); reject(new Error("Notification worker startup timed out.")); }, 15_000);
    function changed() {
      if (worker!.state === "activated" || worker!.state === "redundant") {
        clearTimeout(timer); worker!.removeEventListener("statechange", changed);
        if (worker!.state === "activated") resolve(); else reject(new Error("Notification worker was replaced. Try again."));
      }
    }
    worker.addEventListener("statechange", changed); changed();
  });
}
