# Redundant camera backups

## What is replicated now

Trusted camera pairs automatically replicate saved JPEG evidence in both directions: manual snapshots, scheduled snapshots and person-triggered snapshots with their original timestamps, triggers, origin device IDs and confidence. This is an implemented snapshot-redundancy milestone. Continuous video, full device settings, the doorbell journal, model counters, face templates and a portable recovery-key export are not replicated by this implementation.

No viewer or shared household server is needed for synchronization. Both phone services must be running and have a reachable LAN/VPN HTTPS path. A receiving phone can serve its verified stored copies to its own paired viewers while the source camera is offline. The viewer's Recovered peer evidence section retrieves these copies directly from that receiving camera.

## Pair cameras once

1. Start both camera services with their existing HTTPS identities.
2. On the receiving phone open Camera settings → Manage redundant camera backups. Generate a fresh backup pairing code and copy its displayed certificate SHA-256 fingerprint and HTTPS address.
3. On the other phone open the same screen and enter the receiving phone's address, fingerprint and six-digit code. Confirm this phone's address is reachable from the other phone and covered by its certificate.
4. Tap Pair both cameras for backup. This establishes upload grants and encryption keys for both directions. No second manual reverse pairing is required.
5. Create evidence on each phone. Inspect Backup synchronization status on the phone or Redundant backups in its viewer. Wait for verified copies, then take one source offline and retrieve its evidence from the other camera's viewer.

The code lasts two minutes, has five attempts, and is single-use. Check the fingerprint on the receiving device, not an untrusted message. Up to four peers per camera are supported. Certificate replacement changes the pin; verify and pair again. Normal hostname verification and certificate expiry checks remain enabled in addition to the exact SHA-256 pin. Neither a browser viewer token nor a trust-all TLS policy is used for peer uploads.

## Continuous reconciliation and failure behavior

A bounded worker checks every five seconds. Successful peers reconcile about every 15 seconds; a batch transfers up to four missing snapshots. An unavailable peer retries with exponential backoff and jitter up to five minutes. This is eventual synchronization when paths and storage are available, not instantaneous consistency during an outage.

Each receiver returns a bounded inventory of verified event IDs and hashes. Missing or damaged replicas are uploaded again. Transfers use 32 KiB chunks, fixed size limits, persisted progress and idempotent chunk checks. One encrypted outbox object per target survives restarts so retries reuse exactly the same ciphertext and nonce; it is removed after verified success. Source recording does not wait for transfer success.

The receiver authenticates a dedicated source-bound backup grant, enforces quota before admission, verifies AES-GCM and the plaintext SHA-256, fsyncs data, renames the finalized object, syncs the directory and commits metadata before returning a receipt. Partial objects are not offered as recoverable evidence. Inventory checks detect missing/truncated/corrupted stored objects and make them eligible for repair. Corrupt or expired evidence returns a clear unavailable result rather than an unverified download.

## Encryption, capacity and retention

Evidence is encrypted to its trusted receiving peer with AES-256-GCM, a random 96-bit nonce and authenticated immutable metadata. Pairing exchanges separate directional secrets over pinned HTTPS. Tokens and shared keys are sealed locally using Android Keystore; a revoked incoming peer loses upload authority while the local owner retains the keys needed to read existing copies. A recipient is trusted to decrypt the evidence it protects.

Replica storage is separate from each camera's own snapshots: at most 256 MiB, 500 objects and seven days, with 64 MiB of free device space reserved. Pending transfers reserve their full size and incomplete reservations expire after ten minutes. The outgoing cache is bounded to one object of at most roughly 8 MiB per peer, separate from incoming replica capacity. The earliest storage limit wins. At capacity, older retries cannot evict newer evidence; rejected transfers remain pending and retry with backoff.

Copies rotate independently of the source; source deletion/retention does not immediately delete redundant evidence. An offline peer, full quota, expired history or source retention can prevent a complete redundant copy. Acknowledged counts are as of the last reconciliation. The status UI explicitly reports desired, verified and pending counts, last reconciliation and errors; zero configured peers means no redundancy. Configure enough peer capacity for the retained evidence and verify a source-offline restore before relying on a deployment.

Disconnect backup peer stops outgoing synchronization and revokes that peer's incoming grant. Existing copies remain readable under their independent retention. The other phone may need its own disconnect/re-pair action. Uninstall or loss of the receiving phone's Keystore can destroy its local restore capability; there is no portable key-recovery export yet. Do not uninstall as an upgrade procedure.

## Verification boundaries

Automated tests exercise encryption/authenticated metadata, wrong keys/sources, quota admission, duplicate/resumed chunks, interruption across reopening, lost commit response/retry, durable outbox reuse, verified receipts, corruption repair, source-offline restore and revoke-with-retained-copy behavior. The sync integration test runs actual SQLite/files/encryption through an injected transport, not two physical phones. Physical TLS routing, transfer throughput, flash durability under power loss and multiday/OEM operation remain device gates.
