# Technical architecture

Update: the owner approved GPT Sites registration and signaling as the recommended setup on 20 October 2026. See `managed-setup.md`; the direct/local system below remains the offline/advanced mode.

## Existing system and streaming decision

Android Kotlin owns Camera2 capture, a shared WebRTC VideoTrack, Keystore HTTPS identity, scoped browser grants and SQLite event metadata. React/TypeScript is a static client. Each phone serves the same viewer assets; the bundle can also run on an independent HTTPS static host.

Live streaming already uses the native WebRTC SDK and browser RTCPeerConnection with SDP offer/answer through the camera's authenticated HTTPS API. ICE candidates reach the camera through its API. Each camera provides bounded local STUN discovery on UDP 8444; DTLS-SRTP media goes directly between endpoints. JPEG retrieval is for explicit stored snapshots, not a live-video transport. Keep this standards-based pipeline and qualify codec negotiation and audio on real phones rather than adding a second streaming protocol.

## Component boundaries

- Capture: one Camera2 source feeds local preview, WebRTC and bounded recording/analysis consumers. Slow consumers drop analysis frames rather than blocking live capture.
- Live sessions: owner-bound leases, heartbeat expiry, explicit audio negotiation, one concurrent audio owner; release resources on stop, failure or revocation.
- Evidence: SQLite metadata plus private files, durable temporary-file write, rename and directory fsync before publishing metadata. File identities are UUIDs, not caller-controlled paths.
- Retention: local age, bytes, count and free-space controls. Recovery removes partial/orphan files and reconciles metadata. Disk pressure surfaces an error, never a false “saved” state.
- Doorbell: persisted bounded ring journal; only local phone UI creates rings. Paired viewers list and acknowledge them. Media starts only from an explicit Answer action, with Talk separately controlled.
- Replication (planned): source encrypts immutable evidence, receiver stores ciphertext in a separate budget, source maintains a durable outbox and verified receipts.
- Analytics (planned): local model runner samples capture with a bounded queue; tracker and rule engine write observations independent of media. Browser aggregates per-camera time windows and reports missing coverage.
- Review: `/review.html` embeds the checked-in requirements, architecture, milestones and operations docs at build time. Browser feature preferences are versioned and session-scoped. The page is unlisted, not an authorization boundary.

## Local snapshot milestone

Use the existing EventStore with a backward-compatible SQLite migration for trigger and byte length. Retain the newest 200 snapshots for at most 7 days and 128 MiB. Keep 64 MiB of free storage. Reject new writes if space cannot be reserved after retention. Run maintenance on service startup and periodically, even when no viewer is connected. Owner-enabled automatic capture executes on the service's serial worker, guarded by the existing snapshot admission gate. A failed disk write must not stop HTTPS or poison future scheduled runs.

Manual and scheduled snapshots have distinct triggers. Snapshot history remains compatible with older peers; new capability fields are optional to old viewers. A durable count/byte summary is exposed only to paired viewers. Continuous video uses a separate store in the next milestone; do not relabel snapshots as recorded clips.

## Rolling video design (planned)

Use Android MediaCodec H.264 and MediaMuxer MP4, sharing capture through an EGL input surface where supported. Rotate at keyframe boundaries into 60-second segments; mux only complete timestamps and finalize each segment independently. Audio recording is separately opt-in with AudioRecord/AAC and microphone arbitration. Prefer hardware encoding, negotiate a measured fallback per device, and publish unsupported states. Storage schema: segments(id, originDeviceId, startedAt, endedAt, bytes, sha256, relativePath, state, codec, gapReason). Capture uses monotonic time; UTC metadata records clock corrections.

Write `.partial` files, fsync, close the muxer, hash, rename, sync directory and commit metadata/outbox in one DB transaction. On recovery delete incomplete segments and record the gap; never present corrupt MP4 as playable. Retention evicts oldest unpinned finalized segments, with a finite evidence-pin budget and separate replica budget. A full backup queue must not block local capture. Playback initially downloads a bounded authenticated MP4 segment; HTTP Range support and paginated timeline precede long exports.

## Peer backup protocol (planned)

Provision peer trust explicitly using the household CA plus stable device identity. Issue a distinct backup-only grant with quota and expiry; never copy a browser token into a peer URL. Keep TLS verification and bind credentials to an approved origin/device. No wildcard CORS or trust-all certificate manager.

Encrypt each segment with random AES-256-GCM content key and nonce. Wrap the content key to each authorized restore recipient using a documented standard envelope (for example a reviewed HPKE implementation), including origin, evidence ID, capture window and plaintext digest as authenticated data. A restore/recovery key is exported by an explicit owner flow and never placed in the static page. Use platform-reviewed crypto libraries rather than inventing a new wire cipher.

Proposed routes: POST `/api/v2/replicas/manifests`, PUT `/api/v2/replicas/{id}/chunks/{index}`, POST `/api/v2/replicas/{id}/commit`, GET `/api/v2/replicas/{id}` and DELETE for grant-authorized expiry. Use fixed maximum chunk sizes, streaming I/O, resumable offsets, receiver hash verification, atomic commit and durable receipt. Current v1 HTTP reader does not support PUT bodies or large transfers: a hardened streaming request implementation is a prerequisite, not an assumption.

