# Lantern

Personal, autonomous Android cameras with a direct browser viewer. Each phone
owns its camera, HTTPS identity, authorizations and snapshot history. A viewer
connects to each camera independently; no household server, hosted signaling,
public STUN/TURN service or media relay is required.
Each current camera also runs its own small, bounded STUN discovery socket
on UDP 8444. It discovers a viewer address without relaying media, acquiring
the viewer microphone or introducing a shared server.

**Development prototype, not a deployment-certified security system.** Android
10 / API 29 is the minimum, including the intended Galaxy S23 Plus and
Redmi/MIUI fleet if their installed OS meets that minimum. Physical phone,
browser/TV hardware and Meshnet qualification remain required. Wired power
does not bypass Android permissions, foreground-service rules, OEM process
termination, thermal throttling or battery safety.

## Current scope

- Native Camera2 capture, shared local preview and on-demand WebRTC video.
- Provisioned HTTPS, owner-generated single-use pairing codes, expiring hashed
  grants, local revocation and owner-bound, bounded sessions.
- Browser-initiated audio sessions with explicit listen/talk controls and
  microphone release on stop/disconnect.
- Manual JPEG snapshots with UTC metadata, durable private storage, bounded
  retention and authenticated browser history/downloads.
- Responsive, keyboard-accessible viewer assets served by every phone. The
  same static bundle can be hosted elsewhere without becoming a camera hub.

Person detection, motion/person-triggered recording, video clips, a doorbell
press/notification workflow, encrypted peer replication, cloud uploads and
owner recognition are **not implemented**. There is no automatic peer
discovery or autonomous failover of a camera's stored events yet. Cameras are
added explicitly by HTTPS address and paired individually.

## Build

For the complete first-camera walkthrough, use
[Set up and test Lantern](docs/setup-and-test.md). It covers the new five-step
Android setup, viewer certificate trust, pairing, video, two-way audio,
snapshots, revocation and multi-camera testing. **Setup help** in the app
also provides offline guidance.

Use Node 22.12+ or Node 24, Python 3, OpenSSL, JDK 17 and an Android SDK with
API 35 available. Install browser engines for browser tests; those engines are
not physical iPhone, Windows or TV devices.

```sh
cd web
npm ci
npx playwright install chromium firefox webkit
npm run build
cd ../android
# Set JAVA_HOME to your JDK 17 and ANDROID_HOME to your Android SDK.
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The browser build must precede Android assembly: Gradle embeds `web/dist/` in
the APK. APK output:
`android/app/build/outputs/apk/debug/app-debug.apk`.
Windows native builds use the checked-in `gradlew.bat`.
For native setup and Samsung/MIUI lifecycle settings, see
[android/README.md](android/README.md).

## Provision and pair

Use a private, offline household CA and a separate identity for each phone.
Commands prompt for passwords; do not put household passwords in command
arguments, source files, tests or browser URLs. The output paths must not
already exist. The ignored `provisioning/` directory is a default local
location, **not** a location to publish or commit.

```sh
python3 scripts/provision.py init-ca --out provisioning/household-ca
python3 scripts/provision.py create-peer \
  --ca provisioning/household-ca --out provisioning/front-door \
  --name "Front door" \
  --san front-door.nord --san 192.168.1.20
```

Replace the names and addresses with the phone's actual LAN/Meshnet DNS names
and IPs. Every address used in a browser must appear in that phone's
certificate SANs. Reserve LAN addresses or reprovision when they change.

1. Keep the encrypted household CA signing key offline. Install only its
   public `ca-cert.pem` as a trusted CA on each viewer. On iOS, enable full
   trust for the installed CA; TV/browser firmware may not support this
   configuration and must be qualified. Do not bypass certificate warnings.
2. Sideload the APK. Transfer only this phone's `device.p12` privately to the
   phone, import it through Lantern's file picker, then remove the transfer
   copy. The application imports the private key into Android Keystore.
3. Follow the five-step phone setup: name the camera, import its identity,
   allow access, start the camera, then connect and verify a viewer.
   Optional external browser origins are in **Camera settings**, not required
   for the camera's own hosted viewer. Importing a replacement identity
   restarts HTTPS and terminates existing sessions.
4. Open the camera at `https://<certificate-name-or-ip>:8443/`. Approve a
   different viewer's exact origin on every camera it will access. Paths are
   not part of origins.
