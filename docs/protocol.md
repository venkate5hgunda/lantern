# Lantern peer protocol v1

Lantern is a personal, autonomous camera network. There is no required hub,
cloud signaling service or public media relay. Every Android peer serves its
own HTTPS API and the same static browser client. Local capture and events do
not depend on viewers or other peers.

## Initial milestone

The initial implementation must provide provisioned HTTPS, physical-code
pairing, bounded authenticated WebRTC sessions, browser video/talkback, and
persisted JPEG snapshot events. Person detection may be included if it is
fully implemented and testable; encrypted replication and owner recognition
are subsequent milestones, not claimed capabilities.

## Transport and trust

- HTTPS only on port 8443, with a user-provisioned PKCS#12 identity. Never
  embed a shared private key or enable a cleartext network fallback.
- A separately provisioned household CA is explicitly trusted by viewers.
  The offline CA signing key never resides on a phone.
- The API accepts LAN and explicitly configured Meshnet connectivity. Do
  not equate RFC1918 addressing with authorization or reject Meshnet
  addresses solely because they are outside RFC1918.
- No public ICE servers. A physical-device test must establish that the
  selected media route is actually LAN/Meshnet before remote support is
  considered verified.
- Every current peer also owns a bounded UDP 8444 STUN discovery socket.
  It returns only the caller's mapped address, not media or authorization.
  The viewer derives its sole discovery URL from the paired camera's HTTPS
  hostname and validated `localStunPort`; never accept an arbitrary ICE URL
  from a peer or substitute a public service. This allows privacy-restricted
  WebKit viewers to gather a usable address without acquiring a microphone.
  The socket is not a relay or a central server. Protect UDP 8444 with the
  same deployment isolation policy as HTTPS and negotiated media ports.
- Approved browser origins are configured by the device owner. Defaults
  permit the phone's own HTTPS origin; external origins such as GitHub Pages
  require explicit configuration. Match scheme, hostname and port exactly;
  never reflect arbitrary origins or use a wildcard.
- Handle allowed CORS preflights without authentication. Actual API
  requests still require authentication, except info and pairing.
- Use `Authorization: Bearer <token>`, never tokens in URLs. Tokens are
  generated from 32 cryptographically random bytes, stored hashed on the
  camera, expire after at most 30 days and can be revoked locally. The web
  client holds them only in memory/session storage, not localStorage.
- Pairing uses an owner-visible, short-lived code generated on the phone.
  Codes expire within 120 seconds and are consumed on success. Bound
  failures to five attempts per code, including requests from different
  source addresses. Generating a new code requires a local UI action.
- No token, code, SDP or private-key logging. Error bodies do not expose
  exception details. Reject oversized request bodies before parsing.
- Serve API/media responses with `Cache-Control: no-store`. Use a strict
  viewer CSP, no third-party scripts and no inline executable HTML.
- The first prototype does not promise host-level firewall enforcement.
  OS/router configuration and physical selected-route testing are required
  for verified LAN/Meshnet isolation.

## Wire format

JSON uses UTF-8. All successful timestamps are ISO-8601 UTC strings.
Errors are `{ "error": { "code": "stable_code", "message": "Human readable" } }`.
Use 400 for malformed input, 401 for missing/expired credentials, 403 for
disallowed origins, 404 for unknown or another viewer's resource, 409 for
state/capacity conflicts, 413 for oversized bodies, 429 for pairing limits,
503 for capture unavailable, and 504 for ICE timeout.

### GET /api/v1/info (unauthenticated)

```json
{
  "apiVersion": 1,
  "deviceId": "stable-installation-uuid",
  "name": "Front door",
  "armed": true,
  "localStunPort": 8444,
  "capabilities": { "video": true, "audio": true, "snapshots": true, "personDetection": false }
}
```

Only minimal discovery information is public. No events, grants or media.
`localStunPort` is an optional integer from 1 through 65535, present only
when the peer's discovery listener is running. Older peers without it use no
STUN server; privacy-restricted viewers may then fail rather than using a
public fallback.

### POST /api/v1/pair (unauthenticated)

Request: `{ "code": "123456", "viewerName": "My iPhone" }`.
Names must be trimmed, nonempty and at most 80 characters.

Response (201):

```json
{
  "token": "opaque-base64url-token",
  "expiresAt": "2026-11-01T00:00:00Z",
  "deviceId": "stable-installation-uuid"
}
```

Each camera is paired independently; a browser connecting from a new origin
is a separate client context. Camera-local UI lists and revokes paired grants.

### POST /api/v1/sessions (authenticated)

