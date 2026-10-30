import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";
import { ReferenceCamera } from "./reference-camera";

const cameras: ReferenceCamera[] = [];
test.afterEach(async () => {
  if (test.info().status !== test.info().expectedStatus) {
    for (const camera of cameras) {
      await test.info().attach("media-transport", {
        body: JSON.stringify(await camera.diagnostics()), contentType: "application/json",
      });
    }
  }
  await Promise.all(cameras.splice(0).map((camera) => camera.close()));
});

test("renders actual WebRTC video and closes the direct media session", async ({ page }) => {
  test.setTimeout(70_000);
  const camera = new ReferenceCamera(page); cameras.push(camera);
  await camera.install();
  await page.getByRole("button", { name: "Watch", exact: true }).click();
  await expect(page.getByText("LIVE", { exact: true })).toBeVisible({ timeout: 40_000 });
  const video = page.getByLabel("Media camera live stream");
  await expect.poll(() => video.evaluate((element) => (
    element instanceof HTMLVideoElement ? element.videoWidth : 0
  ))).toBeGreaterThan(0);
  expect(await page.evaluate(() => window.lanternTestMicrophone.requests)).toBe(0);
  await page.getByRole("button", { name: "Disconnect", exact: true }).click();
  await expect.poll(() => camera.sessionCount).toBe(0);
  await expect(page.getByRole("button", { name: "Watch", exact: true })).toBeVisible();
});

test("negotiates bidirectional audio only on request and stops microphone tracks", async ({ page }) => {
  test.setTimeout(70_000);
  const camera = new ReferenceCamera(page); cameras.push(camera);
  await camera.install();
  await page.getByRole("button", { name: "Connect with audio", exact: true }).click();
  await expect(page.getByText("LIVE", { exact: true })).toBeVisible({ timeout: 40_000 });
  expect(await page.evaluate(() => window.lanternTestMicrophone.requests)).toBe(0);
  await page.getByRole("button", { name: "Listen", exact: true }).click();
  await expect.poll(() => page.evaluate(async () => {
    const peer = window.lanternViewerPeer;
    if (!peer) return false;
    return Array.from((await peer.getStats()).values()).some((row) => (
      row.type === "inbound-rtp" && (row.kind === "audio" || row.mediaType === "audio") &&
      row.bytesReceived > 0 && row.totalSamplesReceived > 0
    ));
  })).toBe(true);
  expect(await page.getByLabel("Media camera live stream").evaluate((element) => (
    element instanceof HTMLVideoElement && !element.muted
  ))).toBe(true);
  expect(await page.evaluate(() => navigator.mediaDevices.getUserMedia === window.lanternCaptureStub)).toBe(true);
  await page.getByRole("button", { name: "Talk", exact: true }).click();
  await expect(page.getByText("Your microphone is transmitting to this camera.")).toBeVisible();
  await expect.poll(() => camera.audioPackets).toBeGreaterThan(0);
  await page.getByRole("button", { name: "Stop talking", exact: true }).click();
  expect(await page.evaluate(() => window.lanternTestMicrophone.tracks.every((track) => track.readyState === "ended"))).toBe(true);
  await expect(page.getByText("Your microphone is transmitting to this camera.")).toHaveCount(0);
  await page.getByRole("button", { name: "Disconnect", exact: true }).click();
  await expect.poll(() => camera.sessionCount).toBe(0);
});

test("passes WCAG automated checks before and after pairing", async ({ page }) => {
  await page.goto("/");
  expect((await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze()).violations).toEqual([]);
  const camera = new ReferenceCamera(page); cameras.push(camera);
  await camera.install();
  expect((await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze()).violations).toEqual([]);
});

test("switching cameras closes live media and releases the transmitting microphone", async ({ page }) => {
  test.setTimeout(70_000);
  const camera = new ReferenceCamera(page); cameras.push(camera);
  await camera.install();
  const garage = {
    origin: "https://garage.nord:8443", deviceId: "22222222-2222-4222-8222-222222222222",
    name: "Garage", token: "g".repeat(43), expiresAt: "2099-01-01T00:00:00Z",
  };
  await page.route(`${garage.origin}/**`, async (route) => {
    const body = new URL(route.request().url()).pathname === "/api/v1/info"
      ? { apiVersion: 1, deviceId: garage.deviceId, name: garage.name, armed: true,
        capabilities: { video: true, audio: true, snapshots: true, personDetection: false } }
      : { events: [] };
    await route.fulfill({ status: 200, headers: { "Access-Control-Allow-Origin": "http://127.0.0.1:4173" },
      contentType: "application/json", body: JSON.stringify(body) });
  });
  await page.evaluate((saved) => {
    const peers = JSON.parse(sessionStorage.getItem("lantern.peers.v1") ?? "[]");
    sessionStorage.setItem("lantern.peers.v1", JSON.stringify([...peers, saved]));
  }, garage);
  await page.reload();
  await page.getByRole("button", { name: "Connect with audio", exact: true }).click();
  await expect(page.getByText("LIVE", { exact: true })).toBeVisible({ timeout: 40_000 });
  await page.getByRole("button", { name: "Talk", exact: true }).click();
  await expect.poll(() => camera.audioPackets).toBeGreaterThan(0);
  await page.getByRole("button", { name: "Garage, Armed" }).click();
  await expect(page.getByRole("article", { name: "Garage camera" })).toBeVisible();
  await expect.poll(() => camera.sessionCount).toBe(0);
  expect(await page.evaluate(() => window.lanternTestMicrophone.tracks.every((track) => track.readyState === "ended"))).toBe(true);
  await expect(page.getByRole("article", { name: "Media camera camera" })).toHaveCount(0);
});
