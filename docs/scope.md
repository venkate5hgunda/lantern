# Scope and status

What Lantern does today and what remains in the staged delivery plan.

[← Back to the README](../README.md)

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
- Manual and owner-enabled one-minute JPEG snapshots with UTC metadata, durable private storage, byte/age/count retention and authenticated browser history/downloads.
- Durable local doorbell press, foreground viewer polling, explicit answer and idempotent acknowledgement.
- Unlisted `/review.html` with requirements, architecture, milestone ledger and scoped browser controls.
- A locally tested Sites registry/signaling backend foundation, not yet integrated into Android or the viewer.
- Responsive, keyboard-accessible viewer assets served by every phone. The
  same static bundle can be hosted elsewhere without becoming a camera hub.

Person detection, motion/person-triggered recording, video clips, closed-browser notifications, encrypted peer replication, cloud uploads and
owner recognition are **not implemented**. There is no automatic peer
discovery or autonomous failover of a camera's stored events yet. Cameras are
added explicitly by HTTPS address and paired individually.

The approved recommended direction is GPT Sites setup/signaling with direct media. See [managed setup](product/managed-setup.md). Current camera setup still uses the direct/local identity flow until the managed integration passes its gates.
