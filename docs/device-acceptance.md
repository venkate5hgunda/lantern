# Physical-device acceptance

These checks are **pending**, not evidence of supported hardware. Automated
browser engines and an Android emulator cannot establish them. Run on the
actual Galaxy S23 Plus and Redmi/MIUI, and on every intended viewer/browser
combination: iPhone/iPad Safari, macOS Safari/Chrome/Firefox, Windows
Chrome/Edge/Firefox, Android Chrome and Android TV's chosen browser.
Record actual device, OS/firmware, browser version, network and result.

## Trust and isolation

- Trust the public household CA without warning bypasses. Verify each exact
  LAN IP, IPv6 literal and Meshnet name against certificate SANs. Invalid,
  expired and wrong-device certificates must fail.
- Pair from the peer-hosted viewer, an explicitly approved other phone and
  the intended static-host origin. An unapproved origin must fail. Add and
  remove an origin while HTTPS is running; confirm the change applies.
- Reimport a replacement per-device identity and confirm old HTTPS sessions
  end, new connections use the replacement and import failures are visible.
- Verify unauthorized clients cannot fetch events, JPEGs or create/modify
  sessions. Replay an already-used pairing code. Revoke a live grant and
  confirm both media and event access terminate.
- Inspect selected ICE pairs on LAN and Meshnet. Validate with the camera
  on mobile data as well as Wi-Fi; test public IPv4 and IPv6 reachability
  from an unauthorized off-network device. There must be no reachable public
  camera/API route, forwarding, public STUN/TURN or public media relay.
  Include UDP 8444 discovery and negotiated media ports in the isolation
  test, not just the HTTPS listener.
- Verify the **off-LAN, authorized Meshnet viewer** still works with those
  restrictions. Route loss must stop media and release the viewer microphone.
  Lack of a public relay may legitimately make an unsuitable route fail.

## Camera, audio and lifecycle

- Run native preview, all three direct viewers and manual snapshots together.
  A fourth connection must report a capacity error. Opening/closing viewers
  must not stop an otherwise armed camera.
- Confirm a second audio session is rejected; cancelling negotiation must
  not leave an audio slot occupied when its response can be closed.
- View real moving imagery, not just a LIVE label. Verify portrait/landscape,
  screen rotation, front/back camera selection if exposed and low-light
  behavior.
- Listen to real camera sound. Talk through a real viewer microphone and
  phone speaker, then reverse the conversation. Evaluate echo, feedback,
  clipping and latency at the actual mounting location.
- Video-only Watch must not request the viewer microphone. Talk denial must
  remain visible, never claim transmission. Stop talking, disconnect, remove
  a camera, close a tab and change networks; check actual microphone activity
  indicators and absence of continuing audio transmission.
- Test screen-off, Recents dismissal, notifications, battery restrictions,
  OEM background killing, runtime permission revocation, app force-stop,
  reboot, Wi-Fi roaming, Meshnet restart and unplug/replug.
  Service loss must be visible; locally relaunch/arm rather than silently
  promising unattended resurrection. Test both phones separately.
- Sustain capture and multiple viewers for at least 24 hours on wired power.
  Measure temperature, thermal throttling, frame loss, reconnect behavior,
  disk use and battery condition. Stop using a swollen/damaged phone.

## Storage and viewer interaction

- Take/reopen/download snapshots with correct device ID, UTC timestamp and
  event path. Fill beyond 200 records, verify oldest-file eviction and
  latest-50 ordering, and test reboot, low disk space and interrupted writes.
  No metadata should reference a missing/partial JPEG.
- Remove one phone entirely; other cameras must still pair, capture, serve
  events and stream directly. Stored events on the removed phone are **not
  backed up** by this version.
- Navigate all controls with keyboard and actual TV D-pad/remote. Test focus,
  scrolling, pairing-code input, error dismissal, event previews, audio user
  gestures and multiple camera cards. Check real touch and screen readers.
- Test peer-hosted and intended static-host viewers with private-network
  permission prompts, browser updates and certificate-store restrictions.
  A TV browser without CA trust or functioning WebRTC is not qualified.

Person detection, replication/recovery, doorbell notifications, cloud uploads
and owner recognition need their own acceptance tests when implemented.

## PWA, person events and replicated history

- Install the PWA on Android Chrome and iPhone Home Screen; reopen offline, then restore camera connectivity. Verify remembered cameras only persist after explicit opt-in and are erased on opt-out.
- Deny notifications and confirm video still works. Allow them on two separate viewer devices, close the viewers and trigger a person event and doorbell. Verify both receive alerts, clicks focus the right camera, disabling stays disabled on Watch, and revocation stops delivery. Test vendor outage, internet loss, expiry and OS background restrictions.
- Execute `PersonModelTest` on Android. Test real people at expected distances, low light, empty scenes and false positives. Verify debounce, confidence, snapshot orientation, foreground ambient wake, thermal pause and recovery during a long soak. Counts represent observed presence, not unique identities.
- Pair two cameras in both directions. Verify encrypted replicas, interrupted transfer/restart, duplicate retry, corrupted-copy repair, source-offline downloads, quota/free-space limits, certificate mismatch, clock skew, unlink/re-pair and power loss. Verify UI never labels pending copies as protected. Record convergence time and evidence loss under each outage.
