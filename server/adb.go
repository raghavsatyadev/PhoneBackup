package main

import (
	"bytes"
	"context"
	"crypto/md5"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"time"
)

// Device represents an ADB device.
type Device struct {
	Serial    string `json:"serial"`
	State     string `json:"state"`
	Model     string `json:"model"`
	Transport string `json:"transport"`
}

// RemoteHash represents a hash computed on the device.
type RemoteHash struct {
	Hash      string `json:"hash"`
	Algorithm string `json:"algorithm"`
}

// getADBPath resolves the absolute path to adb to avoid dot-lookup conflicts with adb.js in the directory.
func getADBPath() string {
	if p := os.Getenv("ADB_PATH"); p != "" {
		return p
	}
	name := "adb"
	if runtime.GOOS == "windows" {
		name = "adb.exe"
	}
	if p, err := exec.LookPath(name); err == nil && filepath.IsAbs(p) {
		return p
	}
	home, _ := os.UserHomeDir()
	candidates := []string{
		filepath.Join(os.Getenv("ANDROID_HOME"), "platform-tools", name),
		filepath.Join(os.Getenv("ANDROID_SDK_ROOT"), "platform-tools", name),
		filepath.Join(os.Getenv("LOCALAPPDATA"), "Android", "Sdk", "platform-tools", name),
		filepath.Join(os.Getenv("PROGRAMFILES"), "Android", "platform-tools", name),
		filepath.Join(home, "Library", "Android", "sdk", "platform-tools", name),
		filepath.Join(home, "Android", "Sdk", "platform-tools", name),
	}
	for _, c := range candidates {
		if _, err := os.Stat(c); err == nil {
			return c
		}
	}
	return name
}

// getADBCommand creates an exec.Cmd that never flashes a console window on Windows.
func getADBCommand(ctx context.Context, args ...string) *exec.Cmd {
	cmd := exec.CommandContext(ctx, getADBPath(), args...)
	hideConsoleWindow(cmd)
	return cmd
}

// RunADB executes an ADB command with timeout and returns stdout.
func RunADB(timeout time.Duration, args ...string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	cmd := getADBCommand(ctx, args...)
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr

	err := cmd.Run()
	if err != nil {
		errStr := strings.TrimSpace(stderr.String())
		if errStr == "" {
			errStr = err.Error()
		}
		return "", fmt.Errorf("adb %s failed: %s", strings.Join(args, " "), errStr)
	}
	return strings.TrimSpace(stdout.String()), nil
}

// RunADBSerial executes an ADB command targeting a specific device serial.
func RunADBSerial(serial string, timeout time.Duration, args ...string) (string, error) {
	fullArgs := append([]string{"-s", serial}, args...)
	return RunADB(timeout, fullArgs...)
}

// ListDevices returns all connected ADB devices.
func ListDevices() ([]Device, error) {
	out, err := RunADB(15*time.Second, "devices", "-l")
	if err != nil {
		return nil, err
	}

	var devices []Device
	lines := strings.Split(out, "\n")
	for _, line := range lines {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "List of") {
			continue
		}
		parts := strings.Fields(line)
		if len(parts) < 2 {
			continue
		}
		serial := parts[0]
		state := parts[1]
		if state != "device" {
			continue
		}

		model := ""
		transport := "usb"
		if strings.Contains(serial, ":") {
			transport = "wifi"
		}
		for _, p := range parts[2:] {
			if strings.HasPrefix(p, "model:") {
				model = strings.TrimPrefix(p, "model:")
				model = strings.ReplaceAll(model, "_", " ")
			}
			if strings.HasPrefix(p, "transport_id:") {
				// can keep if needed
			}
		}

		devices = append(devices, Device{
			Serial:    serial,
			State:     state,
			Model:     model,
			Transport: transport,
		})
	}
	return devices, nil
}

// ConnectWifi connects to a device over WiFi ADB.
func ConnectWifi(address string) (string, error) {
	addr := strings.TrimSpace(address)
	if !strings.Contains(addr, ":") {
		addr += ":5555"
	}
	return RunADB(15*time.Second, "connect", addr)
}

