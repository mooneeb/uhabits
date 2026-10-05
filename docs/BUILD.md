# Build this fork

These instructions build `mooneeb/uhabits`, the Android/web fork. Upstream Loop's
store packages and build instructions belong to its separate distribution.
For a signed APK paired with your own Google project, use [RELEASE.md](RELEASE.md).

## Prerequisites

Install Git, JDK 17, Android SDK command-line tools or Android Studio, Python 3 and
Node.js for the browser tooling and supporting tests. Install Android SDK platform
36, build-tools 35.0.0 and platform-tools. Use a Google Play-enabled device/emulator
for real Android Google authorization. [Android SDK tools](https://developer.android.com/studio#command-line-tools-only)

Set `JAVA_HOME` to your installed JDK 17 directory. Set `ANDROID_SDK_ROOT` to your
SDK directory, or set `sdk.dir` in ignored `local.properties`. Use your own paths;
do not commit machine-specific settings.

```sh
git clone https://github.com/mooneeb/uhabits.git
cd uhabits
export JAVA_HOME="<YOUR_JDK_17_DIRECTORY>"
export ANDROID_SDK_ROOT="<YOUR_ANDROID_SDK_DIRECTORY>"
```

The repository's Gradle wrapper pins Gradle; no global Gradle installation is
required. The build also pins R8 8.13.19 for its Kotlin metadata compatibility.

## Android development build

```sh
./gradlew --no-configure-on-demand :uhabits-android:assembleDebug
adb install -r uhabits-android/build/outputs/apk/debug/uhabits-android-debug.apk
```

A debug build cannot update a differently signed release or upstream app. Preserve
a backup and use a dedicated emulator for development instead of uninstalling an
existing installation to bypass a signature mismatch. Minimum Android version is
9 (API 28); compile and target SDK are 36.

In Android Studio, open the cloned repository, select JDK 17 for Gradle, sync,
install any requested SDK components, then run the `uhabits-android` module.

## Web development build

```sh
./gradlew --no-configure-on-demand :uhabits-web:prepareDriveGate
scripts/drive-gate.sh serve
```

Open `http://localhost:8080/app/`. The task's name includes DriveGate because it
also assembles development integration tools. Its output is
`uhabits-web/build/drive-gate/`. For deployment, use `scripts/package-pwa.py` as
shown in [HOSTING.md](HOSTING.md); it removes the development gate and supplies
release configuration.

Set your own web client ID in
`uhabits-web/src/jsMain/resources/app/config.mjs` for local development. Register
localhost and your APK signer in the same Google project before connecting Drive.
See [GOOGLE-SETUP.md](GOOGLE-SETUP.md). A successful build does not establish working
Google consent or cross-device synchronization.

## Next steps

[TEST.md](TEST.md) covers supporting tests and live checks. [RELEASE.md](RELEASE.md)
covers stable signing and portable artifacts. [HOSTING.md](HOSTING.md) covers static,
Docker and Kubernetes deployment examples.
