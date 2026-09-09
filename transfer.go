package main

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// Status constants
const (
	StatusPending   = "pending"
	StatusScanning  = "scanning"
	StatusRunning   = "running"
	StatusPaused    = "paused"
	StatusCompleted = "completed"
	StatusFailed    = "failed"
	StatusCancelled = "cancelled"

	FilePending   = "pending"
	FileRunning   = "running"
	FileDone      = "done"
	FileFailed    = "failed"
	FileSkipped   = "skipped"
	FileVerifying = "verifying"
)

// LogEntry represents an activity event for a single file.
type LogEntry struct {
	Time       string `json:"time"`
	Status     string `json:"status"` // 'done' | 'skipped' | 'failed'
	RemotePath string `json:"remote_path"`
	Size       int64  `json:"size"`
	Error      string `json:"error,omitempty"`
}

func (l *LogEntry) UnmarshalJSON(data []byte) error {
	type Alias LogEntry
	aux := struct {
		*Alias
		RemotePathCamel string `json:"remotePath"`
	}{
		Alias: (*Alias)(l),
	}
	if err := json.Unmarshal(data, &aux); err != nil {
		return err
	}
	if l.RemotePath == "" && aux.RemotePathCamel != "" {
		l.RemotePath = aux.RemotePathCamel
	}
	return nil
}

// FileItem represents a single file being transferred.
type FileItem struct {
	RemotePath string `json:"remote_path"`
	RemoteBase string `json:"remote_base,omitempty"`
	LocalPath  string `json:"local_path"`
	Size       int64  `json:"size"`
	Status     string `json:"status"`
	Hash       string `json:"hash,omitempty"`
	Integrity  string `json:"integrity,omitempty"` // 'verified' | 'failed'
	Error      string `json:"error,omitempty"`
}

func (f *FileItem) UnmarshalJSON(data []byte) error {
	type Alias FileItem
	aux := struct {
		*Alias
		RemotePathCamel string `json:"remotePath"`
		RemoteBaseCamel string `json:"remoteBase"`
		LocalPathCamel  string `json:"localPath"`
	}{
		Alias: (*Alias)(f),
	}
	if err := json.Unmarshal(data, &aux); err != nil {
		return err
	}
	if f.RemotePath == "" && aux.RemotePathCamel != "" {
		f.RemotePath = aux.RemotePathCamel
	}
	if f.RemoteBase == "" && aux.RemoteBaseCamel != "" {
		f.RemoteBase = aux.RemoteBaseCamel
	}
	if f.LocalPath == "" && aux.LocalPathCamel != "" {
		f.LocalPath = aux.LocalPathCamel
	}
	return nil
}

// BackupFilter represents inclusion/exclusion patterns.
type BackupFilter struct {
	Include []string `json:"include,omitempty"`
	Exclude []string `json:"exclude,omitempty"`
}

// BackupStatus represents the exact schema stored in phonebackup_status.json.
type BackupStatus struct {
	Version         int          `json:"version"`
	ID              string       `json:"id"`
	ActiveSerial    string       `json:"active_serial"`
	DeviceSerials   []string     `json:"known_serials,omitempty"`
	DeviceModel     string       `json:"device_model,omitempty"`
	Sources         []string     `json:"sources"`
	RemoteDirs      []string     `json:"remote_dirs,omitempty"`
	RemoteDir       string       `json:"remote_dir,omitempty"`
	LocalDir        string       `json:"local_dir"`
	Filters         BackupFilter `json:"filters,omitempty"`
	Status          string       `json:"status"`
	TotalFiles      int          `json:"total_files"`
	DoneFiles       int          `json:"done_files"`
	SkippedFiles    int          `json:"skipped_files"`
	FailedFiles     int          `json:"failed_files"`
	TotalBytes      int64        `json:"total_bytes"`
	DoneBytes       int64        `json:"done_bytes"`
	CreatedAt       string       `json:"created_at"`
	UpdatedAt       string       `json:"updated_at"`
	Error           string       `json:"error,omitempty"`
	CurrentFile     string       `json:"current_file,omitempty"`
	CurrentFileSize int64        `json:"current_file_size,omitempty"`
	CurrentFileDone int64        `json:"current_file_done,omitempty"`
	SpeedBps        float64      `json:"speed_bps,omitempty"`
	EtaSeconds      int64        `json:"eta_seconds,omitempty"`
	RecentLogs      []LogEntry   `json:"recent_logs,omitempty"`
	FailedList      []*FileItem  `json:"failed_list,omitempty"`
	CatalogFile     string       `json:"catalog_file,omitempty"`
	Files           []*FileItem  `json:"files,omitempty"`

	mu sync.Mutex `json:"-"`
}

