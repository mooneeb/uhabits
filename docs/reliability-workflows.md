# Offline reliability and recovery for issue #3

Both clients use causal revisions: ordinary edits merge independent values; incompatible values remain until the owner chooses a version. Android's Google Drive synchronization screen and the PWA's competing-revisions panel provide that choice. A choice covers exactly the displayed revisions; a newly discovered incompatible revision requires another review. No clock decides a winner and conflicts do not expire.

Deleted habits appear in Recovery with their definition and dated history. Restore retains the UUID. Purge requires deletion and resolution of all known conflicts for that habit. History format 2 removes the habit's payloads and keeps a permanent identity barrier plus the causal envelopes needed for delivery and acknowledgement. Format 1 packs remain readable; old clients reject format 2, so update both clients together. ADR 0004 remains a proposal.

Drive cleanup publishes complete redacted histories before deleting old packages containing purged payloads. An interrupted cleanup retries; a disconnected replica can retain its own old payload until reconnect. After a new purge, both clients scan the existing workspace once and acknowledge that scan only after cleanup succeeds. Later syncs continue listing workspace metadata to detect newly arriving stale payloads, while downloading only unknown packages. Failed cleanup remains unacknowledged and retries. The existing 10 MB per-package limit remains.

## Temporary and owned browser sessions

Use `/session/?testRun=UUID` for an isolated temporary test session, or `/session/` for ordinary temporary access. The separate path lies outside the owned service-worker scope, preventing an older cached owned shell from opening durable storage. It does not read or write IndexedDB, device appearance storage, or access tokens to persistent storage. New edits require an online authorized connection. Pending edits remain in memory and closing warns about losing them; saved means Drive accepted the upload. End temporary session clears data, forms, and authorization references. Owned `/app/` data is unaffected.

Owned clients retain their original account binding through disconnects. Web clearing explicitly warns when pending edits will be discarded. Connecting another account cannot upload the retained original-account history.

## Reuse the acceptance emulator

Use a dedicated Google Play-enabled acceptance emulator. Preserve its test account, native synthetic history and installed PWA for restart checks; do not wipe it during a data-retention check.

```sh
export JAVA_HOME="<YOUR_JDK_17_DIRECTORY>"
export ANDROID_SDK_ROOT="<YOUR_ANDROID_SDK_DIRECTORY>"
export PATH="$ANDROID_SDK_ROOT/emulator:$ANDROID_SDK_ROOT/platform-tools:$PATH"
emulator -avd "<YOUR_TEST_AVD_NAME>" -no-boot-anim -no-audio -port 5554
# In a separate terminal:
adb devices
scripts/tracking-workflow.sh build
scripts/tracking-workflow.sh serve
adb install -r uhabits-android/build/outputs/apk/debug/uhabits-android-debug.apk
adb reverse tcp:8080 tcp:8080
```

The observed test environment used an API 36 ARM64 Google Play image with a retained signed-in test account. `LOOP_ADB_PORT=5038` selects the alternative adb server used by earlier sessions; the current run uses 5037. Configure OAuth and drive actual UI actions using [tracking workflows](tracking-workflows.md). Google account selection and consent remain interactive. Tests never enter passwords.

For offline checks, disable both `adb shell svc wifi disable` and `adb shell svc data disable`, and remove `adb reverse --remove tcp:8080`. Wait for an observable committed value before force-stopping a client; a tap whose asynchronous mutation has not completed is not a successful local save. Restore both network transports and the reverse mapping afterward.

The existing isolated workspace `6b838f1a-1be0-43e7-927b-360762eb17ed` was reused with retained local fixtures after its previous Drive cleanup. The `default` workspace is not used for acceptance.

## Supporting fault checks

Build the web bundle before running Node checks. `scripts/tests/reliability.test.mjs` uses the actual compiled Kotlin facade and injects failures only at external boundaries. It covers temporary isolation/end, purge replacement interruption, accepted uploads with lost replies, duplicate retry reconciliation, and clock skew. Focused shared tests additionally cover causal reordering, late resolutions, deletion conflicts, redacted package recovery, failed native writes, failed acknowledgement persistence across restart, and original-account binding.

