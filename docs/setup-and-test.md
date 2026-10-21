# Set up and test Lantern

This walkthrough takes one camera and one browser viewer from installation
to a tested connection. Repeat the camera steps for your Galaxy S23 Plus and
Redmi/MIUI. Android 10 or later is required.

**Development prototype:** a working stream does not prove network isolation,
OEM background reliability or suitability for security use. No person
detection, automatic recording, doorbell alerts, cloud uploads or peer backups
are implemented yet. Snapshots are manual.

## 1. Prepare one camera and one viewer

Use a phone on wired power and a computer, tablet or second phone with a
WebRTC-capable browser. Begin with both on the same private Wi-Fi network;
leave remote access and static hosting until the local connection works.

Find the camera's current IP in its Wi-Fi connection details. Reserve that
address in your router so it does not change. The examples below use
`192.168.1.20`; **replace it with your phone's actual address**.

Check that guest-network/client isolation is not blocking communication
between your own devices. Do not enable internet port forwarding or UPnP
for the cameras. Keep the CA signing key off all phones and out of any
published website or repository.

## 2. Install the updated APK

The built development APK is:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

Copy it privately to the phone and open it to install, allowing installation
from your chosen file manager if Android prompts. Alternatively, enable USB
debugging and use an explicit device serial:

```sh
adb devices -l
adb -s YOUR_PHONE_SERIAL install -r android/app/build/outputs/apk/debug/app-debug.apk
```

`-r` updates the existing app while keeping its data. If Android reports a
signing-key mismatch, investigate rather than uninstalling or clearing data:
that would remove this phone's identity, authorizations and saved snapshots.

