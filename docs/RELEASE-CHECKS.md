# Issue #4 release checks

Recorded 2026-10-05. The 20310 signed Android / public PWA acceptance matrix is complete,
including the final original-account reconnect and recorded/inferred outcome
comparison. Supporting tests remain distinct from live acceptance.

## Release 20311: empty Drive workspace regression

The owner reported `Cannot read properties of undefined (reading 'some')` after
reconnecting and choosing Sync now, before GitHub publication. The compiled
history serializer omits the default empty `changes` list. Drive synchronization
now treats that omitted list as empty, matching the facade's existing behavior.
The regression test uses the real compiled core, serializer and memory storage;
only the external Drive transport is replaced. It failed with the reported
TypeError before the fix and passed afterward, including the first habit's upload
and acknowledgement. All 11 focused Node checks passed.

Production release 20311 (`2.3.11-personal`), source
`d1261fd10e690ec794d0b4b32aa0f3d870f04d99`, passed the signed build and owner
certificate check. Its public assets are `/home/moon/loop-pwa/20311/site`.
The actual preview initially still loaded the old cached module; completing the
update-page button removed the old shell cache and loaded the fixed module.
With real owner-completed Google consent, the empty isolated workspace
`d22d5aba-6885-4432-8808-ce7d529b7d28` reached Synchronized with Drive, including
an explicit Sync now. Creating “First habit after empty Drive sync” through the
real editor uploaded it and again reached Synchronized with Drive. No tokens
were injected and the normal workspace was untouched.

A real same-key `adb install -r` from 20310 to 20311 retained native account,
habits and Synchronized with Drive. The actual numeric dialog retained `9.876`
and “Public installed offline restart”. Evidence:
`build/issue-4-empty-sync-red.log`, `build/issue-4-empty-sync-green.log`,
`build/issue-4-release-20311.log`, `build/issue-4-20311-upgrade.png`.
The earlier matrix below remains evidence for 20310; it is not relabelled as a
full 20311 rerun. The additional change is confined to this browser sync guard.

Owner cluster manifests and an encrypted copy of the existing tunnel Secret are
committed to lunar-lab (`a469ceb`), pulled on homeserver and applied from
`k8s/apps/habits`. Kustomize renders successfully; live diff returns zero and
both Deployments are ready 1/1. The SOPS-decrypted credential was compared with
the live Secret without printing it. Static release files remain outside Git.

Both review axes found zero remaining findings in the new fix/regression.
The live empty-sync and first-habit checks requested by Spec review passed.

## Environment and release (20310 acceptance)


- Public HTTPS: https://habits.mooneeb.dev/app/, dedicated Cloudflare Tunnel
  `loop-habits`, Kubernetes namespace `habits`, Deployment `loop-pwa`.
  `moon@homeserver`, remote kubeconfig `~/.kube/config`, context `default`.
  Versioned read-only assets: `/home/moon/loop-pwa/20310/site`.
- Owner-signed APK 20310, source `7dc7a5b5`, artifact
  `build/release/20310/Loop-20310.apk`. Signature verified against the retained
  owner certificate in RELEASE.md. Native and public static releases match.
- Actual Google consent and private Drive interoperability passed using the
  dedicated account `m.mooneeb.h@gmail.com`, project `loop-habit-510612`, native
  Google Play API 36 emulator `loop-owner-release-api36` (`emulator-5556`), and
  desktop Chromium through the T3 preview. No credentials/tokens were injected.
- Public routing, signed Android OAuth registration and owner SSH access are
  working. Earlier missing prerequisites are resolved.

## Live signed/public results

