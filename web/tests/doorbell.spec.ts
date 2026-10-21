import { expect, test } from "@playwright/test";
const origin = "https://door.nord:8443";
const deviceId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const ringId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
const token = "a".repeat(43);
test("compatible camera shows and acknowledges a ring and reports snapshot limits", async ({ page }) => {
  let acknowledged = false;
  await page.addInitScript(({ origin, deviceId, token }) => sessionStorage.setItem("lantern.peers.v1", JSON.stringify([{ origin, deviceId, name: "Front door", token, expiresAt: "2099-01-01T00:00:00Z" }])), { origin, deviceId, token });
  await page.route(`${origin}/**`, async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const headers = { "Access-Control-Allow-Origin": "http://127.0.0.1:4173", "Access-Control-Allow-Headers": "Authorization, Content-Type", "Access-Control-Allow-Methods": "GET, POST, OPTIONS" };
    if (request.method() === "OPTIONS") return route.fulfill({ status: 204, headers });
    let body: unknown;
    if (path === "/api/v1/info") body = { apiVersion: 1, deviceId, name: "Front door", armed: true, capabilities: { video: true, audio: true, snapshots: true, personDetection: false, doorbell: true, rollingSnapshots: true } };
    else {
      expect(request.headers().authorization).toBe(`Bearer ${token}`);
      if (path === "/api/v1/events") body = { events: [] };
      if (path === "/api/v1/doorbell") body = { deviceId, rings: [{ id: ringId, createdAt: new Date().toISOString(), acknowledged }] };
      if (path.endsWith("/ack")) { acknowledged = true; return route.fulfill({ status: 204, headers }); }
      if (path === "/api/v1/storage") body = { kind: "snapshots", items: 12, bytes: 1024, maxItems: 200, maxBytes: 134217728, maxAgeSeconds: 604800, reserveBytes: 67108864, availableBytes: 1073741824, automaticSnapshots: true, error: null };
    }
    await route.fulfill({ status: 200, headers, contentType: "application/json", body: JSON.stringify(body) });
  });
  await page.goto("/");
  await expect(page.getByRole("region", { name: "Incoming doorbell" })).toBeVisible();
  await expect(page.getByText("Your microphone starts off.", { exact: false })).toBeVisible();
  await page.getByRole("button", { name: "Dismiss ring" }).click();
  await expect(page.getByRole("region", { name: "Incoming doorbell" })).toHaveCount(0);
  expect(acknowledged).toBe(true);
  await page.getByText("Local storage", { exact: false }).click();
  await expect(page.getByText("Automatic snapshots every minute while armed.")).toBeVisible();
  await expect(page.getByText("Continuous video and peer backups are planned.", { exact: false })).toBeVisible();
});
test("legacy cameras never receive unsupported API requests", async ({ page }) => {
  const requests: string[] = [];
  await page.addInitScript(({ origin, deviceId, token }) => sessionStorage.setItem("lantern.peers.v1", JSON.stringify([{ origin, deviceId, name: "Old camera", token, expiresAt: "2099-01-01T00:00:00Z" }])), { origin, deviceId, token });
  await page.route(`${origin}/**`, async (route) => {
    const path = new URL(route.request().url()).pathname;
    requests.push(path);
    const headers = { "Access-Control-Allow-Origin": "http://127.0.0.1:4173", "Access-Control-Allow-Headers": "Authorization, Content-Type" };
    if (route.request().method() === "OPTIONS") return route.fulfill({ status: 204, headers });
    const body = path.endsWith("/info") ? { apiVersion: 1, deviceId, name: "Old camera", armed: true, capabilities: { video: true, audio: true, snapshots: true, personDetection: false } } : { events: [] };
    await route.fulfill({ status: 200, headers, contentType: "application/json", body: JSON.stringify(body) });
  });
  await page.goto("/");
  await expect(page.getByText("ARMED", { exact: true })).toBeVisible();
  expect(requests.some((path) => /doorbell|storage/.test(path))).toBe(false);
});
