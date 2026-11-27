# PhoneBackup Android: Data safety answers

Checked 2026-11-27 against Play's help page "Provide information for Google Play's Data safety
section" (support.google.com/googleplay/android-developer/answer/10787469).

## Is sending photos to the user's own PC "collection"?

Play defines collection as transmitting data from your app off a user's device. Its listed
exceptions are on-device processing, end-to-end encryption and ephemeral processing. None of them
covers "sent to a computer the user owns". The upload is plain HTTP on the home network, not end to
end encrypted, and the PC keeps the photos, so it is not ephemeral. The developer never receives
anything. Even so, a literal reading counts the upload as collection. So these answers declare the
uploaded data, which is the cautious choice: declaring too much is not a policy breach, but declaring
too little is.

There is no SDK that sends data anywhere: no analytics, crash reporting, ads or Google Play
services. The only libraries that use the network are Ktor and OkHttp (HTTP to the paired PC) and
Coil (thumbnails from the paired PC). Sample mode sends nothing.

## Overview questions

- Does your app collect or share any of the required user data types? **Yes**.
- Is all of the user data collected by your app encrypted in transit? **No**. The phone talks to
  the user's PC over HTTP on the local network, because a home PC has no TLS certificate. The app
  allows cleartext only to private LAN addresses (see `network_security_config.xml`).
- Do you provide a way for users to request that their data is deleted? **No**. The developer holds
  no data. Uploaded photos are files on the user's own PC, and the user deletes them there. The
  phone deletes its temporary copy after each upload.

## Data types

| Category | Type | Collected | Shared | Ephemeral | Required or optional | Purpose |
| --- | --- | --- | --- | --- | --- | --- |
| Photos and videos | Photos | Yes | No | No | Optional (user picks each photo) | App functionality |
| App activity | Other user-generated content | Yes | No | No | Optional | App functionality |
| Device or other IDs | Device or other IDs | Yes | No | No | Required | App functionality |

What each row covers:

- **Photos**: only the photos the user shares to the app or picks in it, with their file name and
  the date taken. They are sent to the PC the user paired.
- **Other user-generated content**: the album name and tags the user types for a batch.
- **Device or other IDs**: the phone's name (the one set in Android settings, else the model name),
  sent once when pairing so the PC can show "Allow this phone to connect?". Cautious: it is a name
  the user can see and change, not a hardware ID. The app reads no advertising ID, IMEI or Android ID.

Everything else: **not collected**. That covers location, personal info, financial info, health,
messages, videos, audio, files and docs, calendar, contacts, web browsing, app info and performance.

"Shared" is No for all of them. Play counts transfers that the user starts as not sharing, and the
only recipient is the user's own device anyway.

## Permissions (for reference)

- `INTERNET` and `ACCESS_LOCAL_NETWORK`: talk to the user's PC on the LAN, and find it with mDNS.
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_DATA_SYNC`: the copy or upload the user started (see
  `policy-notes.md`).
- `POST_NOTIFICATIONS` and `POST_PROMOTED_NOTIFICATIONS`: transfer progress and its result.

The app has no photo or storage permission. It receives photos through the share sheet (`SEND` /
`SEND_MULTIPLE` `image/*`) or the system file picker. So the Photo and Video Permissions declaration
does not apply.
