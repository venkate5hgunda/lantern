# Viewer redesign QA

Date: 25 October 2026. Report lives under `docs/` in accordance with this
repository's documentation convention.

## Comparison target and evidence

- Source visual truth: [selected Roomlight plus multi-camera target](images/redesign-target.webp).
  Original generated image: `exec-a39e688b-bcce-48ab-88c5-11fd45d26aef.png`.
- Implementation: production Vite build at `http://127.0.0.1:4173/`.
- Matching desktop state: four paired cameras, selected console streaming,
  listening enabled, microphone transmitting. Camera names, footage and other
  cameras' states are test data, not the household pictured in the mock.
- Source and [live implementation](images/viewer-live-test.png) are both
  1487 × 1058 pixels. CSS viewport: 1487 × 1058; deviceScaleFactor: 1.
  No density resampling was needed. The WebP reference preserves source size.
- [Full-view comparison](images/redesign-comparison.jpg): source on the left,
  implementation on the right, 2974 × 1058 pixels.
- [Focused controls comparison](images/redesign-controls-comparison.jpg): source
  above implementation, 1119 × 494 pixels. Crops retain their original scale.
- Additional production screenshots: [desktop idle](images/viewer.png),
  [phone idle](images/viewer-mobile.png), [tablet after selection](images/viewer-tablet.png).
  Viewports: 1487 × 1058, 390 × 844 and 768 × 1024 respectively, all at density 1.
  Full-page captures are 1487 × 1281, 390 × 1520 and 768 × 1593 pixels.

## Findings and comparison history

1. **P2, desktop hierarchy and composition — resolved.** The
   [first full-view comparison](images/redesign-comparison-before.jpg) showed
   an oversized heading and video surface, small horizontal action groups,
   and artwork positioned too high relative to the source. Reduced the
   desktop heading cap from 70 to 56px, adjusted the video ratio, enlarged
   the action icons with labels beneath them, and moved/expanded the sidebar
   artwork. The final full-view and focused comparisons show the corrected
   hierarchy, with controls and the transmission notice visible together.
2. **P2, tablet details and primary action — resolved.** At 768px the three
   connection columns overlapped, and the idle-video content clipped Watch
   live. The previous mobile breakpoint was too narrow. Moved the compact
   shell breakpoint to 900px, allowed flex text to shrink, and gave compact
   video panels a 320px minimum height. The final tablet screenshot shows
   separate connection rows and a fully visible Watch live action. Both the
   selected tile and selected console name remain visible.
3. **P2, phone camera identity — resolved.** The small-screen status overlay
   hid the camera name instead of the redundant privacy phrase. Corrected the
   hidden span. The final phone screenshot shows the selected camera name in
   the video header even when its overview label is truncated.
4. **P2, footer contrast — resolved before final visual comparison.** The
   browser accessibility pass measured 3.35:1 for 11px footer text. Changed
   its foreground to `#56675b`; the final automated WCAG checks pass before
   and after pairing in all six browser profiles.

The final combined comparison and the recaptured responsive views have no
remaining actionable P0/P1/P2 visual findings within the implemented scope.

## Fidelity surfaces and intentional adaptations

| Surface | Assessment |
| --- | --- |
| Typography | Serif display type and system sans UI preserve the target's hierarchy. Platform font fallbacks are intentional for offline use. Heading scale and action layout were corrected in the second visual pass. |
| Spacing/layout | Forest sidebar, cream workspace, four-camera overview, large selected video, connection summary and actions retain the selected composition. Phone/tablet shells stack details safely; camera selection scrolls horizontally. |
| Colors/tokens | Forest, cream, mint, amber transmission and red disconnect treatments follow the target. Footer contrast was corrected and the final paired/unpaired WCAG checks pass. |
| Images/assets | Original local lantern/plant artwork preserves the subject and warm lighting. Tabler SVG files provide consistent icons and carry their MIT notice. Live test footage is genuinely encoded/decoded WebRTC video; its green test signal is expected. |
| Copy/content | Labels describe supported behavior. Reachable replaces unmeasured “Excellent connection”; camera state is read-only because arming remains on the phone. Stop talking/Mute camera reflect the current action. |

The target's photographic tile thumbnails and background LIVE badges are
adapted to camera icons plus real reachability/arming/pairing state. The
existing protocol has no passive thumbnail feed, and selecting a camera is
the explicit boundary for starting media. No invented household images or
background streams are shipped. The target's resolution/fullscreen chrome,
settings/help destinations and remote arming toggle were not implemented as
inactive controls; existing setup guidance, event history and removal remain
functional. These are scope/architecture constraints, not claimed pixel parity.

## Interaction and accessibility verification

- Final production build and all 55 viewer unit tests passed.
- Final complete six-profile browser run: 72 passed. Covers pairing, snapshots,
  history, expiry/revocation, keyboard input, camera selection, real decoded
  video, Opus reception, microphone transmission and shutdown.
- Switching away from an actively transmitting camera closes its server
  session, ends microphone tracks and unmounts the old console in all profiles.
- Additional screenshot capture flow verified audio/listening/talk/disconnect,
  camera selection, and no horizontal document overflow at phone/tablet sizes.
  It collected no page errors or console errors.
- Focus outlines, pressed camera-selection buttons, form labels, touch targets
  and reduced-motion styling were retained. Mobile/TV browser profiles emulate
  layout/input and do not qualify physical hardware or certificate stores.

## Trace and implementation checklist

- [x] Traced `App` selection/pairing/removal to `CameraCard` mount/unmount and
  `CameraConnection.stop`, session deletion, remote tracks and microphone cleanup.
- [x] Rechecked action labels/descriptions against every media test consumer;
  the initial label regression was a missed accessibility/test-consumer trace.
- [x] Rechecked tile state labels against selection semantics and revoked-state
  selectors; scoped the Pair again test to the actual action.
- [x] Preserved protocol/API types, tab storage key/serialization, credentials,
  snapshot/history/download behavior, and same-origin CSP asset requirements.
- [x] Traced local icon copies to the MIT notice and static distribution.
- [x] Updated screenshots, README links, scope, design behavior and validation.
- [x] Verified Gradle still consumes `web/dist/` as viewer assets; native Android
  code and APK qualification were not changed or rerun in this viewer checkpoint.

Follow-up polish: the library lamp mark and platform serif fallback are
deliberate approximations of the generated brand/typography. Physical-device
qualification remains governed by [the existing checklist](device-acceptance.md).

final result: passed