To build instead of using the existing APK, follow [the root build
instructions](../README.md#build). Build the web assets before the APK.

## 3. Create this phone's identity on your computer

From the Lantern project root, create the household CA **once**:

```sh
python3 scripts/provision.py init-ca --out provisioning/household-ca
```

The command prompts for a password of at least 12 characters. Keep this CA
password and encrypted `ca-key.pem` offline. The public `ca-cert.pem` is
what viewers trust; it is not a signing key.

Create a different identity for each camera:

```sh
python3 scripts/provision.py create-peer \
  --ca provisioning/household-ca \
  --out provisioning/front-door \
  --name "Front door" \
  --san 192.168.1.20
```

Enter the CA password and choose this device's export password when prompted.
The **export password**, not the CA password, is used in the Android app.
The output includes `device.p12`, `device-cert.pem` and `identity.json`.

Add another `--san` for every additional DNS name or IP you will actually use
to reach this phone. Names must resolve to the phone from the viewer. Do not
include `https://`, a port or a path in `--san`.

For the second phone, use a new output directory and its own actual address:

```sh
python3 scripts/provision.py create-peer \
  --ca provisioning/household-ca \
  --out provisioning/kitchen \
  --name "Kitchen" \
  --san 192.168.1.21
```

Existing output directories are never overwritten. When reprovisioning, use
a new directory. Do not share a camera identity between phones.

## 4. Follow the app's five setup steps

Open Lantern. Setup help is available at the top of every screen and includes
offline provisioning and test instructions.

**Name your camera.** Enter a recognizable name and select **Save camera
name**. This is a display name, not a DNS name.

**Secure the connection.** Transfer only this phone's `device.p12` privately
to the phone. Select **Choose device.p12**, choose the file, enter its export
password, then **Import identity**. Wait for **Identity installed**. Delete
the transfer copy and select **Continue to permissions**. The private signing
key is imported into Android Keystore; the password is not saved. Returning
from another app or recreating this screen clears password input.

**Allow camera and audio.** Select **Allow access**. Grant camera and
microphone access; both are required by this build. Allow status notifications
when offered. Denying notifications does not block setup, but enable them for
visible service status. If media access was denied, retry or use **Open Android
app settings**, then return. Select **Continue to start**. Permission approval
does not automatically turn on the camera.

**Start this camera.** Select **Start camera**. Wait for **Camera is on** and
check the automatically attached preview. Health updates automatically; no
Refresh action is necessary. Select **Continue to viewer setup**.

**Connect your first viewer.** Complete the trust and pairing steps below.
Finish only after you can see actual live video in the viewer. Check **I can
see live video in the viewer**, then **Finish setup**.

Progress resumes after reopening or rotating the app. If the service has
stopped before setup finishes, the flow returns to the start step; it does
not silently re-arm the camera.

## 5. Trust the household CA on the viewer

Privately transfer **only `provisioning/household-ca/ca-cert.pem`** to each
viewer. A private CA can authenticate websites, so trust only your own public
certificate and keep its signing key protected.

On macOS, import the public certificate into the appropriate Keychain and
enable trust for SSL. Chrome/Edge typically use the operating system's
locally trusted roots. On Windows, import it into the appropriate account's
**Trusted Root Certification Authorities** store. Firefox may require a
separate import under its certificate **Authorities**, trusting it to
identify websites, depending on browser policy.

On iPhone/iPad, install the certificate profile, then explicitly enable full
trust under **Settings > General > About > Certificate Trust Settings**.
On Android viewers, use the system's CA-certificate installation settings.
TV/browser firmware may not support installing a trusted CA or may lack
usable WebRTC; those devices are not qualified until the actual browser works.

Exact labels vary by OS and browser version. Confirm the displayed issuer
and certificate match your household CA. **Never bypass a warning, disable
certificate validation or use an ignore-certificate-errors browser flag.**

## 6. Open the browser viewer and pair

On the camera's final setup screen, select a certificate-covered address
that is reachable from this viewer, then **Copy camera address**. For the
sample LAN address it is:

```text
https://192.168.1.20:8443/
```

Open that address on the viewer. Allow local-network access if the browser
asks. The page must load without a certificate warning.

Select **Pair your first camera** or **Add camera**. Enter the camera's HTTPS
address. Once its identity/protocol check succeeds, generate a code on the
phone, enter the **Six-digit pairing code**, name the viewer and select
**Pair camera**.

Codes last 120 seconds, allow five total failed attempts and are single-use.
The phone displays a countdown and recognizes successful pairing. If the
code expires or is limited, generate a new one. Pairing credentials are held
in that browser's tab/session, not permanently in local storage. Closing the
tab is not revocation.

Select **Watch** and move in front of the camera. Check changing imagery,
not merely a connected label. The phone's setup completion requires your
explicit confirmation that video is visible.

## 7. Test the entire first-camera workflow

**Video and independent operation.** Keep moving in view while taking a
snapshot and opening a second viewer. Both should receive real imagery
without restarting capture. Close one viewer: the other should keep working.
Up to three simultaneous sessions are allowed; a fourth must report capacity
rather than displacing an existing viewer.

**Snapshots and metadata.** Select **Take snapshot**, reopen the event
history, open the new snapshot and download it. Check the camera name and
timestamp correspond to the event. Native metadata includes device ID, UTC
creation time, manual trigger and authenticated snapshot path; it is stored
with the event, not embedded into JPEG EXIF. The camera retains up to 200
snapshots and lists the latest 50. Automatic recording is not occurring.

**Two-way audio.** Disconnect the video-only session and select **Connect
with audio**. Select **Listen** and make a sound near the camera. Select
**Talk**, grant the viewer microphone and speak toward the phone. Verify sound
in both directions using headphones or separate rooms to limit feedback.
Select **Stop talking**, then **Disconnect**, and confirm the viewer's
microphone indicator turns off. Video-only Watch must not request a viewer
microphone. A second simultaneous audio session must be rejected.

**Revocation.** Pair a second browser with its own viewer name. On the phone,
open **Camera settings > Paired viewers > Revoke access**, read the confirmation
and revoke that viewer. Its live session must end and subsequent video/event
requests must fail. Re-pairing requires a new code.

**Pause and stop.** Select **Pause camera** and confirm. Live video/audio must
stop, but paired viewers may still access saved snapshots. Resume locally.
Then use **Camera settings > Stop camera and connection**: the whole viewer
connection must go offline. Start the camera locally to restore service.

**Lifecycle.** Repeat while the screen is off, after leaving the app,
changing Wi-Fi, unplugging/replugging and rebooting. This build does not promise
automatic resurrection after Android stops it; reopen and start locally.
Run a 24-hour wired soak test, checking heat, dropped frames and reconnects.
Do not use a damaged or swollen battery.

Samsung: use **Unrestricted** battery mode and **Never sleeping apps**.
Redmi/MIUI: use **No restrictions**, enable **Autostart** if available and
consider locking Lantern in Recents. Firmware labels and behavior vary.

## 8. Add the second camera or a separately hosted viewer

Repeat identity import and setup on the second phone. If the browser is opened
at the first camera's address and will also access the second, add the
**first camera's origin** in the second phone's **Camera settings > Additional
viewer origins**, then save. For example:

```text
https://192.168.1.20:8443
```

Do not include a path. The camera's own certificate-covered origins are
managed automatically and do not need to be entered in this optional field.
An extra origin is permission for a browser page to call this camera; it is
not peer discovery, a camera address or a grant to watch video.

Add and pair the second camera in the browser. Disconnect or shut down the
first phone: the second must still capture and serve its own viewers directly.
The first phone's saved events are not backed up by this version.

For a static-hosted viewer, publish **only `web/dist/`**, then approve its
exact HTTPS origin on each camera. GitHub Pages hosts static assets, not a
camera server. Never publish identities, CA keys, grants, snapshots or streams.
Begin this test only after peer-hosted viewing and trust work.

## 9. Qualify privacy and optional private remote access

The app does not implement a host firewall or prove that all media routes are
private. Configure router/OS policy for HTTPS TCP 8443, discovery UDP 8444
and negotiated WebRTC ports, covering **IPv4 and IPv6**. Test unauthorized
off-network reachability and mobile-data behavior. A trusted viewer connection
is not proof that an unauthorized external route is blocked.

If a private Meshnet transport is available in your environment, enable it
on both endpoints, configure peer permissions and include the camera's
actual Meshnet name/IP in its certificate. Ordinary NordVPN VPN access is not
equivalent. Test from an off-LAN authorized viewer and inspect the actual
selected ICE candidate pair. There is no public TURN relay to rescue a route
that cannot carry direct media.

Complete [the physical-device acceptance checklist](device-acceptance.md)
on both phones and each intended browser/TV. This is the deployment gate,
not a claim already established by emulation.

## When something fails

**Certificate warning:** check CA trust, full iOS trust, certificate expiry
and the exact address in the certificate SANs. Reprovision into a new directory
and replace the identity if the phone's address changed.

**Import failed:** choose the correct phone's `device.p12`, enter its export
password and check file readability/expiry. The password field is cleared
when the screen is recreated. Do not use the CA signing key as an identity.

**Camera needs attention:** read its actionable health message. Check runtime
permissions, free storage, background restrictions and competing camera apps.
For an unavailable connection/discovery port, stop the service in Settings
and start again. Do not assume a failed stop or revoke succeeded.

**Page works but video does not:** check the phone is on, session capacity,
local-network browser permission, Wi-Fi client isolation and negotiated UDP
routes. A successful HTTPS request alone does not establish WebRTC transport.

**Pairing fails:** load the trusted viewer first, then generate a fresh code.
Check the exact optional browser origin if the page came from another phone
or website. Never replace the allowlist with a wildcard.

**Silent audio:** use Connect with audio and the explicit Listen/Talk controls,
check both device permissions and speaker volume, and avoid a second audio
session. Verify actual sound, not only RTP counters.

## Automated checks for development

See [the root verification commands](../README.md#verification) and
[the recorded results and limits](validation.md). Native instrumentation
belongs on a **separate unprovisioned test device**, never an active security
camera. With both debug APKs installed, the guided owner UI can be checked with:

```sh
adb -s YOUR_TEST_SERIAL shell am instrument -w \
  -e class dev.lantern.peer.OwnerUiTest \
  dev.lantern.peer.test/androidx.test.runner.AndroidJUnitRunner
```

The actual native-video/browser gate and its emulator-only UDP mappings are
documented in [the Android README](../android/README.md#actual-camera-to-browser-gate).
Mocked protocols, synthetic audio and mobile/TV layout profiles do not replace
physical trust, microphone/speaker, OEM lifecycle and network-isolation tests.


## Rolling snapshots and doorbell

On the phone, open Camera settings and enable **Save a snapshot every minute while armed**. No viewer is needed for capture. Retention is bounded by 200 images, 7 days and 128 MiB, with 64 MiB free space reserved; the earliest limit wins. Pause/stop suspends capture. Open **Local storage** in a paired viewer to inspect usage and refresh the status. This is a still-image ring, not continuous video or a peer backup.

Press **Ring doorbell** on the armed phone. An open, visible paired viewer polls compatible cameras every five seconds. **Answer doorbell** starts an audio session; tap **Listen** and **Talk** explicitly. **Dismiss ring** acknowledges it across viewers; a failed connection leaves it unanswered. Old/missed rings remain in the bounded doorbell history. Closed/background browser delivery is not guaranteed.

For product requirements, the approved Sites setup direction and the delivery ledger, open the unlisted **`/review.html`** path on the same static host. The current direct/local setup above remains necessary until the Sites client integration is released.

## Ambient display

Leave Lantern on the armed camera page. **Ambient display with significant-motion wake** defaults on in Camera settings. After 30 seconds without input/significant motion, the local preview yields to a black screen with a dim clock that shifts position every 30 seconds. Movement or a tap restores the controls; capture and live sessions continue independently. System brightness and screen-timeout behavior return when leaving the app or disabling the setting. Hardware lock remains Android-controlled. Enable on-device person detection in Camera settings for confirmed-person wake and event snapshots; motion wake itself does not classify people.

## PWA, person alerts and redundant backups

The viewer is an installable PWA. Follow [PWA and notifications](product/notifications-pwa.md) to install, remember cameras on a trusted device and enable alerts for each viewer. Enable person detection in Android camera settings, then verify a confirmed-person snapshot and presence status. Physical model accuracy and push delivery need qualification on your devices.

Follow [peer backup pairing](product/peer-backups.md) on two armed phones. Verify both directions, pending/verified counts and a recovered snapshot with the source disconnected. Replication covers bounded JPEG snapshot history; it does not record continuous video or synchronize system settings.
