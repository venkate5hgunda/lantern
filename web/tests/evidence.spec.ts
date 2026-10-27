import { expect, test } from "@playwright/test";
test.use({ serviceWorkers: "block" });
test("viewer reports presence and pending redundancy and retrieves peer evidence independently", async ({ page }) => {
  const origin = "https://backup-camera.example:8443";
  const deviceId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  const sourceId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
  const eventId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
  const replicaId = "a".repeat(64);
  const token = "e".repeat(43);
  let retrieved = false;
  await page.addInitScript(({ origin, deviceId, token }) => sessionStorage.setItem("lantern.peers.v1", JSON.stringify([{ origin, deviceId, token, name: "Backup camera", expiresAt: "2099-01-01T00:00:00Z" }])), { origin, deviceId, token });
  await page.context().route(`${origin}/**`, async (route) => {
    const request = route.request();
    const headers = { "Access-Control-Allow-Origin": "http://127.0.0.1:4173", "Access-Control-Allow-Headers": "Authorization, Content-Type" };
    if (request.method() === "OPTIONS") return route.fulfill({ status: 204, headers });
    const path = new URL(request.url()).pathname;
    if (!path.endsWith("/info")) expect(request.headers().authorization).toBe(`Bearer ${token}`);
    let body: unknown;
    if (path.endsWith("/info")) body = { apiVersion: 1, deviceId, name: "Backup camera", armed: true, capabilities: { video: true, audio: true, snapshots: true, personDetection: true, analytics: true, peerBackup: true } };
    else if (path === "/api/v1/events") body = { events: [] };
    else if (path.endsWith("/analytics")) body = { deviceId, enabled: true, available: true, present: true, people: 2, peakPeople: 3, observations: 20, sampledAt: new Date().toISOString(), lastSeen: new Date().toISOString(), error: null, model: "efficientdet-lite0-1", window: "current service run" };
    else if (path.endsWith("/backup-status")) body = { desired: 5, quotaBytes: 268435456, replicaBytes: 1000, targets: [{ deviceId: sourceId, origin: "https://offline-source.example:8443", verified: 3, pending: 2, lastSync: new Date().toISOString(), error: "Peer unavailable; retrying" }] };
    else if (path.endsWith("/replica-events")) body = { replicas: [{ id: replicaId, evidence: { sourceDeviceId: sourceId, eventId, created: Date.now(), trigger: "person", confidence: 0.9, plainBytes: 6, sha256: "b".repeat(64) } }] };
    else if (path.endsWith("/snapshot")) { retrieved = true; return route.fulfill({ headers, contentType: "image/jpeg", body: Buffer.from([0xff,0xd8,0xff,0,0xff,0xd9]) }); }
    await route.fulfill({ headers, contentType: "application/json", body: JSON.stringify(body) });
  });
  await page.goto("/");
  await page.getByText("Person detection and presence", { exact: true }).click();
  await expect(page.getByText("2 people currently observed.")).toBeVisible();
  await page.getByText("Redundant backups", { exact: false }).click();
  await expect(page.getByText("3 verified copies of 5 retained local snapshots; 2 pending.")).toBeVisible();
  await page.getByText("Recovered peer evidence", { exact: false }).click();
  await page.getByRole("button", { name: "Retrieve replica" }).click();
  await expect(page.getByRole("link", { name: "Download verified snapshot" })).toBeVisible();
  expect(retrieved).toBe(true);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});
