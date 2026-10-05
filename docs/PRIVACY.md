# Data, Google access and privacy

Tracking works locally without connecting Google Drive. In normal browser mode,
records are saved in that browser's site storage; Android stores them in its local
database. Deleting browser site data or uninstalling Android can remove those
local records. Export a backup before doing either.

## When you enable Drive

Both clients contact Google directly. The requested scope is
`https://www.googleapis.com/auth/drive.appdata`: application-specific storage,
not access to your ordinary Drive documents. These files are not normally visible
in Drive's file list. Android and web must use the same account and correctly
paired Google registrations. [Google's app-data documentation](https://developers.google.com/workspace/drive/api/guides/appdata)

The static website host serves application files and can receive ordinary web
request metadata, such as your IP address. This architecture does not send habit
histories or Google authorization to a custom sync server. The clients retain
Google access tokens in memory. Habit records uploaded to Drive are not protected
by an app-level end-to-end encryption scheme; Google handles the hosted storage.
Whoever controls a deployment's application code can change its behavior, so use
a deployment you trust.

Changing Google's connected account does not move an existing tracker into another
account. The app refuses incompatible account binding and retains pending edits.
Disconnecting authorization does not automatically erase local history or Drive data.

## Temporary sessions

Use Temporary session for short online access. This mode holds app records and
Google tokens in memory and avoids the normal owned browser's durable habit store.
Editing requires an authorized online session. End temporary session clears those
app-held records. It does not sign you out of Google, clear downloads or remove
browser cookies. On a shared device, also close the session and sign out through
Google or the browser as appropriate.

## Website visibility and Google consent

A Google OAuth test-user list restricts who can authorize the Google integration.
It does not make the website's HTML and JavaScript private. A private website needs
separate access control at the hosting provider or reverse proxy. A public static
shell does not make one person's Drive history available to another account.
See [hosting](HOSTING.md) for this distinction when choosing a provider.

Keep keystores, signing passwords, OAuth tokens, tunnel credentials and real habit
backups out of Git and issue reports. OAuth client IDs and certificate fingerprints
are public registration information, but this repository uses placeholders so
others configure their own deployment.
