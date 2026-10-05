# Documentation

This fork adds Android/web tracking and optional Drive sync to upstream Loop.
There is no public hosted instance supplied by this repository.

## Using the fork

| Guide | What it helps you do |
| --- | --- |
| [Fork differences](FORK.md) | Understand additions, inherited features and compatibility |
| [Using the app](USAGE.md) | Track, synchronize, resolve conflicts and restore backups |
| [Data and privacy](PRIVACY.md) | Understand device storage, Google access and temporary sessions |
| [Google setup](GOOGLE-SETUP.md) | Register your account, website and signed Android app |
| [Hosting](HOSTING.md) | Choose free static hosting, Docker or Kubernetes |
| [Fork changelog](FORK-CHANGELOG.md) | See fork releases rather than upstream release history |

## Building and maintaining

- [Build](BUILD.md): Android debug builds and static web output.
- [Release](RELEASE.md): your own signing key, paired clients and release artifacts.
- [Test](TEST.md): automated checks and real Android/web acceptance.
- [Contributor guidelines](GUIDELINES.md): scope, support and code conventions.
- [Documentation and hosting research](research/fork-documentation-and-hosting.md):
  official sources, current free-tier limits and the rationale for these guides.

## Historical implementation evidence

These documents describe specific checkpoints, not a turnkey setup for your account.
They retain the observed results while omitting personal deployment identifiers.

- [Drive integration gate](drive-integration-gate.md): initial OAuth/storage feasibility.
- [Tracking workflows](tracking-workflows.md): Android/web behavior checks.
- [Reliability workflows](reliability-workflows.md): offline, conflict and recovery checks.
- [Release checks](RELEASE-CHECKS.md): signed Android and hosted PWA validation.

The inherited [upstream changelog](../CHANGELOG.md) remains separate from fork releases.