// DisconnectWifi disconnects a WiFi ADB device.
func DisconnectWifi(address string) (string, error) {
	addr := strings.TrimSpace(address)
	if !strings.Contains(addr, ":") {
		addr += ":5555"
	}
	return RunADB(15*time.Second, "disconnect", addr)
}

// DirEntry represents an entry in an Android directory listing.
type DirEntry struct {
	Name    string `json:"name"`
	Path    string `json:"path"`
	IsDir   bool   `json:"isDir"`
	Size    int64  `json:"size"`
	ModTime string `json:"modTime,omitempty"`
}

// BrowseDir lists the contents of an Android directory.
func BrowseDir(serial, dirPath string) ([]DirEntry, error) {
	if !strings.HasPrefix(dirPath, "/") {
		dirPath = "/" + dirPath
	}
	pathWithSlash := dirPath
	if !strings.HasSuffix(pathWithSlash, "/") {
		pathWithSlash += "/"
	}
	quoted := ShellQuote(pathWithSlash)
	cleanBase := strings.TrimRight(dirPath, "/")

	out, err := RunADBSerial(serial, 30*time.Second, "shell", fmt.Sprintf("ls -la -L --color=never %s 2>/dev/null || ls -la %s 2>/dev/null", quoted, quoted))
	if err != nil {
		return nil, err
	}

	var entries []DirEntry
	lines := strings.Split(out, "\n")
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if trimmed == "" || strings.HasPrefix(trimmed, "total") || strings.HasPrefix(trimmed, "ls:") {
			continue
		}

		fields := strings.Fields(trimmed)
		if len(fields) < 8 {
			continue
		}

		perms := fields[0]
		isDir := strings.HasPrefix(perms, "d") || strings.HasPrefix(perms, "l")
		size, _ := strconv.ParseInt(fields[4], 10, 64)

		name := strings.Join(fields[7:], " ")
		if strings.Contains(name, " -> ") {
			name = strings.Split(name, " -> ")[0]
		}
		name = strings.TrimSpace(name)
		if name == "." || name == ".." || name == "" {
			continue
		}

		entryPath := name
		if !strings.HasPrefix(name, "/") {
			entryPath = cleanBase + "/" + name
		}

		entries = append(entries, DirEntry{
			Name:  filepath.Base(name),
			Path:  entryPath,
			IsDir: isDir,
			Size:  size,
		})
	}

	return entries, nil
}

// RemoteFile represents a file discovered on the Android device.
type RemoteFile struct {
	Path string `json:"path"`
	Size int64  `json:"size"`
}

// ShellQuote single-quotes a string safely for adb shell.
func ShellQuote(p string) string {
	return "'" + strings.ReplaceAll(p, "'", "'\\''") + "'"
}

// ScanDir recursively scans a remote directory on the Android device.
func ScanDir(serial, remoteDir string, includeFilters []string, onProgress func(int)) ([]RemoteFile, error) {
	quoted := ShellQuote(remoteDir)
	// Try toybox find first (fastest)
	cmdStr := fmt.Sprintf("find %s -type f -exec stat -c '%%s %%n' {} + 2>/dev/null || find %s -type f 2>/dev/null", quoted, quoted)
	out, err := RunADBSerial(serial, 120*time.Second, "shell", cmdStr)
	if err != nil {
		return nil, err
	}

	var files []RemoteFile
	lines := strings.Split(out, "\n")
	for _, line := range lines {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}

		// Check if line starts with a size: "12345 /path/to/file"
		firstSpace := strings.Index(line, " ")
		var filePath string
		var fileSize int64

		if firstSpace > 0 {
			szStr := line[:firstSpace]
			pStr := strings.TrimSpace(line[firstSpace+1:])
			if sz, err := strconv.ParseInt(szStr, 10, 64); err == nil && strings.HasPrefix(pStr, "/") {
				fileSize = sz
				filePath = pStr
			}
		}

		if filePath == "" {
			filePath = line
		}

		// Filter matching
		if len(includeFilters) > 0 && !MatchesFilters(filePath, includeFilters) {
			continue
		}

		files = append(files, RemoteFile{
			Path: filePath,
			Size: fileSize,
		})

		if onProgress != nil && len(files)%100 == 0 {
			onProgress(len(files))
		}
	}

	return files, nil
}

