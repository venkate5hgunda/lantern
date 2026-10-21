# Current validation — 21 October 2026

Managed Linux workspace, JDK 17, Android SDK API 35 and Node 24. The PWA viewer, per-viewer encrypted event push, optional local person detection and bidirectional encrypted snapshot replication are implemented. This receipt supersedes feature-status claims in older receipts.

- 60 viewer unit tests and the production TypeScript/Vite build pass. Output includes both HTML entrypoints, manifest, icons and versioned service worker. Both HTML pages are explicitly precached; camera API/media responses are excluded.
- All 102 browser cases pass across Chromium, Firefox, WebKit and mobile/TV viewport profiles. Checks cover real encoded WebRTC, accessibility, PWA cache/offline launch, remembered-camera opt-in, notification consent/subscription and independent replica downloads. Notification tests use API doubles, not live push vendors. WebKit uses a real origin socket outage because native offline emulation fails even for a minimal literal-response worker in this environment.
- 58 Android JVM tests pass, including the RFC 8291 encryption golden vector, VAPID signatures, durable viewer fan-out/revocation/retry, presence policy, metadata migration and actual SQLite/filesystem encrypted replication with lost commits, restart, quotas, newer-evidence preservation under old retries and corruption repair.
- Android lint and debug application/instrumentation APK assembly pass. The pinned EfficientDet Lite0 checksum and viewer entrypoints are checked before Android build. Model instrumentation is compiled, not executed.
- The dependency audit identified Vite build-tool advisories; the pinned version was updated from 7.3.6 to 7.3.7 and the audit now reports zero vulnerabilities.
- Seven provisioning tests and six coordinator SQL tests pass. The coordinator remains a local foundation; the static review Site does not deploy managed phone enrollment or signaling.

No physical Android model inference/accuracy, OEM background lifetime, OLED behavior, actual push-vendor delivery, network backup soak or power-loss qualification was performed. Background push needs browser permission, platform support and internet access. Replication is eventual while peers are reachable and covers bounded JPEG snapshots. Continuous video, unique-person/face recognition and suspicious-activity classification remain planned. Complete the device acceptance checklist before relying on these features.

## Android release publication

The signed `v0.2.0-alpha.1` prerelease uses version code 2 and a dedicated stable release signing key. Private key material stays outside the repository and public release assets. Repository Actions secrets are inaccessible to the connected integration (HTTP 403); tag automation is prepared and disabled until the owner configures signing secrets. Publication uses the locally verified release build. Hosted workflow execution and physical install/update compatibility remain separate gates.

# Previous validation — 20 October 2026

Managed Linux workspace, JDK 17 / Android SDK API 35 / Node 24. This receipt supersedes current-feature status in the historical receipt below; historical hardware results remain dated evidence, not re-executed checks.

- 56 viewer unit tests pass and the standard `npm run build` succeeds, producing `index.html`, `review.html` and shared assets.
- All 78 Playwright cases pass across Chromium, Firefox, WebKit, mobile WebKit, mobile Chromium and TV Chromium layouts. They include actual encoded WebRTC video/audio, microphone release, capability-gated doorbell acknowledgement, local storage reporting, review controls, keyboard layout and automated accessibility checks.
- 46 Android JVM tests pass, including populated v1 storage migration, age/byte/count/free-space retention, recovery, persisted doorbell cooldown/acknowledgement, motion/noise/exposure filtering and ambient timeout behavior.
- Android lint and debug app/instrumentation APK assembly succeed. Both viewer entrypoints are required before every Android pre-build.
- Seven provisioning tests pass. Six coordinator tests pass using the actual initial SQL schema and Node SQLite through a D1 adapter (`node coordinator/worker.test.mjs`).

The pinned Rollup tree-shaking pass stalled in this environment. The repository now explicitly disables that pass so the normal documented production build completes; type checking and bundling still run. No dependency versions were changed.

Firefox originally failed on empty end-of-candidates markers; the viewer now ignores them. The remaining lab audio failure was a synthetic Web Audio oscillator waiting for an unavailable OS output device. The Firefox fixture now uses its standard fake microphone device instead, while assertions still verify real RTP packets and track shutdown. This does not qualify physical acoustics or permission prompts.

No physical Android device, emulator instrumentation execution, real OLED display, keyguard wake, OEM process lifetime, LAN/VPN route, NAT/TURN service, person model or backup restore was qualified in this workspace. Ambient UI is compiled and its policies tested; physical dimming/motion/display behavior remains in the device checklist. New recognition and recording/backup capabilities remain disabled/planned.

