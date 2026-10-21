# Milestones and delivery ledger

Last updated: 20 October 2026. A checked box means implemented, not physical-device qualification. This ledger must change in the same commit as capability or release-status changes.

## M0 — Reviewable product foundation

- [x] Audit existing direct native/browser WebRTC and snapshot path.
- [x] Define product requirements, architecture, acceptance criteria and staged delivery.
- [x] Serve source-backed documents at an unlisted static `/review.html` entry.
- [x] Provide scoped browser feature controls; block unsupported features.
- [ ] Complete physical LAN/VPN route, codec and audio baseline on the target phones.

## M1 — Local evidence and foreground doorbell

- [ ] Add byte/age/count/free-space bounds and populated-database migration to snapshot storage.
- [ ] Owner opt-in to one-minute snapshots while armed; preserve manual capture.
- [ ] Persist local doorbell rings, rate-limit presses, acknowledge from a paired viewer.
- [ ] Viewer alerts and explicit answer; no automatic microphone activation.
- [ ] Add automated regression checks and update setup/protocol/validation documentation.

## M2 — Rolling video and timeline

- [ ] Prototype shared capture → hardware H.264 → durable MP4 segments on the target phones.
- [ ] Independent recording without viewers; bounded disk use and thermal handling.
- [ ] Recover interrupted segments and surface recording gaps.
- [ ] Paginated history, authenticated playback/export, storage diagnostics and owner retention controls.
- [ ] 24-hour recording soak, disk-full, reboot and clock-change tests.

## M3 — Encrypted peer backup and restore

Depends on M2 finalized evidence and streaming HTTP hardening.

- [ ] Explicit peer enrollment and backup-only grants.
- [ ] Standard encrypted envelopes and owner recovery-key export.
- [ ] Durable outbox, resumable bounded transfer, verified receipts, separate replica quota.
- [ ] Viewer replication health and replica browsing.
- [ ] Restore with source offline, corrupted chunks, expired grants, full storage and interrupted transfers.

## M4 — Background presence and visitor analytics

- [ ] Pin licensed person model/runtime and benchmark hardware throughput and temperature.
- [ ] Bounded background frame sampler, person detector, anonymous tracker and presence transitions.
- [ ] Coverage-aware time-window counts and searchable evidence links.
- [ ] False-positive, missed-detection, occlusion and re-entry evaluation on representative consented recordings.

## M5 — Familiar faces and activity rules

- [ ] Explicit familiar-face enrollment, encrypted local templates, unknown threshold and deletion.
- [ ] Zone, dwell, schedule, line-crossing and obstruction rules with cooldowns.
- [ ] Explain each alert with rule and evidence; show uncertainty.
- [ ] False-match calibration and owner controls before enabling recognition.

## M6 — Release qualification and intuitive operations

- [ ] Reproducible clean setup by an operator unfamiliar with the code.
- [ ] Signed APK release process, update/migration/rollback guide and embedded asset verification.
- [ ] S23 Plus and Redmi/MIUI: power, permissions, thermal, background and 24-hour soak tests.
- [ ] Real Safari, Firefox, Chromium and TV input/audio/certificate verification.
- [ ] Optional native viewer or notification bridge decision for reliable closed-browser alerts.
- [ ] Publish measured supported device/network matrix and complete backup/restore drill.

## Change protocol

Every implementation commit updates relevant requirements, runtime capability fields, tests and this ledger. Keep design targets distinct from shipped behavior. Record exact commands, counts, failures and untested hardware in `docs/validation.md`. Build all static review documents into the viewer and APK together. Unsupported features must remain disabled; enabling a browser experiment must not pretend to change phone settings. Push milestone commits progressively when repository authentication is available.
