package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
)

func getPublicDir() string {
	if fi, err := os.Stat("public"); err == nil && fi.IsDir() {
		return "public"
	}
	exe, err := os.Executable()
	if err == nil {
		p := filepath.Join(filepath.Dir(exe), "public")
		if fi, err := os.Stat(p); err == nil && fi.IsDir() {
			return p
		}
	}
	return "public"
}

func getPort() int {
	if p := os.Getenv("PORT"); p != "" {
		if val, err := strconv.Atoi(p); err == nil {
			return val
		}
	}
	return 9876
}

func printUsage() {
	fmt.Print(`
📱 PhoneBackup (Go Edition) — Native Android Backup & Web Dashboard
(Zero Terminal Window Flashing • Native Windows Process • Resumable • Built-in Web UI)

USAGE:
  phonebackup.exe [command] [arguments]

COMMANDS:
  server [-port 9876]              Start Web Dashboard (default on http://localhost:9876)
  resume <path-or-file>            Resume backup from phonebackup_status.json (CLI + Web UI)
                                   Example: phonebackup.exe resume D:\Backups\Phone
                                   Example: phonebackup.exe resume D:\Backups\Phone\phonebackup_status.json
  backup [flags]                   Start a new backup (CLI + live Web UI)
  devices                          List connected Android devices
  help                             Show this help message

BACKUP FLAGS:
  -dest string                     Destination directory (e.g. D:\Backups\Phone)
  -sources string                  Comma-separated Android source folders (e.g. /sdcard/DCIM,/sdcard/Recordings)
  -device string                   ADB device serial (optional, defaults to first connected device)
  -include string                  Comma-separated file patterns (e.g. *.jpg,*.mp4)
  -port int                        Web Dashboard port (default: 9876)
`)
}

func main() {
	if len(os.Args) < 2 {
		handleServer(os.Args[1:])
		return
	}

	command := os.Args[1]

	switch command {
	case "server":
		handleServer(os.Args[2:])
	case "devices":
		handleDevices()
	case "resume":
		if len(os.Args) < 3 {
			fmt.Println("Error: please specify the destination directory or status file path.")
			fmt.Println("Example: phonebackup.exe resume D:\\Backups\\Phone\\phonebackup_status.json")
			os.Exit(1)
		}
		handleResume(os.Args[2])
	case "backup":
		handleBackup(os.Args[2:])
	case "help", "-h", "--help":
		printUsage()
	default:
		// Check if argument is a directory or .json status file directly
		if fi, err := os.Stat(command); err == nil && (fi.IsDir() || strings.HasSuffix(strings.ToLower(command), ".json")) {
			handleResume(command)
			return
		}
		fmt.Printf("Unknown command: %s\n", command)
		printUsage()
		os.Exit(1)
	}
}

func handleServer(args []string) {
	fs := flag.NewFlagSet("server", flag.ExitOnError)
	port := fs.Int("port", getPort(), "HTTP server port")
	_ = fs.Parse(args)

	// Auto-detect and register phonebackup_status.json if present in current directory
	var initial *BackupStatus
	candidates := []string{
		"phonebackup_status.json",
	}
	for _, c := range candidates {
		if s, err := LoadStatusFile(c); err == nil {
			initial = s
			fmt.Printf("📂 Auto-loaded existing backup: %s (%d files, %s)\n", c, s.TotalFiles, FormatBytes(s.TotalBytes))
			break
		}
	}

	StartWebServer(*port, getPublicDir(), initial)

	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, os.Interrupt, syscall.SIGTERM)
	fmt.Println("Server is running. Open http://localhost:" + strconv.Itoa(*port) + " in your browser.")
	fmt.Println("Press Ctrl+C to stop.")
	<-sigChan
	fmt.Println("\nStopping PhoneBackup Web Dashboard...")
}