// ToSummary returns a lightweight map representation of the transfer state without the large file catalog.
func (s *BackupStatus) ToSummary() map[string]interface{} {
	s.mu.Lock()
	defer s.mu.Unlock()

	return map[string]interface{}{
		"version":           s.Version,
		"id":                s.ID,
		"active_serial":     s.ActiveSerial,
		"known_serials":     s.DeviceSerials,
		"device_model":      s.DeviceModel,
		"sources":           s.Sources,
		"remote_dirs":       s.RemoteDirs,
		"remote_dir":        s.RemoteDir,
		"local_dir":         s.LocalDir,
		"filters":           s.Filters,
		"status":            s.Status,
		"total_files":       s.TotalFiles,
		"done_files":        s.DoneFiles,
		"skipped_files":     s.SkippedFiles,
		"failed_files":      s.FailedFiles,
		"total_bytes":       s.TotalBytes,
		"done_bytes":        s.DoneBytes,
		"created_at":        s.CreatedAt,
		"updated_at":        s.UpdatedAt,
		"error":             s.Error,
		"current_file":      s.CurrentFile,
		"current_file_size": s.CurrentFileSize,
		"current_file_done": s.CurrentFileDone,
		"speed_bps":         s.SpeedBps,
		"eta_seconds":       s.EtaSeconds,
		"recent_logs":       s.RecentLogs,
		"failed_list":       s.FailedList,
		"catalog_file":      s.CatalogFile,
	}
}

// ComputeLocalPath maps an Android remote path to a local PC path, preserving folder hierarchy.
func ComputeLocalPath(remoteBase, remotePath, localBase string) string {
	if remotePath == remoteBase {
		return filepath.Join(localBase, path.Base(remotePath))
	}

	relative := remotePath
	if strings.HasPrefix(remotePath, remoteBase) {
		relative = strings.TrimPrefix(remotePath, remoteBase)
	}
	relative = strings.TrimPrefix(relative, "/")

	baseName := path.Base(remoteBase)
	if baseName == "" || remoteBase == "/" || remoteBase == "/sdcard" || remoteBase == "/storage/emulated/0" {
		return filepath.Join(localBase, filepath.FromSlash(relative))
	}
	return filepath.Join(localBase, baseName, filepath.FromSlash(relative))
}

