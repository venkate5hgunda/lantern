// Local test-only server: real static/PWA responses and one private-data fixture.
import { createServer } from "node:http";
import { createReadStream, statSync } from "node:fs";
import { resolve, extname, sep } from "node:path";
const root = resolve("dist");
const types = { ".html": "text/html", ".js": "application/javascript", ".css": "text/css", ".png": "image/png", ".webmanifest": "application/manifest+json" };
const server = createServer((request, response) => {
  const path = new URL(request.url, "http://127.0.0.1").pathname;
  if (request.headers.cookie?.split(";").some((value) => value.trim() === "lantern-test-outage=1")) { request.socket.destroy(); return; }
  response.setHeader("Cache-Control", "no-store");
  if (path === "/api/v1/private-test") {
    response.writeHead(200, { "Content-Type": "application/json" }); response.end('{"private":"must-not-be-cached"}'); return;
  }
  let decoded;
  try { decoded = decodeURIComponent(path); } catch { response.writeHead(400); response.end(); return; }
  const file = resolve(root, `.${decoded === "/" ? "/index.html" : decoded === "/review" ? "/review.html" : decoded}`);
  if (!file.startsWith(root + sep)) { response.writeHead(404); response.end(); return; }
  try {
    const stat = statSync(file);
    if (!stat.isFile()) throw new Error("not a file");
    response.writeHead(200, { "Content-Type": types[extname(file)] || "application/octet-stream", "Content-Length": stat.size });
    createReadStream(file).pipe(response);
  } catch { response.writeHead(404); response.end(); }
});
server.listen(4173, "127.0.0.1");
for (const signal of ["SIGINT", "SIGTERM"]) process.on(signal, () => server.close(() => process.exit(0)));
