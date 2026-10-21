import { test, beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { readFileSync } from "node:fs";
import { createCoordinator } from "./worker.mjs";
let sqlite, DB, fetch;
class Statement {
  constructor(sql, args = []) { this.sql = sql; this.args = args; }
  bind(...args) { return new Statement(this.sql, args); }
  async first() { return sqlite.prepare(this.sql).get(...this.args) ?? null; }
  async all() { return { results: sqlite.prepare(this.sql).all(...this.args) }; }
  async run() { return sqlite.prepare(this.sql).run(...this.args); }
}
beforeEach(() => {
  sqlite = new DatabaseSync(":memory:");
  sqlite.exec("PRAGMA foreign_keys=ON;");
  sqlite.exec(readFileSync(new URL("./schema.sql", import.meta.url), "utf8"));
  DB = { prepare: (sql) => new Statement(sql), batch: async (statements) => {
    sqlite.exec("BEGIN");
    try { const result = await Promise.all(statements.map((statement) => statement.run())); sqlite.exec("COMMIT"); return result; }
    catch (error) { sqlite.exec("ROLLBACK"); throw error; }
  } };
  fetch = createCoordinator();
});
afterEach(() => sqlite.close());
async function request(path, { method = "GET", owner, token, body, ip = "192.0.2.1" } = {}) {
  const response = await fetch(new Request(`https://lantern.example${path}`, {
    method, headers: { "cf-connecting-ip": ip, ...(owner ? { "oai-authenticated-user-id": owner } : {}), ...(token ? { authorization: `Bearer ${token}` } : {}), ...(body !== undefined ? { "content-type": "application/json" } : {}) },
    ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
  }), { DB });
  return { status: response.status, data: response.status === 204 ? null : await response.json() };
}
async function register() {
  const response = await request("/api/device/register", { method: "POST", body: { name: "Front door" } });
  assert.equal(response.status, 201);
  return response.data;
}
async function claim(device, owner = "owner-a") {
  return request("/api/owner/claim", { method: "POST", owner, body: { code: device.claimCode } });
}
const offer = { method: "POST", path: "/api/v1/sessions", body: { offer: { type: "offer", sdp: "v=0\r\n" }, audio: false } };
test("registration stores hashes and claim is authenticated one-time and owner-scoped", async () => {
  const device = await register();
  const raw = JSON.stringify(sqlite.prepare("SELECT * FROM devices").all());
  assert.ok(!raw.includes(device.deviceToken));
  assert.ok(!raw.includes(device.claimCode));
  assert.equal((await claim(device, null)).status, 401);
  assert.equal((await claim(device)).status, 200);
  assert.equal((await claim(device, "owner-b")).status, 404);
  assert.equal((await request("/api/owner/devices", { owner: "owner-b" })).data.devices.length, 0);
  assert.equal((await request("/api/owner/devices", { owner: "owner-a" })).data.devices[0].id, device.deviceId);
  assert.equal((await request("/api/device/poll", { token: device.deviceToken })).data.claimed, true);
});
test("signaling roundtrip isolates devices owners and prevents result replacement", async () => {
  const device = await register();
  const other = await register();
  await claim(device);
  const queued = await request(`/api/owner/devices/${device.deviceId}/signals`, { owner: "owner-a", method: "POST", body: offer });
  assert.equal(queued.status, 202);
  const id = queued.data.id;
  assert.equal((await request(`/api/owner/signals/${id}`, { owner: "owner-b" })).status, 404);
  assert.equal((await request(`/api/owner/devices/${device.deviceId}/signals`, { owner: "owner-b", method: "POST", body: offer })).status, 404);
  assert.equal((await request("/api/device/poll", { token: other.deviceToken })).data.commands.length, 0);
  assert.equal((await request("/api/device/poll", { token: device.deviceToken })).data.commands[0].id, id);
  const answer = { status: 201, body: { answer: { type: "answer", sdp: "v=0\r\n" } } };
  assert.equal((await request(`/api/device/signals/${id}`, { token: other.deviceToken, method: "POST", body: answer })).status, 409);
  assert.equal((await request(`/api/device/signals/${id}`, { token: device.deviceToken, method: "POST", body: answer })).status, 200);
  assert.deepEqual((await request(`/api/owner/signals/${id}`, { owner: "owner-a" })).data, { pending: false, result: answer });
  assert.equal((await request(`/api/device/signals/${id}`, { token: device.deviceToken, method: "POST", body: answer })).status, 409);
});
test("revocation invalidates phone token and removes queued signals", async () => {
  const device = await register(); await claim(device);
  await request(`/api/owner/devices/${device.deviceId}/signals`, { owner: "owner-a", method: "POST", body: offer });
  assert.equal((await request(`/api/owner/devices/${device.deviceId}`, { owner: "owner-b", method: "DELETE" })).status, 404);
  assert.equal((await request(`/api/owner/devices/${device.deviceId}`, { owner: "owner-a", method: "DELETE" })).status, 204);
  assert.equal((await request("/api/device/poll", { token: device.deviceToken })).status, 401);
  assert.equal(sqlite.prepare("SELECT COUNT(*) AS count FROM signals").get().count, 0);
});
test("expired claims and jobs are unusable", async () => {
  const device = await register();
  sqlite.prepare("UPDATE devices SET claim_expires=0 WHERE id=?").run(device.deviceId);
  assert.equal((await claim(device)).status, 404);
  assert.equal((await request("/api/device/poll", { token: device.deviceToken })).status, 401);
  const next = await register(); await claim(next);
  const queued = await request(`/api/owner/devices/${next.deviceId}/signals`, { owner: "owner-a", method: "POST", body: offer });
  sqlite.prepare("UPDATE signals SET expires=0").run();
  assert.equal((await request(`/api/owner/signals/${queued.data.id}`, { owner: "owner-a" })).status, 404);
});
test("only bounded standard signaling can be queued", async () => {
  const device = await register(); await claim(device);
  const route = `/api/owner/devices/${device.deviceId}/signals`;
  for (const invalid of [ { ...offer, path: "https://attacker.example" }, { ...offer, path: "/api/v1/events" }, { ...offer, body: { ...offer.body, audio: "true" } }, { ...offer, body: { ...offer.body, offer: { type: "offer", sdp: "x".repeat(65_537) } } } ]) {
    assert.equal((await request(route, { method: "POST", owner: "owner-a", body: invalid })).status, 400);
  }
  for (let i = 0; i < 32; i++) assert.equal((await request(route, { method: "POST", owner: "owner-a", body: offer })).status, 202);
  assert.equal((await request(route, { method: "POST", owner: "owner-a", body: offer })).status, 429);
});
test("registration and claims are rate limited and malformed requests rejected", async () => {
  for (let i = 0; i < 5; i++) await register();
  assert.equal((await request("/api/device/register", { method: "POST", body: { name: "Door" } })).status, 429);
  assert.equal((await request("/api/device/register", { method: "POST", ip: "192.0.2.2", body: null })).status, 400);
  assert.equal((await request("/api/device/register", { method: "POST", ip: "192.0.2.3", body: { name: "x".repeat(2000) } })).status, 413);
  assert.equal((await request("/api/device/poll", { token: "bad" })).status, 401);
  for (let i = 0; i < 10; i++) assert.equal((await request("/api/owner/claim", { method: "POST", owner: "owner-a", body: { code: "0".repeat(16) } })).status, 404);
  assert.equal((await request("/api/owner/claim", { method: "POST", owner: "owner-a", body: { code: "0".repeat(16) } })).status, 429);
});
