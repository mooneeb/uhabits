# Set up Google Drive synchronization

Google Drive is optional. You can track locally without it. To synchronize your
own Android and web installation, register both clients in the same Google Cloud
project and connect the same Google account in each app. Hosting the website does
not create these registrations.

The downloadable release APK uses the publisher's signing identity and project.
It does not provide general access to that project. For a separately operated
installation, [build and sign your own Android APK](RELEASE.md) and pair it with
your own web client. A new website address alone does not change Android's identity.

## 1. Create your project and allow your account

1. Open [Google Cloud Console](https://console.cloud.google.com/) and create or
   select a project for your installation.
2. Enable **Google Drive API** under APIs & Services → Library.
3. In Google Auth Platform, configure the app's name and contact details. For a
   personal external app, keep the audience in **Testing** and add your Google
   account under **Test users**.
4. Under Data Access, add `https://www.googleapis.com/auth/drive.appdata`.
   This is the app's hidden storage, not general access to your Drive documents.

Google's [consent guide](https://developers.google.com/workspace/guides/configure-oauth-consent)
and [app-data guide](https://developers.google.com/workspace/drive/api/guides/appdata)
explain these settings. Testing allows listed users and has authorization-expiry
limits; a broadly distributed app requires separate audience and policy review.
[Google audience rules](https://support.google.com/cloud/answer/15549945)

## 2. Register the website

Create a **Web application** OAuth client. Add your exact HTTPS origin as an
Authorized JavaScript origin, for example `https://habits.example.org` or your
assigned `https://<PROJECT>.pages.dev` address. Do not include `/app/`, a query or
fragment. For development, add `http://localhost:8080` separately.

Copy the public client ID. Pass it as `--web-client-id` to
`scripts/package-pwa.py`, or configure
`uhabits-web/src/jsMain/resources/app/config.mjs` for a local development build.
The browser token flow does not use a client secret; keep secrets and access tokens
out of the static files. [Google web client setup](https://developers.google.com/identity/oauth2/web/guides/get-google-api-clientid)

## 3. Register Android

Create an **Android** OAuth client in the same project. Use package
`org.isoron.uhabits` and the SHA-1 fingerprint of the certificate that signs your
APK. Inspect your release key with the `keytool` command in [RELEASE.md](RELEASE.md).
For a debug build, use `scripts/drive-gate.sh fingerprint`. Debug and release keys
have different identities and need separate registrations.

Android identifies the app through its package and signing certificate; an Android
client ID is not pasted into this APK. Use a device with Google Play services or a
Google Play-enabled emulator. [Android authorization documentation](https://developer.android.com/identity/authorization)

## 4. Verify the pair

Connect the intended account in each client, create a synthetic habit, choose
Sync now and check that the other device receives it. Make an edit in the other
direction too. A successful build or website load is not proof of working Drive
synchronization. Use [tracking workflows](tracking-workflows.md) for isolated tests.

If sign-in fails, check the exact origin, allowed account, project pairing and
installed APK signer. If a browser reload requests reconnect, that is expected:
Google authorization is held in memory while habits remain saved locally.

The test-user list controls Google authorization. A private website needs separate
host-level access control; see [privacy](PRIVACY.md).