func handleDevices() {
	devices, err := ListDevices()
	if err != nil {
		fmt.Printf("Error listing devices: %v\n", err)
		os.Exit(1)
	}
	if len(devices) == 0 {
		fmt.Println("No Android devices connected via USB or WiFi ADB.")
		fmt.Println("Ensure USB debugging is turned ON in Developer Options.")
		return
	}

	fmt.Printf("\n📱 Connected Android Devices (%d):\n", len(devices))
	for _, d := range devices {
		fmt.Printf("  • [%s] %s (%s, transport: %s)\n", d.Serial, d.Model, d.State, d.Transport)
	}
	fmt.Println("")
}

func handleResume(target string) {
	status, err := LoadStatusFile(target)
	if err != nil {
		fmt.Printf("Error loading status file %s: %v\n", target, err)
		os.Exit(1)
	}

	devices, err := ListDevices()
	if (err != nil || len(devices) == 0) && status.ActiveSerial != "" {
		// If target device was over WiFi, attempt auto-reconnect
		for _, s := range append([]string{status.ActiveSerial}, status.DeviceSerials...) {
			if strings.Contains(s, ":") {
				fmt.Printf("🔄 Attempting auto-reconnect to WiFi ADB [%s]...\n", s)
				_, _ = RunADB(5*time.Second, "connect", s)
			}
		}
		devices, _ = ListDevices()
	}

	if len(devices) == 0 {
		fmt.Println("Error: No connected Android devices found. Please connect via USB or WiFi ADB.")
		os.Exit(1)
	}

	// Match device by serial, known serials list, or physical hardware serial (ro.serialno)
	matchedSerial := ""
	for _, d := range devices {
		hw := GetDeviceHardwareSerial(d.Serial)
		if status.MatchesDevice(d.Serial, hw) {
			matchedSerial = d.Serial
			if d.Serial != status.ActiveSerial {
				fmt.Printf("📱 Seamlessly switched connection to [%s] (%s, transport: %s)\n",
					d.Serial, d.Model, d.Transport)
			}
			break
		}
	}

	if matchedSerial == "" {
		if len(devices) == 1 {
			matchedSerial = devices[0].Serial
			hw := GetDeviceHardwareSerial(matchedSerial)
			model := GetDeviceModel(matchedSerial)
			fmt.Printf("Notice: Associating connected device [%s] (%s, HW: %s) to this backup\n", matchedSerial, model, hw)
			status.AddDeviceSerial(matchedSerial)
			if hw != "" {
				status.AddDeviceSerial(hw)
			}
			if model != "" && status.DeviceModel == "" {
				status.DeviceModel = model
			}
		} else {
			matchedSerial = devices[0].Serial
			fmt.Printf("Notice: Device serial defaulted to connected device [%s]\n", matchedSerial)
		}
	}

	if matchedSerial != "" {
		status.ActiveSerial = matchedSerial
		status.AddDeviceSerial(matchedSerial)
		_ = status.Save()
	}

	port := getPort()
	// Start complementary Web Dashboard
	StartWebServer(port, getPublicDir(), status)

	fmt.Println("╔════════════════════════════════════════════════════════════╗")
	fmt.Println("║         📱 PhoneBackup (Go) — Resuming Transfer            ║")
	fmt.Println("╠════════════════════════════════════════════════════════════╣")
	fmt.Printf("║ Destination: %-46s║\n", truncateStr(status.LocalDir, 46))
	fmt.Printf("║ Device:      %-46s║\n", truncateStr(status.ActiveSerial, 46))
	fmt.Printf("║ Sources:     %-46s║\n", truncateStr(strings.Join(status.Sources, ", "), 46))
	fmt.Printf("║ Cataloged:   %-46s║\n", fmt.Sprintf("%d files (%s)", status.TotalFiles, FormatBytes(status.TotalBytes)))
	fmt.Printf("║ Progress:    %-46s║\n", fmt.Sprintf("%d done, %d skipped, %d failed", status.DoneFiles, status.SkippedFiles, status.FailedFiles))
	fmt.Printf("║ Web UI:      http://localhost:%-29d║\n", port)
	fmt.Println("╚════════════════════════════════════════════════════════════╝")
	fmt.Println("Running with ZERO popup windows. View live dashboard in your browser.")
	fmt.Println("Press Ctrl+C to pause cleanly.")

	executeEngineWithGracefulShutdown(status)
}