// LoadStatusFile reads phonebackup_status.json from disk.
func LoadStatusFile(target string) (*BackupStatus, error) {
	target = strings.TrimSpace(target)
	target = strings.Trim(target, `"'`)
	if target == "" {
		return nil, fmt.Errorf("status file path cannot be empty")
	}

	statusPath := filepath.Clean(filepath.FromSlash(target))
	if fi, err := os.Stat(statusPath); err == nil && fi.IsDir() {
		logsStatus := filepath.Join(statusPath, "logs", "phonebackup_status.json")
		if _, err := os.Stat(logsStatus); err == nil {
			statusPath = logsStatus
		} else {
			statusPath = filepath.Join(statusPath, "phonebackup_status.json")
		}
	}

	raw, err := os.ReadFile(statusPath)
	if err != nil {
		// Fallback candidates: check if target was a folder containing backup json or if path was parent
		candidates := []string{
			filepath.Join(statusPath, "logs", "phonebackup_status.json"),
			filepath.Join(statusPath, "phonebackup_status.json"),
			filepath.Join(statusPath, "backup_status.json"),
			filepath.Join(filepath.Dir(statusPath), "logs", "phonebackup_status.json"),
			filepath.Join(filepath.Dir(statusPath), "phonebackup_status.json"),
		}
		for _, c := range candidates {
			if r, err2 := os.ReadFile(c); err2 == nil {
				raw = r
				statusPath = c
				err = nil
				break
			}
		}
	}
	if err != nil {
		return nil, fmt.Errorf("could not read status file %s: %w", statusPath, err)
	}

	var status BackupStatus
	if err := json.Unmarshal(raw, &status); err != nil {
		return nil, fmt.Errorf("invalid json in %s: %w", statusPath, err)
	}

	var camelFallback struct {
		DeviceSerial    string      `json:"deviceSerial"`
		DeviceSerials   []string    `json:"deviceSerials"`
		DeviceModel     string      `json:"deviceModel"`
		DeviceID        string      `json:"deviceId"`
		RemoteDirs      []string    `json:"remoteDirs"`
		RemoteDir       string      `json:"remoteDir"`
		LocalDir        string      `json:"localDir"`
		TotalFiles      int         `json:"totalFiles"`
		DoneFiles       int         `json:"doneFiles"`
		SkippedFiles    int         `json:"skippedFiles"`
		FailedFiles     int         `json:"failedFiles"`
		TotalBytes      int64       `json:"totalBytes"`
		DoneBytes       int64       `json:"doneBytes"`
		CreatedAt       string      `json:"createdAt"`
		UpdatedAt       string      `json:"updatedAt"`
		CurrentFile     string      `json:"currentFile"`
		CurrentFileSize int64       `json:"currentFileSize"`
		CurrentFileDone int64       `json:"currentFileDone"`
		SpeedBps        float64     `json:"speedBps"`
		EtaSeconds      int64       `json:"etaSeconds"`
		RecentLogs      []LogEntry  `json:"recentLogs"`
		FailedList      []*FileItem `json:"failedList"`
		CatalogFile     string      `json:"catalogFile"`
	}
	_ = json.Unmarshal(raw, &camelFallback)

	var legacyFallback struct {
		ActiveSerial  string   `json:"active_serial"`
		DeviceSerial  string   `json:"device_serial"`
		DeviceID      string   `json:"device_id"`
		DeviceSerials []string `json:"known_serials"`
		LocalDir      string   `json:"local_dir"`
	}
	_ = json.Unmarshal(raw, &legacyFallback)

	if status.LocalDir == "" {
		if legacyFallback.LocalDir != "" {
			status.LocalDir = legacyFallback.LocalDir
		} else if camelFallback.LocalDir != "" {
			status.LocalDir = camelFallback.LocalDir
		}
	}
	if status.ActiveSerial == "" {
		if legacyFallback.ActiveSerial != "" {
			status.ActiveSerial = legacyFallback.ActiveSerial
		} else if legacyFallback.DeviceSerial != "" {
			status.ActiveSerial = legacyFallback.DeviceSerial
		} else if legacyFallback.DeviceID != "" {
			status.ActiveSerial = legacyFallback.DeviceID
		} else if camelFallback.DeviceSerial != "" {
			status.ActiveSerial = camelFallback.DeviceSerial
		} else if camelFallback.DeviceID != "" {
			status.ActiveSerial = camelFallback.DeviceID
		}
	}
	if len(status.DeviceSerials) == 0 {
		if len(legacyFallback.DeviceSerials) > 0 {
			status.DeviceSerials = legacyFallback.DeviceSerials
		} else if len(camelFallback.DeviceSerials) > 0 {
			status.DeviceSerials = camelFallback.DeviceSerials
		}
	}
	if status.TotalFiles == 0 && camelFallback.TotalFiles > 0 {
		status.TotalFiles = camelFallback.TotalFiles
	}
	if status.DoneFiles == 0 && camelFallback.DoneFiles > 0 {
		status.DoneFiles = camelFallback.DoneFiles
	}
	if status.TotalBytes == 0 && camelFallback.TotalBytes > 0 {
		status.TotalBytes = camelFallback.TotalBytes
	}
	if status.DoneBytes == 0 && camelFallback.DoneBytes > 0 {
		status.DoneBytes = camelFallback.DoneBytes
	}
	if status.DeviceModel == "" && camelFallback.DeviceModel != "" {
		status.DeviceModel = camelFallback.DeviceModel
	}
	if status.CatalogFile == "" && camelFallback.CatalogFile != "" {
		status.CatalogFile = camelFallback.CatalogFile
	}
	if len(status.Sources) == 0 && len(camelFallback.RemoteDirs) > 0 {
		status.Sources = camelFallback.RemoteDirs
	}

	if len(status.Sources) == 0 && len(status.RemoteDirs) > 0 {
		status.Sources = status.RemoteDirs
	} else if len(status.Sources) == 0 && status.RemoteDir != "" {
		status.Sources = strings.Split(status.RemoteDir, ",")
	}

	if status.LocalDir == "" {
		parent := filepath.Dir(statusPath)
		if strings.EqualFold(filepath.Base(parent), "logs") {
			status.LocalDir = filepath.Dir(parent)
		} else {
			status.LocalDir = parent
		}
	}

	// Ensure DeviceSerials includes ActiveSerial
	if status.ActiveSerial != "" {
		hasSerial := false
		for _, s := range status.DeviceSerials {
			if s == status.ActiveSerial {
				hasSerial = true
				break
			}
		}
		if !hasSerial {
			status.DeviceSerials = append(status.DeviceSerials, status.ActiveSerial)
		}
	}

	// If files list was split into logs/files_catalog.json, load it
	if len(status.Files) == 0 {
		candidates := []string{
			filepath.Join(status.LocalDir, "logs", "files_catalog.json"),
			filepath.Join(filepath.Dir(statusPath), "logs", "files_catalog.json"),
			filepath.Join(status.LocalDir, "files_catalog.json"),
		}
		for _, cp := range candidates {
			if rawCatalog, err := os.ReadFile(cp); err == nil {
				var files []*FileItem
				if err := json.Unmarshal(rawCatalog, &files); err == nil && len(files) > 0 {
					if files[0].RemotePath != "" {
						status.Files = files
						status.CatalogFile = cp
						break
					}
				}
			}
		}
	}

	if len(status.Files) > 0 && status.Files[0].RemotePath == "" {
		status.Files = nil
	}

	// If failedList was split into logs/failed_files.json, load it
	if len(status.FailedList) == 0 {
		failedCandidates := []string{
			filepath.Join(status.LocalDir, "logs", "failed_files.json"),
			filepath.Join(filepath.Dir(statusPath), "logs", "failed_files.json"),
		}
		for _, fp := range failedCandidates {
			if rawFailed, err := os.ReadFile(fp); err == nil {
				var failed []*FileItem
				if err := json.Unmarshal(rawFailed, &failed); err == nil && len(failed) > 0 {
					status.FailedList = failed
					if status.FailedFiles == 0 {
						status.FailedFiles = len(failed)
					}
					break
				}
			}
		}
	}

	return &status, nil
}

