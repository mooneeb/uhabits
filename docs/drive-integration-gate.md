# Android/web Drive integration gate for issue #2

This is a development checkpoint, not the completed PWA or synchronization feature. Do not expand the full UI or finalize the production file layout until the live gate passes. A build, mocked transport, or missing credentials cannot pass it. The harness never opens the ordinary habit database: all uploaded samples use the separate `loop-drive-integration-v1` namespace and a fresh test-run UUID.

## What the Google setup means

A Google Cloud project identifies the application to Google. It does not host habit data. Android and browser OAuth clients in that project ask the signed-in owner to authorize direct access to the application's hidden Drive storage. No habit-data backend, service account, billing account, or server-held client secret is required for this gate.

1. Open [Google Cloud Console](https://console.cloud.google.com/). Use the top project selector → New Project. Name it **Loop Habits**. Keep the same project selected for both clients.
2. Open APIs & Services → Library. Search **Google Drive API** and click Enable.
3. Open Google Auth Platform → Branding. Click Get Started if required. Use **Loop Habits** as the app name and your email for support/contact. Choose **External**, finish the consent setup, and leave the app in **Testing**. Under Audience → Test users → Add users, add the Google account you will use on both devices. [Google consent-screen instructions](https://developers.google.com/workspace/guides/configure-oauth-consent)
4. Under Data Access → Add or Remove Scopes, add `https://www.googleapis.com/auth/drive.appdata` and save. This is access to app-specific hidden data, not your ordinary Drive documents. [Drive app-data documentation](https://developers.google.com/workspace/drive/api/guides/appdata)
5. Under Clients → Create client, choose Web application and name it **Loop Web Local**. Add `http://localhost` and `http://localhost:8080` as Authorized JavaScript origins. Leave redirect URIs empty for this token-based browser flow. Copy the Client ID. The generated client secret is not used. [Google web client setup](https://developers.google.com/identity/oauth2/web/guides/get-google-api-clientid)
6. Run `scripts/drive-gate.sh fingerprint`. Under Clients → Create client, choose Android and name it **Loop Android Local**. Use package name `org.isoron.uhabits` and the **SHA1 of the debug variant** printed by that command. Register both clients in the same project. Debug signing is for the integration gate; release signing will require a separate stable owner-controlled key and corresponding OAuth registration. [Google Android authorization setup](https://developer.android.com/identity/authorization)

The IDs are public configuration. Do not put OAuth access tokens or client secrets in source files. The browser asks for its client ID at runtime. Android identifies the client using the package and signing fingerprint, so its client ID is not embedded in the APK.

Use a phone signed into the test account with Google Play services. Enable Developer options (Settings → About phone → tap Build number seven times), enable USB debugging under Developer options, connect by USB, and accept the computer authorization prompt. `adb devices` must show `device`, not `unauthorized`. Alternatively use a Google Play-enabled emulator signed into that account; an AOSP-only image cannot authorize this client. Set `ANDROID_SERIAL` if more than one device is connected.

## Local prerequisites and commands

Use JDK 17, Android SDK platform 36/build tools, Node, Python 3, and Chrome/Chromium. Set `JAVA_HOME` to your JDK 17 directory and `sdk.dir` in ignored `local.properties` to the SDK directory. On the development Mac these are currently:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
# local.properties: sdk.dir=/opt/homebrew/share/android-commandlinetools
scripts/drive-gate.sh build
scripts/drive-gate.sh serve
```

Open `http://localhost:8080` in Chrome. In another terminal run:

```sh
scripts/drive-gate.sh android
```

This installs a debug APK without uninstalling or clearing app data, then opens the debug-only integration activity. The probe is absent from release builds. A signer mismatch must be handled intentionally; the script never uninstalls an existing app to bypass it.

## Repeatable live procedure

1. In the browser enter the web Client ID and click New test run. Copy that UUID into Android's Test run UUID field. Connect Google Drive on both clients and check that both show the intended test account.
2. Publish the Android sample with amount **12.345** and notes **Android sample — آزمائش**. Discover this run in the browser. Verify the amount is represented as **12345 thousandths**, dated **2026-10-01**, with the exact notes, and no shared-core progress mismatch. These deliberately fixed historical dates make the fixture repeatable.
3. Publish a browser sample with amount **6.789** and notes **Browser sample — آزمائش**. Discover on Android and verify **6789 thousandths**, the date and notes, and matching score/streak. Both clients must actually discover the other producer before recording success.
4. Publish at least one additional sample. Discover on both clients. The page size is deliberately two so three or more files exercise pagination. Save the displayed records and metrics: requests, pages, files, downloaded bytes and elapsed milliseconds. Repeat with a larger synthetic run to evaluate file growth and scan cost; no physical production packaging decision is established by one small passing run.
   Click Measure bundled discovery to compare two files containing 1,000 synthetic records each. This measures real Drive reads and validates every record through the shared core. The producer labels simulate packaging, not additional Android-originated actions. These files have a separate packaging namespace and are deleted by their exact returned identities, including after a failed measurement.
5. Use a second Google account that is also registered as a test user. Reconnect and discover the same run UUID; it must not see the first account's samples. Return to the original account. This harness retains neither tokens nor habit data across a page/activity restart.
6. Verify reconnect behavior after a real token expiry/revocation. Reconnect on the original account and repeat discovery. Do not interpret merely clicking Connect twice as proof that expiry recovery works.
7. After capturing evidence, click Delete this run's synthetic samples in the browser. Confirm only the named run. Discovery and full metadata/payload validation happen before deletion; records outside this namespace/run cannot be selected. Verify both clients discover an empty run afterward. If validation fails, inspect the synthetic record before proceeding; do not broaden the cleanup query.

Both transports talk directly to Google's HTTPS APIs. They use `about.user.permissionId` for account identity; the app-data scope supports that request. [Drive account information API](https://developers.google.com/workspace/drive/api/reference/rest/v3/about/get)

## Evidence and next work

### Observed on 2026-10-04

The live run `ebaf3e67-4dc2-423b-b2ef-7f04e2d9be6f` used project `loop-habit-510612`, web client `436833216636-ehkfmi2pi45qosum1pm96v5v4g2kucen.apps.googleusercontent.com`, and Android client `436833216636-j50dask2qhkg5i0oa7rvj6796vradau7.apps.googleusercontent.com`. The debug APK signer SHA1 was `E2:4E:39:B9:D9:F9:F1:A5:C1:31:B5:7B:05:BD:78:1F:D3:B2:4C:14`. Android ran on the Google Play API 36 ARM64 `loop-drive-api36` emulator. The collaborative browser was Chromium 152.

Real browser-to-Android and Android-to-browser discovery preserved 6.789 and 12.345 amounts, Unicode notes, the historical date, and matching shared-core scores/streaks. The 12-file run exercised six pages on each client: 18 requests, 4,940 bytes, 13,489 ms on Android and 16,599 ms in the browser. After actually revoking the Google token, both clients required reconnect. After consent, both read all 12 records again (13,064 ms Android, 16,624 ms browser).

The separate packaging probe uploaded two synthetic packs totaling 2,000 records. Real discovery and complete validation used three requests, 867,768 bytes and 3,480 ms. Both probe files were deleted before it reported success. This supports bundling immutable logical changes into per-device history packs and caching already validated downloads; it does not measure Android writing such packs or establish unlimited-history performance.

Cleanup deleted exactly 12 validated integration records. Subsequent discovery returned zero files on both clients. Raw token-free evidence is in ignored `build/drive-gate-evidence/`. Second-account isolation remains untested because only one test account was supplied. The production workflow and installed-PWA acceptance remain outstanding.

Record the client/project configuration, APK signer, browser/device versions, test-run UUID, observed records and metrics for each direction, account isolation and reconnect outcomes, and cleanup outcome. Never capture tokens. Until the real procedure is executed, record **live gate unavailable**, not passed.

The shared progress view delegates to Loop's existing original/computed entry, score and streak calculations on both JVM and JS. Common tests support model correctness; the live gate supports authorization and shared app-data feasibility. Neither proves the full user acceptance workflow. Issue #2 still requires durable mutations with pending changes, conflict preservation, the responsive installable PWA, native client integration, shared settings, organization and full progress parity, offline installed cold launch, and real end-to-end habit workflows.
