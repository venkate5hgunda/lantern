# Few-click setup with GPT Sites

Decision: approved by the owner on 20 October 2026. Sites-based setup and signaling will be the recommended path. Keep direct/local mode as an advanced option. This supersedes the earlier target of requiring no hosted signaling. Media, evidence and on-device analytics remain on the phones.

## Intended user journey

1. Install Lantern from a signed APK release and tap Set up camera.
2. Allow camera/microphone permissions. The phone registers outward over HTTPS and shows a short-lived code or QR.
3. Sign into the Lantern Site, choose Add camera, and enter/scan the code. Name the camera and approve it.
4. Tap Watch. The camera appears in the owner's fleet on subsequent sign-ins. Listen and Talk remain explicit actions.

No household CA generation/import, PKCS#12 file picker, per-phone viewer-origin approval, static address entry or router port forwarding belongs in the recommended path. Installation still needs Android sideload approval until an app-store release exists. Setup cannot remove OS permission prompts.

## What Sites changes

A stable, trusted HTTPS Site hosts the viewer plus a small authenticated registry and signaling service. A phone makes outbound HTTPS requests to that service. The browser and phone exchange SDP, ICE candidates and session leases through the service. The actual WebRTC media remains DTLS-SRTP between devices where connectivity permits. The Site never receives captured frames or recordings in this design.

The direct/local path still uses per-phone HTTPS identities and explicit pairing; document it as an advanced or offline deployment rather than sending everyone through it.

## Backend foundation in this repository

`coordinator/worker.mjs` implements registration, single-use claims, owner-scoped fleet lists, revocation, bounded offer/ICE/lease commands and result retrieval using a Sites D1 binding. `coordinator/schema.sql` specifies its initial schema. Tests run the actual SQL through a local D1 adapter. This is a backend foundation; the Android outbound client, browser adapter and hosted migration pipeline are not integrated or enabled yet. The published Site currently serves the viewer and review documents only.

Device secrets are 256-bit random tokens stored only as hashes on the service. Claim codes are 64-bit random, expire in five minutes and can be redeemed once by an authenticated owner. Registration, claims and polling are rate limited. Signal jobs expire after 30 seconds, have a per-device queue bound, and accept only allowlisted WebRTC commands. The service cannot be used as an arbitrary proxy to a phone's APIs. Live session leases still expire locally if the coordinator disappears.

Android must seal the device credential with Keystore, retain its identity through restarts, poll with bounded backoff and process commands idempotently. The phone must provide a local revoke/reset flow. Native foreground-service rules still apply to camera/microphone capture. Recordings continue locally during coordinator outages; a fresh hosted live connection cannot be established while signaling is unavailable.

## Deployment identity gate

Sites browser sign-in and native device authentication are separate. An owner-private Site does not automatically allow a phone service to poll it. The production coordinator needs qualified service-accessible routes while owner routes enforce the Sites-supplied authenticated user ID and device routes enforce their per-device tokens. A public Site with route-level owner authentication is a candidate; confirm the platform strips spoofed identity headers and supports sign-in on that audience before enabling it.

Never distribute a Site-wide authentication bypass token to phones or put one in JavaScript. Do not change the private review Site's audience just to make registration work. Keep the review deployment private until the production audience and identity boundary are validated.

## Connectivity gate

Hosted signaling eliminates manual certificate/address setup; it does not eliminate NAT traversal. Reachable LAN/VPN cameras can connect directly. Standard public STUN can discover mapped addresses without relaying media. Symmetric NAT or restrictive networks may require a separate TURN service. Sites Workers are not a TURN server and cannot relay UDP media.

The proposed remote-friendly mode is direct-first with an explicitly configured, authenticated TURN fallback. TURN handles encrypted live transport only; it does not store recordings. Its operational cost and access controls must be documented. If the user chooses relay-free operation, show a clear VPN/network remedy when direct ICE fails rather than claiming universal few-click remote connectivity.

## Integration acceptance

- Fresh-phone-to-visible-stream walkthrough without a CA, address entry or CLI.
- Sign out and sign in from a second browser: owned fleet returns without copying tokens.
- Wrong/expired/reused claims and cross-owner requests fail; revocation closes active leases.
- Spoofed identity headers cannot authenticate through Sites dispatch.
- Registration abuse, concurrent claims, device reset and queue exhaustion are tested.
- Interrupted polling does not replay session creation or leave microphones active.
- Real Android-to-browser video/audio works on the same LAN, on separate NATs and with an intentionally blocked direct path.
- Current direct/local setup remains functional without a Sites account.
- Hosted snapshot/history/download uses a separately specified authenticated peer data channel or direct transport. Do not route media through the signaling queue or mark it implemented implicitly.


## Current PWA and background alerts

The static Sites viewer is now an installable PWA. Cameras can deliver encrypted background person/doorbell notifications directly to their registered browser push endpoints; this does not depend on the unfinished hosted coordinator transport. Pairing, live camera APIs and peer backup enrollment currently still use the direct/local mode. The private review Site's audience and project ID remain unchanged. Native person inference and camera-to-camera snapshot replication run on the phones, independently of the static host.
