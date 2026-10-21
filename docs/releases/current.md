Installable Android camera app and PWA viewer, for Android 10 and later.

- Standard direct WebRTC viewing and explicit two-way audio/doorbell answering.
- Installable PWA with offline shell, optional remembered cameras and per-viewer encrypted person/doorbell notifications.
- Owner-enabled local person detection, presence/count analytics, confidence snapshots and foreground ambient person wake.
- Bidirectional encrypted snapshot replication with resumable transfers, integrity checks, rolling quotas and recovery while the source phone is offline.
- Embedded product requirements, architecture, operating guides and controls at `/review.html`.

Download the APK below. Verify it with `SHA256SUMS`; `build-info.json` records its exact source and `signing-certificate.txt` records the public signing identity. The APK is a signed, non-debuggable release build; private signing material is not included.

Follow [network setup](https://github.com/venkate5hgunda/lantern/blob/v0.2.0-alpha.1/docs/setup-and-test.md) and [release installation/update guidance](https://github.com/venkate5hgunda/lantern/blob/v0.2.0-alpha.1/docs/releases.md). Existing debug builds use a different signing identity: export evidence before changing installations, because uninstall deletes private data.

This is a development prerelease. Physical Android inference, closed-app push delivery, OEM background lifetime and multi-phone/power-loss backup qualification remain pending. Backups cover bounded JPEG snapshots and converge while peers are reachable. Continuous video, face recognition, unique-person tracking and activity rules remain planned. Few-click Sites enrollment/signaling is not yet enabled.
