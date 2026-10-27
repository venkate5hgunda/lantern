# PWA viewer and event notifications

## Install and open a viewer

The viewer is now a PWA: `manifest.webmanifest`, 192/512-pixel icons including a maskable icon, standalone display, and a versioned service worker ship in every web build and APK. Use the browser's Install option; on iPhone/iPad use Share → Add to Home Screen. A supported browser may offer an Install Lantern button. Installation is optional for ordinary desktop viewing but is required for Web Push on iOS/iPadOS 16.4+.

The offline cache contains only the versioned static app shell, icons and review documents. Camera API responses, credentials, snapshots, replicas, sign-in responses and media are never added to that cache. Offline startup lets the owner open the interface; it does not make an unreachable camera available. New versions wait for an explicit Update viewer and reconnect action before replacing an active app. Phone-hosted viewers and Sites-hosted viewers have separate origins, installation state, pairing grants and notification permissions.

Pairing credentials remain session-only by default. Remember this viewer is an explicit opt-in that retains browser authorization in this browser profile across app restarts, subject to the camera's normal expiry and revocation. Do not enable it on shared devices. The service worker never stores camera bearer tokens. Turning remembering off removes the persistent registry and returns the current credentials to session storage.

## Consent when a viewing session opens

Starting Watch, Connect with audio or Answer doorbell on a compatible camera requests notification permission from that user gesture if permission is still undecided. Camera viewing continues if the user declines. Enable event notifications on a camera card can retry registration or explain blocked permission. Disabling event notifications cancels this camera’s browser subscription and suppresses automatic re-enrollment on later viewing gestures; Enable event notifications opts back in. A denied browser permission must be changed in the browser's site settings; Lantern cannot override it.

Browsers do not expose a general permission to run a page continuously in the background. Lantern registers a service worker and a user-visible Push subscription instead. This is the supported background-alert mechanism. Do not request unrelated permissions or promise continuous background JavaScript. iOS requires an installed Home Screen app; OS battery restrictions, force-stop, browser support, internet loss and notification settings can delay or prevent delivery.

Microphone permission is separate and is still requested only by the Talk control. An event notification never enables a microphone or automatically starts media.

## Person and doorbell event fan-out

The phone sends an encrypted Web Push notification to every active viewer grant with a valid subscription. Each viewer must grant its own browser permission and register its endpoint. A separate service-worker scope per camera supports each camera's own VAPID identity. No Firebase account, hosted push sender or Sites bypass token is needed.

Payloads contain a camera name, event type, stable event ID, camera ID and timestamp, never images or face data. The camera uses RFC 8291/8188 payload encryption and RFC 8292 VAPID with standard JCA cryptographic primitives. The sender matches the published RFC 8291 encryption vector in tests. Push services see delivery metadata but cannot decrypt the event payload. Push uses the browser vendor's internet service; local-only viewing and local recording remain usable without that service.

The camera seals its VAPID private key and subscription secrets with a non-exportable Android Keystore AES key. Allowed delivery hosts are explicitly limited to supported Google, Mozilla, Apple and Windows push providers; redirects and arbitrary destinations are rejected. Paired camera authorization is required to add/remove a subscription. Viewer revocation removes its pending jobs and subscription.

Jobs are persistent, deduplicated per event/viewer, bounded to 2,048 recent jobs, retried with backoff up to five minutes, and expire after one hour. Invalid endpoints (404/410) are removed. Provider acceptance is not proof that a notification appeared on a device. Foreground doorbell polling remains available independently. A notification click focuses the existing viewer or opens its own origin, never a payload-supplied URL.

## On-device person detection

Enable On-device person detection and presence on the camera phone. The bundled, hash-pinned EfficientDet-Lite0 model (TensorFlow Authors, Apache 2.0) runs locally through TensorFlow Lite Task Vision 0.4.4. It samples at most one frame every two seconds, uses two CPU threads, keeps one analysis operation in flight and skips analysis under severe thermal pressure. Native model accuracy, throughput and OS compatibility still need physical-device qualification.

Scores below 0.6 are discarded. Two positive observations establish presence; three misses clear it. Confirmed presence saves a person snapshot with confidence and queues alerts at most once every 30 seconds per camera. Positive person observations also wake the foreground ambient display. Capture, live WebRTC and backup workers are independent of whether the viewer is open.

The viewer reports current observed people, peak count, sample count, last sample/person times and analysis availability. A stale, stopped or failed analysis is unavailable, not evidence that nobody is there. Counts are detections within the current service run, not unique identities. Face recognition, cross-camera identity matching and suspicious-activity rules remain separate milestones.

## Acceptance and operational checks

Install on each supported browser/OS, allow notifications while starting a viewing session, close the app, trigger a real doorbell/person event and verify a visible notification on every subscribed viewer. Repeat with permission denied/revoked, an expired viewer grant, an offline phone, OS force-stop and a provider outage. Test notification click navigation and microphone inactivity. Use `docs/device-acceptance.md`; browser permission fixtures do not prove vendor or physical-OS delivery.

Opt-out also takes precedence over an enable request already in flight. Once that
request settles, the viewer removes any late camera subscription and local push
registration while retaining the browser's disabled preference.
