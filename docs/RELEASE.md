# Personal Loop release

The public PWA is a static shell. Habit histories, dates, notes, conflicts and
recovery records stay in each device and Google Drive's private app-data folder.
Nginx and Cloudflare hold no Google access or refresh tokens. Temporary sessions
use memory and never connect Drive or write the owned browser workspace.

## Owner signing key and OAuth

The stable PKCS12 owner key is outside this repository:
`~/.local/share/loop-release/owner.p12`. Its environment file is
`~/.local/share/loop-release/signing.env`. Back up both files privately; replacing
the key prevents updates to existing installations. Never commit either file.

Certificate SHA-256:
`8D:39:8B:7B:56:10:DC:B4:80:B6:33:4F:3D:A4:97:98:62:9E:29:EE:B0:AA:15:17:D6:CE:9D:10:A2:79:23:54`.

In Google Cloud project `loop-habit-510612`, enable Drive API and allow
`https://www.googleapis.com/auth/drive.appdata` on the OAuth consent screen.
Allow the owner account as a test user while the project is in testing. Create:

- A Web OAuth client with authorized JavaScript origin
  `https://habits.mooneeb.dev` (and `http://localhost:8080` for local acceptance).
- An Android OAuth client for package `org.isoron.uhabits` and release certificate
  SHA-1 `CC:18:D2:F3:AE:7D:0E:9A:4D:64:09:1C:9A:9B:4C:E7:74:C1:B8:68`.

Keep these clients in the same Cloud project. Android consent belongs to Google
Play services; browser consent is interactive Google Identity Services. There
is no client secret in either app. Existing debug certificates require separate
Android OAuth clients; a debug APK cannot upgrade the release-signed app.

## Build and verify

