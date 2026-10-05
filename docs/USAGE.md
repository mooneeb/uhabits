# Using Android and the web app

Start with [Google setup](GOOGLE-SETUP.md) if you want devices to synchronize, and
[hosting](HOSTING.md) for your own website. You can use local tracking without Drive.

## Track habits

Create a Yes / no or numeric habit, choose its frequency and target, then record
an outcome or amount for a date. Notes belong to that date; the description belongs
to the habit itself. A calculated completion for a flexible schedule is different
from an original recorded entry. Android and web share the tracking calculations.

Open habit details for history, strength, streaks and period totals. Archive a
habit to hide it from active tracking while retaining its history. Unarchive it
to resume. Configure reminders from either client; the connected Android app
actually delivers them. Android widgets and notification actions continue to
work locally when offline.

## Synchronize

Connect Google Drive on both devices with the same account. In Android, open
More options → Google Drive synchronization. In the web app, choose Connect Google
Drive. Use Sync now when you want a foreground check. Wait for Synchronized with
Drive on each device; one device finishing does not prove the other has downloaded
its changes yet.

Normal Android and browser edits remain local and pending during network loss.
Reconnect when asked. Android background jobs are best effort. Closing/reloading
the browser loses its in-memory Google authorization, while its saved habits and
pending edits remain. Browser private mode or automatic storage cleanup can affect
those saved records.

## Conflicts, deletion and recovery

If devices change the same value independently, choose the revision to keep in
Android's synchronization screen or the web conflict panel. Independent dates
and fields merge automatically. Resolving one displayed conflict does not discard
a newer competing revision discovered later.

Deleted habits remain in Recovery until restored or explicitly purged. Purging
is permanent and prevents older device history or backups from silently restoring
that habit. Archiving is reversible organization, not deletion.

## Back up and import

Export a full JSON backup from Settings for lossless history/recovery transfer.
CSV is useful for inspection and spreadsheets; original-entry exports preserve
recorded amounts and notes separately from calculated completions. Keep copies
outside the app and browser before clearing local storage or changing Android apps.

Import is a synchronized reconciliation operation. An older backup can expose
conflicts with newer records rather than overwrite them. Independent later entries
survive. Connect the original account before importing an account-bound backup.
Update both clients before importing newer backup formats. Malformed or unsupported
inputs are rejected before the active tracker changes.

## Update without losing records

Install a newer Android APK signed with the same key over the existing app; do
not uninstall it for a routine update. For the PWA, open `/update/` on your website
and select Load current Loop version while online. It refreshes the application
files and preserves stored habits, pending edits and recovery information. Then
reconnect Google if requested. Moving to a different website address creates a
separate browser storage location; export a backup first and reconnect at the new
address to recover the same Drive history.

A temporary browser session is for online use and ends without retaining this
app's records. Read [its privacy boundaries](PRIVACY.md#temporary-sessions) before
using it on a shared device.
