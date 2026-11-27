# PhoneBackup Android: Play policy and release notes

Checked 2026-11-27.

## Foreground service declaration (App content → Foreground service permissions)

The app declares one foreground service, `transfer.TransferService`, with type `dataSync`
(`FOREGROUND_SERVICE_DATA_SYNC`).

- **Feature it supports**: copying the photos the user shared into the app, then uploading them to
  the user's own PC on the local network.
- **Why it can't wait or be deferred**: the user starts the transfer and is waiting for it. The
  progress shows live, in the app and in the notification. A share of a few hundred photos can take
  minutes, and Android would stop a background job the moment the user switches apps. WorkManager
  would delay or batch the job, but the user expects it to start now and to show progress now.
- **When it runs**: only while a copy or upload the user started is in progress. It stops as soon as
  that run ends or the user taps Cancel in the notification. It never starts by itself, on boot or
  on a schedule.
- **User-visible**: an ongoing progress notification (a Live Update on Android 16+) with a Cancel
  action.
- **Android 15+ time limit**: Android lets a `dataSync` service run for about 6 hours in each
  24-hour window. When that runs out, `onTimeout` cancels the transfer and stops the service. Photos
  that did not finish stay in the list so the user can send them again. If the limit is already used
  up, starting the service throws `ForegroundServiceStartNotAllowedException`. The app catches it
  and stops without crashing.
- **Video for the declaration**: Play asks for a link to a short video. Record one in sample mode:
  1. Share 3–4 photos to PhoneBackup from the gallery.
  2. Tap Send.
  3. Pull down the notification shade to show the progress notification and its Cancel action.

  Upload the video unlisted to YouTube, or to Drive with link sharing on, and paste the link.

## Release format

- Upload an Android App Bundle: from `cmp/`, `./gradlew :androidApp:bundleRelease`. The output is
  `androidApp/build/outputs/bundle/release/androidApp-release.aab`. Don't upload the APK.
- Enrol in Play App Signing. The release key named in `~/.gradle/gradle.properties`
  (`PHONEBACKUP_*`) becomes the upload key. Keep it backed up.
- `targetSdk` 37 meets Play's target API requirement.
- The build uses AGP 9.6.0-alpha05, because compileSdk 37 with the Compose 1.13 betas needs it.
  Play doesn't care which AGP built a bundle. Move to the first stable 9.6 when it ships.
- Bump `versionCode` in `cmp/gradle/libs.versions.toml` for every upload.

## New personal developer account: closed test first

Personal Play developer accounts made after 13 Nov 2023 have to run a closed test before they can
ask for production access. The test needs at least 12 testers who stay opted in for 14 days in a
row. Start it as early as possible; it is the longest wait in the release.

1. Create the closed-test track and add a Google Group or email list of 12 or more testers.
2. Upload the bundle, share the opt-in link, and keep 12 or more testers opted in for 14 days.
3. Apply for production access in Play Console. It asks about the test and the testers' feedback.

Testers without the PC app can use sample mode.

## Network security

Cleartext HTTP is off for the whole app except the alias domain `pc.phonebackup.lan`.

- `LanHostInterceptor` rewrites a request to a private IPv4 address (10/8, 172.16/12, 192.168/16,
  169.254/16, 100.64/10 or loopback) to `a-b-c-d.pc.phonebackup.lan`. `LanHostDns` maps the alias
  back to that same address, only within those ranges.
- So the app can't send HTTP to any public host, which a plain "allow cleartext" would have
  permitted.
- A domain-config can't list IP ranges, so an alias domain is the only way to narrow cleartext to
  the LAN.

## Store screenshots

Take them in sample mode on a headless emulator (`emulator -avd <name> -no-window`), so no real
photos or personal data show. Play allows an aspect ratio of up to 2:1, so set the screen to exactly
2:1 first: `adb shell wm size 1080x2160` and `adb shell wm density 420`. Turn on SystemUI demo mode
for a clean status bar (clock 12:00, full battery and Wi-Fi). Take each shot with
`adb exec-out screencap -p`. Keep the sample banner in the shot.
