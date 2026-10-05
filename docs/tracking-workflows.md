# Live Android/web tracking workflows for issue #2

The static PWA is at `/app/`. It uses the existing Kotlin core for habit outcomes, scores, streaks, targets and graph calculations, and synchronizes directly with Google Drive application data. Android writes its causal changes in the same SQLite transaction as habit mutations; the owned web app uses IndexedDB transactions. Google access tokens stay in memory. No habit-data server or client secret is used.

This records the issue #2 implementation checkpoint. Subsequent conflict resolution, deletion recovery/purge, temporary sessions, imports/exports and release validation are documented in [reliability workflows](reliability-workflows.md) and [release checks](RELEASE-CHECKS.md). Competing revisions remain in durable history; ordinary edits cannot silently resolve them. ADR 0004 remains a proposal, not an accepted protocol decision.

## Configuration and launch

Use JDK 17, Android SDK 36, Node, Python 3, Chrome/Chromium and a Google Play-enabled emulator or Android device. See [the initial gate setup](drive-integration-gate.md) for Google console instructions. Both OAuth clients must belong to the same project and the account must be an authorized test user.

Configure your own web client ID in `uhabits-web/src/jsMain/resources/app/config.mjs`
for local development, or pass it to `scripts/package-pwa.py` when packaging.
Android is identified by package `org.isoron.uhabits` and the APK signing
certificate, not an embedded Android client ID. Use `scripts/drive-gate.sh fingerprint` to inspect your debug signer. Register a production signer separately.
See [Google setup](GOOGLE-SETUP.md).

```sh
export JAVA_HOME="<YOUR_JDK_17_DIRECTORY>"
# Ignored local.properties: sdk.dir=<YOUR_ANDROID_SDK_DIRECTORY>
scripts/tracking-workflow.sh build
scripts/tracking-workflow.sh serve
```

The default app starts empty at `http://localhost:8080/app/`. Use a new isolated UUID for acceptance tests, on an empty **dedicated debug installation**:

```sh
run=$(python3 -c 'import uuid; print(uuid.uuid4())')
export ANDROID_SERIAL=emulator-5554
export LOOP_ADB_PORT=5038  # Omit for the usual adb server on 5037.
scripts/tracking-workflow.sh android "$run"
```

Open the printed `/app/?testRun=UUID` URL in desktop and Android Chrome. Connect the same test account in each client. In native Loop, use More options → Google Drive synchronization → Connect Google Drive. The debug selector refuses to move an existing history to a different workspace, and the script never uninstalls or clears app data. Use a separate empty emulator to begin another run.

The T3 desktop preview intermittently creates a Google popup without displaying it. This was observed again after its authorization expired at the end of testing. If no account chooser appears, open the same localhost URL in ordinary Chrome and connect there; each browser owns its own local data and authorization. The desktop live checks below completed with an authorized preview session, and the installed Android Chrome account chooser worked during reconnect testing.

Localhost is the secure development origin for installation. Public hosting needs HTTPS and its authorized Google JavaScript origin. Build output is `uhabits-web/build/drive-gate/`; the app directory and sibling `loop-core.js` must be deployed together. Custom APK updates use `adb install -r` with the same signer.

## UI harness

`scripts/android-workflow.py` inspects and drives actual accessible Android/Chrome controls. It rejects password input and ambiguous targets. Run actions sequentially; overlapping `uiautomator` dumps interfere with each other.

```sh
scripts/android-workflow.py --port 5038 inspect
scripts/android-workflow.py --port 5038 tap 'id=actionCreateHabit'
scripts/android-workflow.py --port 5038 tap 'text=Yes or No'
scripts/android-workflow.py --port 5038 input 'id=nameInput' 'Android Reading'
scripts/android-workflow.py --port 5038 assert 'text=Android Reading'
scripts/android-workflow.py --port 5038 shot build/feature-evidence/android.png
```

The browser counterpart is `/app/workflow.mjs`. Import it in the connected synthetic page through the collaborative preview evaluator or browser console. It submits the real entry form, including historical dates outside the visible week. It never writes history through a test business API and returns no token:

```js
const workflow = await import('./workflow.mjs');
await workflow.record('Browser Distance', '2026-10-01', 6.789, 'Browser note — آزمائش');
await workflow.synchronized();
workflow.visibleHabits();
```

For repeatable automation of the actual installed Android Chrome window, Node 22+ and its built-in WebSocket can use Chrome's adb debugging socket. The script selects exactly one localhost app page with the supplied test UUID and drives the same public forms:

```sh
adb -P 5038 -s emulator-5554 forward tcp:9223 localabstract:chrome_devtools_remote
node scripts/android-web-workflow.mjs inspect "$run"
node scripts/android-web-workflow.mjs record "$run" 'Browser Distance' 2026-10-01 amount 6.789 'Browser note'
# Only after all pending edits are synchronized and native Loop is stopped:
node scripts/android-web-workflow.mjs cleanup "$run"
```

Google account selection remains interactive in Android Chrome; the harness does not enter credentials or bypass consent. `LOOP_CHROME_PORT` can select another forwarded port.

For yes/no outcomes use strings: `'2'` completed, `'0'` missed, `'3'` skipped, `'-1'` unknown. A numeric JavaScript value means an amount. Wait for visible outcomes with a bounded timeout; the harness waits up to 60 seconds for save/sync. Tools with a shorter evaluation deadline should start an asynchronous action and poll its result. “Synchronized” means this client reconciled Drive, not that every other device already downloaded it.

## Repeatable acceptance sequence

1. Create **Browser Walk**, yes/no, 3 times in 7 days; provide a question and a description. Create **Browser Distance**, numeric, daily, at least 10 km. Create **Browser Limit**, numeric, 2 times in 7 days, at most 2.5 hours. Wait for native Loop to show their names, types, frequencies and target settings.
2. Create **Android Reading** through native Add habit. Edit its question and description. Wait for the desktop to display the habit and those fields. Open its native editor, change its name, change its color from the browser while the editor stays open, then save the native rename. Both independent edits must survive.
3. Record Distance on October 1 through the browser with amount 6.789 and a Unicode note. Open the native historical entry editor: verify all three decimal places and the note, then change the amount to 12.345 and change the dated note. Verify the browser history, amount and original habit description. Add 0.125 on October 3.
4. Record Walk as completed, skipped, missed and unknown on distinct historical dates using its entry form. Toggle a completion and undo it. Verify native calendar outcomes and browser history/notes. Flexible-frequency inferred completions must remain distinct from recorded entries.
5. Move Distance above Walk in the web app. Verify native manual order. Archive Reading in the browser; native Hide archived must hide it. Show archived, reactivate it in native Loop and verify it returns to the active web list with its old entry. Drag Walk above Distance on Android and verify browser order. Exercise web search, name sort and Hide completed, then restore manual order.
6. Review equivalent progress on both clients. Native charts expose their actual values through accessibility descriptions; web charts have expandable value tables. Compare score, original history, streak dates/lengths, targets and weekday amounts. Change graph periods and shared week start, then repeat comparisons.
7. Change shared day start between midnight and 3 a.m.; native Settings and the web Settings form must agree. With a device timezone where local time is before 3 a.m., verify the two settings select different current habit dates. Change timezone across a date boundary and verify existing recorded dates and notes remain fixed. Restore the emulator timezone and shared settings. Appearance is local to each device.
8. Install the PWA through Install Loop or Chrome's Install app menu. Confirm a launcher entry and standalone window. Disable emulator Wi-Fi/mobile data **and remove the adb reverse mapping**; force-stop Chrome and the installed WebAPK, then open the launcher entry. Cached assets and saved habits must reopen without either Google or the development server.
9. Save a new amount, for example 1.234, offline. Verify Saved on this device and one pending change. Force-stop and reopen again while offline; verify the amount and pending change remain. Restore network and the reverse mapping, reconnect through the actual Google account chooser, and verify the entry reaches native Loop and desktop with matching progress.
10. Save screenshots and visible values without credentials. Before cleanup, wait for all clients' pending counts to reach zero and stop the native synthetic client. In the **updated, connected** test PWA open Test workspace cleanup, type the exact run UUID, and delete this test's Drive files. Cleanup validates every revision's metadata, payload, account, namespace and workspace before selecting exact file IDs. It cannot target the ordinary `default` workspace. Local synthetic data is retained; close those test pages and retire their dedicated installation after cleanup. Retry a partial network failure using the same UUID; never broaden the query.

