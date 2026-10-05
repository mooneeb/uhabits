# Building and releasing this fork

[Release 20311](https://github.com/mooneeb/uhabits/releases/tag/loop-20311) includes
a signed Android APK, static PWA archive and SHA-256 checksums. Its application
source is `d1261fd10e690ec794d0b4b32aa0f3d870f04d99`.
The published APK uses the release publisher's signing identity and Google
registration; it does not grant arbitrary users access to that Google project.

The PWA is a static application. Habit history stays on devices and, when
connected, in Google's private app-data storage. Hosting does not require a
habit-data API or server-held Google credentials. See [hosting](HOSTING.md),
[Google setup](GOOGLE-SETUP.md) and [privacy](PRIVACY.md).

## Signing and Google registration

Use a stable signing key stored outside the repository. Choose the keystore
location and alias yourself, keep its passwords private, and back it up securely.
Keep using the same key for future APK updates. A different key cannot normally
update an existing installation of the same Android package.

Register the web and Android OAuth clients in the same Google Cloud project.
Use your own website's HTTPS origin for the web client and
`org.isoron.uhabits` plus your signing certificate's SHA-1 for Android. Debug and
release certificates need their own registrations. Add the intended Google
account as a test user while the project is in Testing.

Inspect your certificate with JDK's `keytool`:

```sh
keytool -list -v -keystore "$LOOP_KEY_STORE" -alias "$LOOP_KEY_ALIAS"
```

`LOOP_KEY_STORE` and `LOOP_KEY_ALIAS` refer to your own keystore and alias.
The command prompts for its password. Register its SHA-1 with Google and use its
SHA-256 below to check that the built APK has the intended signer. Do not reuse
someone else's fingerprint or commit the keystore or password file.

## Build the artifacts

Install the prerequisites in [BUILD.md](BUILD.md): JDK 17, Android SDK platform
36/build-tools 35.0.0, Python 3 and the repository's Gradle wrapper. The settings
pin R8 8.13.19 for Kotlin 2.3 metadata; see the official
[Kotlin/R8 compatibility table](https://developer.android.com/build/kotlin-support).

Set these variables locally. The angle-bracket values below are placeholders;
replace them with your own values. Load passwords privately into the corresponding
environment variables, without committing them or displaying them in logs.

```sh
export JAVA_HOME="<YOUR_JDK_17_DIRECTORY>"
export ANDROID_SDK_ROOT="<YOUR_ANDROID_SDK_DIRECTORY>"
export LOOP_KEY_STORE="<YOUR_KEYSTORE_PATH>"
export LOOP_KEY_ALIAS="<YOUR_KEY_ALIAS>"
# Set LOOP_KEY_PASSWORD and LOOP_STORE_PASSWORD privately.
export LOOP_SIGNER_SHA256="<YOUR_CERTIFICATE_SHA256>"
export LOOP_VERSION_CODE=20312
export LOOP_VERSION_NAME=2.3.12-personal
export LOOP_WEB_CLIENT_ID="<YOUR_WEB_CLIENT_ID>.apps.googleusercontent.com"
export LOOP_HOSTNAME=habits.example.org
export LOOP_PWA_IMAGE=your-registry/loop-pwa:20312
scripts/release.sh
```

Choose a version code greater than the APK currently installed with your key.
Build from a committed tree so the recorded source commit identifies the artifacts.
The script checks the signer and creates `build/release/<VERSION_CODE>/` containing
the APK, static site, Dockerfile, Kubernetes examples and `checksums.json`.
Existing release output is preserved; choose a new version for each release.
Always set `LOOP_HOSTNAME` explicitly to your own address.

Host only the packaged `pwa/site/` directory on a static provider. For containers,
build and publish the image from the packaged `pwa/` directory. Use an immutable
tag or image digest. The Kubernetes examples must be adapted to your cluster's
Gateway, HTTPS listener and namespace access rules. Keep deployment credentials
and private infrastructure names out of this public repository.

## Publishing and checking a release

Provide the signed APK, static PWA archive, download checksums, exact source
revision and build instructions. Describe the actual changes and validation,
including any failed or unavailable checks. Keep release notes independent of the
publisher's personal website, hostnames, account identities and local file layout.
Verify uploaded downloads against the local hashes.

Before declaring a deployment ready, check `/app/`, `/app/sql-wasm.wasm`,
`/app/migrations/25.sql` and `/session/` over HTTPS, then complete actual Google
consent. Container hosting also supplies `/healthz`. Do not assume an ordinary
static provider implements that container-specific endpoint.

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
