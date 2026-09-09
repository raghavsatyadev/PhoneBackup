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
  - `StartWebServer(port, publicDir, initialStatus)`: Registers routes, serves static assets from `public/`, and initiates HTTP listener.
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
  - `phonebackup.exe server [-port 9876]`: Starts the Web UI server (default port `9876`). Auto-detects and registers `phonebackup_status.json` in the current folder if present.
  - `phonebackup.exe resume <path-or-file>`: Directly resumes a backup given a folder or status JSON file.
  - `phonebackup.exe backup -dest <dir> -sources <dirs> [-device <serial>] [-include <patterns>]`: Starts a CLI backup while simultaneously broadcasting to the web dashboard.
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

### 6. Configuration & Dependency Files
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
   - Verify changes with `go build -v ./...` and recompile `phonebackup.exe` with `go build -o phonebackup.exe .`.