Outbox states: pending, transferring, verifying, replicated, retrying, expired, failed. Persist attempts, next attempt time and acknowledged recipient. Retry with exponential backoff/jitter and per-peer concurrency one. Verify restore with source offline, wrong keys, bit corruption, grant revocation, quota exhaustion and both peers restarting mid-transfer. Replicas are independent copies; rotation/deletion policy must be explicit, not accidental mirroring of source deletions.

## Background computer vision design (planned)

Use a pinned on-device runtime (LiteRT/TFLite) and licensed, versioned person-detection model. Sample 1–2 frames/second initially; queue size one; release frame references immediately. Track anonymous detections per camera with configurable confidence and expiry. Presence uses debounced transitions, not every frame. Keep model output, tracker ID, observation timestamp, camera ID and model version; aggregate into explicit minute/hour windows.

Face recognition is an independent opt-in pipeline with owner enrollment, an unknown threshold, embedding encryption, removal and complete deletion. Do not claim that a detector or short-lived track proves identity. No external face database. Recognition and cross-camera matching need their own calibration and false-match evaluation before release.

Rules use zones, dwell duration, schedules, line crossing and camera-obstruction signals. Alerts include rule name, window, confidence and evidence references, with cooldown/deduplication. Disable inference under thermal pressure before impairing recording; show analytics unavailable instead of reporting zero. Persist coverage and model failures so the viewer can distinguish missing data from absence of people.

## Versioning, deployment and migration

Keep v1 additive fields optional and capability-gated. Introduce v2 only for breaking contracts such as paginated media and streaming replica uploads. Maintain fixtures and rejection tests in both Kotlin and TypeScript. Schema migrations must preserve existing events/grants; test upgrades from a populated v1 database. Build the viewer before Android so the hidden review content is embedded in every APK. Static hosts must serve `review.html` and assets together; no SPA rewrite is required.

Owner device settings stay local until a separate role-based owner API exists. A paired viewer grant is not authority to enable cameras, enrollment, recording or replication. Browser preferences affect presentation/polling only. No secret, CA key or deployment password belongs in the review bundle.

## Operational failures

Capture stopped: keep HTTPS and history usable, report degraded state. Viewer lost: session lease expires and microphone stops. Peer offline: local recording continues, bounded queue reports lag. Disk full: prune within policy, reject admission with visible status, never delete outside evidence stores. Identity replacement: close sessions and listeners before restart. Process killed/reboot: preserve evidence and settings, require explicit camera arming according to Android foreground-service rules; do not promise unattended restart on OEM devices without a measured supported flow.

## Ambient display and motion wake

CameraRtc samples a 32×24 luminance grid from its existing one-frame-per-second I420 copy. The significant-motion policy removes mean exposure difference, thresholds changed-pixel fraction, requires two consecutive significant observations and resets its baseline on sampling gaps. It reports only a monotonic timestamp, retaining no extra image or biometric data. The capture thread does bounded work and never waits on UI or analytics.

MainActivity applies a 30-second activity timeout while the camera page is foreground, armed and healthy. Ambient mode detaches the local renderer (WebRTC and capture remain independent), hides controls from accessibility traversal, shows a black frame with dim shifting text, hides system bars and sets window brightness to 1%. Scene activity or user interaction restores controls and system brightness. FLAG_KEEP_SCREEN_ON applies only to this foreground mode and is cleared on pause or opt-out. No wake lock, keyguard dismissal, background activity launch or global brightness change is used. Later person inference supplies the same monotonic activity signal after M4 qualification.


## Implemented PWA, push and snapshot replication

The viewer now builds a cache-first static PWA shell and a versioned service worker. Root scope owns the immutable shell cache and explicit app updates. Per-camera push scopes use the same worker without touching the shell cache, allowing independent VAPID identities. Camera APIs, media and authentication responses bypass caching. Remembered browser grants are an explicit opt-in, separate from the service worker.

Push delivery runs directly from each camera to an allowlisted browser push service with RFC 8291 encrypted payloads and RFC 8292 VAPID. A durable SQLite fan-out outbox is scoped to active viewer grants; private signing/subscription secrets are Keystore-sealed. The camera reports provider acceptance separately from actual OS display, and does not need a Sites background sender or bypass credential. See `notifications-pwa.md` for consent and operating limits.

The native person consumer uses the bundled, hash-pinned EfficientDet-Lite0 metadata model via TensorFlow Lite Task Vision 0.4.4. One in-flight analysis samples rotated JPEG evidence at most every two seconds; debounce, confidence threshold, event cooldown and thermal gating bound work. Positive observations update ambient display activity. Runtime availability and sampling times are exposed so missing coverage is not represented as zero people.

The first replication slice protects finalized JPEG evidence using the current bounded HTTPS reader. A single-use physical pairing flow creates separate directional backup-only grants and shared encryption keys; exact certificate pins retain normal hostname/expiry checks. AES-GCM authenticates source/event/timestamp/trigger/confidence/size/digest metadata. Resumable 32 KiB POST chunks fit the existing body bound. Receivers verify/decrypt, hash, fsync, rename and commit before acknowledging. Persisted per-peer outbox ciphertext makes restart/resume safe; bounded periodic inventory reconciliation detects corruption and repairs missing copies. The receiving camera exposes restored evidence through its ordinary authenticated viewer API. See `peer-backups.md` for independent quotas, retention and source-offline verification.

The earlier v2 streaming/video/envelope design remains the expansion plan for large MP4 segments and portable recovery. It is not required to replicate the existing bounded snapshots. Snapshots do not become continuous video by being replicated.