// AddDeviceSerial registers a new serial connection (e.g. WiFi IP or USB hardware serial) for this device.
func (s *BackupStatus) AddDeviceSerial(newSerial string) {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.ActiveSerial = newSerial
	has := false
	for _, x := range s.DeviceSerials {
		if x == newSerial {
			has = true
			break
		}
	}
	if !has {
		s.DeviceSerials = append(s.DeviceSerials, newSerial)
	}
}

// MatchesDevice checks if a connected serial or hardware ID belongs to this backup.
func (s *BackupStatus) MatchesDevice(serial string, hardwareID string) bool {
	if s.ActiveSerial == serial {
		return true
	}
	for _, x := range s.DeviceSerials {
		if x == serial {
			return true
		}
	}
	if hardwareID != "" {
		for _, x := range s.DeviceSerials {
			if x == hardwareID {
				return true
			}
		}
	}
	return false
}

// Save writes the lightweight status checkpoint in phonebackup_status.json and the full file catalog in logs/files_catalog.json.
func (s *BackupStatus) Save() error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.LocalDir == "" {
		return fmt.Errorf("localDir is empty")
	}
	if err := os.MkdirAll(s.LocalDir, 0755); err != nil {
		return err
	}

	logsDir := filepath.Join(s.LocalDir, "logs")
	if err := os.MkdirAll(logsDir, 0755); err != nil {
		return err
	}

	// Always sync FailedList with current failed files
	var failed []*FileItem
	for _, f := range s.Files {
		if f.Status == FileFailed {
			failed = append(failed, f)
		}
	}
	s.FailedList = failed
	s.FailedFiles = len(failed)
	s.CatalogFile = filepath.Join("logs", "files_catalog.json")
	s.UpdatedAt = time.Now().UTC().Format(time.RFC3339)

	// 1. Write logs/files_catalog.json (contains all file paths, sizes, hashes, and statuses)
	if len(s.Files) > 0 {
		catalogPath := filepath.Join(logsDir, "files_catalog.json")
		rawCatalog, err := json.MarshalIndent(s.Files, "", "  ")
		if err == nil {
			_ = os.WriteFile(catalogPath, rawCatalog, 0644)
		}
	}

	// 2. Write logs/failed_files.json (contains current failed files for fast inspection)
	failedPath := filepath.Join(logsDir, "failed_files.json")
	rawFailed, err := json.MarshalIndent(s.FailedList, "", "  ")
	if err == nil {
		_ = os.WriteFile(failedPath, rawFailed, 0644)
	}

	// 3. Write phonebackup_status.json inside logs/ AND at root directory WITHOUT the massive files array (checkpoint < 15KB)
	statusCopy := *s
	statusCopy.Files = nil

	raw, err := json.MarshalIndent(&statusCopy, "", "  ")
	if err != nil {
		return err
	}

	// Write inside logs/
	logsStatusPath := filepath.Join(logsDir, "phonebackup_status.json")
	if err := os.WriteFile(logsStatusPath, raw, 0644); err != nil {
		return err
	}

	// Clean up legacy status file at root destination folder if it exists so status json is ONLY maintained inside logs/
	rootStatus := filepath.Join(s.LocalDir, "phonebackup_status.json")
	if _, statErr := os.Stat(rootStatus); statErr == nil {
		_ = os.Remove(rootStatus)
	}
	return nil
}

