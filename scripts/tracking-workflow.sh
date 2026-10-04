#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

device=(adb -P "${LOOP_ADB_PORT:-5037}" -s "${ANDROID_SERIAL:-emulator-5554}")
case "${1:-help}" in
  build) scripts/drive-gate.sh build ;;
  serve) scripts/drive-gate.sh serve ;;
  android)
    run=${2:?Supply the isolated test UUID}
    [[ "$run" =~ ^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$ ]] || { echo "Invalid test UUID" >&2; exit 2; }
    "${device[@]}" get-state
    "${device[@]}" install -r uhabits-android/build/outputs/apk/debug/uhabits-android-debug.apk
    "${device[@]}" reverse tcp:8080 tcp:8080
    "${device[@]}" shell am start -n org.isoron.uhabits/.MainActivity --es loopTestWorkspace "$run"
    echo "Browser: http://localhost:8080/app/?testRun=$run"
    echo "Use an empty debug installation. An existing workspace is never erased or reassigned."
    ;;
  *)
    echo "Usage: scripts/tracking-workflow.sh {build|serve|android TEST_UUID}"
    echo "Google consent is interactive. Build success alone does not pass live acceptance."
    ;;
esac