The Sites registry/signaling code is a locally tested backend foundation only. Native enrollment/polling, the authenticated viewer transport, hosted migrations and Sites phone-access authentication remain integration gates. The private review Site publishes the static viewer/documents, not the coordinator. Current camera setup still requires the direct/local identity flow.

# Historical validation — 4 October 2026

Executed on 4 October 2026 on an Apple Silicon macOS host. This is a
development-prototype receipt, not certification of the intended household.

## Browser and provisioning

54 viewer unit tests and 7 OpenSSL provisioning tests passed. The production
viewer build succeeded. `npm audit --audit-level=moderate` reported zero
vulnerabilities in the installed dependency graph.

All 60 Playwright cases passed across desktop Chromium, Firefox and WebKit,
iPhone-layout WebKit, Pixel-layout Chromium and TV-layout Chromium. This
includes 42 mocked protocol cases and 18 real-media/accessibility cases.
Media checks use actual encoded video, decoded video frames, received Opus
samples and browser-to-reference-peer audio packets, with explicit microphone
activation and shutdown. Accessibility checks include automated WCAG checks
and keyboard interaction.

The reference camera is test-only. HTTPS interception is not proof of
certificate trust. Layout profiles are not tests on physical iPhones, Windows,
Android phones or televisions; synthetic audio is not real microphone/speaker
or echo-cancellation qualification.

## Android

The minimum supported build target is Android 10 / API 29, with compile/target
API 35. Intended physical phones are Galaxy S23 Plus and Redmi/MIUI.
Native JVM tests, lint, debug assembly and real Android 16 / API 36 emulator
instrumentation are separate gates.

36 native JVM tests passed, Android lint reported zero errors, and both debug
APKs assembled. The three embedded viewer assets match `web/dist/`
byte-for-byte.

All seven guided-owner UI instrumentation cases passed in normal/light mode
and again at 160% system font scale with dark mode. These cover first-run
guidance and inline validation, rotation/relaunch without automatic arming,
accessible labels and touch targets, offline help, identity import and
explicit start, pairing/settings/pause, invalid imports and missing
certificate addresses. Startup also restores certificate-covered viewer
origins after interrupted setup metadata persistence.

The document-picker result is intercepted in the test; PKCS#12 parsing,
Keystore import, camera startup and authenticated HTTPS pairing are real.
External file-picker navigation remains a physical-device check. The UI test
simulates the owner's explicit video confirmation; it does not substitute
that checkbox for browser decoding. Initial setup screenshots were visually
inspected in light mode and large-font dark mode.

All five core instrumentation cases passed on the dedicated Android 16
emulator: real Camera2/JPEG and multi-frame shared VideoTrack delivery,
durable storage, Keystore TLS and the embedded HTTPS API, the exact
OpenSSL-generated PKCS#12 import profile, and UDP STUN round-trip/immediate
rebind.

The separate actual Android-camera-to-Chromium gate passed with Chromium
140.0.7339.186: **60 decoded frames at 180 x 320** in the final artifact run.
Earlier runs reached 270 x 480 and 360 x 640; these are observed adaptive
stream dimensions, not a guaranteed resolution or image-quality qualification.
It used real Camera2
capture and native WebRTC, not the reference camera. Signaling traveled
through private ADB fixture files; actual emulator-console UDP NAT mappings
made the guest media sockets reachable. This proves capture, negotiation,
encoding, transport and browser decoding in that lab configuration, not a
physical LAN/Meshnet route or browser-to-camera HTTPS trust.

App APK: `android/app/build/outputs/apk/debug/app-debug.apk`.
SHA-256: `1f1196c2949260b391bc5980a240d445d03058031f5353894a3574834759d154`.
Test APK SHA-256:
`de269e4c401f8c8b3e16b63d6a7c3f3f4347f78bc3371a08382ee6c81a3ad21b`.

Install and perform the full owner/viewer walkthrough in
[setup-and-test.md](setup-and-test.md).

Neither emulator results nor a successful build establish OEM background
behavior, hardware codecs or physical routes. Actual native-camera audio
and browser-to-phone acoustics are not qualified by the reference-peer tests.

## Outstanding qualification

Physical S23 Plus and Redmi/MIUI testing, actual iOS/macOS Safari/Windows/TV
browser and trust-store behavior, real two-way acoustics, 24-hour wired
operation, LAN/Meshnet route selection and IPv4/IPv6 public-network isolation
remain pending. Follow [device-acceptance.md](device-acceptance.md).

Person detection, automatic recording, doorbell notifications, encrypted
peer backup, cloud uploads and owner recognition are not implemented.
