import { expect, test, type Page } from "@playwright/test";

const origin = "https://door.nord:8443";
const deviceId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const token = "a".repeat(43);
const info = {
  apiVersion: 1, deviceId, name: "Front door", armed: true,
  capabilities: { video: true, audio: true, snapshots: true, personDetection: false },
};
const event = {
  id: "cccccccc-cccc-4ccc-8ccc-cccccccccccc", deviceId,
  createdAt: "2026-10-04T12:00:00Z", trigger: "manual", confidence: null,
  snapshotPath: "/api/v1/events/cccccccc-cccc-4ccc-8ccc-cccccccccccc/snapshot",
};

async function mockCamera(page: Page, options: { badCode?: boolean; revoked?: boolean; malformed?: boolean } = {}) {
  await page.route(`${origin}/**`, async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const headers = {
      "Access-Control-Allow-Origin": "http://127.0.0.1:4173",
      "Access-Control-Allow-Headers": "Authorization, Content-Type",
      "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
      "Cache-Control": "no-store",
    };
    if (request.method() === "OPTIONS") {
      await route.fulfill({ status: 204, headers }); return;
    }
    let body: unknown = { error: { code: "not_found", message: "Unknown endpoint" } };
    let status = 404;
    if (path === "/api/v1/info") {
      body = options.malformed ? { ...info, apiVersion: 2 } : info;
      status = 200;
    } else if (path === "/api/v1/pair") {
      expect(request.postDataJSON()).toMatchObject({ code: "123456", viewerName: "Browser viewer" });
      body = options.badCode
        ? { error: { code: "invalid_code", message: "Pairing code expired or invalid." } }
        : { deviceId, token, expiresAt: "2099-01-01T00:00:00Z" };
      status = options.badCode ? 400 : 201;
    } else if (path === "/api/v1/events") {
      expect(request.headers().authorization).toBe(`Bearer ${token}`);
      body = options.revoked
        ? { error: { code: "unauthorized", message: "Grant revoked." } }
        : request.method() === "POST" ? event : { events: [event] };
      status = options.revoked ? 401 : request.method() === "POST" ? 201 : 200;
    }
    await route.fulfill({ status, headers, contentType: "application/json", body: JSON.stringify(body) });
  });
}

async function pair(page: Page) {
  await page.getByRole("button", { name: "Pair your first camera" }).click();
  await page.getByLabel("Camera HTTPS address").fill(origin);
  await page.getByLabel("Six-digit pairing code").fill("123456");
  await page.getByRole("button", { name: "Pair camera", exact: true }).click();
}

test("loads privately with accessible onboarding and no third-party requests", async ({ page }) => {
  const external: string[] = [];
  page.on("request", (request) => {
    if (!request.url().startsWith("http://127.0.0.1:4173")) external.push(request.url());
  });
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto("/");
  await expect(page.getByRole("heading", { level: 1 })).toHaveText("Cameras");
  await expect(page.getByRole("button", { name: "Pair your first camera" })).toBeVisible();
  await page.getByRole("button", { name: "Connection and privacy checklist" }).click();
  await expect(page.getByText("Peer replication, cloud backup and owner recognition", { exact: false })).toBeVisible();
  expect(external).toEqual([]);
  expect(errors).toEqual([]);
});

test("pairs directly, retrieves authenticated history and creates snapshots", async ({ page }) => {
  await mockCamera(page);
  await page.goto("/");
  await pair(page);
  const card = page.getByRole("article", { name: "Front door camera" });
  await expect(card).toBeVisible();
  await expect(card.getByText("ARMED", { exact: true })).toBeVisible();
  await card.getByText("Event history", { exact: false }).click();
  await expect(card.getByText("Manual snapshot", { exact: true })).toBeVisible();
  await card.getByRole("button", { name: "Take snapshot" }).click();
  await expect(card.getByText("Manual snapshot", { exact: true })).toHaveCount(1);
  expect(await page.evaluate(() => localStorage.length)).toBe(0);
  const saved = await page.evaluate(() => sessionStorage.getItem("lantern.peers.v1"));
  expect(saved).toContain(token);
  await page.reload();
  await expect(page.getByRole("article", { name: "Front door camera" })).toBeVisible();
});