Use JDK 17, Android SDK/build-tools 35.0.0 and the repository Gradle wrapper.
The settings pin R8 8.13.19 to handle the project’s Kotlin 2.3 metadata; older
AGP-bundled R8 produces parsing errors. See the official
[Kotlin/R8 compatibility table](https://developer.android.com/build/kotlin-support).
Choose a version code greater than every APK previously installed with this key.
From the repository root:

```sh
set -a
source "$HOME/.local/share/loop-release/signing.env"
set +a
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_SDK_ROOT=/opt/homebrew/share/android-commandlinetools
export LOOP_VERSION_CODE=20303
export LOOP_VERSION_NAME=2.3.3-personal
export LOOP_WEB_CLIENT_ID=436833216636-ehkfmi2pi45qosum1pm96v5v4g2kucen.apps.googleusercontent.com
export LOOP_PWA_IMAGE=your-registry/loop-pwa:20303
export LOOP_SIGNER_SHA256=8d398b7b5610dcb480b6334f3da49798629e29eeb0aa1517d6ce9d10a2792354
scripts/release.sh
```

The script verifies the expected signer and produces `build/release/20303/` with
APK, complete static site, Dockerfile, Kubernetes manifests and SHA-256 checksums.
Existing release directories are preserved. Build from a clean committed tree
so the recorded source commit identifies the artifact source. Build/push the
PWA image from its `pwa/` directory; provide an immutable tag or digest.
`package-pwa.py --help` exposes hostname, namespace, Gateway namespace/name and
TLS listener. A public OAuth client ID is configuration, not a secret.

## Home-server deployment

The lab uses Tailscale and `ssh moon@homeserver`. Remote kubectl needs
`export KUBECONFIG="$HOME/.kube/config"`; its current context is `default`.
The app namespace is `habits`. The Gateway is `gateway/main`, listener `https`,
with the existing wildcard certificate. This path alone is Tailscale-only.

The server can also use the stock nginx image with a versioned read-only asset
directory, avoiding a local image build and privileged Docker socket:

```sh
python3 scripts/package-pwa.py \
  --web-client-id "$LOOP_WEB_CLIENT_ID" \
  --image nginxinc/nginx-unprivileged:1.28.0-alpine \
  --site-dir /home/moon/loop-pwa/20303/site --node homeserver \
  --output build/release/20303/homeserver
ssh moon@homeserver 'mkdir -p /home/moon/loop-pwa/20303'
scp -r build/release/20303/homeserver/site \
  build/release/20303/homeserver/kubernetes moon@homeserver:/home/moon/loop-pwa/20303/
ssh moon@homeserver 'export KUBECONFIG="$HOME/.kube/config"; chmod -R a+rX /home/moon/loop-pwa/20303; kubectl apply -k /home/moon/loop-pwa/20303/kubernetes; kubectl rollout status -n habits deployment/loop-pwa'
```

Keep older asset directories. Roll back by applying the previous release's
manifests. Never overwrite an active version directory. The pod mounts only the
static directory read-only, has no service-account token, and writes only `/tmp`.

For public access create a dedicated remotely managed Cloudflare Tunnel named
`loop-habits`, with ingress `habits.mooneeb.dev` →
`http://loop-pwa.habits.svc.cluster.local:8080`, followed by a catch-all 404.
Create a proxied CNAME from `habits.mooneeb.dev` to its `<UUID>.cfargotunnel.com`.
Store its tunnel token in Secret `habits/loop-public-tunnel`, key `token`.
Apply `deploy/public-tunnel` with Kustomize. This route publishes only this shell.
Do not add other private lab services. Cloudflare's
[API guide](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/get-started/create-remote-tunnel-api/)
describes the required Cloudflare Tunnel Edit and DNS Edit permissions.
Use the lab's SOPS workflow for any credential retained in Git.

Check Deployment readiness, HTTPRoute Accepted/ResolvedRefs, public `/healthz`,
`/app/`, `/app/sql-wasm.wasm`, `/app/migrations/25.sql` and `/session/` over HTTPS.
Verify the site from a device outside Tailscale, then complete Google consent.

## Updates and portability

Install signed APK upgrades with `adb install -r APK`, or the normal Android
installer, using the same signer and an increasing version code. Never uninstall
or clear app data to resolve an update problem. Full JSON backups are portable
causal histories; native automatic database backups also retain sync state.

Browser updates retain IndexedDB records, device identity, account binding,
pending watermark, conflicts, deleted habits and purge barriers. The service
worker caches only the shell. If an older shell cannot open newer history, visit
`/update/` online and choose **Load current Loop version**. That page refreshes
only owned shell registrations/caches; it does not clear device data. Back up
before browser profiles or site storage are deleted by the owner or browser.

Import from Settings on either platform. PWA CSV archives contain definition
registers, exact original entry integers and notes, competing revisions and a
full JSON backup. Native CSV exports retain existing calculated checkmarks plus
original-entry CSVs. Restore the included JSON for lossless recovery semantics.
Supported external inputs are Loop databases, Rewire/Tickmate databases and
HabitBull CSV. Inputs are parsed in staging and validated before the active
tracker changes. A malformed file or unsupported version must leave it intact.

Import is an intentional synchronized operation. Original causal revisions are
retained; restoring older values can expose conflicts with newer work instead
of silently overwriting it. Independent later dates survive. Current deleted
habits require explicit recovery; purged UUIDs never return. Restoring a bound
backup requires connecting its original Google account first. Both clients must
be updated before synchronizing imports: complete import packages use Drive
package schema 3, which older clients reject with an update instruction.

Android background sync uses persisted network-required OS jobs with exponential
retry. It is best effort; Doze, network loss and Google consent can defer work.
Local actions remain durable and pending. Reconnect in the foreground when
requested. Browser tokens stay in memory and require interactive reconnection
once expired; the PWA does not promise unattended background Drive sync.

## Real acceptance procedure

Use synthetic fixtures in a dedicated Google test account/workspace. Existing
`scripts/tracking-workflow.sh` and `scripts/android-workflow.py` support native
UI checks; `/app/?testRun=UUID` isolates development Drive files. Signed releases
use the normal owned workspace, so use a separate test account rather than the
owner's real habit history. Never replace real Google consent with injected tokens.

1. Install a signed APK, authorize Drive, open the deployed PWA, authorize the
   same account and reconcile. Create/edit Boolean and numeric habits in both
   directions, including `1.234`, explicit skip/unknown and dated multiline notes.
2. In the PWA select a custom reminder weekday set/time. Reconcile, verify the
   native alarm, let the notification arrive, use its action and snooze. Confirm
   the action reaches the PWA and disable the reminder there. Check cancellation.
3. Add a native widget, perform a widget action offline, restart the app, reconnect
   and verify the PWA. Change the entry remotely and verify native widget/view refresh.
4. Leave Android in the background. Change the PWA, allow the OS job to run and
   inspect the native result. Repeat with a pending local action, interrupted network
   and expired consent; foreground reconnection must reconcile deferred work.
5. Export a full backup. Edit the same old date plus an independent later date.
   Restore the old backup on the other client; resolve the competing old values,
   retain the independent date, and verify both devices converge through Drive.
   Repeat with recoverable deletion and a permanent purge barrier.
6. Import each supported format; export CSV and inspect original precision/notes.
   Submit malformed CSV/JSON/database and unsupported versions, comparing the full
   tracker before/after. Verify pending imports reach a clean third device even
   when the backup's original device is offline.
7. With pending/conflicting/deleted/purged state, install a later APK signed by
   the same key and update the PWA shell. Reconcile and compare all records/state.
   End a temporary session and reopen the owned workspace to verify isolation.
8. Run parent issue #1's complete acceptance list. Record actual UI/Drive observations
   and missing prerequisites in `docs/RELEASE-CHECKS.md`; compilation is supporting
   evidence, not a substitute for deployed signed cross-device workflows.
