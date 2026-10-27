# Milestones and delivery ledger

Last updated: 21 October 2026. A checked box means implemented, not physical-device qualification. This ledger must change in the same commit as capability or release-status changes.

## M0 — Reviewable product foundation

- [x] Audit existing direct native/browser WebRTC and snapshot path.
- [x] Define product requirements, architecture, acceptance criteria and staged delivery.
- [x] Serve source-backed documents at an unlisted static `/review.html` entry.
- [x] Provide scoped browser feature controls; block unsupported features.
- [ ] Complete physical LAN/VPN route, codec and audio baseline on the target phones.

## M1 — Local evidence and foreground doorbell

- [x] Add byte/age/count/free-space bounds and populated-database migration to snapshot storage.
- [x] Owner opt-in to one-minute snapshots while armed; preserve manual capture.
- [x] Persist local doorbell rings, rate-limit presses, acknowledge from a paired viewer.
- [x] Viewer alerts and explicit answer; no automatic microphone activation.
- [x] Add automated regression checks and update setup/protocol/validation documentation.

## M1.1 — Ambient camera display and significant-motion wake

- [x] Sample bounded luminance from existing capture without an open viewer.
- [x] Filter sensor noise/uniform exposure changes and require sustained significant movement.
- [x] Quiet-time ambient screen, dim moving clock, touch/motion wake, restore brightness on exit.
- [x] Owner display toggle; no Android lock bypass or background activity launch.
- [x] Pure policy tests for noise, movement, sampling gaps and inactivity.
- [ ] Physical OLED, brightness, rotation, large-font and sustained camera/display qualification.
- [x] Feed owner-enabled on-device person observations into ambient wake.

## M1.5 — Few-click Sites setup (now the recommended direction)

Approved: use GPT Sites for setup and signaling; keep local/direct mode available. See `managed-setup.md` for the revised architecture and actual status.

- [x] Specify install → permissions → short-lived device code → owned fleet journey.
- [x] Implement and locally test D1 registry, hashed device credentials, one-time claims, owner scoping, revocation and bounded signaling jobs.
- [ ] Integrate generated hosted migrations and qualify Sites browser/device identity boundaries.
- [ ] Implement Android outbound enrollment/polling with Keystore secrets and idempotent command execution.
- [ ] Implement the authenticated hosted viewer transport and restore fleet after sign-in.
- [ ] Qualify direct/STUN/TURN connectivity, microphone cleanup and coordinator outages on real phones.
- [ ] Enable managed mode only after a clean first-camera setup passes without certificates or address entry.

## M2 — Rolling video and timeline

- [ ] Prototype shared capture → hardware H.264 → durable MP4 segments on the target phones.
- [ ] Independent recording without viewers; bounded disk use and thermal handling.
- [ ] Recover interrupted segments and surface recording gaps.
- [ ] Paginated history, authenticated playback/export, storage diagnostics and owner retention controls.
- [ ] 24-hour recording soak, disk-full, reboot and clock-change tests.

## M3 — Encrypted peer backup and restore

A snapshot-first slice is implemented now using finalized JPEG evidence and bounded chunks. MP4/video replication still depends on M2 and the streaming expansion.

- [x] Single-use physical enrollment with bidirectional source-bound backup grants and pinned TLS.
- [x] Keystore-sealed directional keys, AES-GCM evidence and authenticated metadata.
- [x] Durable per-peer outbox, resumable chunks, verification receipts and separate replica quota.
- [x] Automatic reconciliation/backoff, corrupt-copy repair and source-offline authenticated restore.
- [x] Viewer replication health and stored peer evidence downloads.
- [x] Automated snapshot interruption, quota, corruption, resume, receipt and revocation tests.
- [ ] Physical multi-phone replication, power-loss durability and multiday restore qualification.

- [ ] Owner recovery-key export and full device-state restore.

## M4 — Background presence and visitor analytics

- [x] Bundle and hash-pin licensed EfficientDet-Lite0 with standard on-device runtime.
- [ ] Benchmark hardware throughput, detection accuracy and temperature.
- [x] Bounded background sampler, person detector, debounced presence, confidence evidence and person wake.
- [ ] Anonymous multi-frame visitor tracker and coverage-aware historical windows.
- [ ] Coverage-aware time-window counts and searchable evidence links.
- [ ] False-positive, missed-detection, occlusion and re-entry evaluation on representative consented recordings.

## M5 — Familiar faces and activity rules

- [ ] Explicit familiar-face enrollment, encrypted local templates, unknown threshold and deletion.
- [ ] Zone, dwell, schedule, line-crossing and obstruction rules with cooldowns.
- [ ] Explain each alert with rule and evidence; show uncertainty.
- [ ] False-match calibration and owner controls before enabling recognition.

## M6 — Release qualification and intuitive operations

- [ ] Reproducible clean setup by an operator unfamiliar with the code.
- [ ] Physical upgrade/rollback qualification preserving evidence and encryption keys.
- [x] Signed prerelease APK, signature/checksum/source receipts and explicit debug-to-release migration guide.
- [x] Prepare gated GitHub tag workflow; repository signing secrets require owner configuration because the integration cannot access them.
- [ ] Execute hosted release automation after secrets are configured; qualify device update/rollback behavior.
- [ ] S23 Plus and Redmi/MIUI: power, permissions, thermal, background and 24-hour soak tests.
- [ ] Real Safari, Firefox, Chromium and TV input/audio/certificate verification.
- [x] Implement encrypted Web Push directly from camera to every subscribed viewer.
- [ ] Qualify actual closed-app vendor/OS notification delivery on supported devices.
- [ ] Publish measured supported device/network matrix and complete backup/restore drill.

## M7 — Installable PWA and background event alerts

- [x] Manifest, standalone viewer, maskable icons and install guidance.
- [x] Versioned offline shell with explicit updates; exclude private APIs/media from caches.
- [x] Optional remembered authorizations with visible device/privacy scope.
- [x] Notification consent from a viewing gesture; unsupported/denied states do not block video.
- [x] Per-camera push scopes, VAPID keys, encrypted event fan-out and durable bounded retries.
- [x] Viewer revocation/unsubscribe and safe notification click routing.
- [ ] Physical installed-PWA, denied permission, force-stop and provider-outage acceptance.

## Change protocol

Every implementation commit updates relevant requirements, runtime capability fields, tests and this ledger. Keep design targets distinct from shipped behavior. Record exact commands, counts, failures and untested hardware in `docs/validation.md`. Build all static review documents into the viewer and APK together. Unsupported features must remain disabled; enabling a browser experiment must not pretend to change phone settings. Push milestone commits progressively when repository authentication is available.
