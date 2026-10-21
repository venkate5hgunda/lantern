# Lantern working agreements

Keep `docs/product/requirements.md`, `architecture.md`, `managed-setup.md` and `milestones.md` current in the same change as runtime capabilities. `/review.html` embeds these documents and `docs/validation.md` at build time; rebuild both web and Android assets after updating them. Distinguish implemented code, integrated behavior and physical qualification. Unsupported switches must stay disabled.

The owner approved GPT Sites for device registration/setup/signaling, with live media, recordings and analytics on devices. This is the recommended future setup; preserve direct/local mode as an advanced/offline option. Never distribute a Site-wide authentication bypass credential to phones. Validate Sites identity boundaries and native/browser integration before enabling managed mode.

Ambient display is an in-app foreground mode, not a system lock bypass. Motion is a display cue, not person detection. Person wake depends on the actual person model. Preserve owner opt-out, inactivity behavior and restoration of per-window brightness/timeout on pause.

Work in reviewable milestone commits and push the task branch progressively. Do not merge or overwrite another branch. Update `docs/validation.md` with actual commands/results and limitations; never reuse historical hardware results as new evidence.

Run the meaningful gates in `docs/verification.md`. Build web before assembling Android. `coordinator/README.md` documents its separate SQL tests. Build-only checks do not replace physical camera/audio/display/network tests. Keep all keys, tokens and runtime credentials out of the static bundle and repository.

The companion review Site identity is recorded in `docs/product/hosting.json`. Preserve its private audience and project ID. Use `scripts/export-review.py` to prepare the source-backed static snapshot in its Site checkout after building. Publish through Sites tooling and confirm deployment success when updating it.
