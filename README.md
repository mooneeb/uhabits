# Loop Habit Tracker — Android + web fork

This is an independent, personal-use fork of [iSoron/uhabits](https://github.com/iSoron/uhabits).
It adds an installable web app and optional Google Drive synchronization to Loop's
Android habit tracker. It is not an official Loop release or a hosted service.
The maintainer's personal deployment is not offered for public use.

## What this fork adds

| Capability | Upstream Loop baseline | This fork |
| --- | --- | --- |
| Android tracking | Habits, flexible schedules, scores, graphs, reminders and widgets | Retained; connected to shared tracking history |
| Web app | Android app is the upstream product | Installable PWA for desktop and mobile browsers |
| Cross-device updates | Backup/import transfers | Optional direct Android ↔ browser Google Drive sync |
| Offline edits | Local Android tracking | Durable Android and owned-browser edits, reconciled after reconnect |
| Conflicting edits | No shared Android/web workflow in the baseline | Competing changes retained for an explicit choice |
| Deleted habits | Upstream archive/delete behavior | Recoverable deletion and explicit permanent purge across devices |
| Backups | SQLite and CSV exports | Causal JSON backups, original-entry CSVs, cross-device restore/import |
| Temporary browser use | No corresponding client | Online session that keeps this app's records in memory until ended |

The comparison describes the upstream code this fork started from, not a promise
about every future upstream release. See [fork differences](docs/FORK.md) for
scope, compatibility and limitations, and the [fork changelog](docs/FORK-CHANGELOG.md).
The inherited [CHANGELOG.md](CHANGELOG.md) records upstream development.

## Get started

- **Use Android locally:** download the signed APK from [this fork's releases](https://github.com/mooneeb/uhabits/releases).
  Android 9 or later is required. There is no automatic update channel for this fork.
- **Use the web app:** [host your own PWA](docs/HOSTING.md). Cloudflare Pages can
  serve it for free on a provider-supplied HTTPS address; no home server is needed.
- **Sync Android and web:** follow [Google setup](docs/GOOGLE-SETUP.md).
  A downloaded APK is not a guarantee of access to the maintainer's Google project.
  An independent installation needs matching Android and web registrations in
  your own project and an Android build signed with your own registered key.
- **Build or contribute:** start with [build instructions](docs/BUILD.md),
  [testing](docs/TEST.md) and [contributor guidelines](docs/GUIDELINES.md).

Google Drive is optional. Local tracking works without connecting an account.
The PWA host serves static application files; records stay on your devices and,
when you enable sync, in Google's private app-data storage. This fork does not
implement end-to-end encryption of Drive records. Read [data and privacy](docs/PRIVACY.md)
and [using the app](docs/USAGE.md) before enabling synchronization or restoring data.

## Android installation compatibility

This fork retains upstream's package name, `org.isoron.uhabits`, and uses a
separate signing key. It cannot be installed alongside upstream under that name,
and Android will normally reject a direct update from a differently signed
Play Store or F-Droid installation. Export a backup before changing installations;
uninstalling removes app-local data. Do not uninstall to fix a routine fork update.
Same-key fork updates use the newer APK and preserve stored records.

The Play Store and F-Droid listings belong to **upstream Loop**, not this fork.
If you want upstream's app, use its [installation instructions](https://github.com/iSoron/uhabits#installing).

## Documentation and support

[Documentation index](docs/README.md) links to user guides, hosting, developer
instructions and historical validation. Report fork bugs and setup problems in
[this repository's Issues](https://github.com/mooneeb/uhabits/issues), with the
version and steps to reproduce. Keep Google tokens, passwords and habit backups
out of reports. Do not report fork-specific behavior to upstream maintainers.

## License and attribution

Copyright (C) 2016–2025 Álinson Santos Xavier and upstream contributors.

Loop Habit Tracker is originally developed by Álinson Santos Xavier and upstream
contributors. Their copyright and third-party notices are retained in the source
and [NOTICE.md](NOTICE.md). This fork remains licensed under
[GNU GPL v3 or later](LICENSE.txt). See [upstream](https://github.com/iSoron/uhabits)
for the original project and contribution history.
