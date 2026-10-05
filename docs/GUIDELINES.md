# Contributor guidelines for this fork

This is an independent Android/web fork of [upstream Loop](https://github.com/iSoron/uhabits).
Discuss fork bugs, feature proposals and setup problems in
[this repository's Issues](https://github.com/mooneeb/uhabits/issues). Include the
fork version and reproduction steps. Keep personal domains, machine paths,
credentials and real habit backups out of public reports and examples.

Use upstream's support and contribution channels only for upstream work. Store
listings and upstream release schedules are not this fork's distribution policy.

## Scope and changes

Discuss substantial changes in an issue before implementing them. State the
problem, intended behavior and validation. Keep changes focused and separate
unrelated refactors. Preserve upstream license/copyright notices and attribution.

Prefer the existing shared tracking calculations and explicit synchronization
boundaries. Preserve offline edits, account isolation and unresolved competing
revisions. Test failure paths when changing storage, synchronization or imports.
Record unavailable live prerequisites honestly; a build is not a passing Drive
acceptance check.

See [BUILD.md](BUILD.md), [TEST.md](TEST.md) and [RELEASE.md](RELEASE.md).
Issues and specifications are tracked in this fork. Coordinate contribution
submission with the maintainer through the relevant issue.

## Code style

The inherited Kotlin style uses ktlint with its configured settings. Run
`./gradlew ktlintCheck` for relevant Kotlin changes. Follow nearby conventions for
legacy Java and existing JavaScript/Python scripts; avoid unrelated formatting.
New Android/core code should follow the existing Kotlin architecture.

## Documentation and releases

Identify the fork and link upstream early. Explain concrete differences, local
setup, support and limitations. Use relative links between repository documents.
Separate user instructions from architecture research and dated validation.
[Documentation research](research/fork-documentation-and-hosting.md) explains the
sources behind this structure; it is guidance, not a formal certification.

Use placeholders or reserved example domains for deployment values. Public docs
must not depend on a maintainer's private infrastructure repository or local
filenames. Releases should include their exact source revision, build instructions,
checksums and actual validation results. Preserve an increasing APK version code
and the same signing key for updates. Do not promise automatic store updates or
access to the publisher's Google project.
