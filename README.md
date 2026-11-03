# 📱 PhoneBackup

[![CodeQL Security Scan](https://github.com/OWNER/PhoneBackup/actions/workflows/codeql.yml/badge.svg)](https://github.com/OWNER/PhoneBackup/actions/workflows/codeql.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Go Report Card](https://goreportcard.com/badge/github.com/OWNER/PhoneBackup)](https://goreportcard.com/report/github.com/OWNER/PhoneBackup)

A robust, cross-platform Android backup tool and web dashboard powered by ADB. Features zero-flash background execution on Windows, real-time speed charts, resume capabilities, file integrity verification, and failed file tracking with one-click retry.

Supports **Windows**, **macOS**, and **Linux**.

## ⬇️ Download

Get the archive for your system from the [latest release](https://github.com/raghavsatyadev/PhoneBackup/releases/latest)
(`windows-amd64.zip`, `linux-amd64` / `linux-arm64`, `darwin-arm64` for Apple silicon Macs,
`darwin-amd64` for Intel Macs), unpack it and run `phonebackup` from that folder. Keep the `public`
folder next to the program. You still need ADB (see below). On macOS, if Gatekeeper blocks it, run
`xattr -d com.apple.quarantine phonebackup` once.

---

## ⚡ Prerequisites

1. **Go (1.20+)** installed to build from source : download from [go.dev/dl](https://go.dev/dl/) OR
   - **Windows**: Install via `winget install GoLang.Go`.
   - **macOS**: `brew install go`
   - **Linux (Ubuntu/Debian)**: `sudo apt install golang-go`
2. **ADB (Android Debug Bridge)** installed and accessible in your system `PATH`:
   - **Windows**: Install via `winget install Google.PlatformTools` or download Android SDK Platform-Tools.
   - **macOS**: `brew install android-platform-tools`
   - **Linux (Ubuntu/Debian)**: `sudo apt install adb`
3. **USB Debugging** enabled on your Android device (**Settings** > **Developer Options** > **USB Debugging**).

---

## 🚀 Quick Start

The repository has two parts: `server/` (the Go backup tool, web dashboard and upload API) and `cmp/` (the Compose Multiplatform phone app).

### 1. Build Binary
```bash
cd server

# Linux / macOS:
go build -o phonebackup .

# Windows:
go build -o phonebackup.exe .
```

### 2. Start Web Dashboard
```bash
# Linux / macOS:
./phonebackup server

# Windows:
.\phonebackup.exe server
```
Then open `http://localhost:9876` in your browser.

### 3. CLI Backup & Resume
```bash
# Start a new backup:
./phonebackup backup -dest "/path/to/backup" -sources "/sdcard/DCIM,/sdcard/Documents"

# Resume an existing backup from directory or status JSON:
./phonebackup resume "/path/to/backup"
./phonebackup resume "/path/to/backup/phonebackup_status.json"
```

### 4. Share Photos from Your Phone (Android app)
The `cmp/` folder holds **PhoneBackup Share**, an Android app that sends photos to your PC over Wi-Fi.

1. Start the server (`phonebackup server`). It prints the PC address (e.g. `http://192.168.1.20:9877`) and an upload token. Allow it through the firewall on first run.
2. Build and install the app: `cd cmp && ./gradlew :androidApp:installDebug`.
3. In the app, enter the address and token, then tap **Test connection**.
4. Share photos to **PhoneBackup Share** from any app (or pick them in the app). Set an album name and tags, check or edit the dates, and tap **Upload to PC**.

Photos are saved unchanged in `~/Pictures/PhoneBackup/<album>/` (change with `-upload-dir`). Each file's modified time is set to the photo's date, and tags and dates are stored in `<album>/.phonebackup/<file>.json`.

**AI captions and tags (optional).** Start the server with a local [Ollama](https://ollama.com) vision model and every uploaded photo gets a caption, tags and any readable text, saved to `<album>/.phonebackup/<file>.vision.json`. Your own tags are never changed. It runs in the background after each upload, and on start it also tags earlier uploads that have none yet. HEIC photos and videos need [ffmpeg](https://ffmpeg.org) on PATH: HEIC is converted, and a video is described from 3 frames (at 20%, 50% and 80% of its length; 1 frame for clips under 2 s, 5 for clips over a minute).

```bash
ollama pull qwen3-vl:8b
phonebackup server -vision-model qwen3-vl:8b
```

Use `-vision-url` if Ollama runs somewhere other than `http://127.0.0.1:11434`.

---

## ✨ Features

- **Real-Time Web Dashboard**: Monitor active transfers, view live throughput speed charts (MB/s), and inspect streaming file activity logs.
- **Resumable & Incremental**: Safely skips already transferred files and resumes interrupted backups via `phonebackup_status.json`.
- **Failed Files Box & Retry**: Failed files are highlighted in their own section, preserved in the status JSON, and can be retried with one click.
- **Silent Windows Execution**: Native process flags (`CREATE_NO_WINDOW`) prevent console window flashing during ADB file pulls.
- **WiFi ADB Support**: Pair and connect wirelessly over your local WiFi network directly from the dashboard.
- **Android Share App**: Share photos from any app to your PC over Wi-Fi, grouped into an album with tags and editable dates (read from photo data). The upload API listens on your LAN (port 9877) and needs a token.

---

## 🔒 Security, Privacy & Code Quality

Your privacy and code security are verified through automated audits and transparent design:

- **Automated Repository Security Scanning (GitHub CodeQL)**:
  This repository includes continuous static analysis via [GitHub CodeQL](https://codeql.github.com/) (`.github/workflows/codeql.yml`). Every commit and pull request is scanned for vulnerabilities, CWE security flaws, injection risks, and malicious patterns.
- **Zero Supply-Chain Risk (Standard Library First)**:
  Unlike projects with hundreds of nested dependencies, PhoneBackup uses standard Go libraries and exactly **one** audited dependency (`gorilla/websocket`). There are no obfuscated scripts, no minified mystery bundles, and no telemetry SDKs.
- **100% Offline & Local**:
  Zero tracking, zero analytics, and zero outbound network calls. The web dashboard binds strictly to local loopback (`http://localhost:9876`).
- **Direct P2P Transfer**:
  All file transfers occur directly between your device and your PC over your private USB cable or local WiFi network via Google's official Android Debug Bridge (`adb`).
- **Transparent & Auditable**:
  Every file in this repository is clean, readable source code. Users can inspect every line and compile the executable directly using standard `go build`.

---

## 🙏 Acknowledgements & Attribution

This project draws inspiration from and acknowledges the following open-source projects:

- [bkrajendra/android_backup](https://github.com/bkrajendra/android_backup) — For architectural inspiration and patterns on executing fast, reliable Android backups natively in Go via ADB.
- [riyad/android-backup](https://github.com/riyad/android-backup) — For pioneering concepts in ADB-based Android incremental backups and directory traversal.

---

## 📄 License

This project is licensed under the [Apache License 2.0](LICENSE).
