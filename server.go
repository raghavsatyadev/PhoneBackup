package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// WSHub manages active WebSocket clients.
type WSHub struct {
	clients map[*websocket.Conn]bool
	mu      sync.Mutex
}

var upgrader = websocket.Upgrader{
	CheckOrigin: func(r *http.Request) bool {
		return true // Allow all origins for local UI
	},
}

func NewWSHub() *WSHub {
	return &WSHub{
		clients: make(map[*websocket.Conn]bool),
	}
}

func (h *WSHub) Add(conn *websocket.Conn) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.clients[conn] = true
}

func (h *WSHub) Remove(conn *websocket.Conn) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if _, ok := h.clients[conn]; ok {
		delete(h.clients, conn)
		_ = conn.Close()
	}
}

func (h *WSHub) Broadcast(data interface{}) {
	h.mu.Lock()
	defer h.mu.Unlock()

	raw, err := json.Marshal(data)
	if err != nil {
		return
	}

	for conn := range h.clients {
		err := conn.WriteMessage(websocket.TextMessage, raw)
		if err != nil {
			_ = conn.Close()
			delete(h.clients, conn)
		}
	}
}

// Global server state
var (
	wsHub           = NewWSHub()
	activeTransfers = make(map[string]*BackupStatus)
	transfersMu     sync.RWMutex
	cancelFuncs     = make(map[string]func())
	cancelMu        sync.Mutex
)

// RegisterTransfer registers a transfer in memory and broadcasts its initial state.
func RegisterTransfer(status *BackupStatus) {
	transfersMu.Lock()
	canonicalLocal := filepath.Clean(status.LocalDir)
	for existingID, existingStatus := range activeTransfers {
		if existingID != status.ID && filepath.Clean(existingStatus.LocalDir) == canonicalLocal {
			delete(activeTransfers, existingID)
		}
	}
	activeTransfers[status.ID] = status
	transfersMu.Unlock()

	wsHub.Broadcast(map[string]interface{}{
		"type":     "transfer_update",
		"transfer": status,
	})
}

// RegisterCancelFunc registers a context cancel func for a transfer ID.
func RegisterCancelFunc(id string, cancel func()) {
	cancelMu.Lock()
	defer cancelMu.Unlock()
	cancelFuncs[id] = cancel
}

// UnregisterCancelFunc unregisters the cancel func for a transfer ID.
func UnregisterCancelFunc(id string) {
	cancelMu.Lock()
	defer cancelMu.Unlock()
	delete(cancelFuncs, id)
}

// BroadcastTransferUpdate sends live progress over WebSocket.
func BroadcastTransferUpdate(status *BackupStatus) {
	wsHub.Broadcast(map[string]interface{}{
		"type":     "transfer_update",
		"transfer": status,
	})
}

