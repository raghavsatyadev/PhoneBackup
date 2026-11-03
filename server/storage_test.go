package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
)

func folderRequest(t *testing.T, u *UploadServer, remote, body string) *httptest.ResponseRecorder {
	t.Helper()
	method := http.MethodGet
	if body != "" {
		method = http.MethodPost
	}
	req := httptest.NewRequest(method, "/api/upload/folder", strings.NewReader(body))
	req.RemoteAddr = remote
	req.Header.Set("Authorization", "Bearer secret-token-123456")
	rec := httptest.NewRecorder()
	u.Handler().ServeHTTP(rec, req)
	return rec
}

func TestFolderChangeOnlyFromThisPC(t *testing.T) {
	cfg := t.TempDir()
	t.Setenv("AppData", cfg)         // os.UserConfigDir on Windows
	t.Setenv("XDG_CONFIG_HOME", cfg) // and elsewhere
	old := t.TempDir()
	u := &UploadServer{Root: NewRootDir(old), Token: "secret-token-123456"}
	next := filepath.Join(t.TempDir(), "New Photos")
	body, _ := json.Marshal(map[string]string{"path": next})

	// A phone on the LAN with a valid token may read but not change it.
	rec := folderRequest(t, u, "192.168.1.50:4000", "")
	var info folderInfo
	_ = json.Unmarshal(rec.Body.Bytes(), &info)
	if rec.Code != http.StatusOK || info.Path != old || info.CanChange {
		t.Fatalf("phone GET: %d %+v", rec.Code, info)
	}
	if rec = folderRequest(t, u, "192.168.1.50:4000", string(body)); rec.Code != http.StatusForbidden {
		t.Fatalf("phone POST: want 403, got %d", rec.Code)
	}
	if u.Root.Get() != old {
		t.Fatal("phone changed the folder")
	}

	// Relative paths are refused.
	if rec = folderRequest(t, u, "127.0.0.1:4000", `{"path":"photos"}`); rec.Code != http.StatusBadRequest {
		t.Fatalf("relative path: want 400, got %d", rec.Code)
	}

	// This PC can change it; the folder is created and remembered.
	if rec = folderRequest(t, u, "127.0.0.1:4000", string(body)); rec.Code != http.StatusOK {
		t.Fatalf("local POST: %d %s", rec.Code, rec.Body)
	}
	if u.Root.Get() != next || savedUploadDir() != next || DefaultUploadRoot() != next {
		t.Fatalf("root %q saved %q", u.Root.Get(), savedUploadDir())
	}
}
