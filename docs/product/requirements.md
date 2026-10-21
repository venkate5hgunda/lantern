# Lantern product requirements

Status: staged implementation. This document defines the target product; milestone status records what actually ships. Review at `/review.html` on any static viewer host or camera. Last reviewed: 20 October 2026.

## Product promise

Turn spare Android phones into an understandable, private home camera network. Each camera records locally, serves standard live WebRTC, accepts a doorbell conversation, and can retain encrypted copies for trusted peers. A browser viewer coordinates the owner's experience without becoming an always-on server. Local capture must continue when viewers, the internet, or other cameras disappear.

## Users and primary journeys

Owner: build/install the APK, name a camera, import its network identity, grant permissions, test video/audio, choose storage and analytics settings, and pair viewers. A second phone joins explicitly and is independently usable. No terminal work should be needed after identity provisioning.

Viewer: pair with a short-lived code, see which cameras are reachable, start video, listen, talk, inspect the timeline, download evidence, and understand gaps, storage pressure and backup health.

Visitor: press Ring on the camera phone. The owner sees a foreground alert, answers with a deliberate action, and explicitly enables their microphone. A missed ring remains in history. Microphones never activate because a ring arrived.

Operator: diagnose certificate expiry, missing permissions, address changes, untrusted peers, full disks, thermal throttling, replication lag and failed restores without reading source code.

## Functional requirements and acceptance

| ID | Requirement | Acceptance gate |
| --- | --- | --- |
| SET-01 | Guided Android setup, offline instructions, explicit arming | Fresh install and second-camera walkthrough completed by a new operator on API 29 and current Android |
| SET-02 | Stable per-camera identity and revocable pairing | Address changes cannot impersonate a saved device; revoked viewers lose media and API access |
| LIVE-01 | Native standard WebRTC video | Actual Android camera decodes in Chromium, Firefox and Safari; video remains RTP rather than HTTP frame polling |
| LIVE-02 | Listen and talk independently; one talk session per phone | Permission denial, disconnect, tab close and grant revocation release microphone and speaker route |
| BELL-01 | Durable, rate-limited local ring and viewer acknowledgement | Ring survives process restart; open viewer reports it within 10 seconds; acknowledgement is idempotent |
| BELL-02 | Honest notification availability | UI states that closed/background browser alerts need a separate delivery service; no silent promise of browser push |
| STORE-01 | Rolling local snapshots bounded by age, bytes and count | Oldest evidence evicts predictably; invalid/partial writes never appear as saved; no automatic capture when paused |
| STORE-02 | Rolling video segments independent of a viewer | H.264 MP4 segments playable after crash recovery; measured storage budget, free-space reserve and gap markers |
| STORE-03 | Searchable timeline and reliable export | Cursor pagination, UTC ordering, local-time display, explicit missing/expired media, export includes origin and capture time |
| BACKUP-01 | Encrypted replication to explicitly trusted phones | One replica target by default; receiver acknowledges only after durable write and hash verification |
| BACKUP-02 | Bounded retry and independent restore | Offline peer retries with jitter; duplicate upload is idempotent; source-loss restore verifies decryption and manifest |
| BACKUP-03 | Explain copy versus protection | Show local-only, queued, verified-copy, expired and failed states; never report success from merely queueing a job |
| CV-01 | Background motion/person/presence | Sampling has a bounded queue and no dependency on an open viewer; include model version, confidence and observation window |
| CV-02 | Unique visitors | Anonymous track IDs count within a stated time window; mark occlusion, re-entry and cross-camera uncertainty |
| CV-03 | Opt-in familiar-face recognition | Owner-managed enrollment, local encrypted embeddings, unknown outcome below threshold, deletion/revocation tested |
| CV-04 | Explainable activity rules | Owner-defined zones, schedules, dwell/line-crossing and camera obstruction; show which rule fired and supporting clip |
| CV-05 | Useful analytics | Time-window presence, person counts and rule events distinguish zero observations from unavailable coverage |
| OPS-01 | Documented standalone setup and operations | Reproduce build, pairing, backup, restore and update from repo documentation with no private knowledge |
| OPS-02 | Controlled releases | Capability negotiation, reversible feature controls, versioned schemas and migrations, recorded validation receipts |