func handleBackup(args []string) {
	fs := flag.NewFlagSet("backup", flag.ExitOnError)
	dest := fs.String("dest", "", "Destination folder")
	sourcesStr := fs.String("sources", "", "Comma-separated Android source folders")
	device := fs.String("device", "", "ADB device serial")
	includeStr := fs.String("include", "", "Comma-separated file patterns (e.g. *.jpg,*.mp4)")
	port := fs.Int("port", getPort(), "Web Dashboard port")
	_ = fs.Parse(args)

	if *dest == "" || *sourcesStr == "" {
		fmt.Println("Error: -dest and -sources are required.")
		printUsage()
		os.Exit(1)
	}

	sources := strings.Split(*sourcesStr, ",")
	for i := range sources {
		sources[i] = strings.TrimSpace(sources[i])
	}

	var include []string
	if *includeStr != "" {
		for _, p := range strings.Split(*includeStr, ",") {
			if s := strings.TrimSpace(p); s != "" {
				include = append(include, s)
			}
		}
	}

	devices, err := ListDevices()
	if err != nil || len(devices) == 0 {
		fmt.Println("Error: No connected Android devices found.")
		os.Exit(1)
	}

	serial := *device
	if serial == "" {
		serial = devices[0].Serial
		fmt.Printf("Auto-selected device: [%s] %s\n", serial, devices[0].Model)
	}

	absDest, err := filepath.Abs(*dest)
	if err != nil {
		absDest = *dest
	}

	status := &BackupStatus{
		Version:      1,
		ID:           fmt.Sprintf("%x", time.Now().UnixNano())[:8],
		ActiveSerial: serial,
		Sources:      sources,
		RemoteDirs:   sources,
		RemoteDir:    strings.Join(sources, ", "),
		LocalDir:     absDest,
		Filters:      BackupFilter{Include: include},
		Status:       StatusPending,
		CreatedAt:    time.Now().UTC().Format(time.RFC3339),
		UpdatedAt:    time.Now().UTC().Format(time.RFC3339),
		Files:        make([]*FileItem, 0),
	}
	status.AddDeviceSerial(serial)
	if m := GetDeviceModel(serial); m != "" {
		status.DeviceModel = m
	}

	// Start complementary Web Dashboard
	StartWebServer(*port, getPublicDir(), status)

	fmt.Println("╔════════════════════════════════════════════════════════════╗")
	fmt.Println("║         📱 PhoneBackup (Go) — New Transfer                ║")
	fmt.Println("╠════════════════════════════════════════════════════════════╣")
	fmt.Printf("║ Destination: %-46s║\n", truncateStr(status.LocalDir, 46))
	fmt.Printf("║ Device:      %-46s║\n", truncateStr(status.ActiveSerial, 46))
	fmt.Printf("║ Sources:     %-46s║\n", truncateStr(strings.Join(status.Sources, ", "), 46))
	fmt.Printf("║ Web UI:      http://localhost:%-29d║\n", *port)
	fmt.Println("╚════════════════════════════════════════════════════════════╝")
	fmt.Println("Running with ZERO popup windows. View live dashboard in your browser.")
	fmt.Println("Press Ctrl+C to pause cleanly.")

	executeEngineWithGracefulShutdown(status)
}

