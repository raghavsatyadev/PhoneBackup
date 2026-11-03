# AGENTS.md — PhoneBackup Architecture & File Reference

This document maps out the architecture, file organization, key structs, functions, and endpoints for the **PhoneBackup** project. Consult this guide to immediately locate where features, bug fixes, or APIs live without searching the entire codebase.

---

## 🏗️ Architecture & Technology Overview

PhoneBackup is a **lightweight, native Go-based Android backup solution** with an embedded **HTTP Web Dashboard** and CLI interface.

- **Language & Runtime:** Go (1.21+) using standard library (`net/http`, `os/exec`, `path/filepath`, `sync`, etc.).
- **Frontend Dashboard:** Pure Vanilla HTML5 / CSS3 / JavaScript (in `public/index.html`), dark mode, zero external node dependencies, real-time updates via Server-Sent Events (SSE).
- **Android Interop:** Native ADB (`adb.exe`) wrapper configured with Windows-specific `CREATE_NO_WINDOW` flags (eliminating terminal window popup flashing).
- **Resumability:** Atomic status tracking saved in `phonebackup_status.json` inside each backup destination directory.

---

## 📂 File Map & Component Responsibilities

Two top-level folders: `server/` holds the Go module (sections 1–6 and 8, paths below are relative to it) and `cmp/` holds the Compose Multiplatform app (section 7).

### 1. `adb.go` — Low-Level ADB Process & Hardware Communication
Handles all communication with `adb.exe` and connected Android devices.

- **Key Structs:**
  - `Device`: `{ Serial, State, Model, Transport }`
  - `RemoteFile`: `{ Path, Name, Size, ModTime, IsDir, Mode }`
  - `RemoteHash`: `{ Hash, Algorithm }`
- **Key Functions:**
  - `getADBPath()`: Resolves absolute path to `adb.exe` (checks environment variables, known SDK candidate paths, and system PATH).
  - `getADBCommand(ctx, args...)`: Constructs an `exec.Cmd` with `SysProcAttr` setting `HideWindow: true` and `CreationFlags: 0x08000000` (`CREATE_NO_WINDOW`) to prevent console flashing on Windows.
  - `ListDevices()`: Parses `adb devices -l` output to detect USB and WiFi ADB devices.
  - `ConnectWifi(ip, port)` / `DisconnectWifi(addr)` / `PairDevice(ip, port, code)`: Handles wireless ADB pairing and connections.
  - `BrowseDir(serial, remoteDir)`: Fast directory listing via remote `ls -la` or `stat`.
  - `ListRemoteDir(serial, remoteDir)`: Recursive file scanner returning full list of `RemoteFile` entries.
  - `PullFileDirectly(serial, remotePath, localPath)`: Directly pulls an individual file via `adb -s <serial> pull <remote> <local>` without intermediate memory buffering.
  - `GetRemoteFileHash(serial, remotePath)`: Computes md5/sha256 on the Android device via `toybox md5sum` or `sha256sum`.

---

### 2. `transfer.go` — Backup Engine, State Serialization & Resumability
Core orchestration engine managing file scanning, transfer progress, rate calculations, failure tracking, and persistence.

- **Key Structs:**
  - `BackupStatus`: Full transfer session state. Fields:
    - `ID`: Unique transfer ID (`tx-...`)
    - `Sources`: Array of remote Android source directories (e.g., `["/sdcard/DCIM", "/sdcard/Pictures"]`)
    - `LocalDir`: Absolute path to destination directory on PC.
    - `Status`: Current state (`pending`, `scanning`, `running`, `paused`, `completed`, `failed`, `cancelled`).
    - `Files`: Map of `remotePath -> FileStatus` (`pending`, `done`, `skipped`, `failed`).
    - `FailedList`: Detailed error logs for failed files (`RemotePath`, `Error`, `Size`, `Retries`).
    - `DoneFiles`, `TotalFiles`, `DoneBytes`, `TotalBytes`, `SkippedFiles`, `FailedFiles`.
    - `SpeedBps`, `ETASeconds`, `CurrentFile`, `RecentLogs`.
