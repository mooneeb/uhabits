#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${LOOP_KEY_STORE:?Set the path to the stable owner-controlled keystore}"
: "${LOOP_KEY_ALIAS:?Set the key alias}"
: "${LOOP_KEY_PASSWORD:?Set the key password}"
: "${LOOP_STORE_PASSWORD:?Set the keystore password}"
: "${LOOP_VERSION_CODE:?Set a monotonically increasing Android version code}"
: "${LOOP_VERSION_NAME:?Set the release version name}"
: "${LOOP_WEB_CLIENT_ID:?Set the public web OAuth client ID}"
: "${LOOP_PWA_IMAGE:?Set an immutable PWA image tag or digest}"
: "${LOOP_SIGNER_SHA256:?Set the expected owner certificate SHA256 fingerprint}"
[[ "$LOOP_VERSION_CODE" =~ ^[1-9][0-9]{0,8}$ ]] || { echo 'Use a positive Android version code below one billion' >&2; exit 2; }
[[ -f "$LOOP_KEY_STORE" ]] || { echo 'Keystore does not exist' >&2; exit 2; }
output="build/release/$LOOP_VERSION_CODE"
[[ ! -e "$output" ]] || { echo 'Release output already exists. Choose a new version code.' >&2; exit 2; }
./gradlew --no-configure-on-demand -PloopVersionCode="$LOOP_VERSION_CODE" -PloopVersionName="$LOOP_VERSION_NAME" \
  :uhabits-android:assembleRelease :uhabits-web:prepareDriveGate
apk=uhabits-android/build/outputs/apk/release/uhabits-android-release.apk
: "${ANDROID_SDK_ROOT:?Set the Android SDK path}"
verify="$ANDROID_SDK_ROOT/build-tools/35.0.0/apksigner"
[[ -x "$verify" ]] || { echo 'Install Android build-tools 35.0.0' >&2; exit 2; }
fingerprint=$("$verify" verify --print-certs "$apk" | sed -n 's/Signer #1 certificate SHA-256 digest: //p')
expected=$(echo "$LOOP_SIGNER_SHA256" | tr '[:upper:]' '[:lower:]' | tr -d ':')
[[ "$fingerprint" == "$expected" ]] || { echo 'APK signer differs from the configured owner key' >&2; exit 2; }
mkdir -p "$output"
cp "$apk" "$output/Loop-$LOOP_VERSION_CODE.apk"
python3 scripts/package-pwa.py --hostname "${LOOP_HOSTNAME:-habits.mooneeb.dev}" \
  --web-client-id "$LOOP_WEB_CLIENT_ID" --image "$LOOP_PWA_IMAGE" --output "$output/pwa"
python3 - "$output" <<'PY'
import hashlib, json, pathlib, subprocess, sys
out = pathlib.Path(sys.argv[1])
files = {str(p.relative_to(out)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(out.rglob('*')) if p.is_file()}
(out / 'checksums.json').write_text(json.dumps({'commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(), 'files': files}, indent=2) + '\n')
PY
echo "Release: $output · verified owner signer $fingerprint"