Request: `{ "offer": { "type": "offer", "sdp": "..." }, "audio": false }`.
Maximum SDP length: 64 KiB. Maximum overall JSON body: 96 KiB.

The browser posts its initial offer immediately after setting its local
description and trickles candidates through the owned session endpoint below.
Buffer candidates until the session ID is available. The peer returns a
complete answer with a bounded gathering deadline. Do not wait for browser
gathering to complete before sending the offer: WebKit can remain in gathering
until an answer is installed.
Candidate acknowledgments run independently of connection establishment:
working DTLS/media must not wait for all other host candidates to resolve.

Response (201):

```json
{
  "sessionId": "opaque-uuid",
  "answer": { "type": "answer", "sdp": "..." },
  "expiresAt": "2026-10-04T12:00:30Z"
}
```

- Camera video is sent on demand, using a shared capture pipeline.
- Browser offers a recvonly video transceiver and, when `audio` is true,
  a sendrecv audio transceiver. Mic tracks are only acquired on an explicit
  user action. Viewer listen/talkback toggles must release microphone tracks
  when disabled or disconnected, not merely hide UI.
- Cap live sessions at three globally. Allow one audio/talkback session at a
  time; reject another with 409 rather than mixing competing visitors/viewers.
- Bind each session to its creating grant. A different authenticated viewer
  cannot inspect, heartbeat or close it.
- Expire abandoned sessions after 30 seconds without heartbeat. On revocation,
  disarming or service shutdown close the affected sessions immediately.

### PUT /api/v1/sessions/{sessionId} (authenticated heartbeat)

No body. Response: `{ "expiresAt": "..." }`. Client interval: 10 seconds.

### POST /api/v1/sessions/{sessionId}/ice (authenticated)

Request: `{ "candidate": "...", "sdpMid": "0", "sdpMLineIndex": 0 }`.
`candidate` must contain 1 to 4096 characters; `sdpMid` is null or a string
of at most 64 characters; `sdpMLineIndex` is an integer from 0 through 1.
Ignore additional optional browser fields, not malformed required fields.
Response: 204 only after native acceptance. Enforce session ownership and
expiration exactly as for heartbeats; the candidate endpoint cannot resurrect
an expired session. Do not log candidate addresses or credentials.

### DELETE /api/v1/sessions/{sessionId} (authenticated)

Response: 204. Closing a missing session owned by the same viewer may return
404; the client treats this as already closed. No resource leak on failed
negotiation, timeout or superseded UI actions.

### GET /api/v1/events (authenticated)

Response: `{ "events": [<event>, ...] }`. Newest first, at most 50 records.
Initial local retention: at most 200 snapshots; never unbounded disk growth.

An event is:

```json
{
  "id": "uuid",
  "deviceId": "stable-installation-uuid",
  "createdAt": "2026-10-04T12:00:00Z",
  "trigger": "manual",
  "confidence": null,
  "snapshotPath": "/api/v1/events/uuid/snapshot"
}
```

`trigger` is `manual` or `person`. Person confidence is a finite number
between 0 and 1; manual confidence is null. No fabricated detections.

### POST /api/v1/events (authenticated)

No body. Creates a manual snapshot from the latest shared camera frame.
Response (201): event. Return 503 if not armed or no valid frame is available.
Bound concurrent snapshot creation and snapshot frequency; do not hold capture
threads while writing JPEG/files/database. Persist metadata only after a
successful durable media write, and clean up failed partial writes.

### GET /api/v1/events/{eventId}/snapshot (authenticated)

Response: `image/jpeg` bytes. Validate the event ID against stored records;
never interpolate arbitrary filesystem paths. Browser fetches with bearer
authentication and creates a short-lived blob URL. Do not put credentials in
an image URL. Revocation prohibits further retrieval.

## Browser lifecycle and deployment

- Pair only with a normalized HTTPS origin: no URL credentials, query,
  fragment, path, cleartext HTTP or public relay configuration.
- Browser-to-peer requests are direct. The bootstrap peer is not a media hub.
- Persist endpoint and grant in session storage with schema validation.
  Clear stale grants after 401; display other failures explicitly.
- Bound fetch and ICE operations and cancel superseded connections.
- Stop tracks, close RTCPeerConnections, clear heartbeats and revoke blob URLs
  on disconnect, removal and unmount. An aborted operation cannot reattach
  state or leak a newly created server session.
- Network errors explain certificate trust, origin allowlists and Meshnet
  reachability without pretending the browser can diagnose which one failed.
- Include keyboard accessibility, explicit loading/error/armed states and
  user-gesture audio controls. TV compatibility is measured, not assumed.
