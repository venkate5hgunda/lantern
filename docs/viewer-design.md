# Viewer design

[← Back to the README](../README.md)

The viewer uses the selected Roomlight direction: cream surfaces, forest-green
navigation, serif headings, mint actions and original lantern artwork. The
multi-camera overview keeps paired cameras together above the selected camera.

## Camera and media behavior

- Each tile checks the camera's protocol-v1 information and shows checking,
  armed, disarmed, unreachable or pairing-required state. These describe camera
  availability and capture state; only an established media session says LIVE.
- The overview uses camera icons rather than invented camera footage. It does
  not start background streams or represent an idle camera as live.
- One camera console is active. Selecting another camera closes the previous
  media session and releases its microphone. Pairing selects the newly paired
  camera; removing the selected camera selects the next available entry.
- Video, listening and microphone transmission remain explicit actions.
  Talk toggles the microphone; Stop talking releases its tracks.
- Credentials retain the existing tab-scoped `lantern.peers.v1` format.
  Expired or revoked credentials require pairing again. Remove forgets this
  tab's credentials; revocation remains an owner action on the phone.
- Pairing, snapshots, authenticated history/downloads, direct WebRTC and the
  existing HTTPS/origin requirements use the established protocol and APIs.

On phones, the camera overview scrolls horizontally and the console stacks its
details and controls. The on-video Watch live action remains available before
the lower control list. Setup guidance stays collapsed until requested.

## Assets

The sidebar image is original generated artwork, compressed to a local WebP.
UI icons are local [Tabler Icons](https://tabler.io/icons) SVG assets from
`@tabler/icons` 3.49.0; the [MIT notice](../web/public/assets/icons/LICENSE)
ships with them. Assets use same-origin paths compatible with the existing
Content Security Policy and relative-path static hosting.

Screenshots in this repository show the production viewer using synthetic
protocol fixtures. The live-test image shows actual WebRTC-decoded test video,
not a physical camera or household image. See the [design QA](design-qa.md)
and [validation receipt](validation.md) for evidence and qualification limits.
