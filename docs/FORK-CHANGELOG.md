# Fork changelog

Fork releases are separate from the inherited [upstream changelog](../CHANGELOG.md).
APK version codes increase across releases signed with the same key.

## Unreleased

- Replace upstream-only introduction and support links with fork-specific guides.
- Document free static hosting, Docker, Kubernetes, independent OAuth and signing.
- Remove personal deployment identifiers from documentation and release notes.

## [2.3.11-personal / 20311](https://github.com/mooneeb/uhabits/releases/tag/loop-20311) — 2026-10-05

First published personal Android/web release. Adds the installable web client,
direct Drive reconciliation, offline histories, conflict resolution, recovery and
purge, temporary sessions and portable import/export. Retains Loop tracking,
Android reminders and widgets.

Fixes Sync now after connecting an empty tracker whose serialized history omits
its empty changes list. Signed upgrade, live empty-workspace sync and first-habit
upload passed. See [release checks](RELEASE-CHECKS.md) for the observations and
remaining test-suite limitation.
