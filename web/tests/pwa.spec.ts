import { expect, test } from "@playwright/test";

test("installable viewer starts offline and never caches camera API responses", async ({ page, context, browserName }) => {
  await page.goto("/");
  await page.waitForFunction(() => !!navigator.serviceWorker.controller);
  const manifestURL = await page.locator('link[rel="manifest"]').getAttribute("href");
  expect(manifestURL).toBe("./manifest.webmanifest");
  const manifest = await (await page.request.get("/manifest.webmanifest")).json();
  expect(manifest.display).toBe("standalone");
  expect(manifest.icons.some((icon: { sizes: string; purpose: string }) => icon.sizes === "512x512" && icon.purpose === "maskable")).toBe(true);
  await page.evaluate(() => fetch("/api/v1/private-test").then((response) => response.json()));
  const privateCached = await page.evaluate(async () => {
    for (const name of await caches.keys()) if (await (await caches.open(name)).match("/api/v1/private-test")) return true;
    return false;
  });
  expect(privateCached).toBe(false);
  expect(await page.evaluate(async () => {
    for (const name of await caches.keys()) if (await (await caches.open(name)).match(new URL("index.html", location.href).href)) return true;
    return false;
  })).toBe(true);
  // Deny the real origin too: some engines do not apply offline emulation to workers.
  await context.addCookies([{ name: "lantern-test-outage", value: "1", url: "http://127.0.0.1:4173" }]);
  if (browserName === "webkit") {
    // This Linux WebKit reports an internal error for setOffline even with
    // a standalone worker that returns a literal Response. A real origin
    // socket outage verifies cached startup without that emulation defect.
    test.info().annotations.push({ type: "environment", description: "WebKit offline emulation is broken; using real origin socket outage." });
  } else await context.setOffline(true);
  const offlinePage = await context.newPage();
  await offlinePage.goto("/index.html");
  await expect(offlinePage.getByRole("heading", { name: "A window into home." })).toBeVisible();
  if (browserName !== "webkit") await expect(offlinePage.getByText("Offline: the viewer shell is available.", { exact: false })).toBeVisible();
  await context.setOffline(false);
  await context.clearCookies();
});

test("remembered PWA authorization survives a new page and can be returned to session-only storage", async ({ page, context }) => {
  const origin = "https://pwa-camera.example:8443";
  const deviceId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  const token = "z".repeat(43);
  await context.route(`${origin}/**`, async (route) => {
    const headers = { "Access-Control-Allow-Origin": "http://127.0.0.1:4173", "Access-Control-Allow-Headers": "Authorization, Content-Type" };
    if (route.request().method() === "OPTIONS") return route.fulfill({ status: 204, headers });
    const info = { apiVersion: 1, deviceId, name: "PWA camera", armed: true, capabilities: { video: true, audio: true, snapshots: true, personDetection: false } };
    await route.fulfill({ headers, contentType: "application/json", body: JSON.stringify(route.request().url().endsWith("/info") ? info : { events: [] }) });
  });
  await page.addInitScript(({ origin, deviceId, token }) => sessionStorage.setItem("lantern.peers.v1", JSON.stringify([{ origin, deviceId, token, name: "PWA camera", expiresAt: "2099-01-01T00:00:00Z" }])), { origin, deviceId, token });
  await page.goto("/");
  await expect(page.getByRole("article", { name: "PWA camera camera" })).toBeVisible();
  await page.getByLabel("Remember this viewer on this device").check();
  expect(await page.evaluate(() => localStorage.getItem("lantern.peers.v1"))).toContain(token);
  const next = await context.newPage();
  await next.goto("/");
  await expect(next.getByRole("article", { name: "PWA camera camera" })).toBeVisible();
  await next.getByLabel("Remember this viewer on this device").uncheck();
  expect(await next.evaluate(() => localStorage.getItem("lantern.peers.v1"))).toBeNull();
  expect(await next.evaluate(() => sessionStorage.getItem("lantern.peers.v1"))).toContain(token);
});

