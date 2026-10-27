/* Versioned static shell only. No camera API responses, credentials, or media. */
const VERSION = __LANTERN_VERSION__;
const SHELL = __LANTERN_SHELL__;
const BASE = new URL("./", self.location.href);
const ROOT = self.registration.scope === BASE.href;
const PREFIX = `lantern-shell-${encodeURIComponent(BASE.pathname)}-`;
const CACHE = `${PREFIX}${VERSION}`;
const urls = SHELL.map((path) => new URL(path, BASE).href);
self.addEventListener("install", (event) => {
  if (ROOT) event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(urls)));
});
self.addEventListener("activate", (event) => {
  event.waitUntil((async () => {
    if (ROOT) for (const key of await caches.keys()) if (key.startsWith(PREFIX) && key !== CACHE) await caches.delete(key);
    await self.clients.claim();
  })());
});
self.addEventListener("message", (event) => {
  if (event.data?.type === "SKIP_WAITING") self.skipWaiting();
});
self.addEventListener("fetch", (event) => {
  if (!ROOT || event.request.method !== "GET") return;
  const url = new URL(event.request.url);
  if (url.origin !== BASE.origin || url.search || url.pathname.includes("/api/")) return;
  const entry = ["index.html", "review.html"].find((name) => url.pathname === new URL(name, BASE).pathname || url.pathname === new URL(name.replace(/\.html$/, ""), BASE).pathname);
  if (event.request.mode === "navigate" && (entry || url.pathname === BASE.pathname)) {
    event.respondWith(caches.open(CACHE).then(async (cache) => (await cache.match(new URL(entry || "index.html", BASE).href)) || fetch(event.request)));
  } else if (urls.includes(url.href)) {
    event.respondWith(caches.open(CACHE).then(async (cache) => (await cache.match(event.request)) || fetch(event.request)));
  }
});
self.addEventListener("push", (event) => {
  event.waitUntil((async () => {
    let value = {};
    try { value = event.data?.json() || {}; } catch { /* generic visible fallback */ }
    const validId = (id) => typeof id === "string" && /^[0-9a-f-]{36}$/.test(id);
    const deviceId = validId(value.deviceId) ? value.deviceId : "";
    const eventId = validId(value.eventId) ? value.eventId : "";
    const name = typeof value.cameraName === "string" ? value.cameraName.slice(0, 80) : "Lantern camera";
    const body = value.type === "person" ? "Person detected. Open Lantern to review the evidence." : value.type === "doorbell" ? "Someone rang the doorbell. Open Lantern to answer." : "A camera event is ready to review.";
    await self.registration.showNotification(name, { body, icon: new URL("icons/icon-192.png", BASE).href,
      tag: deviceId && eventId ? `${deviceId}:${eventId}` : "lantern-event", data: { deviceId, eventId } });
  })());
});
self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  event.waitUntil((async () => {
    const deviceId = event.notification.data?.deviceId || "";
    const windows = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
    const client = windows.find((item) => {
      const url = new URL(item.url);
      return url.origin === BASE.origin && [BASE.pathname, new URL("index.html", BASE).pathname].includes(url.pathname);
    });
    if (client) { client.postMessage({ type: "focusCamera", deviceId }); await client.focus(); }
    else await self.clients.openWindow(new URL(`index.html#camera=${encodeURIComponent(deviceId)}`, BASE).href);
  })());
});
