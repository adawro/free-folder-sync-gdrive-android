# Free Folder Sync for Google Drive

A small, free and open-source Android app that copies **one folder from your phone to Google Drive** once a day.
No ads, no tracking, no account on anyone's server - it talks directly to *your* Google Drive using *your own*
Google Cloud OAuth client.

Made for things like Signal backups: the phone keeps only the newest backups, Drive keeps all of them.

> 🇬🇧 English and 🇵🇱 Polish UI (follows the phone language).

## Features

- **One-way copy** phone → Drive. Nothing is ever deleted on Drive.
- **Daily at a time you choose** (default 03:00), plus a "sync now" button.
- **Network switches**: Wi-Fi and/or mobile data (never while roaming), optional "only while charging".
  If conditions aren't met at the scheduled time, the upload starts as soon as they are.
- **Resumable uploads** in 8 MiB chunks - a big file interrupted by a lost connection continues where it stopped.
- **MD5 verification** of every uploaded file.
- Changed file → **new revision** of the same Drive file (no duplicates).
- Files modified in the last 2 minutes are skipped (e.g. a backup still being written).
- Folder chosen with the system picker (Storage Access Framework) - no "all files" permission.
- Minimal Drive scope **`drive.file`**: the app can only see files and folders it created.

## Setup: your own Google OAuth client (one time, ~10 minutes)

The app does not ship with a shared Google login. Every user uses their own free Google Cloud project,
the same way [rclone](https://rclone.org/drive/#making-your-own-client-id) does - no shared quotas or user limits.

1. Open [console.cloud.google.com](https://console.cloud.google.com) and create a new project.
2. *APIs & Services → Library* → **Google Drive API** → **Enable**.
3. *Google Auth Platform → Branding*: set an app name and your e-mail.
   *Audience*: **External**, then **Publish app** (in *Testing* mode Google revokes the login after 7 days).
4. *Google Auth Platform → Clients → Create client* → type **Desktop app** → copy the **Client ID** and **Client secret**.
5. In the app, section **"1. Google account"**: paste both and tap **"Sign in via browser"**.
   Google may warn that the app is unverified - it's your own project, choose *Advanced → Continue*.

How it works: the browser redirects to `http://127.0.0.1:<random port>` on the phone, where the app receives the
authorization code (OAuth 2.0 with PKCE) and exchanges it for a refresh token. The client secret and refresh token
are stored encrypted with a key from the Android Keystore.

## Install

Download the APK from [Releases](../../releases). Android 10+.
Updates can be tracked automatically with [Obtainium](https://github.com/ImranR98/Obtainium).

## Build

Requirements: JDK 17, Android SDK (platform 35).

```bash
./gradlew assembleRelease   # app/build/outputs/apk/release/
```

`local.properties` (not in git):

```properties
sdk.dir=/path/to/Android/Sdk
# optional - release signing
signing.storeFile=keys/release.jks
signing.storePassword=...
signing.keyAlias=...
signing.keyPassword=...
# optional - sign-in via Google Play Services instead of the user's own client; works only with an
# Android OAuth client registered for this package name + signing key SHA-1 in your Google Cloud project
builtinAuth=false
```

## Code overview

| File | Purpose |
|---|---|
| `MainActivity.kt` | UI (Jetpack Compose): account, folders, schedule, status, log |
| `SyncWorker.kt` | WorkManager job, daily scheduling, notifications |
| `SyncEngine.kt` | compares the folder with the local database, uploads, MD5 check |
| `DriveApi.kt` | minimal Drive API v3 client (OkHttp): folders, resumable upload |
| `CustomOAuth.kt` | browser sign-in with the user's own OAuth client, token refresh |
| `Auth.kt` | access token: custom client or built-in (Google Identity) |
| `SecretStore.kt` | encrypted storage (Android Keystore) |
| `LocalFiles.kt` | lists files in the SAF folder |
| `Db.kt` | SQLite: uploaded files, Drive folder ids, log |

## License

[GPL-3.0](LICENSE) - free to use, modify and share; derived apps must stay open source.
