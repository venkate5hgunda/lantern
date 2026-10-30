# Resume Lantern in Codex Cloud

Use repository `venkate5hgunda/lantern`. The redesign originated on
`codex/lantern-redesign`; after its merge, use current `main` for new work.
Read [viewer design](viewer-design.md), [design QA](design-qa.md),
[validation](validation.md), and [device acceptance](device-acceptance.md).

## Decisions and completed work

The chosen direction is Roomlight (option 2), with the multi-camera overview
from option 3. The overview shows real protocol status; selecting a camera
opens one console and closes the previous media session and microphone.
Pairing, snapshots, history, listen/talk and revocation retain protocol-v1
behavior. Screenshots and comparison images are committed under `docs/images/`.
The October 25 checkpoint passed 55 unit and 72 browser tests, including
encoded media and accessibility; see validation for the limits of that evidence.

## Reproduce the viewer checks

Use Node 24, Python 3 and the checked-in lockfile:

```sh
cd web
npm ci
npx playwright install --with-deps chromium firefox webkit
npm test
npm run test:browser
```

Allow package registry and Playwright browser downloads in the environment.
The browser suite starts its own production preview on port 4173, which must
be free. Local macOS `.tools`, `node_modules`, `dist`, and test reports are
reproducible artifacts and are intentionally absent from Git.

## Pending work

- Run the physical-phone, browser/TV, certificate, Meshnet, acoustics and
  24-hour acceptance checks in [device acceptance](device-acceptance.md).
  Cloud browser tests cannot establish these results.
- Rebuild and qualify an APK with the redesigned embedded viewer using
  [build instructions](build.md); the redesign checkpoint did not build one.
- Inspect separate draft PR #1 before continuing its PWA/person-alert/backup/
  signed-APK work. Those features are outside this viewer redesign; their
  current status must be determined from that branch.
- Keep README visuals current when UI changes. Review and push coherent
  checkpoints; verify the live Pages deployment after merging viewer changes.

## Hosting

The `Publish viewer` workflow builds `web/dist` on `main` and deploys it to
GitHub Pages. Only the static viewer is published. Camera origins still need
explicit approval, trusted certificates and browser private-network access;
see [networking](networking.md). Do not commit camera credentials or identities.

To resume: select this GitHub repository in a Codex Cloud environment, prepare
the toolchain above, and ask Codex to read this page and validate the current
branch before continuing. The local chat and running preview are not required.