On a Pixel 7 emulator, native drag can also be automated using one continuous `input touchscreen motionevent DOWN`, a short hold, then `MOVE` and `UP`. Obtain coordinates from a fresh screenshot; do not hard-code coordinates from another screen size. Calendar canvas interactions likewise require a fresh screenshot.

## Observed evidence, 2026-10-04

The initial authorization/discovery/packaging gate passed before the full UI was built; its measurements and strictly scoped cleanup are in the separate gate document. The production workflow used isolated run `6b838f1a-1be0-43e7-927b-360762eb17ed`, API 36 Google Play ARM64 emulator, native debug Loop, Android Chrome 133, and desktop Chromium 152 in T3 Code.

Actual Drive propagation covered browser-created yes/no and numeric definitions, native-created Reading, independent native rename/web color changes, historical three-decimal amounts and dated notes, browser archive/native reactivation with retained history, and manual ordering in both directions. Web search and name sort produced the expected visible lists. Completed/skipped/missed/unknown dated records remained distinct. A numeric at-most habit retained its flexible frequency, unit, target and description in native Loop.

The installed WebAPK `org.chromium.webapk.a984e50201c1c6f5e_v2` reopened offline as Chrome's standalone WebAPK activity. With both network transports disabled and the host bridge removed, an offline 1.234 entry remained locally saved and pending across a cold restart. After reconnect it appeared in native Loop and desktop. Distance then showed 1.234 today, 0.125 on October 3 and 12.345 on October 1. Both clients showed weekly/monthly total 13.704, weekly target 70, monthly target 310, quarterly target 910 and yearly target 3650. Native weekly score was 0.04993463138775734 and web displayed 4.99%; both showed the October 1 one-day streak and matching weekday amounts. Token-free screenshots/XML are in ignored `build/feature-evidence/`.

At approximately 2 a.m. October 5 in the emulator's Australia/Sydney timezone, a 3 a.m. day start kept October 4 as the habit date. Switching to midnight selected October 5 in the installed PWA and native Loop; the October 4 amount remained 1.234 and October 1 remained 12.345. This exposed and fixed a stale native calendar header. Shared settings controls now refresh both their selections and summaries without writing new changes. The emulator timezone is restored to Asia/Karachi after testing.

The final installed Android Chrome CLI run submitted the actual entry form with amount 0.125 on October 3 and notes “Verified through the installed Android UI harness.” Both desktop history and the native entry dialog showed the exact amount and notes. After all pending changes reached zero, native Loop was stopped and the installed PWA cleanup form removed 33 validated files from this run's private Drive workspace. The visible result was “Removed 33 validated test files. Close this test workspace.” Local synthetic fixtures are retained for inspection. Emulator networking, the localhost bridge and Asia/Karachi timezone were restored; the emulator remains running.

Only one Google test account was provided, so second-account live isolation is untested. Account/schema validation and refusal to rebind owned pending data are covered by supporting tests. Loop reserves raw entry value 3 for skipped days; the web rejects amount 0.003 explicitly rather than silently recording it as skipped. Other supported amounts retain three decimal places.

The full shared JVM suite passes 287 tests; Android debug and release pass 5 tests each. Browser model tests run against real ChromeHeadless: the completed run passed 258 of 259 tests. `CanvasTest.testDrawTestImage` times out in the unchanged base commit as well as this branch; the final repeated browser attempt also disconnected after that failure. This existing browser canvas failure is not counted as a passing suite. APK/static builds, Kotlin lint, JavaScript syntax checks and the two test-cleanup fault checks pass.