// MatchesFilters checks if a filename matches glob patterns.
func MatchesFilters(pathStr string, patterns []string) bool {
	if len(patterns) == 0 {
		return true
	}
	base := strings.ToLower(filepath.Base(pathStr))
	for _, p := range patterns {
		p = strings.ToLower(strings.TrimSpace(p))
		if p == "" {
			continue
		}
		if matched, _ := filepath.Match(p, base); matched {
			return true
		}
		if strings.HasPrefix(p, "*.") && strings.HasSuffix(base, strings.TrimPrefix(p, "*")) {
			return true
		}
	}
	return false
}

// GetDeviceHardwareSerial queries the unique physical hardware serial (ro.serialno).
// This stays constant whether the phone is connected via USB or WiFi ADB!
func GetDeviceHardwareSerial(serial string) string {
	out, err := RunADBSerial(serial, 5*time.Second, "shell", "getprop", "ro.serialno")
	if err == nil {
		return strings.TrimSpace(out)
	}
	return ""
}

// GetDeviceModel queries the phone model (ro.product.model).
func GetDeviceModel(serial string) string {
	out, err := RunADBSerial(serial, 5*time.Second, "shell", "getprop", "ro.product.model")
	if err == nil {
		return strings.TrimSpace(out)
	}
	return ""
}

// PullFile pulls a file from the device to local destination with NO console window flash.
// Accepts context for immediate cancellation/pause and deletes incomplete/partial files if aborted or failed.
func PullFile(ctx context.Context, serial, remotePath, localPath string) error {
	dir := filepath.Dir(localPath)
	if err := os.MkdirAll(dir, 0755); err != nil {
		return fmt.Errorf("failed to create directory %s: %w", dir, err)
	}

	// Always delete any existing incomplete/corrupted file at localPath before pulling
	_ = os.Remove(localPath)

	pullCtx, cancel := context.WithTimeout(ctx, 30*time.Minute)
	defer cancel()

	cmd := getADBCommand(pullCtx, "-s", serial, "pull", "-a", remotePath, localPath)
	var stderr bytes.Buffer
	cmd.Stderr = &stderr

	if err := cmd.Run(); err != nil {
		// If transfer was stopped or interrupted, delete partial bytes immediately!
		_ = os.Remove(localPath)
		return fmt.Errorf("adb pull failed: %s (%v)", strings.TrimSpace(stderr.String()), err)
	}
	return nil
}

// GetRemoteHash calculates hash on Android device.
func GetRemoteHash(serial, remotePath string) (*RemoteHash, error) {
	quoted := ShellQuote(remotePath)

	// Try sha256sum
	out, err := RunADBSerial(serial, 30*time.Second, "shell", fmt.Sprintf("sha256sum %s 2>/dev/null", quoted))
	if err == nil && len(out) >= 64 {
		fields := strings.Fields(out)
		if len(fields) >= 1 && len(fields[0]) == 64 {
			return &RemoteHash{Hash: strings.ToLower(fields[0]), Algorithm: "sha256"}, nil
		}
	}

	// Fallback to md5sum
	out, err = RunADBSerial(serial, 30*time.Second, "shell", fmt.Sprintf("md5sum %s 2>/dev/null", quoted))
	if err == nil && len(out) >= 32 {
		fields := strings.Fields(out)
		if len(fields) >= 1 && len(fields[0]) == 32 {
			return &RemoteHash{Hash: strings.ToLower(fields[0]), Algorithm: "md5"}, nil
		}
	}

	return nil, nil
}

// ComputeLocalHash computes SHA-256 or MD5 for a local file.
func ComputeLocalHash(localPath, algorithm string) (string, error) {
	f, err := os.Open(localPath)
	if err != nil {
		return "", err
	}
	defer f.Close()

	if algorithm == "md5" {
		h := md5.New()
		if _, err := io.Copy(h, f); err != nil {
			return "", err
		}
		return hex.EncodeToString(h.Sum(nil)), nil
	}

	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}