// SetupHTTPServer sets up the routing for the Go Web Dashboard.
func SetupHTTPServer(publicDir string) http.Handler {
	mux := http.NewServeMux()

	// ── WebSocket ────────────────────────────────────────────────────────────
	mux.HandleFunc("/ws", func(w http.ResponseWriter, r *http.Request) {
		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		wsHub.Add(conn)
		defer wsHub.Remove(conn)

		// Read loop to keep alive and detect disconnect
		for {
			_, _, err := conn.ReadMessage()
			if err != nil {
				break
			}
		}
	})

	// ── API Routes: Devices ──────────────────────────────────────────────────
	mux.HandleFunc("/api/devices", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		devices, err := ListDevices()
		if err != nil {
			http.Error(w, fmt.Sprintf(`{"error":%q}`, err.Error()), 500)
			return
		}
		_ = json.NewEncoder(w).Encode(devices)
	})

	connectHandler := func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		var body struct {
			Address string `json:"address"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		addr := strings.TrimSpace(body.Address)
		if addr == "" {
			addr = strings.TrimSpace(r.URL.Query().Get("address"))
		}
		if addr == "" {
			_ = r.ParseForm()
			addr = strings.TrimSpace(r.FormValue("address"))
		}
		if addr == "" {
			w.WriteHeader(http.StatusBadRequest)
			_ = json.NewEncoder(w).Encode(map[string]string{"error": "address required"})
			return
		}
		out, err := ConnectWifi(addr)
		if err != nil {
			w.WriteHeader(http.StatusInternalServerError)
			_ = json.NewEncoder(w).Encode(map[string]string{"error": err.Error()})
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]string{"message": out})
	}
	mux.HandleFunc("/api/devices/connect", connectHandler)
	mux.HandleFunc("/api/device/connect-wifi", connectHandler)

	disconnectHandler := func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		var body struct {
			Address string `json:"address"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		addr := strings.TrimSpace(body.Address)
		if addr == "" {
			addr = strings.TrimSpace(r.URL.Query().Get("address"))
		}
		if addr == "" {
			_ = r.ParseForm()
			addr = strings.TrimSpace(r.FormValue("address"))
		}
		if addr == "" {
			w.WriteHeader(http.StatusBadRequest)
			_ = json.NewEncoder(w).Encode(map[string]string{"error": "address required"})
			return
		}
		out, err := DisconnectWifi(addr)
		if err != nil {
			w.WriteHeader(http.StatusInternalServerError)
			_ = json.NewEncoder(w).Encode(map[string]string{"error": err.Error()})
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]string{"message": out})
	}
	mux.HandleFunc("/api/devices/disconnect", disconnectHandler)
	mux.HandleFunc("/api/device/disconnect-wifi", disconnectHandler)

	// ── API Routes: Device Browse ────────────────────────────────────────────
	browseHandler := func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		serial := r.URL.Query().Get("serial")
		dirPath := r.URL.Query().Get("path")
		if serial == "" {
			http.Error(w, `{"error":"serial required"}`, 400)
			return
		}
		if dirPath == "" {
			dirPath = "/sdcard"
		}
		entries, err := BrowseDir(serial, dirPath)
		if err != nil {
			http.Error(w, fmt.Sprintf(`{"error":%q}`, err.Error()), 500)
			return
		}
		_ = json.NewEncoder(w).Encode(entries)
	}
	mux.HandleFunc("/api/browse", browseHandler)
	mux.HandleFunc("/api/device/browse", browseHandler)

	// ── API Routes: Local Browse ─────────────────────────────────────────────
	mux.HandleFunc("/api/local/browse", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		dirPath := r.URL.Query().Get("path")

		if dirPath == "" {
			// List drive letters on Windows
			var roots []map[string]interface{}
			for r := 'A'; r <= 'Z'; r++ {
				drive := fmt.Sprintf("%c:\\", r)
				if _, err := os.Stat(drive); err == nil {
					roots = append(roots, map[string]interface{}{
						"name":  drive,
						"path":  drive,
						"isDir": true,
						"size":  0,
					})
				}
			}
			_ = json.NewEncoder(w).Encode(roots)
			return
		}

		entries, err := os.ReadDir(dirPath)
		if err != nil {
			http.Error(w, fmt.Sprintf(`{"error":%q}`, err.Error()), 500)
			return
		}

		var result []map[string]interface{}
		for _, e := range entries {
			info, _ := e.Info()
			sz := int64(0)
			if info != nil {
				sz = info.Size()
			}
			result = append(result, map[string]interface{}{
				"name":  e.Name(),
				"path":  filepath.Join(dirPath, e.Name()),
				"isDir": e.IsDir(),
				"size":  sz,
			})
		}

		sort.Slice(result, func(i, j int) bool {
			isDirI := result[i]["isDir"].(bool)
			isDirJ := result[j]["isDir"].(bool)
			if isDirI != isDirJ {
				return isDirI
			}
			return strings.ToLower(result[i]["name"].(string)) < strings.ToLower(result[j]["name"].(string))
		})

		_ = json.NewEncoder(w).Encode(result)
	})

	// ── API Routes: Destination Setting ──────────────────────────────────────
	mux.HandleFunc("/api/local/set-destination", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		var body struct {
			Path string `json:"path"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		destPath := strings.TrimSpace(body.Path)
		if destPath == "" {
			destPath = strings.TrimSpace(r.URL.Query().Get("path"))
		}
		if destPath == "" {
			_ = r.ParseForm()
			destPath = strings.TrimSpace(r.FormValue("path"))
		}
		if destPath == "" {
			w.WriteHeader(http.StatusBadRequest)
			_ = json.NewEncoder(w).Encode(map[string]string{"error": "path required"})
			return
		}

		resolved, err := filepath.Abs(destPath)
		if err != nil {
			http.Error(w, fmt.Sprintf(`{"error":%q}`, err.Error()), 400)
			return
		}

		created := false
		isStatusFile := false
		statusFilePath := ""

		if fi, err := os.Stat(resolved); err == nil && !fi.IsDir() && strings.HasSuffix(strings.ToLower(resolved), ".json") {
			statusFilePath = resolved
			parent := filepath.Dir(resolved)
			if strings.EqualFold(filepath.Base(parent), "logs") {
				resolved = filepath.Dir(parent)
			} else {
				resolved = parent
			}
		} else {
			if _, err := os.Stat(resolved); os.IsNotExist(err) {
				_ = os.MkdirAll(resolved, 0755)
				created = true
			}
			// Check if resolved itself is "logs" folder
			if strings.EqualFold(filepath.Base(resolved), "logs") {
				candidate := filepath.Join(resolved, "phonebackup_status.json")
				if _, err := os.Stat(candidate); err == nil {
					statusFilePath = candidate
					resolved = filepath.Dir(resolved)
				}
			}
			if statusFilePath == "" {
				logsStatus := filepath.Join(resolved, "logs", "phonebackup_status.json")
				if _, err := os.Stat(logsStatus); err == nil {
					statusFilePath = logsStatus
				} else {
					rootStatus := filepath.Join(resolved, "phonebackup_status.json")
					if _, err := os.Stat(rootStatus); err == nil {
						statusFilePath = rootStatus
					}
				}
			}
		}

		var statusInfo map[string]interface{}
		var parsedStatus *BackupStatus
		if statusFilePath != "" {
			if st, err := LoadStatusFile(statusFilePath); err == nil {
				parsedStatus = st
				isStatusFile = true
				if st.Status == StatusRunning {
					st.Status = StatusPaused
				}
				RegisterTransfer(st)
				if st.LocalDir != "" {
					resolved = st.LocalDir
				}
				statusInfo = map[string]interface{}{
					"id":          st.ID,
					"sources":     st.Sources,
					"localDir":    st.LocalDir,
					"local_dir":   st.LocalDir,
					"status":      st.Status,
					"doneFiles":   st.DoneFiles,
					"done_files":  st.DoneFiles,
					"totalFiles":  st.TotalFiles,
					"total_files": st.TotalFiles,
					"totalBytes":  st.TotalBytes,
					"total_bytes": st.TotalBytes,
					"doneBytes":   st.DoneBytes,
					"done_bytes":  st.DoneBytes,
					"updatedAt":   st.UpdatedAt,
					"updated_at":  st.UpdatedAt,
				}
			}
		}

		resMap := map[string]interface{}{
			"success":      true,
			"path":         resolved,
			"local_dir":    resolved,
			"localDir":     resolved,
			"created":      created,
			"hasStatus":    statusInfo != nil,
			"statusInfo":   statusInfo,
			"isStatusFile": isStatusFile,
		}
		if isStatusFile && parsedStatus != nil {
			resMap["id"] = parsedStatus.ID
			resMap["sources"] = parsedStatus.Sources
			resMap["local_dir"] = parsedStatus.LocalDir
			resMap["localDir"] = parsedStatus.LocalDir
			resMap["filters"] = parsedStatus.Filters
			resMap["active_serial"] = parsedStatus.ActiveSerial
			resMap["deviceSerial"] = parsedStatus.ActiveSerial
			resMap["known_serials"] = parsedStatus.DeviceSerials
			resMap["deviceSerials"] = parsedStatus.DeviceSerials
			resMap["device_model"] = parsedStatus.DeviceModel
			resMap["deviceModel"] = parsedStatus.DeviceModel
			resMap["transfer"] = parsedStatus.ToSummary()
		}

		_ = json.NewEncoder(w).Encode(resMap)
	})

	// ── API Routes: Transfer Management ──────────────────────────────────────
	mux.HandleFunc("/api/transfer/list", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		transfersMu.RLock()
		defer transfersMu.RUnlock()

		var list []map[string]interface{}
		for _, t := range activeTransfers {
			list = append(list, t.ToSummary())
		}
		_ = json.NewEncoder(w).Encode(list)
	})

	mux.HandleFunc("/api/transfer/import", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		var body struct {
			FilePath   string        `json:"filePath"`
			StatusData *BackupStatus `json:"statusData"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)

		// PowerShell query string fallback
		if body.FilePath == "" {
			body.FilePath = strings.TrimSpace(r.URL.Query().Get("filePath"))
		}

		var status *BackupStatus
		var err error

		if body.StatusData != nil && body.StatusData.LocalDir != "" {
			status = body.StatusData
			// Hydrate catalog if not present in uploaded status
			if len(status.Files) == 0 {
				candidates := []string{
					filepath.Join(status.LocalDir, "logs", "files_catalog.json"),
					filepath.Join(status.LocalDir, "files_catalog.json"),
				}
				for _, cp := range candidates {
					if rawCatalog, err := os.ReadFile(cp); err == nil {
						var files []*FileItem
						if err := json.Unmarshal(rawCatalog, &files); err == nil && len(files) > 0 {
							status.Files = files
							status.CatalogFile = cp
							break
						}
					}
				}
			}
		} else if body.FilePath != "" {
			status, err = LoadStatusFile(body.FilePath)
			if err != nil {
				http.Error(w, fmt.Sprintf(`{"error":"Failed to parse status file: %v"}`, err), 400)
				return
			}
		} else {
			http.Error(w, `{"error":"filePath or statusData required"}`, 400)
			return
		}

		if status.Status == StatusRunning {
			status.Status = StatusPaused
		}
		RegisterTransfer(status)
		status.LogUserAction(fmt.Sprintf("User imported backup status on Dashboard from '%s' (%d files in catalog)", body.FilePath, status.TotalFiles))

		_ = json.NewEncoder(w).Encode(map[string]interface{}{
			"success":       true,
			"id":            status.ID,
			"transfer":      status.ToSummary(),
			"sources":       status.Sources,
			"local_dir":     status.LocalDir,
			"localDir":      status.LocalDir,
			"filters":       status.Filters,
			"active_serial": status.ActiveSerial,
			"deviceSerial":  status.ActiveSerial,
			"known_serials": status.DeviceSerials,
			"deviceSerials": status.DeviceSerials,
			"device_model":  status.DeviceModel,
			"deviceModel":   status.DeviceModel,
		})
	})

	mux.HandleFunc("/api/transfer/resume/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		id := strings.TrimPrefix(r.URL.Path, "/api/transfer/resume/")
		transfersMu.RLock()
		status, exists := activeTransfers[id]
		transfersMu.RUnlock()

		if !exists {
			w.WriteHeader(http.StatusNotFound)
			_ = json.NewEncoder(w).Encode(map[string]string{"error": "transfer not found"})
			return
		}

		var body struct {
			ActiveSerial string `json:"active_serial"`
			DeviceSerial string `json:"deviceSerial"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		targetSerial := strings.TrimSpace(body.ActiveSerial)
		if targetSerial == "" {
			targetSerial = strings.TrimSpace(body.DeviceSerial)
		}
		if targetSerial == "" {
			targetSerial = strings.TrimSpace(r.URL.Query().Get("active_serial"))
		}
		if targetSerial == "" {
			targetSerial = strings.TrimSpace(r.URL.Query().Get("deviceSerial"))
		}

		if targetSerial != "" && targetSerial != status.ActiveSerial {
			status.ActiveSerial = targetSerial
			if m := GetDeviceModel(targetSerial); m != "" {
				status.DeviceModel = m
			}
			status.AddDeviceSerial(targetSerial)
			_ = status.Save()
		}

		cancelMu.Lock()
		if _, alreadyRunning := cancelFuncs[id]; alreadyRunning {
			cancelMu.Unlock()
			status.Status = StatusRunning
			_ = json.NewEncoder(w).Encode(status)
			return
		}

		// Conflict prevention: check if another transfer is already using this destination or device
		cleanLocal := filepath.Clean(status.LocalDir)
		transfersMu.RLock()
		for otherID, existing := range activeTransfers {
			if otherID != id && (existing.Status == StatusRunning || existing.Status == StatusScanning) {
				if filepath.Clean(existing.LocalDir) == cleanLocal {
					transfersMu.RUnlock()
					cancelMu.Unlock()
					http.Error(w, fmt.Sprintf(`{"error":"Destination '%s' is already being backed up by transfer #%s"}`, status.LocalDir, otherID), 409)
					return
				}
				if existing.ActiveSerial != "" && existing.ActiveSerial == status.ActiveSerial {
					transfersMu.RUnlock()
					cancelMu.Unlock()
					http.Error(w, fmt.Sprintf(`{"error":"Device [%s] is already active in transfer #%s. Pause it first."}`, status.ActiveSerial, otherID), 409)
					return
				}
			}
		}
		transfersMu.RUnlock()

		ctx, cancel := context.WithCancel(context.Background())
		cancelFuncs[id] = cancel
		cancelMu.Unlock()

		status.Status = StatusRunning
		status.LogUserAction(fmt.Sprintf("User clicked Resume on Dashboard (target device: %s, model: %s)", status.ActiveSerial, status.DeviceModel))
		_ = status.Save()
		BroadcastTransferUpdate(status)

		go func() {
			_ = RunTransferEngine(ctx, status, func(s *BackupStatus) {
				BroadcastTransferUpdate(s)
			})
			cancelMu.Lock()
			delete(cancelFuncs, id)
			cancelMu.Unlock()
			BroadcastTransferUpdate(status)
		}()

		_ = json.NewEncoder(w).Encode(status)
	})

	mux.HandleFunc("/api/transfer/retry-failed/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		id := strings.TrimPrefix(r.URL.Path, "/api/transfer/retry-failed/")
		transfersMu.RLock()
		status, exists := activeTransfers[id]
		transfersMu.RUnlock()

		if !exists {
			http.Error(w, `{"error":"transfer not found"}`, 404)
			return
		}

		var body struct {
			ActiveSerial string `json:"active_serial"`
			DeviceSerial string `json:"deviceSerial"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		targetSerial := strings.TrimSpace(body.ActiveSerial)
		if targetSerial == "" {
			targetSerial = strings.TrimSpace(body.DeviceSerial)
		}
		if targetSerial != "" && targetSerial != status.ActiveSerial {
			status.ActiveSerial = targetSerial
			if m := GetDeviceModel(targetSerial); m != "" {
				status.DeviceModel = m
			}
			status.AddDeviceSerial(targetSerial)
			_ = status.Save()
		}

		retriedCount := status.RetryFailed()
		status.LogUserAction(fmt.Sprintf("User clicked Retry Failed on Dashboard (%d files re-queued)", retriedCount))
		_ = status.Save()
		BroadcastTransferUpdate(status)

		// If engine not already running, kick it off
		cancelMu.Lock()
		_, isRunning := cancelFuncs[id]
		if !isRunning && retriedCount > 0 {
			ctx, cancel := context.WithCancel(context.Background())
			cancelFuncs[id] = cancel
			status.Status = StatusRunning
			BroadcastTransferUpdate(status)
			go func() {
				_ = RunTransferEngine(ctx, status, func(s *BackupStatus) {
					BroadcastTransferUpdate(s)
				})
				cancelMu.Lock()
				delete(cancelFuncs, id)
				cancelMu.Unlock()
				BroadcastTransferUpdate(status)
			}()
		}
		cancelMu.Unlock()

		_ = json.NewEncoder(w).Encode(map[string]interface{}{
			"success":      true,
			"retriedCount": retriedCount,
			"transfer":     status,
		})
	})

	mux.HandleFunc("/api/transfer/pause/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		id := strings.TrimPrefix(r.URL.Path, "/api/transfer/pause/")

		cancelMu.Lock()
		if cancel, ok := cancelFuncs[id]; ok {
			cancel()
			delete(cancelFuncs, id)
		}
		cancelMu.Unlock()

		transfersMu.RLock()
		status, exists := activeTransfers[id]
		transfersMu.RUnlock()

		if exists {
			status.Status = StatusPaused
			status.CurrentFile = ""
			status.CurrentFileSize = 0
			status.CurrentFileDone = 0
			status.SpeedBps = 0
			status.EtaSeconds = 0
			status.LogUserAction(fmt.Sprintf("User clicked Pause on Dashboard at %d/%d files (%s/%s processed)", status.DoneFiles+status.SkippedFiles, status.TotalFiles, FormatBytes(status.DoneBytes), FormatBytes(status.TotalBytes)))
			_ = status.Save()
			BroadcastTransferUpdate(status)
		}
		_ = json.NewEncoder(w).Encode(status)
	})

	mux.HandleFunc("/api/transfer/start", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		var body struct {
			ActiveSerial string       `json:"active_serial"`
			DeviceSerial string       `json:"deviceSerial"`
			RemoteDirs   []string     `json:"remote_dirs"`
			RemoteDir    string       `json:"remote_dir"`
			Sources      []string     `json:"sources"`
			LocalDir     string       `json:"local_dir"`
			LegacyLocal  string       `json:"localDir"`
			Filters      BackupFilter `json:"filters"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)

		targetSerial := strings.TrimSpace(body.ActiveSerial)
		if targetSerial == "" {
			targetSerial = strings.TrimSpace(body.DeviceSerial)
		}
		localDir := strings.TrimSpace(body.LocalDir)
		if localDir == "" {
			localDir = strings.TrimSpace(body.LegacyLocal)
		}

		sources := body.RemoteDirs
		if len(sources) == 0 {
			sources = body.Sources
		}
		if len(sources) == 0 && body.RemoteDir != "" {
			sources = strings.Split(body.RemoteDir, ",")
		}

		if targetSerial == "" || len(sources) == 0 || localDir == "" {
			http.Error(w, `{"error":"active_serial, sources, and local_dir required"}`, 400)
			return
		}

		cleanLocal := filepath.Clean(localDir)

		// 1. Conflict Check: check if another transfer is already running for this directory or device
		transfersMu.RLock()
		for _, existing := range activeTransfers {
			if existing.Status == StatusRunning || existing.Status == StatusScanning {
				if filepath.Clean(existing.LocalDir) == cleanLocal {
					transfersMu.RUnlock()
					http.Error(w, fmt.Sprintf(`{"error":"A backup transfer for '%s' is already in progress (#%s)"}`, localDir, existing.ID), 409)
					return
				}
				if existing.ActiveSerial != "" && existing.ActiveSerial == targetSerial {
					transfersMu.RUnlock()
					http.Error(w, fmt.Sprintf(`{"error":"Device [%s] is already busy with running transfer #%s. Pause it first."}`, targetSerial, existing.ID), 409)
					return
				}
			}
		}
		transfersMu.RUnlock()

		// 2. Reuse Check: does a paused transfer or status file already exist for this folder?
		var status *BackupStatus
		transfersMu.RLock()
		for _, existing := range activeTransfers {
			if filepath.Clean(existing.LocalDir) == cleanLocal {
				status = existing
				break
			}
		}
		transfersMu.RUnlock()

		if status == nil {
			if existingStatus, err := LoadStatusFile(localDir); err == nil && len(existingStatus.Files) > 0 {
				status = existingStatus
			}
		}

		if status != nil {
			// Update device serial & filters if changed
			status.ActiveSerial = targetSerial
			status.AddDeviceSerial(targetSerial)
			if len(body.Filters.Include) > 0 {
				status.Filters = body.Filters
			}
			status.Status = StatusPending
			_ = status.Save()
		} else {
			id := fmt.Sprintf("%x", time.Now().UnixNano())[:8]
			status = &BackupStatus{
				Version:      1,
				ID:           id,
				ActiveSerial: targetSerial,
				Sources:      sources,
				RemoteDirs:   sources,
				RemoteDir:    strings.Join(sources, ", "),
				LocalDir:     localDir,
				Filters:      body.Filters,
				Status:       StatusPending,
				CreatedAt:    time.Now().UTC().Format(time.RFC3339),
				UpdatedAt:    time.Now().UTC().Format(time.RFC3339),
				Files:        make([]*FileItem, 0),
			}
			status.AddDeviceSerial(targetSerial)
			if m := GetDeviceModel(targetSerial); m != "" {
				status.DeviceModel = m
			}
		}

		RegisterTransfer(status)
		status.LogUserAction(fmt.Sprintf("User initiated backup via Dashboard (device: %s, sources: %v)", status.ActiveSerial, status.Sources))

		ctx, cancel := context.WithCancel(context.Background())
		cancelMu.Lock()
		cancelFuncs[status.ID] = cancel
		cancelMu.Unlock()

		go func() {
			_ = RunTransferEngine(ctx, status, func(s *BackupStatus) {
				BroadcastTransferUpdate(s)
			})
			cancelMu.Lock()
			delete(cancelFuncs, status.ID)
			cancelMu.Unlock()
			BroadcastTransferUpdate(status)
		}()

		_ = json.NewEncoder(w).Encode(status)
	})

	mux.HandleFunc("/api/transfer/cancel/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		id := strings.TrimPrefix(r.URL.Path, "/api/transfer/cancel/")

		cancelMu.Lock()
		if cancel, ok := cancelFuncs[id]; ok {
			cancel()
			delete(cancelFuncs, id)
		}
		cancelMu.Unlock()

		transfersMu.RLock()
		status, exists := activeTransfers[id]
		transfersMu.RUnlock()

		if exists {
			status.Status = StatusCancelled
			status.LogUserAction(fmt.Sprintf("User clicked Cancel via Dashboard at %d/%d files", status.DoneFiles+status.SkippedFiles, status.TotalFiles))
			_ = status.Save()
			BroadcastTransferUpdate(status)
		}
		_ = json.NewEncoder(w).Encode(status)
	})

	mux.HandleFunc("/api/transfer/log-action", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		var body struct {
			ID     string `json:"id"`
			Action string `json:"action"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		if body.ID != "" && body.Action != "" {
			transfersMu.RLock()
			st, ok := activeTransfers[body.ID]
			transfersMu.RUnlock()
			if ok {
				st.LogUserAction(body.Action)
			}
		}
		_ = json.NewEncoder(w).Encode(map[string]bool{"success": true})
	})

	mux.HandleFunc("/api/transfer/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		subPath := strings.TrimPrefix(r.URL.Path, "/api/transfer/")
		parts := strings.Split(subPath, "/")
		id := parts[0]

		transfersMu.RLock()
		status, exists := activeTransfers[id]
		transfersMu.RUnlock()

		if !exists {
			http.Error(w, `{"error":"transfer not found"}`, 404)
			return
		}

		if len(parts) > 1 && parts[1] == "failed" {
			var failed []*FileItem
			for _, f := range status.Files {
				if f.Status == FileFailed {
					failed = append(failed, f)
				}
			}
			_ = json.NewEncoder(w).Encode(map[string]interface{}{
				"total": len(failed),
				"items": failed,
			})
			return
		}

		if len(parts) > 1 && parts[1] == "files" {
			offset, _ := strconv.Atoi(r.URL.Query().Get("offset"))
			limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
			if limit <= 0 {
				limit = 100
			}
			total := len(status.Files)
			if offset > total {
				offset = total
			}
			end := offset + limit
			if end > total {
				end = total
			}
			_ = json.NewEncoder(w).Encode(map[string]interface{}{
				"total": total,
				"items": status.Files[offset:end],
			})
			return
		}

		_ = json.NewEncoder(w).Encode(status)
	})

	// ── Static Files (public/) ───────────────────────────────────────────────
	fileServer := http.FileServer(http.Dir(publicDir))
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		p := filepath.Join(publicDir, filepath.Clean(r.URL.Path))
		if fi, err := os.Stat(p); err == nil && !fi.IsDir() {
			fileServer.ServeHTTP(w, r)
			return
		}
		// Fallback to index.html for SPA
		http.ServeFile(w, r, filepath.Join(publicDir, "index.html"))
	})

	return mux
}

// StartWebServer starts the Go HTTP & WebSocket dashboard server.
func StartWebServer(port int, publicDir string, initialStatus *BackupStatus) {
	if initialStatus != nil {
		RegisterTransfer(initialStatus)
	}

	handler := SetupHTTPServer(publicDir)
	addr := fmt.Sprintf(":%d", port)

	server := &http.Server{
		Addr:    addr,
		Handler: handler,
	}

	go func() {
		_ = server.ListenAndServe()
	}()

	fmt.Println("╔════════════════════════════════════════════════════════════╗")
	fmt.Println("║       🌐 PhoneBackup Go Dashboard Online                  ║")
	fmt.Println("╠════════════════════════════════════════════════════════════╣")
	fmt.Printf("║ Web Dashboard:  http://localhost:%-25d║\n", port)
	fmt.Printf("║ Local Network:  http://127.0.0.1:%-25d║\n", port)
	fmt.Println("╚════════════════════════════════════════════════════════════╝")
}
