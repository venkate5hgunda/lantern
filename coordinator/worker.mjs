// Sites supplies trusted visitor identity at the dispatch boundary. Never expose
// this handler on a host that accepts spoofed oai-authenticated-user-id headers.
const json = (status, data) => new Response(JSON.stringify(data), {
  status, headers: { "Content-Type": "application/json", "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" },
});
const error = (status, code) => json(status, { error: { code } });
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const hash = async (value) => [...new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)))].map((n) => n.toString(16).padStart(2, "0")).join("");
const secret = () => [...crypto.getRandomValues(new Uint8Array(32))].map((n) => n.toString(16).padStart(2, "0")).join("");

async function body(request, limit = 98_304) {
  if (request.headers.get("content-type")?.split(";")[0] !== "application/json") throw new Error("content_type");
  const reader = request.body?.getReader();
  if (!reader) throw new Error("body");
  let length = 0;
  const parts = [];
  try {
    for (;;) {
      const part = await reader.read();
      if (part.done) break;
      length += part.value.length;
      if (length > limit) { await reader.cancel(); throw new Error("too_large"); }
      parts.push(part.value);
    }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const part of parts) { bytes.set(part, offset); offset += part.length; }
  const parsed = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error("body");
  return parsed;
}
function command(input) {
  if (!input || typeof input !== "object") return null;
  const { method, path, body: payload = null } = input;
  // Only WebRTC setup and lease messages. No arbitrary HTTP proxy or media.
  if (method === "POST" && path === "/api/v1/sessions") {
    if (payload?.offer?.type !== "offer" || typeof payload.offer.sdp !== "string" || !payload.offer.sdp || new TextEncoder().encode(payload.offer.sdp).length > 65_536 || typeof payload.audio !== "boolean") return null;
  } else if ((method === "PUT" || method === "DELETE") && /^\/api\/v1\/sessions\/[0-9a-f-]{36}$/.test(path)) {
    if (!uuid.test(path.split("/").at(-1)) || payload !== null) return null;
  } else if (method === "POST" && /^\/api\/v1\/sessions\/[0-9a-f-]{36}\/ice$/.test(path)) {
    if (!uuid.test(path.split("/").at(-2)) || typeof payload?.candidate !== "string" || !payload.candidate || payload.candidate.length > 4096 || /[\r\n\x00]/.test(payload.candidate) || !(payload.sdpMid === null || typeof payload.sdpMid === "string" && payload.sdpMid.length <= 64) || !Number.isInteger(payload.sdpMLineIndex) || payload.sdpMLineIndex < 0 || payload.sdpMLineIndex > 1) return null;
  } else return null;
  return { method, path, body: payload };
}

