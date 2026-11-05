# Sharing a test APK

How to build an APK you can send to the client (WhatsApp, Google Drive, USB) so they can install
Dukaan on their phone, use it, and tell you what to change. This is not the Play Store build; that
is in [docs/RELEASE.md](docs/RELEASE.md).

## 1. Build it

From the repo root, in Git Bash:

```bash
./gradlew assembleRelease \
  -Pandroid.injected.signing.store.file="$HOME/.android/debug.keystore" \
  -Pandroid.injected.signing.store.password=android \
  -Pandroid.injected.signing.key.alias=androiddebugkey \
  -Pandroid.injected.signing.key.password=android
```

The APK is at `app/build/outputs/apk/release/app-release.apk` (about 30 MB). Rename it to something
like `Dukaan-0.16.0.apk` before sending.

Why this command:
- **Release, not debug.** The release build is shrunk by R8, so it is about 30 MB instead of 50 MB
  and runs as fast as the final app. A debug build is slower on a cheap phone, and the client
  would end up reviewing the lag.
- **Signed with this PC's debug key.** Android only installs signed APKs. Without a
  `keystore.properties`, `assembleRelease` gives an unsigned APK that will not install. The debug
  key's SHA-1 is already registered in Firebase, so Google sign-in works.

Check it before sending:

```bash
./gradlew installRelease   # with the same -P flags as above; or drag the APK onto the emulator
```

Open the app and do a sale, then share the receipt. `docs/RELEASE.md` has the full smoke test.

## 2. Send it

- **GitHub release (download link):** tag the pushed commit and attach the APK:
  ```bash
  cp app/build/outputs/apk/release/app-release.apk Dukaan-0.17.0.apk
  gh release create v0.17.0 Dukaan-0.17.0.apk --target main --title "Dukaan 0.17.0" --notes "..."
  ```
  Push first, so the tag points at the code you built. The repo is public, so anyone with the link
  can install it. Share https://github.com/Ahmed-Islam-AI/Dukaan-Inventory/releases/latest: it
  always opens the newest release (not pre-releases).
- **WhatsApp:** send it as a *Document*, not as media.
- **Google Drive:** upload it, share the link, and tell the client to tap **Download**.

## 3. What the client does

1. Open the APK file on the phone (Android 8.0 or newer).
2. If Android says *"For your security, your phone is not allowed to install unknown apps from this
   source"*, tap **Settings**, turn on **Allow from this source**, then go back.
3. Tap **Install**. If **Play Protect** warns about an unknown app, tap **More details → Install
   anyway**. It warns because the app is not from the Play Store.
4. Open **Dukaan → Get started**.

## Sending a new version

1. Raise `versionCode` by 1 in `app/build.gradle.kts` (and `versionName`, e.g. `0.16.0` → `0.17.0`).
   Android refuses to install a lower version over a higher one.
2. Build with the same command **on the same PC**, then send the new APK.
3. The client opens it and taps **Update**. Their shop data stays.

## Keep the same key

Android installs an update only when it is signed with the same key as the installed app.

- The key is `C:\Users\<you>\.android\debug.keystore`. **Back it up.** If it is lost, or you build
  on another PC, the next APK will not install over the old one ("App not installed"). The client
  would then have to uninstall it, and that deletes the shop data on the phone.
- When the real upload key exists (`keystore.properties`, see
  [docs/RELEASE.md](docs/RELEASE.md#signing-set-up-in-m6-key-not-created-yet)), plain
  `./gradlew assembleRelease` signs with it, and you don't need the `-P` flags. Before you switch
  keys, have the client take a backup (**More → Backup and restore → Export**), uninstall,
  install the new APK, and import the backup. Also add the new key's SHA-1 in Firebase → Project
  settings, or Google sign-in will fail.

## Troubleshooting

| Problem | Fix |
|---|---|
| "App not installed" | Old app signed with a different key, or the new `versionCode` is lower. See above. |
| "There was a problem parsing the package" | The download is incomplete or the phone runs Android 7 or older. Resend as a WhatsApp *Document*. |
| Google sign-in fails | The signing key's SHA-1 is not in Firebase → Project settings → your Android app. |
| Gradle: `keystore ... not found` | The debug key is created the first time you build a debug APK. Run `./gradlew assembleDebug` once. |