test("viewing gesture requests notification consent and registers a camera-scoped push subscription", async ({ page }) => {
  const origin = "https://notify-camera.example:8443";
  const deviceId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  const token = "n".repeat(43);
  await page.addInitScript(({ origin, deviceId, token }) => {
    sessionStorage.setItem("lantern.peers.v1", JSON.stringify([{ origin, deviceId, token, name: "Notify camera", expiresAt: "2099-01-01T00:00:00Z" }]));
    let permission = "default";
    (window as unknown as { permissionRequests: number }).permissionRequests = 0;
    Object.defineProperty(window, "Notification", { configurable: true, value: class {
      static get permission() { return permission; }
      static requestPermission() { (window as unknown as { permissionRequests: number }).permissionRequests++; permission = "granted"; return Promise.resolve("granted"); }
    } });
    if (!("PushManager" in window)) Object.defineProperty(window, "PushManager", { configurable: true, value: class {} });
    // Protocol fixture only. Physical Apple/Google/Mozilla delivery is a
    // separate device gate; their external services are not called by tests.
    Object.defineProperty(ServiceWorkerRegistration.prototype, "pushManager", { configurable: true, get: () => ({
      getSubscription: async () => null,
      subscribe: async () => ({ toJSON: () => ({ endpoint: "https://fcm.googleapis.com/fcm/send/fixture", keys: { p256dh: "fixture-public-key", auth: "fixture-auth" } }) }),
    }) });
  }, { origin, deviceId, token });
  await page.addInitScript(({ origin, deviceId, token }) => {
    const network = window.fetch.bind(window);
    (window as unknown as { notificationRegistered: boolean }).notificationRegistered = false;
    // Window-side protocol fixture: WebKit does not expose service-worker
    // network interception to Playwright. Static shell/worker traffic is real.
    window.fetch = async (input, init) => {
      const url = typeof input === "string" ? input : input instanceof Request ? input.url : input.href;
      if (!url.startsWith(origin)) return network(input, init);
      const path = new URL(url).pathname;
      const headers = { "Content-Type": "application/json" };
      const response = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers });
      if (path.endsWith("/info")) return response({ apiVersion: 1, deviceId, name: "Notify camera", armed: true, capabilities: { video: true, audio: true, snapshots: true, personDetection: false, pushNotifications: true } });
      if (path.endsWith("/events")) return response({ events: [] });
      if (path.endsWith("/notifications")) {
        if ((init?.headers as Record<string, string>)?.Authorization !== `Bearer ${token}`) return response({ error: { code: "unauthorized", message: "Unauthorized fixture" } }, 401);
        if (init?.method === "DELETE") return new Response(null, { status: 204 });
        if (init?.method === "POST") { (window as unknown as { notificationRegistered: boolean }).notificationRegistered = true; return new Response(null, { status: 204 }); }
        return response({ publicKey: "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8", registered: false, pending: 0 });
      }
      return response({ error: { code: "fixture_no_media", message: "No media in permission fixture" } }, 503);
    };
  }, { origin, deviceId, token });
  await page.goto("/");
  await expect(page.getByText("ARMED", { exact: true })).toBeVisible();
  expect(await page.evaluate(() => (window as unknown as { permissionRequests: number }).permissionRequests)).toBe(0);
  await page.getByRole("button", { name: "Watch", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("Event alerts enabled");
  expect(await page.evaluate(() => (window as unknown as { notificationRegistered: boolean }).notificationRegistered)).toBe(true);
  expect(await page.evaluate(() => (window as unknown as { permissionRequests: number }).permissionRequests)).toBe(1);
  const scopes = await page.evaluate(async () => (await navigator.serviceWorker.getRegistrations()).map((registration) => registration.scope));
  expect(scopes.some((scope) => scope.endsWith(`/push/${deviceId}/`))).toBe(true);
  await page.getByRole("button", { name: "Disable event notifications", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("Event alerts disabled");
  await page.reload();
  await page.getByRole("button", { name: "Watch", exact: true }).click();
  expect(await page.evaluate(() => (window as unknown as { permissionRequests: number }).permissionRequests)).toBe(0);
  expect(await page.evaluate(() => (window as unknown as { notificationRegistered: boolean }).notificationRegistered)).toBe(false);
  await page.getByRole("button", { name: "Enable event notifications", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("Event alerts enabled");
  expect(await page.evaluate(() => (window as unknown as { permissionRequests: number }).permissionRequests)).toBe(1);
});
