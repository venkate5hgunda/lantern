# Network setup and operations

## Reproducible starting point

Start with the root README and `docs/build.md` for exact prerequisites. Build the static viewer before the Android APK. Install the APK on each Android 10+ phone. Keep the household CA private key on the provisioning computer; transfer only each phone's PKCS#12 identity to that phone and the public CA certificate to viewers. Follow `docs/provisioning.md` and `docs/setup-and-test.md` for the complete commands and trust-store steps.

Each phone needs a stable reachable LAN/VPN address covered by its certificate. Reserve LAN addresses or use stable private DNS. Open camera HTTPS TCP 8443, camera STUN UDP 8444 and the direct ICE media path only within the intended LAN/VPN. The media path uses negotiated UDP sockets, so opening just the HTTPS port does not establish WebRTC connectivity. Do not port-forward cameras to the public internet. Check IPv4 and IPv6 separately. Meshnet/VPN provides routing, not HTTPS trust or camera authorization.

## First and second camera checklist

1. Install, name, import identity, grant camera/microphone permissions, then explicitly start capture.
2. Trust the public CA on the viewer device and visit the camera HTTPS address to verify trust.
3. Approve the exact static viewer origin on the phone. Camera-hosted and independently hosted viewers are different origins.
4. Generate a fresh six-digit code on the phone and pair the browser. Watch real video before marking setup complete.
5. Start an audio session, explicitly Listen and Talk, and verify acoustics in both directions; stop and verify microphone release.
6. Save, reload and download a snapshot. Revoke a disposable viewer and confirm access fails.
7. Repeat independently for the second phone. Losing either camera must not disconnect the other.

## Hidden review and controls

Open `/review.html` next to the normal viewer `index.html`. This is an unlisted URL, not a protected admin area. It contains public product documents and browser-only preferences. It never stores a household key or grants extra phone permissions. Review preferences last for the browser tab session; camera setup/arming and recording controls remain on the phone. Rebuild the viewer and APK to update embedded documents.

## Foreground doorbell milestone

When implemented, press Ring on the local phone. Paired visible viewers poll compatible phones every five seconds and display recent unanswered rings. Answer starts a normal audio-capable WebRTC session; Listen and Talk remain deliberate controls. Acknowledgement dismisses the ring across viewers. Reloading or reopening the viewer shows only recent unacknowledged rings. A closed or background browser does not guarantee alerts; no push service is present.

## Rolling snapshot milestone

When implemented, enable automatic snapshots in Android camera settings. Capture occurs once per minute while armed, without an open viewer. Pause or stop ends automatic capture. The initial policy retains at most 200 snapshots, 7 days and 128 MiB, while reserving 64 MiB free space. The earliest applicable limit wins; the setting is not a guaranteed seven-day history. At one image/minute, the 200-item cap holds roughly three hours twenty minutes. These are still images, not video clips or peer backups. Low disk space rejects captures and must be surfaced in storage diagnostics.

## Updates and recovery

Save build/version and validation evidence before installing an update. Rebuild viewer assets before APK assembly. Use the same application ID and signing identity to preserve private storage. Android uninstall removes app-private evidence; do not use uninstall as an upgrade or recovery procedure. No complete peer export/restore exists until M3. Do not treat a second paired camera as evidence redundancy.

After a process kill/reboot, reopen the app, inspect health and explicitly arm it. Verify a new snapshot and live stream. Check certificate expiry and approved origins if HTTPS/pairing breaks. Check permission, foreground notification and OEM battery settings if capture stops. Check VPN remote-access grants and UDP routing if HTTPS works but video does not.

## Qualification before relying on a deployment

Execute `docs/device-acceptance.md` on the intended physical devices and network. Browser reference peers and emulators prove particular software paths; they do not qualify phone acoustics, OEM power management, trust-store installation or private routing. Future video, backup and CV milestones add their own failure and soak gates before their switches become available.
