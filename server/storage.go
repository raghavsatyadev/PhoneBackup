package main

// Where uploaded photos are saved, and changing that from the PC.
//
// The folder is kept in <config>/phonebackup/upload_dir so it survives restarts; an
// explicit `server -upload-dir` wins for that run. Phones hold the same token as the
// desktop viewer, so a change is allowed only when the request comes from this machine.

import (
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
)

// RootDir is the upload folder, shared by uploads, the gallery, grouping and vision.
type RootDir struct {
	mu   sync.RWMutex
	path string
}

func NewRootDir(path string) *RootDir { return &RootDir{path: path} }

func (d *RootDir) Get() string {
	d.mu.RLock()
	defer d.mu.RUnlock()
	return d.path
}

func (d *RootDir) Set(path string) {
	d.mu.Lock()
	d.path = path
	d.mu.Unlock()
}

// DefaultUploadRoot is the folder chosen from the PC app, else D:\PhoneBackup on Windows
// (<home>/Pictures/PhoneBackup elsewhere).
func DefaultUploadRoot() string {
	if p := savedUploadDir(); p != "" {
		return p
	}
	if runtime.GOOS == "windows" {
		return `D:\PhoneBackup`
	}
	if home, err := os.UserHomeDir(); err == nil {
		return filepath.Join(home, "Pictures", "PhoneBackup")
	}
	return "PhoneBackupUploads"
}

func uploadDirSetting() (string, error) {
	dir, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, "phonebackup", "upload_dir"), nil
}

func savedUploadDir() string {
	path, err := uploadDirSetting()
	if err != nil {
		return ""
	}
	b, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(b))
}

func saveUploadDir(dir string) error {
	path, err := uploadDirSetting()
	if err != nil {
		return err
	}
	if err = os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	return os.WriteFile(path, []byte(dir), 0o600)
}

// prepareUploadDir checks the folder is absolute, creates it and makes sure it is writable.
func prepareUploadDir(dir string) (string, error) {
	dir = strings.Trim(strings.TrimSpace(dir), `"`)
	if dir == "" || !filepath.IsAbs(dir) {
		return "", errors.New("Use a full folder path, like D:\\PhoneBackup.")
	}
	dir = filepath.Clean(dir)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", errors.New("Cannot create this folder: " + err.Error())
	}
	f, err := os.CreateTemp(dir, ".write-test-*")
	if err != nil {
		return "", errors.New("Cannot save files in this folder: " + err.Error())
	}
	f.Close()
	os.Remove(f.Name())
	return dir, nil
}

// fromSameMachine is true for loopback, or when the caller used one of this PC's own
// addresses as its source (the desktop viewer pointed at the PC's LAN address).
func fromSameMachine(r *http.Request) bool {
	ip := net.ParseIP(remoteIP(r))
	if ip == nil {
		return false
	}
	if ip.IsLoopback() {
		return true
	}
	if local, ok := r.Context().Value(http.LocalAddrContextKey).(net.Addr); ok {
		if h, _, err := net.SplitHostPort(local.String()); err == nil {
			return net.ParseIP(h).Equal(ip)
		}
	}
	return false
}

type folderInfo struct {
	Path      string `json:"path"`
	CanChange bool   `json:"canChange"`
}

// handleFolder is GET (where photos go) and POST {path} (change it, this PC only).
func (u *UploadServer) handleFolder(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodGet {
		writeUploadJSON(w, http.StatusOK, folderInfo{u.Root.Get(), fromSameMachine(r)})
		return
	}
	if r.Method != http.MethodPost {
		writeUploadJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "GET or POST only"})
		return
	}
	if !fromSameMachine(r) {
		writeUploadJSON(w, http.StatusForbidden, map[string]string{"error": "The photo folder can only be changed on the PC itself."})
		return
	}
	var body struct {
		Path string `json:"path"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 8<<10)).Decode(&body); err != nil {
		writeUploadJSON(w, http.StatusBadRequest, map[string]string{"error": "bad request"})
		return
	}
	dir, err := prepareUploadDir(body.Path)
	if err != nil {
		writeUploadJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	if err = saveUploadDir(dir); err != nil {
		writeUploadJSON(w, http.StatusInternalServerError, map[string]string{"error": "Cannot save the setting: " + err.Error()})
		return
	}
	if dir != u.Root.Get() {
		u.Root.Set(dir)
		if u.Groups != nil {
			go u.Groups.Rebuild()
		}
		if u.Vision != nil {
			go u.Vision.backfill()
		}
	}
	writeUploadJSON(w, http.StatusOK, folderInfo{dir, true})
}