- Vite relative asset paths support both peer hosting and GitHub Pages project
  paths. No external fonts, CDN dependencies or analytics.


## Additive local-evidence extensions (20 October 2026)

`capabilities.doorbell` and `capabilities.rollingSnapshots` are optional booleans. Older peers omit them; viewers must not request the following endpoints from those peers. All new endpoints require the existing valid bearer grant and approved browser origin.

- `GET /api/v1/doorbell` returns `{deviceId, rings:[{id,createdAt,acknowledged}]}` newest first, at most 50. Rings are created only by the local phone UI, with a five-second persisted cooldown. They survive restarts.
- `POST /api/v1/doorbell/{id}/ack` has an empty body; returns 204 even if already acknowledged, or 404 if absent. Acknowledgement is shared across viewers. It does not activate media or a microphone.
- `GET /api/v1/storage` returns `{kind:"snapshots",items,bytes,maxItems,maxBytes,maxAgeSeconds,reserveBytes,availableBytes,automaticSnapshots,error}`. Bytes are integers; `error` is a string or null. This route is read-only. Camera settings remain owner-local.
- Snapshot event `trigger` may additionally be `scheduled`, with `confidence:null`. Existing manual/person semantics remain. Legacy viewers that reject new enum values must be updated alongside automatic capture; leaving automatic capture disabled preserves their event compatibility.

The ring poll interval is five seconds only while the viewer is visible. Recent unanswered rings (under two minutes) get an incoming banner; all retained rings remain in history. Signaling still uses standard WebRTC. The browser ignores null and empty end-of-candidates notifications rather than sending them to the non-empty ICE route.

The planned managed Sites protocol is separate from these camera APIs; see `coordinator/README.md` and `docs/product/managed-setup.md`. The coordinator is not an arbitrary camera API/media proxy.

## Additive PWA, person and snapshot-replication capabilities

`info.capabilities` optionally advertises `pushNotifications`, `analytics` and `peerBackup`. Older peers may omit them; clients hide unsupported controls. `personDetection` requires an owner-enabled initialized model. Person snapshot events add `trigger: "person"` and numeric `confidence` in [0,1]; manual/scheduled events retain null confidence.

Viewer bearer grants authorize these endpoints:

| Endpoint | Behavior |
| --- | --- |
| `GET /api/v1/notifications` | Camera VAPID public key and this grant's subscription/queue status. |
| `POST /api/v1/notifications` | Store this grant's standard PushSubscription JSON (`endpoint`, `keys.p256dh`, `keys.auth`); 204. |
| `DELETE /api/v1/notifications` | Remove this grant's subscription and pending jobs; 204. |
| `GET /api/v1/analytics` | Current-service-run presence/count observations, freshness, model and availability. |
| `GET /api/v1/backup-status` | Targets, verified/pending counts, last reconciliation, errors and receiver quotas. |
| `GET /api/v1/replica-events` | Bounded independently recoverable snapshot metadata on this camera. |
| `GET /api/v1/replica-events/{64hex}/snapshot` | Locally decrypt and verify retained JPEG, or 404 for missing/expired/corrupt evidence. |

Push endpoints are restricted to supported Google, Mozilla, Apple and Windows HTTPS push-provider hosts. Payloads use RFC 8291/8188 encryption and RFC 8292 VAPID signatures. Provider acceptance is not a delivery receipt. See [notification operations](product/notifications-pwa.md).

Camera replication uses a separate credential domain; viewer grants cannot upload replicas. `POST /api/v1/backup/pair` consumes a physical single-use backup code plus source UUID and reverse origin/certificate pin/token/key, returning receiver UUID and independent directional token/key. Origins must be HTTPS with normal hostname validation and the exchanged exact leaf-certificate SHA-256 pins. Codes expire after two minutes with five attempts. Re-pair after certificate rotation.

Backup bearer authorization is source-bound: `GET /api/v1/backup/inventory`, `POST /api/v1/backup/begin`, `POST /api/v1/backup/{64hex}/chunk` and `POST /api/v1/backup/{64hex}/commit`. Begin carries immutable source/event UUIDs, creation timestamp, trigger/confidence, plaintext size and SHA-256; ID is SHA-256 of source UUID + colon + event UUID. Chunk carries integer offset and base64url ciphertext data of at most 32 KiB. Begin/chunk return `received`; commit returns verified plaintext `sha256`. AES-256-GCM ciphertext includes nonce/tag with canonical manifest as AAD. The receiver reserves full size before accepting chunks, supports exact duplicate retries and acknowledges commit after verification and durable publication. Inventory checks ciphertext integrity for repair. See [replication and retention limits](product/peer-backups.md).