```sh
./gradlew --no-configure-on-demand :uhabits-web:prepareDriveGate
node --test scripts/tests/*.test.mjs
./gradlew :uhabits-core:jvmTest --tests '*ChangeHistoryTest'
./gradlew :uhabits-core:jvmTest --tests '*SQLiteChangeStoreTest'
```

In a connected synthetic **owned** PWA, `await (await import('./workflow.mjs')).failedLocalSave()` injects an IndexedDB write failure through the actual habit form. It checks that the editor stays open, the status reports failure, and the habit does not appear. It restores the storage adapter afterward.

## Observed evidence

Real Drive on 2026-10-04 established the following with the native API 36 emulator and T3 Chromium:

- A temporary PWA created Reliability Distance and a dated 4.567 km entry with notes. Save feedback changed from pending memory to Saved in Drive after acceptance. Native charts showed the same amount. End cleared displayed history and the entry form; reopening owned mode retained its separate fixtures.
- Native Android saved 9.876 and `Native offline` for October 2 with Wi-Fi, mobile data, and the host bridge disabled. The exact amount and note reopened after force-stop. Reconnection preserved it alongside the web's concurrent 7.123 and distinct note in both conflict UIs. Native selection of 9.876 propagated to the web and removed the entry conflict.
- Native Wednesday week start versus web Friday, and native offline rename versus web deletion, both appeared as competing revisions on both clients. Web choices resolved them explicitly. Recovery retained the renamed definition, 9.876, note, and date. Web restore brought the same habit back to native charts.
- A browser IndexedDB write fault kept the form open, reported the injected durable-save failure, and did not create its habit.
- Web purge initially exposed a cross-platform bug: Android's integral Double targets serialized as `1.0`, while browser JSON serialized the same values as `1`. Tracking groups now normalize through their domain type before operation comparison. Both regressions failed before the fix and passed afterward. The stale native client then reconciled its retained offline rename, removed the purged habit, and reached zero pending changes. A read-only audit of all nine remaining Drive packages found no purged habit payloads; the identity barrier remained.
- With all emulator network paths disabled, the installed PWA created PWA Offline Created and October 2 amount 2.468 with `PWA offline creation survived restart`. Cold launch through the installed WebAPK reopened standalone with the exact entry, notes, and two pending changes. Native separately created Native Offline Created and amount 3.579 with `Native offline creation`; both survived force-stop. Reconnecting delivered both identities and exact entries through Drive without duplication.
- The user selected a second registered OAuth test account with an original-account edit pending. The original binding and one pending edit remained. Reconnecting the original account uploaded Account-bound Pending, which appeared on native Android.
- A real Google token revocation returned HTTP 200. The owned web UI reported Reconnect required with one pending 4.321 entry and its note retained. Reauthorization uploaded it, and the native entry dialog showed the exact amount and note. Native also recovered its expired authorization through Connect.
- A fresh temporary page had no habits, manifest, or service-worker controller, and disabled editing before authorization. After real authorization, an upload-boundary interruption left 6.789 and its note pending in memory, with close-warning feedback. End displayed the pending-discard confirmation and cleared history, detail, forms, and account title. The synthetic confirmation was accepted by the automation at the browser-dialog boundary. Owned data and the Drive audit contained no pending temporary note.

Token-free native and installed-PWA screenshots are in ignored `build/reliability-evidence/`. Temporary interrupted-save and failed-write journeys inject external boundary failures; the cross-platform synchronization, account selection, authorization revocation, restart, conflicts, and lifecycle journeys use real clients and Drive.

## Final checks

All 297 JVM tests, five Android debug and five release unit tests, seven Node boundary checks, and ktlint passed. The full JavaScript browser run completed 268 tests with one failure in the unchanged `org.isoron.platform.gui.CanvasTest.testDrawTestImage` reference-image test, also recorded in the earlier tracking workflow baseline. The 15 JavaScript history tests passed separately after the numeric normalization fix. APK and static PWA builds passed; source syntax and diff whitespace checks passed.

Before cleanup, all accepted owned-device edits reached zero pending changes, and both emulator clients were stopped. The owned PWA cleanup form reported `Removed 9 validated test files. Close this test workspace.` Only this UUID's exact validated Drive file IDs were removed; local synthetic fixtures remain for inspection. Emulator networking, the localhost bridge, and Asia/Karachi timezone remain restored.