test("rejects pairing failures explicitly without adding an unauthorized camera", async ({ page }) => {
  await mockCamera(page, { badCode: true });
  await page.goto("/");
  await pair(page);
  await expect(page.getByRole("alert")).toContainText("expired or invalid");
  await expect(page.getByRole("article")).toHaveCount(0);
});

test("rejects an unsupported peer protocol before issuing a pairing grant", async ({ page }) => {
  await mockCamera(page, { malformed: true });
  await page.goto("/");
  await pair(page);
  await expect(page.getByRole("alert")).toContainText("incompatible");
  await expect(page.getByRole("article")).toHaveCount(0);
});

test("clears revoked credentials and requires re-pairing", async ({ page }) => {
  await mockCamera(page, { revoked: true });
  await page.goto("/");
  await pair(page);
  await expect(page.getByRole("button", { name: "Pair again", exact: true })).toBeVisible();
  const saved = await page.evaluate(() => JSON.parse(sessionStorage.getItem("lantern.peers.v1") ?? "[]"));
  expect(saved[0].token).toBeNull();
  await expect(page.getByRole("alert")).toContainText("revoked");
});

test("does not overflow narrow viewports and supports keyboard pairing", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "Add camera", exact: true }).focus();
  await page.keyboard.press("Enter");
  await expect(page.getByLabel("Camera HTTPS address")).toBeFocused();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test("removing a camera forgets tab credentials without pretending to revoke the grant", async ({ page }) => {
  await mockCamera(page);
  await page.goto("/");
  await pair(page);
  const card = page.getByRole("article", { name: "Front door camera" });
  await card.getByRole("button", { name: "Remove camera" }).click();
  await card.getByRole("button", { name: "Remove", exact: true }).click();
  await expect(page.getByRole("article")).toHaveCount(0);
  expect(await page.evaluate(() => sessionStorage.getItem("lantern.peers.v1"))).toBe("[]");
});

test("switches the active camera without mounting every camera console", async ({ page }) => {
  const cameras = [
    { origin: "https://front-door.nord:8443", deviceId: "11111111-1111-4111-8111-111111111111", name: "Front door", armed: true, token: "b".repeat(43) },
    { origin: "https://garage.nord:8443", deviceId: "22222222-2222-4222-8222-222222222222", name: "Garage", armed: false, token: "c".repeat(43) },
  ];
  for (const camera of cameras) {
    await page.route(`${camera.origin}/**`, async (route) => {
      const path = new URL(route.request().url()).pathname;
      const body = path === "/api/v1/info"
        ? { apiVersion: 1, deviceId: camera.deviceId, name: camera.name, armed: camera.armed,
          capabilities: { video: true, audio: true, snapshots: true, personDetection: false } }
        : { events: [] };
      await route.fulfill({ status: 200, headers: { "Access-Control-Allow-Origin": "http://127.0.0.1:4173" },
        contentType: "application/json", body: JSON.stringify(body) });
    });
  }
  await page.goto("/");
  await page.evaluate((saved) => sessionStorage.setItem("lantern.peers.v1", JSON.stringify(saved)),
    cameras.map(({ armed: _armed, ...camera }) => ({ ...camera, expiresAt: "2099-01-01T00:00:00Z" })));
  await page.reload();

  await expect(page.getByRole("button", { name: "Front door, Armed" })).toHaveAttribute("aria-pressed", "true");
  await expect(page.getByRole("article", { name: "Front door camera" })).toBeVisible();
  await page.getByRole("button", { name: "Garage, Disarmed" }).click();
  await expect(page.getByRole("button", { name: "Garage, Disarmed" })).toHaveAttribute("aria-pressed", "true");
  await expect(page.getByRole("article", { name: "Garage camera" })).toBeVisible();
  await expect(page.getByRole("article", { name: "Front door camera" })).toHaveCount(0);
});
