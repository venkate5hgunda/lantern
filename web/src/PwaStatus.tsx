import { useEffect, useState } from "react";
import { registerShell } from "./pwa";
interface InstallEvent extends Event { prompt(): Promise<void>; userChoice: Promise<{ outcome: string }> }
export function PwaStatus({ remember, onRemember }: { remember: boolean; onRemember(value: boolean): void }) {
  const [install, setInstall] = useState<InstallEvent | null>(null);
  const [waiting, setWaiting] = useState<ServiceWorker | null>(null);
  const [offline, setOffline] = useState(!navigator.onLine);
  const [shell, setShell] = useState<"starting" | "ready" | "unavailable">("starting");
  useEffect(() => {
    let alive = true;
    const offered = (event: Event) => { event.preventDefault(); setInstall(event as InstallEvent); };
    const network = () => setOffline(!navigator.onLine);
    const installed = () => setInstall(null);
    window.addEventListener("beforeinstallprompt", offered);
    window.addEventListener("appinstalled", installed);
    window.addEventListener("online", network); window.addEventListener("offline", network);
    void registerShell().then((registration) => {
      if (!alive) return;
      setShell(registration ? "ready" : "unavailable");
      if (registration?.waiting) setWaiting(registration.waiting);
      registration?.addEventListener("updatefound", () => {
        const worker = registration.installing;
        worker?.addEventListener("statechange", () => { if (alive && worker.state === "installed" && navigator.serviceWorker.controller) setWaiting(worker); });
      });
    });
    const focus = (event: MessageEvent) => {
      if (event.data?.type === "focusCamera" && /^[0-9a-f-]{36}$/.test(event.data.deviceId)) document.getElementById(`camera-${event.data.deviceId}`)?.scrollIntoView({ behavior: "smooth" });
    };
    const initialCamera = new URLSearchParams(location.hash.slice(1)).get("camera");
    if (initialCamera && /^[0-9a-f-]{36}$/.test(initialCamera)) requestAnimationFrame(() => document.getElementById(`camera-${initialCamera}`)?.scrollIntoView());
    navigator.serviceWorker?.addEventListener("message", focus);
    return () => { alive = false; window.removeEventListener("beforeinstallprompt", offered); window.removeEventListener("appinstalled", installed); window.removeEventListener("online", network); window.removeEventListener("offline", network); navigator.serviceWorker?.removeEventListener("message", focus); };
  }, []);
  return <aside className="pwa-status" aria-label="Viewer installation and background alerts">
    <p className="small muted">{offline ? "Offline: the viewer shell is available. Cameras still need a reachable network." : shell === "ready" ? "Install Lantern from your browser menu for a standalone viewer. On iPhone, use Share → Add to Home Screen." : shell === "unavailable" ? "Offline installation is unavailable in this browser. Live viewing still works online." : "Preparing the offline viewer shell…"}</p>
    <p className="small muted">When you start viewing a compatible camera, Lantern asks to enable notifications. Service-worker push delivers background alerts; browsers have no separate permission for continuous background execution.</p>
    <label className="remember-viewer"><input type="checkbox" checked={remember} onChange={(event) => onRemember(event.target.checked)} /> Remember this viewer on this device</label>
    <p className="small muted">Remembering keeps camera authorizations across restarts until expiry or revocation. Enable only on your own device; otherwise credentials stay in this tab.</p>
    {install && <button onClick={() => { void install.prompt().then(() => install.userChoice).then(() => setInstall(null)); }}>Install Lantern</button>}
    {waiting && <button onClick={() => {
      navigator.serviceWorker.addEventListener("controllerchange", () => location.reload(), { once: true });
      waiting.postMessage({ type: "SKIP_WAITING" });
    }}>Update viewer and reconnect</button>}
  </aside>;
}
