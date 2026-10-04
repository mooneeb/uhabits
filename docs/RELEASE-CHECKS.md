# Issue #4 release checks

Recorded 2026-10-04. Issue #4 remains open until the deployed-PWA/signed-Android
and complete parent acceptance procedures pass. The supporting tests below are
not a claim that those live journeys passed.

## Confirmed

- SSH key access to `moon@homeserver` works. Remote kubeconfig
  `~/.kube/config`, context `default`, node `homeserver` is Ready.
- Static PWA release 20302 runs as Deployment `habits/loop-pwa` (1/1 ready).
  HTTPRoute `habits/habits` is Accepted and ResolvedRefs on `gateway/main`.
  HTTPS `/healthz` returns `ok` through the Tailscale gateway with its wildcard
  certificate. Its versioned static site is `/home/moon/loop-pwa/20302/site`.
- APK 20302 was built and `apksigner verify --print-certs` confirms the stable
  owner SHA-256 fingerprint documented in RELEASE.md. This is signature/build
  evidence; signed Google authorization has not yet been verified.
- The real retained native debug installation authorized the test Google account,
  reached `Synchronized with Drive · 0 pending`, and persisted network-required
  JobScheduler job 7401. Forcing that OS job first exposed an expired token;
  foreground Connect recovered authorization and reconciled. This does not yet
  prove a remote edit was applied by a normal unattended scheduled job.
- The real browser PWA imported a six-column HabitBull CSV through its import
  form. `Release Import` has dated value `12` on 2026-10-01 and its dated note.
  The UI reported a durable local import and pending reconnect. After the shell update, the browser
  durable upload watermark advanced to the import envelope (sequence 48).
  A native UI confirmation of this imported record is still pending.
- The real browser `/update/` recovery page refreshed a retained earlier shell.
  Existing Boolean/numeric habits, original entries and the new imported record
  remain displayed; no site storage was cleared.
- Seven Node transport/storage checks pass: temporary isolation, purge cleanup
  interruption, duplicate upload retry after lost response, clock-skew conflicts
  and deletion/purge cases.
- Focused JVM checks pass for staged external imports/malformed rejection,
  original-entry CSV precision, restore conflicts/independent later work,
  deletion/purge barriers, third-replica import delivery, durable SQLite pending
  imports and reminder weekday/cancellation/snooze behavior. A new numeric CSV
  regression failed before its fix and passed after it.

## Unavailable live prerequisites

- Initial Cloudflare setup found no `habits.mooneeb.dev` DNS record. The lab certificate
  token can read tunnel metadata but tunnel creation returned HTTP 403. A token
  with Cloudflare Tunnel Edit and DNS Edit (or owner dashboard setup) is required
  for the dedicated public tunnel. The owner supplied a new local API token. A dedicated `loop-habits` tunnel
  was created and connected (four QUIC connections); proxied CNAME and the
  Kubernetes tunnel deployment now provide public HTTPS. `/healthz` returns
  HTTP 200. The initial missing-permission prerequisite is resolved.
- The owner confirmed Google Cloud registration of the public web origin and
  stable release certificate. Real consent and transport verification remain pending. The debug OAuth client is not sufficient for the
  signed APK. Owner interaction is also needed to complete Google consent on a
  fresh signed test installation and in the browser; no tokens were injected.
- T3 preview clicks have not exposed a Google chooser. Preview snapshot currently
  reports an automation error; focused evaluation can inspect the app. Android
  Chrome/native UI inspection also encountered a UIAutomator idle timeout.

## Remaining real acceptance

Follow RELEASE.md's numbered procedure and parent issue #1. Specifically still
unproven on the public deployed shell and signed APK: reminder delivery/custom
weekdays/disable/snooze, widget and notification quick actions through Drive,
normal permitted background propagation, import/older-restore reconciliation
through Drive, and same-key APK upgrades preserving all recovery/conflict/purge
and pending states. The retained debug/local-shell observations and shared-core
regressions support these behaviors but do not replace the signed deployed checks.

## Test runner notes

The full JVM and Android unit tasks completed without reported failures before
the browser task. The full JS ChromeHeadless run hit the previously documented
`CanvasTest.testDrawTestImage` rendering failure, then browser disconnection and
no-activity timeouts. It was stopped after repeated disconnects, so the full JS
suite is not reported as passing. The final full JVM run and Android unit run passed; focused JS portability/sync
tests also passed. Their counts are recorded below.

Final supporting test results: 312 JVM tests, 5 Android unit tests, 43 focused
JS sync/import tests and 7 Node tests passed, with no failures in those runs.

## Code review

### Standards

Zero documented-standard violations and one initial heuristic finding: the
backup export task reached through a global application component. The task now
receives its SQLite change store through the existing injected factory.

### Spec

Three code findings were fixed: zero-weekday reminder scheduling now
cancels safely; custom clock snoozes persist and survive scheduling refreshes;
malformed source database rows are rejected instead of skipped or date-normalized.
New regressions cover all three supported database formats and custom/off-weekday
snooze persistence. Required signed/public live acceptance remains pending as
recorded above; no material scope creep was found.

Review totals: Standards 1 heuristic (resolved), 0 hard violations; Spec 3 code
findings (resolved) and 1 remaining live-acceptance gap. The worst remaining Spec
issue is the unavailable signed/public end-to-end verification.

Public browser startup caught a deployment MIME issue: stock nginx served `.mjs`
modules as application/octet-stream. The release nginx config now explicitly
serves `.mjs` as JavaScript, preserving the standard MIME mappings for other assets.

Public release 20304 is now deployed at https://habits.mooneeb.dev/app/.
HTTP checks pass for health, app HTML, JavaScript modules, SQLite WASM, migrations,
temporary session and update page. The real public browser initializes the
tracker and durable local workspace. An explicit reload was needed for the
browser’s earlier MIME response cache; the update page now refreshes the HTTP
cache before replacing the owned shell. Google Connect opens a consent popup,
but completing that interactive consent remains pending.

Final signed artifact: `build/release/20304/Loop-20304.apk`, source commit
`f05f63e2`, verified owner signer. The build passes with the pinned R8 and
without the earlier Kotlin metadata parsing errors.

Follow-up review of `f05f63e2`: Standards reports zero remaining hard or heuristic
findings; Spec confirms all three code defects are resolved, with the live
acceptance gap remaining. No material scope creep or new defect was found.
