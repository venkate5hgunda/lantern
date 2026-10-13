# Build from source

Toolchain requirements and build commands for the web viewer and Android app.

[← Back to the README](../README.md)

For the complete first-camera walkthrough, use
[Set up and test Lantern](setup-and-test.md). It covers the new five-step
Android setup, viewer certificate trust, pairing, video, two-way audio,
snapshots, revocation and multi-camera testing. **Setup help** in the app
also provides offline guidance.

Use Node 22.12+ or Node 24, Python 3, OpenSSL, JDK 17 and an Android SDK with
API 35 available. Install browser engines for browser tests; those engines are
not physical iPhone, Windows or TV devices.

```sh
cd web
npm ci
npx playwright install chromium firefox webkit
npm run build
cd ../android
# Set JAVA_HOME to your JDK 17 and ANDROID_HOME to your Android SDK.
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The browser build must precede Android assembly: Gradle embeds `web/dist/` in
the APK. APK output:
`android/app/build/outputs/apk/debug/app-debug.apk`.
Windows native builds use the checked-in `gradlew.bat`.
For native setup and Samsung/MIUI lifecycle settings, see
[android/README.md](../android/README.md).
