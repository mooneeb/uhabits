# What differs from upstream Loop

This repository is an independent personal-use fork of
[iSoron/uhabits](https://github.com/iSoron/uhabits). The maintainer built it to
track the same habits from an Android phone and a browser. It does not provide a
shared public service, upstream distribution, or a promise of multi-user service support.

## Inherited from Loop

Habit definitions, Boolean and numeric entries, flexible frequencies, scores,
streaks, graphs, Android reminders, notification actions and widgets are based
on Loop. The web client uses the Kotlin core for tracking calculations so the
clients can agree on recorded outcomes and inferred completions.

Upstream's own [README](https://github.com/iSoron/uhabits) describes its Android
product. The feature comparison here refers to the upstream starting point;
upstream development can continue independently.

## Added by this fork

- An installable browser PWA with habit editing, dated entries and notes, search,
  ordering, archive/reactivation, statistics and shared tracking settings.
- Optional direct Google Drive synchronization between this Android build and
  the web client, using the same Google account and OAuth project.
- Durable offline changes on Android and in the browser's normal mode. Independent
  changes merge; incompatible edits remain visible until explicitly resolved.
- Recovery for deleted habits and a permanent purge barrier that prevents stale
  clients or older backups from silently reviving purged history.
- A temporary browser session for authorized online use without writing records
  into this app's normal browser workspace.
- Full causal JSON backups and CSV exports retaining original entries and notes.
  Supported external imports include Loop, Rewire and Tickmate databases and
  HabitBull CSV; inputs are validated before active records change.
- Static hosting templates and scripts for independently configured releases.

## Boundaries

Android delivers reminders and widget actions. The web client does not replace
Android notification delivery. Android background sync is best effort and depends
on network access, Google authorization and operating-system scheduling. Browser
Google authorization stays in memory and can require an interactive reconnect.
An ended temporary session clears app-held history and tokens, not browser cookies
or your Google sign-in session.

The maintainer's release APK uses the maintainer's signing identity and Google
registration. Other users should not assume they are allowed OAuth users. For
independent Drive sync, use [your own Google setup](GOOGLE-SETUP.md), signing key
and [Android release build](RELEASE.md). Website hosting alone does not register
Android or change Google's allowed users.

The fork keeps `org.isoron.uhabits`, so it does not coexist with a differently
signed upstream installation. Preserve an exported backup before changing apps.
No automatic update mechanism, shared family workspace, alternate sync provider,
or end-to-end Drive encryption is implemented.

## Where changes live

`uhabits-core` contains shared tracking and reconciliation; `uhabits-android`
contains native UI, storage and Google integration; `uhabits-web` contains the
web facade, browser storage, Drive transport and PWA shell. `deploy` contains
portable examples, while private deployment configuration belongs outside this
public repository. Historical acceptance is recorded in [release checks](RELEASE-CHECKS.md).
