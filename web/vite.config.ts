import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import { readFileSync } from "node:fs";
import { createHash } from "node:crypto";

export default defineConfig({
  base: "./",
  plugins: [react(), {
    name: "lantern-service-worker",
    generateBundle(_, bundle) {
      const files = Array.from(new Set(["index.html", "review.html", ...Object.keys(bundle), "manifest.webmanifest", "icons/icon-192.png", "icons/icon-512.png", "icons/maskable-512.png"]));
      const template = readFileSync("service-worker.js", "utf8");
      const revision = createHash("sha256").update(template).update(readFileSync("index.html")).update(readFileSync("review.html")).update(JSON.stringify(files)).update(JSON.stringify(Object.values(bundle).map((value) => value.type === "asset" ? value.source : value.code))).update(["manifest.webmanifest", "icons/icon-192.png", "icons/icon-512.png", "icons/maskable-512.png"].map((path) => readFileSync(`public/${path}`).toString("base64")).join(":")).digest("hex").slice(0, 16);
      this.emitFile({ type: "asset", fileName: "sw.js", source: template.replace("__LANTERN_VERSION__", JSON.stringify(revision)).replace("__LANTERN_SHELL__", JSON.stringify(files)) });
    },
  }],
  build: { sourcemap: false, rollupOptions: { treeshake: false, input: { viewer: "index.html", review: "review.html" } } },
  test: {
    environment: "jsdom",
    include: ["src/**/*.test.ts", "src/**/*.test.tsx"],
    clearMocks: true,
    restoreMocks: true,
  },
});
