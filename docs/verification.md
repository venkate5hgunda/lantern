# Verification

Automated test commands and what each test layer does and does not prove.

[← Back to the README](../README.md)

```sh
python3 -m unittest discover -s scripts -p 'test_*.py'
cd web
npm test
npm run build
npx playwright test
npm audit --audit-level=moderate
cd ../android
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

Browser tests distinguish mocked HTTPS protocol cases from **real encoded
WebRTC transport** against a local test-only reference peer. The reference
encoder is Chromium; the viewer runs the selected Chromium, Firefox or WebKit
engine. Phone/mobile/TV profiles emulate viewport/input/layout, not their
physical OS, microphone, speaker, hardware codec, certificate store or remote
control. The test-only peer is not part of the deployed architecture.

Native instrumentation uses real Android Camera2, WebRTC preview,
SQLite/directory fsync, Keystore TLS and the embedded HTTPS API. It uses
disposable identities, refuses to replace an owner's identity and cleans up
its own keys. A separate optional fixture verifies the exact OpenSSL
provisioning profile; see the Android README. Never give it a production CA
key or production password.

Before installing for real security use, complete
[the physical-device acceptance checklist](device-acceptance.md).
Executed checks and their limits are recorded in
[the validation receipt](validation.md). The optional real
Android-camera-to-Chromium gate is documented in
[the Android README](../android/README.md#actual-camera-to-browser-gate).
The shared wire contract is [docs/protocol.md](protocol.md).
