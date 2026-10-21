# Lantern Android peer

Build with JDK 17 and the checked-in Gradle wrapper. From `android/`, set
`JAVA_HOME` to the local `.tools/jdk-17.0.20.1+1/Contents/Home` (or another
JDK 17) and run `./gradlew :app:testDebugUnitTest :app:lintDebug
:app:assembleDebug`. Set the Android SDK via the local, ignored
`local.properties` (`sdk.dir=...`) or `ANDROID_HOME`. Assemble requires
`../web/dist/index.html`; build the browser bundle first. The APK is
`app/build/outputs/apk/debug/app-debug.apk` and serves the actual Vite assets
from its APK at the peer's HTTPS origin.
The minimum is Android 10 / API 29: this conservatively covers the specified
2020-and-newer OS fleet, including the Galaxy S23 Plus and the Redmi/MIUI
provided those phones actually run Android 10 or later.

## Guided owner setup

First launch guides you through **Name your camera → Secure the connection →
Allow camera and audio → Start this camera → Connect your first viewer**.
Progress resumes on rotation/relaunch. Passwords are not restored; identity
import transactions survive rotation without interrupting a live connection.
Permissions do not auto-arm the phone. Health and local preview update
automatically, and pairing shows an expiring code and viewer confirmation.

After finishing, the camera screen offers preview, pause/resume and pairing.
**Camera settings** holds optional additional HTTPS origins, viewer revocation,
identity replacement and stopping the whole service. Pause, stop, revoke and
identity replacement explain their effects before confirmation. Certificate
addresses are shown separately from optional browser-origin permissions.

Use **Setup help** for the offline guide, or the complete
[setup and end-to-end testing walkthrough](../docs/setup-and-test.md).

On Redmi/MIUI, explicitly allow camera/microphone and notifications; set
Lantern's battery policy to **No restrictions**, allow **Autostart** where
available and consider locking the app in Recents. On the Galaxy S23 Plus,
exclude Lantern from Sleeping/Deep sleeping apps (add to **Never sleeping
apps**) and use **Unrestricted** battery mode where available. OEM labels and
behavior vary by firmware; a sideloaded APK does not automatically receive
special background, network, autostart or notification privileges. Check
foreground-service persistence on each physical phone after screen-off,
network changes and reboot. Relaunch/arm locally after service termination.

