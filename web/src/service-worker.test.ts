import { readFileSync } from "node:fs";
import { runInNewContext } from "node:vm";
import { expect, it, vi } from "vitest";
function worker(scope = "https://viewer.example/") {
  const events = new Map<string, (value: unknown) => void>();
  const showNotification = vi.fn().mockResolvedValue(undefined);
  const openWindow = vi.fn().mockResolvedValue(undefined);
  const self = { location: { href: "https://viewer.example/sw.js" }, registration: { scope, showNotification }, clients: { matchAll: vi.fn().mockResolvedValue([]), openWindow }, addEventListener: (name: string, callback: (value: unknown) => void) => events.set(name, callback) };
  const source = readFileSync("service-worker.js", "utf8").replace("__LANTERN_VERSION__", '"test"').replace("__LANTERN_SHELL__", '["index.html","assets/viewer.js"]');
  runInNewContext(source, { self, URL });
  return { events, showNotification, openWindow };
}
it("push creates a visible private event notification and click cannot navigate to payload URLs", async () => {
  const { events, showNotification, openWindow } = worker();
  let operation = Promise.resolve();
  events.get("push")!({ data: { json: () => ({ type: "person", deviceId: "a".repeat(36), eventId: "b".repeat(36), cameraName: "Front door", url: "https://attacker.test" }) }, waitUntil: (value: Promise<void>) => operation = value });
  await operation;
  expect(showNotification).toHaveBeenCalledWith("Front door", expect.objectContaining({ body: expect.stringContaining("Person detected"), tag: `${"a".repeat(36)}:${"b".repeat(36)}` }));
  events.get("notificationclick")!({ notification: { close: vi.fn(), data: { deviceId: "a".repeat(36), url: "https://attacker.test" } }, waitUntil: (value: Promise<void>) => operation = value });
  await operation;
  expect(openWindow).toHaveBeenCalledWith(`https://viewer.example/index.html#camera=${"a".repeat(36)}`);
});
it("camera APIs media and authentication are never intercepted or cached", () => {
  const { events } = worker();
  for (const url of ["https://camera.example/api/v1/events", "https://viewer.example/api/v1/private", "https://viewer.example/signin-with-chatgpt", "https://viewer.example/snapshot.jpg"]) {
    const respondWith = vi.fn();
    events.get("fetch")!({ request: { method: "GET", url, mode: "cors" }, respondWith });
    expect(respondWith).not.toHaveBeenCalled();
  }
});
it("per-camera push workers never control the app-shell cache", () => {
  const { events } = worker("https://viewer.example/push/device/");
  const respondWith = vi.fn(); const waitUntil = vi.fn();
  events.get("fetch")!({ request: { method: "GET", url: "https://viewer.example/index.html", mode: "navigate" }, respondWith });
  events.get("install")!({ waitUntil });
  expect(respondWith).not.toHaveBeenCalled(); expect(waitUntil).not.toHaveBeenCalled();
});