- **Key Functions:**
  - `LoadStatusFile(target)`: Reads and deserializes `phonebackup_status.json` from a file path or directory. Handles path normalization, quotes, and fallback names.
  - `SaveStatusFile()`: Writes `phonebackup_status.json` atomically in the target local backup folder.
  - `ExecuteBackup(status, sseChan)`: Main worker loop. Scans files, skips identical local files (same size & modification time), executes pulls, updates metrics, broadcasts progress via SSE, and preserves failure logs.
  - `RetryFailedFiles(status, sseChan)`: Re-queues failed items from `FailedList` without having to re-scan or re-transfer completed files.
  - `PauseTransfer(id)` / `ResumeTransfer(id)` / `CancelTransfer(id)`: Thread-safe transfer lifecycle controls.

---

### 3. `server.go` — Embedded HTTP REST & Server-Sent Events (SSE) Server
Provides the web dashboard API backend and serves static assets.

- **Key Functions:**
  - `StartWebServer(port, lan, publicDir, initialStatus)`: Registers routes, serves static assets from `public/`, and starts the HTTP listener on `127.0.0.1` (all interfaces only when `lan` is true, see `dashboardAddr`). The `/ws` socket uses the default same-origin check.
- **REST Endpoints:**
  - `GET /api/devices`: Returns connected Android devices.
  - `POST /api/device/connect-wifi`, `POST /api/device/disconnect-wifi`, `POST /api/device/pair`: Wireless ADB controls.
  - `GET /api/device/browse?serial=...&path=...`: Browse remote Android filesystem folders.
  - `GET /api/local/browse?path=...`: Browse local PC drives (A: to Z:) and directories.
  - `POST /api/local/set-destination`: Validate or create destination directory; checks and auto-detects existing `phonebackup_status.json`.
  - `GET /api/transfer/list`: Get list of all active/recent transfers.
  - `POST /api/transfer/start`: Start a new backup transfer.
  - `POST /api/transfer/pause/{id}`, `POST /api/transfer/resume/{id}`, `POST /api/transfer/cancel/{id}`: Transfer control actions.
  - `POST /api/transfer/retry-failed/{id}`: Re-run failed files.
  - `POST /api/transfer/import`: Import a `phonebackup_status.json` file or resume from disk.
  - `GET /api/transfer/events`: Real-time SSE stream sending transfer progress and system events.

---

### 4. `main.go` — CLI Entry Point & Flag Parsing
Entry point for command-line execution and service bootstrapping.

- **Supported CLI Commands:**
  - `phonebackup.exe server [-port 9876] [-lan] [-upload-port 9877] [-upload-dir <dir>] [-vision-model <name>] [-vision-url <url>]`: Starts the Web UI server (default port `9876`, this PC only; `-lan` opens it to the network). `backup` also takes `-lan`. Auto-detects and registers `phonebackup_status.json` in the current folder if present. Also starts the photo upload API for the Android app on `-upload-port` (0 disables it), saving to `-upload-dir` (default: the folder saved by the PC app, else `D:\PhoneBackup` on Windows, `~/Pictures/PhoneBackup` elsewhere; an explicit flag wins for that run).
  - `phonebackup.exe resume <path-or-file>`: Directly resumes a backup given a folder or status JSON file.
  - `phonebackup.exe backup -dest <dir> -sources <dirs> [-device <serial>] [-include <patterns>]`: Starts a CLI backup while simultaneously broadcasting to the web dashboard.
  - `phonebackup.exe organize [-dir <upload-dir>] [-apply]`: Sorts vision-tagged uploads into `_Organized/<Category>/<Year Event>` (dry run unless `-apply`).
  - `phonebackup.exe group [-dir <upload-dir>]`: Rebuilds and prints similar-photo groups.
  - `phonebackup.exe devices`: Lists connected Android devices in the console.
  - `phonebackup.exe help`: Prints usage instructions.

---

### 5. `public/index.html` — Responsive Web Dashboard UI
Complete single-page application containing UI layout, CSS stylesheets, and client JavaScript.

