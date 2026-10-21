#!/usr/bin/env python3
"""Export a reproducible static review snapshot into an existing Sites checkout.

Preserves Site identity and Git state. Does not publish or copy credentials.
"""
import argparse
import json
from pathlib import Path
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("checkout", type=Path)
    args = parser.parse_args()
    repository = Path(__file__).resolve().parents[1]
    checkout = args.checkout.resolve()
    if checkout == repository or repository in checkout.parents:
        parser.error("Use a separate Site checkout, outside the Lantern repository.")
    manifest = checkout / ".openai/hosting.json"
    expected = json.loads((repository / "docs/product/hosting.json").read_text())
    if not manifest.is_file() or json.loads(manifest.read_text()).get("project_id") != expected["project_id"]:
        parser.error("Site checkout must already contain the matching project identity.")
    bundle = repository / "web/dist"
    if not all((bundle / entry).is_file() for entry in ("index.html", "review.html", "sw.js", "manifest.webmanifest")):
        parser.error("Build web first with npm run build.")
    # Only dedicated generated trees are replaced, never checkout identity/Git.
    for target in (checkout / "dist", checkout / "source"):
        if target.is_symlink():
            parser.error("Generated target must not be a symlink.")
        if target.exists():
            shutil.rmtree(target)
    shutil.copytree(bundle, checkout / "dist")
    shutil.copytree(repository / "docs", checkout / "source/docs")
    for name in ("src", "tests", "public"):
        shutil.copytree(repository / "web" / name, checkout / "source/web" / name)
    for name in ("package.json", "package-lock.json", "vite.config.ts", "tsconfig.json", "playwright.config.ts", "index.html", "review.html", "service-worker.js"):
        shutil.copyfile(repository / "web" / name, checkout / "source/web" / name)
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repository, text=True).strip()
    (checkout / "SOURCE.json").write_text(json.dumps({"repository": "https://github.com/venkate5hgunda/lantern", "commit": revision}, indent=2) + "\n")
    (checkout / "README.md").write_text("# Lantern viewer and product review\n\nStatic snapshot of the Lantern repository. Open `/review.html` for source-backed requirements, architecture, milestones and scoped controls.\n\nSource revision: " + revision + ". No coordinator backend is deployed.\n\nTo rebuild: run `npm ci && npm run build` in `source/web`, then replace root `dist/` with `source/web/dist/`. Preserve `.openai/hosting.json` and its private audience. The upstream `scripts/export-review.py` prepares a fresh snapshot.\n")
    (checkout / ".gitignore").write_text("node_modules/\n**/*.tsbuildinfo\n**/test-results/\n**/playwright-report/\n")
    print(f"Exported review snapshot from {revision} to {checkout}")


if __name__ == "__main__":
    main()
