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