- **Major UI Sections:**
  - **Header & Device Bar:** Connection status, device selector, refresh button, and "Connect WiFi" modal trigger.
  - **Source Folder Picker (Left Panel):** Expandable Android filesystem tree, preset buttons (Photos, Videos, Docs, Music), quick-source shortcut pills (DCIM, Pictures, WhatsApp, etc.), and custom path paste modal.
  - **Destination Picker (Right Panel):** Direct path input with auto-complete/validation, Windows drive browser (`C:\`, `D:\`, etc.), breadcrumb navigation, detected backup banner (`statusDetectedBanner`), and status file badge (`Load & Resume`).
  - **Active Transfers List:** Cards displaying progress bars, speed badges, elapsed & ETA timers, pause/resume/cancel buttons.
  - **Expanded Transfer Modal (`#transferModal`):** Live Speed Chart (HTML5 Canvas), activity stream log, and **Failed Files Viewer** with one-click **Retry Failed Files** button.
  - **Device Mismatch Warning Modal (`#deviceMismatchModal`):** Prompts user in layman's terms if resuming a paused backup while a different phone is selected in the top dropdown, allowing safe device switching or cancellation.
- **Frontend Architecture Notes:**
  - Uses `data-*` attributes (`data-lpath`, `data-statuspath`) when passing Windows file paths in DOM elements to prevent JavaScript string escape consumption of backslashes (`\`).
  - Dropdown device selection (`selectedDevice`) is bound to transfers on start/resume and verified upon resumption.

---

### 6. `upload.go` — Photo Upload API for the Android Share App
Separate LAN listener (default `:9877`) that receives photos shared from the Android app. Kept apart from `server.go` because it is reachable from the network and needs auth.

- **Auth:** `Authorization: Bearer <token>`. Token is random, created once by `LoadOrCreateUploadToken()` and stored at `os.UserConfigDir()/phonebackup/upload_token`; compared in constant time. The token and LAN addresses are printed in the console banner only.
- **Discovery (`announce.go`):** announces mDNS `_phonebackup._tcp` on the upload port (zeroconf, LAN interfaces with a private IPv4). TXT: `id` (random, stable, `os.UserConfigDir()/phonebackup/server_id`), `name` (hostname), `v=1`. Never the token.
- **Pairing (`pair.go`, `public/pair.js`):** the app `POST /api/pair/request {name}` on the upload port (no auth) and gets a private poll secret + 6-digit code; it polls `POST /api/pair/status {secret}` until `allowed` (returns the token), `denied` or `expired`. The dashboard lists `GET /api/pair/pending` and answers with `POST /api/pair/decide {id, allow}` (this PC only, `fromThisPC`), showing an "Allow this phone to connect?" dialog with the same code. The poll secret never goes to the dashboard or WS broadcasts. Tests: `pair_test.go`.
- **Endpoints:**
  - `GET /api/upload/ping`: Returns `{ok, host, id, uploadRoot}` (the app's "Test connection").
  - `POST /api/upload`: Streaming multipart. Text fields `album`, `tags` (comma-separated), `takenAt` (RFC 3339), `fileName` must come before the `file` part.
- **Photo folder (`storage.go`):** `RootDir` holds the folder (mutex; read it once per request). `GET /api/upload/folder` returns `{path, canChange}`; `POST {path}` changes it only from this PC (`fromSameMachine`: loopback or the connection's own local address, so the PC's LAN IP counts too; phones get 403). The path must be absolute and writable; it is saved to `os.UserConfigDir()/phonebackup/upload_dir` and groups/vision re-run on the new folder. Ping also returns `canChangeFolder`. Tests: `storage_test.go`.
- **Storage:** `<upload-dir>/<album>/<file>` (album defaults to `Unsorted`). Names are sanitized (`sanitizeName`: no traversal, no Windows-reserved names). Never overwrites: a same-name same-size file counts as a retry and is skipped; otherwise ` (n)` is added. File mtime is set to `takenAt`; EXIF is never rewritten. Metadata (album, tags, dates) goes in a sidecar `<album>/.phonebackup/<file>.json` (`PhotoMeta`).
- **Tests:** `upload_test.go`.
- **Vision tagging (`vision.go`, `vision_ollama.go`):** on when `server -vision-model <name>` is set (`-vision-url`, default Ollama `http://127.0.0.1:11434`). `VisionWorker` takes jobs from a bounded channel (uploads never wait; one photo at a time) and on start backfills every sidecar without a result. `OllamaVision.Analyze` shrinks JPEG/PNG/GIF to 1024px (stdlib only), sends it to `/api/chat` with a JSON schema, and writes `VisionMeta` (caption, tags, text, model, analyzedAt, error) to `<album>/.phonebackup/<file>.vision.json`, a separate file so upload retries that rewrite the main sidecar can't erase it. HEIC/AVIF and video go through ffmpeg/ffprobe on PATH (`vision_media.go`): HEIC is decoded to JPEG (tile grids included; never add `-vf` for HEIC, ffmpeg builds its own filter graph for the grid and fails), video sends 1/3/5 frames (under 2 s / up to 60 s / longer) at 768px in one request. Requests set `num_ctx` 8192 so models don't spill to CPU. Other files get an error result; a missing ffmpeg, like server/model failures write nothing so the next start retries. Tests: `vision_test.go` (fake Ollama).
- **Categories (`categories.go`):** built-in Gujarati/Indian categories, or `<upload-dir>/categories.json` (read per photo). The prompt lists them plus the sidecar `takenAt` date and album as clues; the schema limits `category` to the names (enum) and adds `event`. `matchCategory` falls back to the last entry. `needsVision` redoes results that have no category and no error, so old results get categories on the next start.
- **Gallery API (`gallery.go`, `library.go`, `thumbs.go`, `media_image.go`):** read-only, same token. `GET /api/gallery/photos` (every photo with caption, tags, category, event, groupId/groupSize/isBest, newest first), `GET /api/gallery/thumb?id=<album>/<file>&size=small|large` (512/1600 px JPEG, cached in `<album>/.phonebackup/thumbs/<side>/`, EXIF-rotated; HEIC/WebP/video via ffmpeg), `GET /api/gallery/file?id=` (original, range requests). `resolvePhotoID` only accepts one safe album + file name (no `..`, dot-files or `_Organized`). `scanLibrary` skips `_Organized` and dot-folders. Never put new `*.json` files directly in an album's `.phonebackup/` (they would be read as photo sidecars).
- **Similar photos (`group.go`):** dHash + Laplacian sharpness + exposure from the 512 px thumbnail, cached in `<root>/.phonebackup/features.json`. Similar = within 2 min and ≤10 bits apart, or ≤4 bits at any date. Best = 0.65 sharpness (relative to the group) + 0.35 exposure. Written to `<root>/.phonebackup/groups.json`; `GroupIndex` rebuilds on start and 20 s after the last upload; CLI `phonebackup group`. Tests: `gallery_test.go`.
- **Organize (`organize.go`):** `phonebackup organize [-dir] [-apply]`. Dry run by default; `-apply` hard-links (copy fallback) to `<upload-dir>/_Organized/<Category>/<YYYY>[ <Event>]/<file>`. Never moves or deletes; existing targets are skipped. Tests: `organize_test.go`.

---

### 7. `cmp/` — Android Share App and Desktop Viewer (Kotlin Multiplatform + Compose Multiplatform)
Gradle project (own wrapper, `gradle/libs.versions.toml`). Build: `cd cmp; ./gradlew :androidApp:assembleDebug` (release: `:androidApp:assembleRelease`, signed with the key named in `~/.gradle/gradle.properties` `PHONEBACKUP_*`, shrunk with R8); tests: `./gradlew :shared:testAndroidHostTest`. Desktop viewer: `./gradlew :desktopApp:run`.

- `androidApp/transfer/`: `TransferService` (foreground service, type `dataSync`) runs while `ShareQueue.progress` is non-null, mirroring copy/upload progress into an Android 16 `ProgressStyle` Live Update (`TransferNotifications`, throttled to 500 ms, Cancel action while uploading). A result notification posts only when no activity is visible.
- `androidApp/`: Thin Android shell. `MainActivity` receives `ACTION_SEND` / `ACTION_SEND_MULTIPLE` for `image/*` (onCreate + onNewIntent) and the in-app picker (`OpenMultipleDocuments`, the system file picker: the photo picker hides real file names); `PhoneBackupApp` builds the `AppGraph` and imports/restores photos on an app-wide scope.
- `shared/commonMain`: All UI and logic.
  - `data/UploadClient.kt` (Ktor, streams the file), `ServerConfig.kt`, `ShareQueue.kt` (app-wide photo list, copy-in and upload runs, `progress` for the notification), `PhotoDates.kt` (EXIF and filename date parsing), `LocalFiles.kt`.
  - `ui/share/`: Share screen (album, tags, per-photo or group date editing, upload with per-photo status/retry) with `ShareViewModel` (UDF: state + events + one-off messages).
  - `nav/`: Nav3 keys (`NavKeys.kt`, `NavConfig`); always use Nav3 for new screens. First run opens `ServersKey` over the root.
  - `data/ServerList.kt`: several saved PCs (`SavedServer`, merged by server id or address) with one `activeId` that receives photos; stored as JSON in app settings (`SettingsStore`), migrated from the old single address. `ServerDiscovery.kt` (`found` flow, `NoDiscovery` on desktop), `PairClient.kt`.
  - `ui/servers/`: PC list (`ServersScreen`: found on this Wi-Fi + saved, radio for the active PC) and `PairScreen`/`PairViewModel` (shows the code, polls, saves the token and activates the PC).
  - `ui/settings/`: add or edit a PC by address (name, address, Pair, Test connection).
  - `ui/share/PcPicker.kt`: "Send to" menu of paired PCs on the upload bar (locked while uploading).
  - `ui/library/`: Photo viewer over the gallery API. `LibraryViewModel` (views: All / Albums / Categories / Events, hide-similar shows only best shots), `LibraryScreen` grid, `PhotoViewer` (HorizontalPager swipe, Telephoto `zoomable` pinch/double-tap zoom; Image must use `ContentScale.Inside`; desktop gets prev/next/zoom buttons and arrow/Esc keys), `PhotoDetails`. `App` = Android (Share + Library tabs), `ViewerApp` = desktop (Library + Settings).
  - Share drafts: album and tags are saved through `SettingsStore.saveDraft` (Android prefs) and cleared once nothing is waiting; a date the user picks is written to the photo's `.meta` (`saveImportedDate`), so both survive Android killing the app.
  - Strings live in `composeResources/values/strings.xml`.
- `shared/androidMain`: `AndroidPhotoImporter` copies shared URIs to `filesDir/incoming/<id>/` straight away (the URI grant ends with the activity) and detects the date: EXIF DateTimeOriginal → EXIF DateTime → MediaStore DATE_TAKEN → file name → file time → now. Saves `.meta` so the queue survives restarts. Local copies are deleted after upload. Also `AndroidSettingsStore`, OkHttp client, `BackHandler`.
- `androidApp/discovery/`: `NsdDiscovery` (NSD `_phonebackup._tcp`, IPv4, resolve with a 5 s timeout) searches only while the app is resumed and only after `LocalNetworkAccess` has the Android 17 `ACCESS_LOCAL_NETWORK` answer (asked once from `MainActivity`).
- Launcher icon: adaptive vectors `res/drawable/ic_launcher_{background,foreground,monochrome}.xml` + `res/mipmap-anydpi/`. App name is `appName` in `gradle/libs.versions.toml`.
- `desktopApp/` + `shared/desktopMain`: Compose Desktop window running `ViewerApp`; `DesktopSettingsStore` keeps the PC list in Java Preferences. `ThisPc.kt` adds "This PC" (`127.0.0.1:9877`) on start from the server's `upload_token` and `server_id` in the config folder, no pairing. `AppGraph.pickFolder` (desktop only, `JFileChooser`) turns on the photo-folder card (`ui/settings/FolderCard.kt`, `FolderViewModel`) in that PC's edit screen. Window icon: `desktopApp/src/main/resources/icon.svg` (same art as the launcher icon).
- Emulator testing: `adb reverse tcp:9877 tcp:9877`, then use `127.0.0.1:9877` in the app.

---

### 8. Configuration & Dependency Files
- `go.mod` / `go.sum`: Standard Go module definition. Zero external CGo or runtime dependencies.
- `README.md`: User-facing documentation and usage guide.

---

## ⚡ Quick Rules & Gotchas for Future Edits

1. **Windows Paths in HTML / JavaScript:**
   - Always avoid embedding raw Windows paths in inline single-quoted JavaScript attributes (e.g. `onclick="fn('${path}')"`). Instead, store the path in a DOM attribute (e.g. `data-path="${escAttr(path)}"`) and read it via `this.getAttribute('data-path')`.
2. **Zero Window Flashing on Windows:**
   - Never use raw `exec.Command("adb", ...)` directly. Always invoke `getADBCommand(...)` in `adb.go` to ensure `CREATE_NO_WINDOW` is applied.
3. **Status File Synchronization:**
   - Any modifications to the `BackupStatus` struct in `transfer.go` should remain JSON backward-compatible with older `phonebackup_status.json` files.
4. **Build Verification:**
   - From `server/`, verify changes with `go build -v ./...` and recompile `phonebackup.exe` with `go build -o phonebackup.exe .`.