| Journey | Observed result |
| --- | --- |
| Bidirectional entries | Public Boolean completion and multiline note reached signed Android; native numeric 12.345 and note reached public PWA. Later 34.567 and independent 1.234 notes retained full precision. |
| Native widget offline | Home-screen Checkmark widget changed Browser Walk offline. One pending change survived same-key APK upgrade/restart and reached the public PWA after reconnect, preserving the note. |
| Reminder weekdays | Public 00:54 Mon/Wed/Sat configured the native exact alarm and delivered the actual notification. Its No action propagated as Missed with the existing note. |
| Modern notification snooze | Release 20309 showed Yes/No/Later on API 36. Later opened the actual custom clock picker; 01:35 snooze persisted. Public removal of Monday left Wed/Sat, yet the stored Monday snooze delivered after process death. Actual Yes reached public Completed with its dated note. |
| Reminder disable | Public disable propagated as null reminder in native full backup; the future 01:28 regular alarm and 01:35 snooze were absent afterward. |
| Background update | Native Home/process death followed by public rename to Release Background Walk updated the home-screen widget before the native app reopened. The normal pending-action job 7402 reconciled it. Periodic job 7401 also ran naturally; this rename is not attributed specifically to 7401. |
| Supported imports | Actual public forms imported Loop, Rewire and Tickmate database fixtures and HabitBull CSV; resulting definitions/history reached signed native UI/full backup. |
| Older restore | Actual native Settings import of an older causal JSON exposed 12.345 versus newer 34.567 instead of overwriting. Independent earlier-date 1.234 and its multiline note survived. Native explicit Keep 34.567 converged in both clients. |
| Recovery and purge | Native deletion and explicit purge propagated. A real stale pre-lifecycle JSON import in the public PWA retained the permanent purge barrier with no purged UUID payload. Recoverable deletion remained until explicit public Restore; the restored habit reappeared natively. Extra ordering/reminder conflicts from the old restore were explicitly resolved. |
| Malformed inputs | Public malformed JSON, unsupported backup version, invalid February-30 CSV and Loop database entry with null date produced actionable errors. Full exports before/after malformed database were byte-identical. A valid later import/export worked after failed JSON import. |
| CSV/full backup | Actual native exports and public CSV/causal JSON exports preserved raw original entries, three-decimal amounts, multiline dated notes and purge/recovery history. Native files retained in ignored build artifacts. |
| Signed upgrade | Offline 20305 → 20309 `adb install -r`, same owner key: pending widget edit, numeric conflict, recoverable deletion and purge barrier all retained. Reconnect uploaded the pending edit. 20310 installation also retained data and account. |
| Public shell update | Actual update-page button preserved a 25,837-byte full backup byte-for-byte. Another update preserved one pending dated Skip/note and its 26,428-byte full backup; owner reconnect uploaded it and native received the Skip/note. HTTP refresh now includes CSS. |
| Delete-versus-edit | Native offline rename and installed-PWA offline deletion produced a deletion conflict in both clients. Native explicit Keep false retained the renamed habit; desktop reached Synchronized with Drive with no conflicts. |
| Fresh third replica | Public importing client was navigated to the update page and native was stopped. Fresh Android Chrome authorized the same test account and downloaded all imported/later history. Its actual exported 54,688-byte full backup exactly matched the original public export: 57 envelopes and the permanent purge barrier. |
| Installed public PWA offline | Android Chrome installed WebAPK `org.chromium.webapk.aabb6ce3a360002e6_v2`. With Wi-Fi/mobile disabled, cold launcher open retained habits. Offline 9.876/note survived another force-stop/cold launch with one pending change. Signed native 7.123/distinct note separately survived offline restart. After reconnect both actual clients preserved competing 7.123/native and 9.876/installed-PWA versions with distinct notes. Native explicit Keep 9.876 reached desktop with its note; the independent earlier-date 1.234 remained. |
| Public temporary session | Fresh Android Chrome temporary page started empty with New habit disabled. Real Google authorization enabled online editing. October-2 2.468/note reached desktop and signed native; save status became Saved in Drive. Disabling Wi-Fi/mobile disabled New habit and showed Offline, zero pending. End cleared history and reported memory cleared. Returning to owned mode retained its earlier cached history/conflicts, and another fresh temporary page started empty. |
| Flexible habits/statistics | Public numeric at-most 2.5 cups, 3/7 frequency, historical 1.234 and dated multiline note reached signed Android. Both displayed 100% strength and week/month/quarter/year totals/targets 1.234/2.5, /10, /32.5, /130. Native 3-times-per-week Boolean creation and October-4 calendar completion reached the public grid. |
| Independent definition edits | Native editor rename/description and public color edit retained both after Drive reconciliation. Dated note remained separate from the description. |
| Archive/reactivation and navigation | Public archive hid the numeric habit from native active list. Native Unarchive returned it to the public grid with its 1.234 record/note. Public search/name-sort/filter behaved as expected, and manual move propagated. Day/week/month/quarter/year graph periods and expandable values rendered. |
| Shared settings | Public 3 a.m. day start selected October 4 before 3 a.m. October 5. Native Settings showed delayed day start enabled and Sunday week start. Native Monday selection then reached public Settings; public midnight selection returned the grid to October 5. Existing October-5 and older records retained their dates. |
| Travel dates | Actual Android system time zone changed from Asia/Karachi to Pacific/Pago_Pago across the date boundary. Cold native and installed-PWA launches selected Sunday October 4 instead of Monday October 5; October-4 1.234 remained on October 4, and the installed PWA retained its cached October-5 conflict identity. Actual native full exports preserved all 65 prior causal changes and purge barriers unchanged. The sole additional change was the separately authorized October-1 account-check fixture. Asia/Karachi and automatic time-zone selection were restored afterward. |
| Account isolation | With one pending October-3 0.125/note bound to the original test account, actual Google selection of mooneeb.hussain@gmail.com displayed “Stored habits belong to another Google account”. The app remained disconnected, retained the original account title and pending dated note, and did not adopt the other account. Original-account reconnect uploaded it; signed native entry dialog showed the exact 0.125 and Account-bound retry acceptance note. Both clients reached Synchronized with Drive, zero pending. |
| Keyboard and touch controls | Public desktop search accepted typing and Tab moved focus to Sort. In the actual entry form, Tab moved from notes to Save entry; Enter saved the completion and closed the dialog. Public Android Chrome calendar, editor, temporary-session and launcher journeys used real touch controls. |
| Streak/weekday comparison | Release Background Walk showed matching one-day streaks for October 3 (Skip) and October 5 (Completed) in signed native accessibility values and public details. October weekday frequency showed one recorded Monday completion and zero on every other weekday in both; native orders Saturday first, web Monday first. Both showed 5% strength. |
| Completion undo and inference | Release Three Per Week retained October-1/2/4 original completions and an explicit October-5 Unknown undo with its keyboard-entered note. Native separately completed, marked Missed, then selected Unknown with the same note through its actual entry dialog. After reconnect both agreed: 6% strength, October 1–7 seven-day streak, and recorded Thursday/Friday/Sunday counts of one, other weekdays zero. Native calendar distinguished lighter inferred October-3/5 cells from original completions; public history labelled October-5 Unknown · inferred completion and October-3 Inferred completion. Actual native CSV contained October-5 UNKNOWN/note in Original entries, no October-3 original entry, and YES_AUTO for both in calculated Checkmarks. Native local question-mark presentation preference was restored afterward. |