export function createCoordinator() {
  return async function fetch(request, env) {
    const db = env.DB;
    if (!db) return error(503, "coordinator_not_configured");
    const url = new URL(request.url);
    if (url.search || !url.pathname.startsWith("/api/")) return error(404, "not_found");
    const now = Date.now();
    const owner = request.headers.get("oai-authenticated-user-id");
    try {
      // Bounded opportunistic housekeeping. Migrations are applied separately.
      await db.batch([
        db.prepare("DELETE FROM signals WHERE id IN (SELECT id FROM signals WHERE expires <= ? LIMIT 100)").bind(now),
        db.prepare("DELETE FROM devices WHERE id IN (SELECT id FROM devices WHERE owner_id IS NULL AND claim_expires <= ? LIMIT 100)").bind(now),
        db.prepare("DELETE FROM rate_limits WHERE key IN (SELECT key FROM rate_limits WHERE expires <= ? LIMIT 100)").bind(now),
      ]);
      async function rate(key, limit) {
        const bucket = `${key}:${Math.floor(now / 60_000)}`;
        const value = await db.prepare("INSERT INTO rate_limits(key,count,expires) VALUES (?,1,?) ON CONFLICT(key) DO UPDATE SET count=count+1 RETURNING count").bind(bucket, now + 120_000).first();
        return value.count <= limit;
      }
      if (url.pathname === "/api/device/register" && request.method === "POST") {
        const ip = request.headers.get("cf-connecting-ip");
        if (!ip) return error(503, "trusted_client_address_required");
        if (!await rate(`register:${await hash(ip)}`, 5)) return error(429, "registration_limited");
        const input = await body(request, 1024);
        const name = typeof input.name === "string" ? input.name.trim() : "";
        if (!name || name.length > 80 || /[\x00-\x1f\x7f]/.test(name)) return error(400, "invalid_name");
        const deviceId = crypto.randomUUID();
        const deviceToken = secret();
        // 64 random bits, five-minute TTL, hashed at rest. Never use six digits
        // as the sole protection for an internet-facing claim endpoint.
        const claimCode = secret().slice(0, 16).toUpperCase();
        await db.prepare("INSERT INTO devices(id,name,token_hash,claim_hash,claim_expires,created) VALUES (?,?,?,?,?,?)").bind(deviceId, name, await hash(deviceToken), await hash(claimCode), now + 300_000, now).run();
        return json(201, { deviceId, deviceToken, claimCode, expiresAt: new Date(now + 300_000).toISOString() });
      }
      if (url.pathname.startsWith("/api/owner/")) {
        if (!owner) return error(401, "sign_in_required");
        if (!await rate(`owner:${owner}`, 240)) return error(429, "owner_limited");
        if (url.pathname === "/api/owner/claim" && request.method === "POST") {
          if (!await rate(`claim:${owner}`, 10)) return error(429, "claim_limited");
          const input = await body(request, 1024);
          if (!/^[A-Fa-f0-9]{16}$/.test(input.code)) return error(400, "invalid_claim");
          const claimed = await db.prepare("UPDATE devices SET owner_id=?, claim_hash=NULL WHERE claim_hash=? AND claim_expires>? AND owner_id IS NULL AND (SELECT COUNT(*) FROM devices WHERE owner_id=?)<16 RETURNING id,name").bind(owner, await hash(input.code.toUpperCase()), now, owner).first();
          return claimed ? json(200, claimed) : error(404, "claim_unavailable");
        }
        if (url.pathname === "/api/owner/devices" && request.method === "GET") {
          const found = await db.prepare("SELECT id,name,last_seen FROM devices WHERE owner_id=? ORDER BY created DESC LIMIT 16").bind(owner).all();
          return json(200, { devices: found.results });
        }
        const deviceMatch = /^\/api\/owner\/devices\/([0-9a-f-]{36})(\/signals)?$/.exec(url.pathname);
        if (deviceMatch && uuid.test(deviceMatch[1])) {
          const device = await db.prepare("SELECT id FROM devices WHERE id=? AND owner_id=?").bind(deviceMatch[1], owner).first();
          if (!device) return error(404, "device_unavailable");
          if (request.method === "DELETE" && !deviceMatch[2]) {
            await db.batch([db.prepare("DELETE FROM signals WHERE device_id=?").bind(device.id), db.prepare("DELETE FROM devices WHERE id=? AND owner_id=?").bind(device.id, owner)]);
            return new Response(null, { status: 204 });
          }
          if (request.method === "POST" && deviceMatch[2]) {
            const validated = command(await body(request));
            if (!validated) return error(400, "invalid_signal");
            const id = crypto.randomUUID();
            // Admission and pending-count check occur in one SQLite statement.
            const accepted = await db.prepare("INSERT INTO signals(id,device_id,owner_id,method,path,body,created,expires) SELECT ?,?,?,?,?,?,?,? WHERE (SELECT COUNT(*) FROM signals WHERE device_id=? AND expires>? AND result IS NULL)<32 RETURNING id").bind(id, device.id, owner, validated.method, validated.path, JSON.stringify(validated.body), now, now + 30_000, device.id, now).first();
            return accepted ? json(202, { id, expiresAt: new Date(now + 30_000).toISOString() }) : error(429, "signal_queue_full");
          }
        }
        const signalMatch = /^\/api\/owner\/signals\/([0-9a-f-]{36})$/.exec(url.pathname);
        if (signalMatch && request.method === "GET") {
          const signal = await db.prepare("SELECT result FROM signals WHERE id=? AND owner_id=? AND expires>?").bind(signalMatch[1], owner, now).first();
          if (!signal) return error(404, "signal_unavailable");
          return json(200, { pending: signal.result === null, result: signal.result === null ? null : JSON.parse(signal.result) });
        }
      }
      if (url.pathname.startsWith("/api/device/")) {
        const header = request.headers.get("authorization") ?? "";
        if (!/^Bearer [a-f0-9]{64}$/.test(header)) return error(401, "device_unauthorized");
        const device = await db.prepare("SELECT id,owner_id FROM devices WHERE token_hash=?").bind(await hash(header.slice(7))).first();
        if (!device) return error(401, "device_unauthorized");
        if (!await rate(`device:${device.id}`, 120)) return error(429, "device_limited");
        if (url.pathname === "/api/device/poll" && request.method === "GET") {
          await db.prepare("UPDATE devices SET last_seen=? WHERE id=?").bind(now, device.id).run();
          const signals = await db.prepare("SELECT id,method,path,body FROM signals WHERE device_id=? AND expires>? AND result IS NULL ORDER BY created LIMIT 8").bind(device.id, now).all();
          return json(200, { claimed: device.owner_id !== null, commands: signals.results.map((row) => ({ ...row, body: JSON.parse(row.body) })) });
        }
        const reply = /^\/api\/device\/signals\/([0-9a-f-]{36})$/.exec(url.pathname);
        if (reply && request.method === "POST") {
          const input = await body(request);
          if (!Number.isInteger(input.status) || input.status < 200 || input.status > 599 || (input.body !== null && (typeof input.body !== "object" || Array.isArray(input.body)))) return error(400, "invalid_result");
          const updated = await db.prepare("UPDATE signals SET result=? WHERE id=? AND device_id=? AND expires>? AND result IS NULL RETURNING id").bind(JSON.stringify({ status: input.status, body: input.body }), reply[1], device.id, now).first();
          return updated ? json(200, { accepted: true }) : error(409, "signal_closed");
        }
      }
      return error(404, "not_found");
    } catch (cause) {
      if (cause instanceof SyntaxError || ["body", "content_type", "too_large"].includes(cause.message)) return error(cause.message === "too_large" ? 413 : 400, "invalid_request");
      return error(503, "coordinator_unavailable");
    }
  };
}
export default { fetch: createCoordinator() };
