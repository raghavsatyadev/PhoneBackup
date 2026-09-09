# 📱 PhoneBackup

[![CodeQL Security Scan](https://github.com/OWNER/PhoneBackup/actions/workflows/codeql.yml/badge.svg)](https://github.com/OWNER/PhoneBackup/actions/workflows/codeql.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Go Report Card](https://goreportcard.com/badge/github.com/OWNER/PhoneBackup)](https://goreportcard.com/report/github.com/OWNER/PhoneBackup)

A robust, cross-platform Android backup tool and web dashboard powered by ADB. Features zero-flash background execution on Windows, real-time speed charts, resume capabilities, file integrity verification, and failed file tracking with one-click retry.

Supports **Windows**, **macOS**, and **Linux**.

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

### 1. Build Binary
```bash
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

---

## ✨ Features

- **Real-Time Web Dashboard**: Monitor active transfers, view live throughput speed charts (MB/s), and inspect streaming file activity logs.
- **Resumable & Incremental**: Safely skips already transferred files and resumes interrupted backups via `phonebackup_status.json`.
- **Failed Files Box & Retry**: Failed files are highlighted in their own section, preserved in the status JSON, and can be retried with one click.
- **Silent Windows Execution**: Native process flags (`CREATE_NO_WINDOW`) prevent console window flashing during ADB file pulls.
- **WiFi ADB Support**: Pair and connect wirelessly over your local WiFi network directly from the dashboard.

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
