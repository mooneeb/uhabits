#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

case "${1:-help}" in
    build)
        ./gradlew :uhabits-android:assembleDebug :uhabits-web:prepareDriveGate
        ;;
    serve)
        if [[ ! -f uhabits-web/build/drive-gate/loop-core.js ]]; then
            echo "Build the gate first: scripts/drive-gate.sh build" >&2
            exit 2
        fi
        exec python3 -m http.server 8080 --bind 127.0.0.1 --directory uhabits-web/build/drive-gate
        ;;
    android)
        if ! adb get-state >/dev/null 2>&1; then
            echo "Live environment unavailable: connect and authorize a Google-enabled Android device, or set ANDROID_SERIAL for an emulator." >&2
            exit 2
        fi
        adb install -r uhabits-android/build/outputs/apk/debug/uhabits-android-debug.apk
        adb shell am start -n org.isoron.uhabits/.sync.DriveIntegrationActivity
        ;;
    fingerprint)
        ./gradlew :uhabits-android:signingReport
        ;;
    *)
        echo "Usage: scripts/drive-gate.sh {build|serve|android|fingerprint}"
        echo "This interactive live gate requires Google OAuth setup; a successful build is not a passing gate."
        ;;
esac