func executeEngineWithGracefulShutdown(status *BackupStatus) {
	ctx, cancel := context.WithCancel(context.Background())
	RegisterCancelFunc(status.ID, cancel)
	defer UnregisterCancelFunc(status.ID)

	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, os.Interrupt, syscall.SIGTERM)

	go func() {
		<-sigChan
		fmt.Println("\n\n⏸ Received pause signal (Ctrl+C). Saving state...")
		cancel()
	}()

	lastUpdate := time.Now()

	err := RunTransferEngine(ctx, status, func(s *BackupStatus) {
		// Broadcast update to Web Dashboard WebSocket clients
		BroadcastTransferUpdate(s)

		if time.Since(lastUpdate) < 300*time.Millisecond && s.Status == StatusRunning {
			return
		}
		lastUpdate = time.Now()

		processed := s.DoneFiles + s.SkippedFiles + s.FailedFiles
		pct := 0
		if s.TotalFiles > 0 {
			pct = (processed * 100) / s.TotalFiles
		}

		if s.Status == StatusScanning {
			fmt.Printf("\r🔍 Scanning Android storage... Found %d files so far", s.TotalFiles)
		} else if s.Status == StatusRunning {
			speedStr := ""
			if s.SpeedBps > 1024 {
				speedStr = fmt.Sprintf(" | %s/s", FormatBytes(int64(s.SpeedBps)))
			}
			etaStr := ""
			if s.EtaSeconds > 0 {
				etaStr = fmt.Sprintf(" | ETA: %s", formatDuration(s.EtaSeconds))
			}
			fileProgressStr := ""
			skippedStr := ""
			if s.SkippedFiles > 0 {
				skippedStr = fmt.Sprintf(" | Skipped: %d", s.SkippedFiles)
			}
			fmt.Printf("\r⚡ [%3d%%] %d/%d files (%s/%s)%s%s%s%s | Failed: %d     ",
				pct, processed, s.TotalFiles,
				FormatBytes(s.DoneBytes), FormatBytes(s.TotalBytes),
				fileProgressStr, speedStr, etaStr,
				skippedStr, s.FailedFiles)
		}
	})

	fmt.Println()
	if err != nil {
		if ctx.Err() != nil {
			fmt.Printf("⏸ Transfer paused at %d/%d files. State saved in %s\n", status.DoneFiles+status.SkippedFiles, status.TotalFiles, filepath.Join(status.LocalDir, "phonebackup_status.json"))
			fmt.Printf("To resume later: phonebackup.exe resume \"%s\"\n", filepath.Join(status.LocalDir, "phonebackup_status.json"))
		} else {
			fmt.Printf("❌ Transfer ended with error: %v\n", err)
		}
		return
	}

	fmt.Println("╔════════════════════════════════════════════════════════════╗")
	fmt.Println("║               ✅ BACKUP COMPLETED SUCCESSFULLY!            ║")
	fmt.Println("╠════════════════════════════════════════════════════════════╣")
	fmt.Printf("║ Total Files: %-46s║\n", fmt.Sprintf("%d files (%s)", status.TotalFiles, FormatBytes(status.TotalBytes)))
	fmt.Printf("║ Transferred: %-46s║\n", fmt.Sprintf("%d files", status.DoneFiles))
	fmt.Printf("║ Skipped:     %-46s║\n", fmt.Sprintf("%d files (already verified)", status.SkippedFiles))
	fmt.Printf("║ Failed:      %-46s║\n", fmt.Sprintf("%d files", status.FailedFiles))
	fmt.Printf("║ Saved At:    %-46s║\n", truncateStr(status.LocalDir, 46))
	fmt.Println("╚════════════════════════════════════════════════════════════╝")
}

func formatDuration(seconds int64) string {
	if seconds <= 0 {
		return "--"
	}
	if seconds < 60 {
		return fmt.Sprintf("~%ds", seconds)
	}
	if seconds < 3600 {
		return fmt.Sprintf("~%dm", (seconds+30)/60)
	}
	hours := float64(seconds) / 3600.0
	return fmt.Sprintf("~%.1fh", hours)
}

func truncateStr(s string, max int) string {
	if len(s) <= max {
		return s
	}
	return "..." + s[len(s)-(max-3):]
}