// AppendLog adds an activity log entry and writes to logs/activity.log.
func (s *BackupStatus) AppendLog(status, remotePath string, size int64, errStr string) {
	s.mu.Lock()
	defer s.mu.Unlock()

	now := time.Now()
	entry := LogEntry{
		Time:       now.Format("15:04:05"),
		Status:     status,
		RemotePath: remotePath,
		Size:       size,
		Error:      errStr,
	}
	s.RecentLogs = append(s.RecentLogs, entry)
	if len(s.RecentLogs) > 60 {
		s.RecentLogs = s.RecentLogs[len(s.RecentLogs)-60:]
	}

	// Append to logs/activity.log in backup destination
	if s.LocalDir != "" {
		logsDir := filepath.Join(s.LocalDir, "logs")
		_ = os.MkdirAll(logsDir, 0755)
		logPath := filepath.Join(logsDir, "activity.log")
		logLine := fmt.Sprintf("[%s] [%-8s] %s (%s)", now.Format("2006-01-02 15:04:05"), strings.ToUpper(status), remotePath, FormatBytes(size))
		if errStr != "" {
			logLine += fmt.Sprintf(" - ERROR: %s", errStr)
		}
		logLine += "\n"

		if f, err := os.OpenFile(logPath, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0644); err == nil {
			_, _ = f.WriteString(logLine)
			_ = f.Close()
		}
	}
}

// LogUserAction writes user dashboard interactions to logs/dashboard_actions.log in the backup folder.
func (s *BackupStatus) LogUserAction(action string) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.LocalDir == "" {
		return
	}
	logsDir := filepath.Join(s.LocalDir, "logs")
	_ = os.MkdirAll(logsDir, 0755)
	logPath := filepath.Join(logsDir, "dashboard_actions.log")
	now := time.Now()
	line := fmt.Sprintf("[%s] %s\n", now.Format("2006-01-02 15:04:05"), action)

	if f, err := os.OpenFile(logPath, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0644); err == nil {
		_, _ = f.WriteString(line)
		_ = f.Close()
	}
}