Screenshots and actual exported fixtures are in ignored `build/issue-4-*` files.
Notable captures: `issue-4-upgrade-before.png`, `issue-4-upgrade-after.png`,
`issue-4-off-weekday-snooze-delivery.png`, `issue-4-widget-background-later.png`.
Native exports: `issue-4-signed-snooze-complete.loop.json`,
`issue-4-signed-csv.zip`, `issue-4-final-outcomes-csv.zip`. Final captures include
`issue-4-account-original-delivery.png` and
`issue-4-flexible-streak-frequency.png`. These are synthetic dedicated-account fixtures.

## Discovery cost validation

Before the purge fix, the actual public client downloaded 620 package bodies in
658.7 seconds (642 Drive API requests) and continuously displayed Synchronizing.
Both clients now acknowledge a complete purge scan only after successful cleanup,
then discover unknown bodies incrementally while still checking all metadata for
late stale payloads. The public first successful 20310 cycle downloaded 31 bodies;
the next observed automatic cycle listed metadata with zero body downloads and
showed Synchronized with Drive. A subsequent idle window of 95.3 seconds made four metadata requests and zero body downloads, with Synchronized with Drive displayed.
Native 20310 reached Synchronized with Drive, zero pending.

## Acceptance boundary

All remaining primary signed/public checks passed, including travel dates,
account-switch refusal and original-account delivery, keyboard/touch controls,
streak/weekday agreement, and original Unknown versus derived completion.
Temporary mid-save interruption remains supporting boundary-fault coverage, as
permitted for rare interruptions by parent #1; live ordinary save/end/offline
gating passed. Prior debug/local issue #2/#3 evidence remains in
tracking-workflows.md and reliability-workflows.md and is not silently relabelled
as signed/public evidence.

## Supporting checks

Latest purge/quick-action change validation: 316 JVM tests and 5 Android unit tests,
20 focused core JS tests, one web facade browser regression, and 10 Node checks
passed. Earlier focused portability/sync JS runs also passed. Logs:
`build/issue-4-discovery-validation.log`,
`build/issue-4-discovery-js-validation.log`,
`build/issue-4-discovery-node.log`, `build/issue-4-release-20310.log`.

The full JS run hit the previously documented unchanged
`CanvasTest.testDrawTestImage` reference-render failure, then browser disconnects
and no-activity timeouts. It was stopped; the full JS suite is not reported as
passing. Versioned release build and owner signature verification passed.

## Code review

### Standards

Review against owner-confirmed `ed95237`: no remaining documented-standard or
meaningful heuristic violation. Initial global-component reach-through in backup
export was replaced by injection through the existing factory.

### Spec

Resolved findings: zero-weekday cancellation, persisted custom snooze, malformed
source database rejection, quick-action receiver lifetime through durable save,
and purge discovery repeatedly downloading unchanged history. Both parallel
reviewers confirmed the final purge fix, including retry/new-barrier handling.
No material scope creep found. The previously missing primary outcome,
streak/weekday and keyboard observations are now recorded above, together with
original-account delivery. Closure relies on these live results alongside
supporting checks, rather than test counts alone.