Install the household CA **on each viewer as a trusted CA** and import this
peer's device-specific `device.p12` with its password through the Android file
picker. The private key is imported into Android Keystore; neither key nor
password is shipped in the APK or stored as a shared application secret.
Certificate DNS/IP SANs become the peer's approved own HTTPS origins on
`:8443`. In **Camera settings**, approve any *other* viewer origin explicitly (including
Pages or another camera's origin); values are exact, normalized HTTPS origins
without a path. Saving settings updates the running server's allowlist
immediately. Importing a replacement identity stops the old HTTPS listener
and its connections, closes live media sessions, then starts HTTPS with the
new certificate; restart failure is displayed rather than leaving the old
listener advertised as ready. Save settings, grant camera and microphone permissions, then
arm. Generate a new pairing code **on the phone** when pairing; it is valid for
120 seconds and five total attempts. The foreground notification indicates
the service remains active without an open viewer. Disarming stops camera
capture and all sessions; an armed app whose service has stopped needs to be
started again locally.

Core media uses Camera2 → one WebRTC video source shared with native preview,
remote video tracks and manual JPEG snapshots. The camera advertises host
candidates; viewers use only that camera's local STUN discovery, with no
public STUN/TURN server. Each phone runs its own minimal,
unauthenticated UDP STUN Binding responder on **8444** while HTTPS is running;
it reports only the sender's observed source address/port and is not TURN,
signaling, media or an authorization service. Its port appears in `/api/v1/info`
only while the UDP listener is running. UDP bind failure degrades the service
and closes HTTPS rather than advertising discovery falsely. Disarming retains
HTTPS and UDP discovery; stopping the service closes both. The Android peer returns a complete ICE
answer but accepts individually authenticated, owner-bound browser candidates
at `POST /api/v1/sessions/{id}/ice`; a 204 confirms the native WebRTC callback
actually accepted that candidate. Up to three sessions and one
bidirectional audio session are allowed. Snapshot metadata is private SQLite;
JPEGs are durable private files, capped at 200, with up to 50 listed per
request. Person detection is **not** provided (`personDetection: false`).
Replication, clips and owner recognition are **not** provided. WAN isolation
is **not** enforced by the app: configure OS/router/Meshnet access and verify
the selected ICE route on physical devices before treating a deployment as
LAN/Meshnet-only.

Physical-device verification still required: import a locally signed P12,
confirm Android Keystore TLS handshakes and CA/SAN trust from Chrome,
pair and revoke from both same-origin and explicit external origins, confirm
camera/microphone permission behavior and bidirectional sound, test native
preview concurrently with remote video/snapshots, inspect selected ICE route,
restart/service-loss behavior, and fill/evict the local snapshot store. JVM
tests and an APK build do not establish these results. Storage JVM tests use
an injected directory-sync seam because Robolectric cannot fsync directories;
Android device testing must verify the real fsync path.

Optional instrumentation tests compile with
`./gradlew :app:assembleDebugAndroidTest` and produce
`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`. On a
**separately managed** emulator/device, run
`dev.lantern.peer.test/androidx.test.runner.AndroidJUnitRunner` after
installing both APKs. These tests check a real Camera2 frame to JPEG and
shared preview track, actual directory fsync/SQLite storage, and an HTTPS
handshake with a newly generated, in-memory **test-only** CA and PKCS#12
identity. No household CA signing key or reusable test private key is bundled.
The import test skips if an owner's identity is already installed and removes
its own test alias afterward. Compilation alone is not an execution result;
it does not validate the production OpenSSL PKCS#12 profile or physical route.

For a separate, optional OpenSSL-profile import test, provision a **disposable
test-only** camera identity with `scripts/provision.py` including
`--san 127.0.0.1`, and copy its `device.p12` into the debug app's private
`files/` directory (e.g. with a debug `run-as` operation managed by the device
test owner). Pass `lantern.testP12File` (basename, ending `.p12`) and
`lantern.testP12Password` (its disposable export password) as instrumentation
runner arguments. Do not pass a household/production password through
instrumentation arguments. The test refuses an installed identity, checks the
chain, Keystore signature and trusted HTTPS handshake, then deletes the test
key alias and fixture. Keep test passwords out of persistent command logs.

## Actual camera-to-browser gate

On a dedicated test device with both debug APKs installed and the camera
service stopped, run from the project root:

```sh
node scripts/test_camera_interop.mjs --serial <dedicated-device-serial>
```

The opt-in instrumentation fixture captures real Camera2 frames and exchanges
SDP through debug-only, app-private ADB files. Chromium must establish actual
WebRTC transport and decode at least 60 video frames; successful negotiation or a LIVE
label is insufficient. The fixture cleans up its media session and private
files. Never run it on a device currently providing security coverage.
It does not test HTTPS pairing, real audio, Meshnet or firewall isolation.

For the dedicated Android emulator, its private guest addresses are not
directly reachable from the host. Disable that emulator's virtual Wi-Fi
(`adb -s <emulator-serial> shell svc wifi disable`) and enable its emulated
mobile/Ethernet network (`adb -s <emulator-serial> shell svc data enable`).
Pass `--emulator-udp` to create real emulator-console UDP NAT mappings for
the native Ethernet candidates. The driver advertises those actual mappings
in its test-only SDP and deletes its own mappings afterward. This option
refuses physical-device serials. The Chromium raw-IP lab setting and NAT
mappings are not production viewer settings and do not qualify a real route.
Use `--adb <path>` and `--adb-port <port>` for a separately owned ADB bridge.
Restore emulator network settings if reusing that dedicated test instance.

## Person detection, notifications and camera redundancy

Signed installable APKs are published through [GitHub Releases](https://github.com/venkate5hgunda/lantern/releases). [Release guidance](../docs/releases.md) covers install/update compatibility, checksums, signing-key backup and the optional tag workflow.

Camera settings expose opt-in local person detection and Manage redundant camera backups. The bundled EfficientDet Lite0 model is pinned and licensed in `app/src/main/assets/models/README.md`. Two positive samples confirm presence; three misses clear it. Detection is sampled while armed, pauses at severe thermal status and does not identify people. Confirmed events wake the foreground ambient screen, save a snapshot and enqueue encrypted Web Push to all active subscribed viewers. Camera/microphone permissions and the visible Android foreground service remain necessary; battery optimization settings cannot guarantee OEM process lifetime.

Backup pairing configures two separately authorized directions using the receiving phone's certificate fingerprint and a fresh physical code. Snapshot transfers are encrypted, resumable, integrity checked and bounded by quotas. Keep both phone addresses reachable and their foreground capture services running. See [operations](../docs/product/operations.md), [PWA alerts](../docs/product/notifications-pwa.md) and [backup recovery](../docs/product/peer-backups.md) for complete setup and limitations. App uninstall destroys its private keys and local evidence; upgrade with the same signing identity instead.