// RetryFailed resets all failed files to pending state and resets counters.
func (s *BackupStatus) RetryFailed() int {
	s.mu.Lock()
	defer s.mu.Unlock()

	count := 0
	for _, f := range s.Files {
		if f.Status == FileFailed {
			f.Status = FilePending
			f.Error = ""
			f.Integrity = ""
			count++
		}
	}

	s.FailedFiles = 0
	s.FailedList = nil
	if count > 0 && s.Status == StatusFailed {
		s.Status = StatusPaused
	}
	return count
}

// FormatBytes converts bytes into human-readable representation.
func FormatBytes(b int64) string {
	const unit = 1024
	if b < unit {
		return fmt.Sprintf("%d B", b)
	}
	div, exp := int64(unit), 0
	for n := b / unit; n >= unit; n /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.2f %cB", float64(b)/float64(div), "KMGTPE"[exp])
}

// RunTransferEngine runs or resumes a backup transfer.
func RunTransferEngine(ctx context.Context, status *BackupStatus, onProgress func(*BackupStatus)) error {
	status.Status = StatusRunning
	if onProgress != nil {
		onProgress(status)
	}

	// Ensure active device connection & auto-switch transport (e.g. from WiFi to USB or vice versa)
	devices, devErr := ListDevices()
	if devErr == nil && len(devices) > 0 {
		deviceActive := false
		for _, d := range devices {
			if d.Serial == status.ActiveSerial {
				deviceActive = true
				break
			}
		}

		if !deviceActive {
			// Current status.ActiveSerial is offline! Find matching connected device by HW serial or model
			for _, d := range devices {
				hw := GetDeviceHardwareSerial(d.Serial)
				if status.MatchesDevice(d.Serial, hw) {
					fmt.Printf("📱 Seamlessly switched backup connection from [%s] to active [%s] (%s)\n",
						status.ActiveSerial, d.Serial, d.Model)
					status.ActiveSerial = d.Serial
					status.AddDeviceSerial(d.Serial)
					if hw != "" {
						status.AddDeviceSerial(hw)
					}
					if d.Model != "" {
						status.DeviceModel = d.Model
					}
					deviceActive = true
					_ = status.Save()
					break
				}
			}
			// If not matched by serial/HW but only 1 device is connected, associate it
			if !deviceActive && len(devices) == 1 {
				d := devices[0]
				fmt.Printf("📱 Auto-associated connected device [%s] (%s) to transfer\n", d.Serial, d.Model)
				status.ActiveSerial = d.Serial
				status.AddDeviceSerial(d.Serial)
				if d.Model != "" {
					status.DeviceModel = d.Model
				}
				_ = status.Save()
			}
		}
	}

	// Discover hardware serial (ro.serialno) & product model to uniquely identify device across USB and WiFi
	if status.ActiveSerial != "" {
		if hw := GetDeviceHardwareSerial(status.ActiveSerial); hw != "" {
			status.AddDeviceSerial(hw)
		}
		if status.DeviceModel == "" {
			status.DeviceModel = GetDeviceModel(status.ActiveSerial)
		}
	}

	// Reconcile counts from existing file list if resuming
	if len(status.Files) > 0 {
		var doneBytes int64 = 0
		doneFiles := 0
		skippedFiles := 0
		failedFiles := 0
		var totalBytes int64 = 0

		for _, f := range status.Files {
			totalBytes += f.Size
			if f.Status == FileDone || (f.Status == FileSkipped && f.Integrity == "verified") {
				f.Status = FileDone
				f.Integrity = "verified"
				doneFiles++
				doneBytes += f.Size
			} else if f.Status == FileSkipped {
				skippedFiles++
			} else if f.Status == FileFailed {
				// Re-queue previously failed files so they are retried on this run
				f.Status = FilePending
				f.Error = ""
				f.Integrity = ""
			} else if f.Status == FileRunning {
				// Interrupted previously while in transit: reset to pending and remove incomplete file
				f.Status = FilePending
				if fi, err := os.Stat(f.LocalPath); err == nil && fi.Size() != f.Size {
					_ = os.Remove(f.LocalPath)
				}
			}
		}
		status.DoneFiles = doneFiles
		status.SkippedFiles = skippedFiles
		status.FailedFiles = failedFiles
		status.FailedList = nil
		status.DoneBytes = doneBytes
		status.TotalBytes = totalBytes
		status.TotalFiles = len(status.Files)
	}

	_ = status.Save()

	// If no files yet, perform scan
	if len(status.Files) == 0 {
		status.TotalFiles = 0
		status.TotalBytes = 0
		status.DoneFiles = 0
		status.DoneBytes = 0
		status.SkippedFiles = 0
		status.FailedFiles = 0
		status.Status = StatusScanning
		_ = status.Save()
		if onProgress != nil {
			onProgress(status)
		}

		for _, src := range status.Sources {
			src = strings.TrimSpace(src)
			if src == "" {
				continue
			}

			files, err := ScanDir(status.ActiveSerial, src, status.Filters.Include, func(count int) {
				status.TotalFiles += count
				if onProgress != nil {
					onProgress(status)
				}
			})
			if err != nil {
				fmt.Printf("\nWarning: failed scanning %s: %v\n", src, err)
				continue
			}

			for _, f := range files {
				item := &FileItem{
					RemotePath: f.Path,
					RemoteBase: src,
					LocalPath:  ComputeLocalPath(src, f.Path, status.LocalDir),
					Size:       f.Size,
					Status:     FilePending,
				}
				status.Files = append(status.Files, item)
				status.TotalBytes += f.Size
			}
		}

		status.TotalFiles = len(status.Files)
		status.Status = StatusRunning
		_ = status.Save()
		if onProgress != nil {
			onProgress(status)
		}
	}

	// Transfer loop
	processedSinceSave := 0
	var sessionNetBytes int64 = 0
	var lastSampleBytes int64 = 0
	var smoothedSpeed float64 = 0
	lastSpeedTime := time.Now()

	sampleSpeed := func(inFlightBytes int64) {
		now := time.Now()
		dur := now.Sub(lastSpeedTime).Seconds()
		if dur < 0.4 {
			return
		}

		currentTotal := sessionNetBytes + inFlightBytes
		diff := currentTotal - lastSampleBytes
		if diff < 0 {
			diff = 0
		}

		instantSpeed := float64(diff) / dur
		lastSpeedTime = now
		lastSampleBytes = currentTotal

		if instantSpeed > 0 {
			if smoothedSpeed <= 0 {
				smoothedSpeed = instantSpeed
			} else {
				// Exponential moving average (alpha = 0.15) for rock-steady ETA
				smoothedSpeed = (0.15 * instantSpeed) + (0.85 * smoothedSpeed)
			}
		}

		status.mu.Lock()
		status.SpeedBps = instantSpeed
		remainingBytes := status.TotalBytes - (status.DoneBytes + inFlightBytes)
		if smoothedSpeed > 1024 && remainingBytes > 0 {
			status.EtaSeconds = int64(float64(remainingBytes) / smoothedSpeed)
		} else if status.Status == StatusCompleted {
			status.EtaSeconds = 0
		}
		status.mu.Unlock()
	}

	for idx, item := range status.Files {
		select {
		case <-ctx.Done():
			status.Status = StatusPaused
			// If transfer was interrupted in the middle of this file, delete the incomplete file!
			if item.Status == FileRunning {
				item.Status = FilePending
				_ = os.Remove(item.LocalPath)
			}
			status.CurrentFile = ""
			status.CurrentFileSize = 0
			status.CurrentFileDone = 0
			status.SpeedBps = 0
			status.EtaSeconds = 0
			_ = status.Save()
			if onProgress != nil {
				onProgress(status)
			}
			return ctx.Err()
		default:
		}

		// Skip if already done or skipped
		if item.Status == FileDone || item.Status == FileSkipped {
			continue
		}

		// Incremental check: check if file already exists locally with exact same size
		if fi, err := os.Stat(item.LocalPath); err == nil {
			if item.Size > 0 && fi.Size() == item.Size {
				item.Status = FileDone
				item.Integrity = "verified"
				status.DoneFiles++
				status.DoneBytes += item.Size
				status.AppendLog("verified", item.RemotePath, item.Size, "")
				// Update ETA with remaining bytes without spiking network speed
				status.mu.Lock()
				remainingBytes := status.TotalBytes - status.DoneBytes
				if smoothedSpeed > 1024 && remainingBytes > 0 {
					status.EtaSeconds = int64(float64(remainingBytes) / smoothedSpeed)
				}
				status.mu.Unlock()

				processedSinceSave++
				if processedSinceSave%20 == 0 {
					_ = status.Save()
				}
				if onProgress != nil {
					onProgress(status)
				}
				continue
			} else {
				// File exists locally, but size does not match (was stopped/interrupted previously).
				// Delete the incomplete partial file first, then re-copy!
				_ = os.Remove(item.LocalPath)
			}
		}

		// Ensure any corrupted or partial remnant is removed before pulling
		if fi, err := os.Stat(item.LocalPath); err == nil && (fi.Size() != item.Size || item.Status != FileDone) {
			_ = os.Remove(item.LocalPath)
		}

		item.Status = FileRunning
		status.mu.Lock()
		status.CurrentFile = item.RemotePath
		status.CurrentFileSize = item.Size
		status.CurrentFileDone = 0
		status.mu.Unlock()
		if onProgress != nil {
			onProgress(status)
		}

		// Live in-flight monitor ticker: polls localPath size every 400ms while ADB pulls
		stopMonitor := make(chan struct{})

		go func() {
			ticker := time.NewTicker(400 * time.Millisecond)
			defer ticker.Stop()
			for {
				select {
				case <-stopMonitor:
					return
				case <-ticker.C:
					if fi, err := os.Stat(item.LocalPath); err == nil {
						sz := fi.Size()
						status.mu.Lock()
						status.CurrentFileDone = sz
						status.mu.Unlock()

						sampleSpeed(sz)
						if onProgress != nil {
							onProgress(status)
						}
					}
				}
			}
		}()

		// Pull file via ADB (using CREATE_NO_WINDOW so ZERO terminal flash!)
		var pullErr error
		for attempt := 1; attempt <= 3; attempt++ {
			pullErr = PullFile(ctx, status.ActiveSerial, item.RemotePath, item.LocalPath)
			if pullErr == nil || ctx.Err() != nil {
				break
			}
			time.Sleep(1 * time.Second)
		}

		close(stopMonitor)

		if ctx.Err() != nil {
			_ = os.Remove(item.LocalPath)
			status.Status = StatusPaused
			item.Status = FilePending
			status.CurrentFile = ""
			status.CurrentFileSize = 0
			status.CurrentFileDone = 0
			status.SpeedBps = 0
			status.EtaSeconds = 0
			_ = status.Save()
			if onProgress != nil {
				onProgress(status)
			}
			return ctx.Err()
		}

		if pullErr != nil {
			_ = os.Remove(item.LocalPath) // Clean up any partial bytes if pull failed
			item.Status = FileFailed
			item.Error = pullErr.Error()
			item.Integrity = "failed"
			status.FailedFiles++
			status.AppendLog("failed", item.RemotePath, item.Size, pullErr.Error())
			fmt.Printf("\n✗ Failed: %s: %v\n", item.RemotePath, pullErr)
		} else {
			item.Status = FileDone
			item.Integrity = "verified"
			status.DoneFiles++
			status.DoneBytes += item.Size
			sessionNetBytes += item.Size
			status.CurrentFileDone = item.Size
			status.AppendLog("done", item.RemotePath, item.Size, "")
		}

		sampleSpeed(0)
		processedSinceSave++
		if processedSinceSave%10 == 0 || idx == len(status.Files)-1 {
			_ = status.Save()
		}
		if onProgress != nil {
			onProgress(status)
		}
	}

	status.CurrentFile = ""
	status.CurrentFileSize = 0
	status.CurrentFileDone = 0
	status.SpeedBps = 0
	status.EtaSeconds = 0
	status.Status = StatusCompleted
	_ = status.Save()
	if onProgress != nil {
		onProgress(status)
	}

	return nil
}