5. Generate a fresh code on the phone, enter that camera's HTTPS address and
   the six-digit code in the viewer, then Watch. Use Connect with audio,
   Listen and Talk explicitly when needed. Remove camera forgets the tab's
   credentials; **revoke in Camera settings** to terminate its authorization.

Pairing codes last 120 seconds, allow five total failures and are consumed on
success. Grants expire within 30 days. Credentials live in session storage,
not persistent local storage. Closing a tab is not revocation.
There are at most three sessions per camera, one with audio. Unreachable or
abandoned sessions expire after 30 seconds.

## LAN, Meshnet and static hosting

Meshnet is an optional private transport, not application authorization.
Enable it on both the camera and a remote viewer, configure the appropriate
peer access permissions and use certificate-covered Meshnet addresses.
Ordinary NordVPN VPN access is not equivalent to Meshnet access. Current
physical-device Meshnet media routes are **not verified**.

The application has no public relay, but **does not enforce a host firewall
or prove that every selected media route is LAN/Meshnet-only**. Do not expose
port 8443, configure router forwarding/UPnP, or deploy on publicly reachable
interfaces and call that isolated. Configure OS/router IPv4 **and IPv6**
inbound policy, verify mobile-data behavior, disable public forwarding and
inspect actual selected ICE candidate pairs before treating a setup as
private. Deployment isolation is an outstanding acceptance gate.
The policy must cover UDP 8444 and negotiated WebRTC ports as well as HTTPS;
STUN address discovery is deliberately not application authentication.

GitHub Pages can host `web/dist/` as static files only, using Vite's relative
asset paths. No credentials, certificates, snapshots or streams belong in
the published files. An origin such as `https://<account>.github.io` must be
explicitly approved on every camera. Some browsers additionally require
local-network permission and trusted certificates for HTTPS-to-private-network
requests; firmware/policy can prevent them even with correct CORS.
Peer-hosting avoids making Pages availability a prerequisite.

## Verification

```sh
python3 -m unittest discover -s scripts -p 'test_*.py'
cd web
npm test
npm run build
npx playwright test
npm audit --audit-level=moderate
cd ../android
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

Browser tests distinguish mocked HTTPS protocol cases from **real encoded
WebRTC transport** against a local test-only reference peer. The reference
encoder is Chromium; the viewer runs the selected Chromium, Firefox or WebKit
engine. Phone/mobile/TV profiles emulate viewport/input/layout, not their
physical OS, microphone, speaker, hardware codec, certificate store or remote
control. The test-only peer is not part of the deployed architecture.

Native instrumentation uses real Android Camera2, WebRTC preview,
SQLite/directory fsync, Keystore TLS and the embedded HTTPS API. It uses
disposable identities, refuses to replace an owner's identity and cleans up
its own keys. A separate optional fixture verifies the exact OpenSSL
provisioning profile; see the Android README. Never give it a production CA
key or production password.

Before installing for real security use, complete
[the physical-device acceptance checklist](docs/device-acceptance.md).
Executed checks and their limits are recorded in
[the validation receipt](docs/validation.md). The optional real
Android-camera-to-Chromium gate is documented in
[the Android README](android/README.md#actual-camera-to-browser-gate).
The shared wire contract is [docs/protocol.md](docs/protocol.md).

## License

Original project code and assets are licensed under the [MIT License](LICENSE).
Copyright (c) 2026 Venkatesh Gunda.
Third-party code, assets, and content remain subject to their respective licenses.
