# Android APK releases

Download the signed APK from [GitHub Releases](https://github.com/venkate5hgunda/lantern/releases). The initial release is `v0.2.0-alpha.1`, a development prerelease with Android 10+ support. It includes the PWA viewer and bundled local person model. Setup still requires the [direct/local network setup](setup-and-test.md); managed Sites enrollment is not enabled yet.

## Install and update

1. Download `lantern-0.2.0-alpha.1.apk` and `SHA256SUMS` from the same release. The release also includes `build-info.json` and `signing-certificate.txt`.
2. On a computer, run `sha256sum --check SHA256SUMS` after downloading all listed assets (macOS: `shasum -a 256 --check SHA256SUMS`). Compare the APK signing certificate with the published receipt when inspecting it with Android SDK `apksigner verify --print-certs`.
3. Transfer/open the APK on the phone. Allow installation from that browser or file manager when Android asks. Open Lantern and complete its camera setup.
4. Future APKs with this application ID, the same release signing key and an increased version code can update the installed app while retaining private storage. Keep the signing key stable.

Existing development/debug builds have a different signing certificate. Android cannot update them with this release APK. Export needed evidence and verify redundant copies before changing installations: uninstalling Lantern deletes its app-private identities, authorizations, evidence and encryption keys. A replica is a snapshot recovery mechanism, not a complete application-state migration.

## Rebuild a signed APK locally

Use the prerequisites in [build.md](build.md). Supply owner-controlled signing values through the environment; never add passwords to Gradle files or commit a keystore:

```sh
# Set these using your private credential manager or a protected environment:
# LANTERN_KEYSTORE_PATH=/absolute/path/to/lantern-release.p12
# LANTERN_KEYSTORE_PASSWORD, LANTERN_KEY_ALIAS, LANTERN_KEY_PASSWORD
cd web
npm ci && npm test && npm run build
cd ../android
./gradlew --no-daemon :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
cd ..
python3 scripts/package-android-release.py
```

Release builds fail if signing values are missing; an unsigned or debug APK is never substituted. Output is `android/app/build/outputs/apk/release/app-release.apk`. The packager requires clean committed source, verifies the APK signature, rejects a debuggable/wrong-package APK or a signer differing from the public pin in `docs/releases/signing-certificate.sha256`, checks its version and embedded viewer bytes, and writes versioned assets under ignored `build/releases/`. It publishes only the public signing certificate, never the keystore. The `build-info.json` source revision must match the release tag.

Use a stable owner-controlled PKCS#12 key. Keep an encrypted offline backup of the key, alias and passwords. The initial private signing backup was created outside the repository at `/workspace/lantern-release-signing/`; copy it into durable encrypted owner storage before this workspace is removed. Its public certificate fingerprint is attached to the GitHub release. The connected GitHub integration can publish release assets but cannot access repository Actions secrets (HTTP 403), so the backup has not been uploaded to repository secrets.

## Enable automatic GitHub releases

`.github/workflows/android-release.yml` is ready for version tags. It stays disabled until the repository variable `LANTERN_PUBLISH_RELEASES` is set to `true`. As the repository owner, install these encrypted Actions secrets through GitHub Settings → Secrets and variables → Actions:

| Secret | Value |
| --- | --- |
| `LANTERN_KEYSTORE_BASE64` | Base64 of the same release PKCS#12 file, without line wrapping. |
| `LANTERN_KEYSTORE_PASSWORD` | Keystore password. |
| `LANTERN_KEY_ALIAS` | Release key alias. |
| `LANTERN_KEY_PASSWORD` | Private key password (same as store password for this PKCS#12). |

Then set the repository Actions variable `LANTERN_PUBLISH_RELEASES=true`. The workflow validates that `v<versionName>` matches the checked-in Android version, builds/tests the PWA, runs browser/provisioning/coordinator/native checks, builds a non-debuggable signed release, verifies/packages it and publishes immutable prerelease assets. Signing material is decoded only under the runner's temporary directory and removed afterward. Version code must increase with every Android update; never reuse a published tag or replace release assets.

The first release is built and signed locally. A one-time GitHub-hosted job verifies its source tag, signature, pinned certificate and checksums, uploads the prepared public assets and checks their remote digests before publication. This avoids the managed environment’s missing credentials for GitHub’s separate upload host; no signing key is uploaded. The temporary staging branch is removed afterward. This qualifies hosted asset publication, not the full build-from-tag workflow. Once configured, subsequent tags run the workflow. Manual dispatch must target an existing version tag.

## Publish a locally verified version

After committing and pushing the tested source, building and packaging that exact revision:

```sh
git tag -a v0.2.0-alpha.1 -m 'Lantern 0.2.0-alpha.1'
git push origin v0.2.0-alpha.1
gh release create v0.2.0-alpha.1 build/releases/v0.2.0-alpha.1/* \
  --verify-tag --prerelease --title 'Lantern v0.2.0-alpha.1' \
  --notes-file docs/releases/current.md
```

Do not run both local publication and enabled automation for the same tag. Physical device inference, OEM background lifetime, real push delivery, OLED behavior and multi-phone backup/power-loss qualification remain required; see [validation](validation.md) and [device acceptance](device-acceptance.md).