## Analytics semantics

A person detection is a model observation, not an identity. A track is an approximate sequence within one camera's observation window. Unique-person estimates must include the window and quality limits; they are not an exact household census. Familiar-face labels apply only to owner-enrolled people and remain distinct from anonymous tracks. Avoid automatically inferring intent, dangerousness or criminality from appearance. “Suspicious activity” means a configured observable rule such as a person dwelling in a zone after hours, with evidence for human review.

## Quality targets and release gates

On supported physical phones: LAN video first frame p95 under 5 seconds; foreground ring visibility p95 under 10 seconds; one audio plus two video-only viewers; 24-hour powered soak with bounded memory, queue depth and disk usage; restart recovers committed evidence; 72-hour peer outage never blocks local recording. Establish and publish hardware-specific inference throughput and thermal behavior before enabling CV by default. These are targets, not current measured guarantees.

Accessibility: keyboard navigation, labelled controls, 44px browser targets, 48dp Android targets, readable large-font/dark mode, no meaning conveyed only through color. Explain certificate and connectivity errors with concrete recovery steps.

Security: LAN/VPN by default, approved HTTPS viewer origins, short-lived pairing codes, hashed bearer grants and owner-only device configuration. Peer backup grants must be distinct from viewer grants. Hidden review URLs are discoverable and must contain no credentials or privileged unauthenticated actions.

## Storage and control defaults

Automatic snapshot capture starts disabled. When enabled on the phone, capture at most once a minute while armed. Snapshot retention defaults to 7 days, 200 items, 128 MiB, with a 64 MiB free-space reserve. This is a snapshot ring, not continuous video or a redundant backup.

Target video defaults: silent 60-second segments, a configurable per-camera budget and age limit, at least 512 MiB free-space reserve; capacity estimate shown before enabling. Recording audio requires a separate explicit control. Peer replicas have their own quota and expiry; a receiving peer cannot consume all storage reserved for its own camera.

Face recognition, replica transfer, automatic video and activity alerts stay disabled until their implementation and release gates exist. Controls on the review page must state whether they affect this browser, this phone or the network. Unsupported capabilities cannot be activated by a decorative switch.

## Scope boundaries

The recommended setup uses GPT Sites for registration and signaling, as approved on 20 October 2026; see `managed-setup.md`. Advanced direct/local mode has no hosted signaling or mandatory cloud account. Direct WebRTC works on reachable LAN/VPN paths; networks without a route require a deliberately configured TURN service or VPN. WebRTC is live transport, not a durable recording format. Browser-only reliable closed-tab notifications conflict with an entirely offline, serverless system; an optional notification bridge or native viewer is a later architectural choice.

## Ambient camera display (owner addition, 20 October 2026)

The armed foreground camera defaults to an in-app ambient screen after 30 seconds without interaction or significant scene motion. Use a black background, low per-window brightness, hidden system bars and a dim clock/hint that moves broadly every 30 seconds. Significant motion or a tap returns to the camera controls; returning to quiet re-enters ambient. Restore system brightness and normal screen-timeout behavior when the app pauses, the camera stops, or the setting is disabled. Android camera settings expose the owner control.

Motion wake uses bounded low-resolution luminance sampling independently of viewers. Ignore small sensor noise and compensate for uniform exposure shifts; require two significant consecutive observations. This is a display wake cue, not person detection or an intrusion alert. Person-triggered wake joins the same display-activity policy when a real person detector ships in M4.

The initial implementation keeps the foreground display on at low brightness; it does not switch the physical panel completely off or unlock Android's keyguard. The hardware power button/system lock remains authoritative. True locked-device wake needs a separate OS/OEM/device-owner qualification. Pixel movement and dimming reduce persistent exposure; neither software nor OLED black can guarantee prevention of burn-in. Qualify clock placement, brightness, display wake and capture continuity on both target phones and large fonts/orientation changes.
