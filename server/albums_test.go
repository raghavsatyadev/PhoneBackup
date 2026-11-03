package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestAlbumsNewestFirstWithoutHiddenFolders(t *testing.T) {
	root := t.TempDir()
	now := time.Now()
	for i, name := range []string{"Old Trip", "Diwali", organizedDirName, ".phonebackup"} {
		dir := filepath.Join(root, name)
		os.MkdirAll(dir, 0o755)
		at := now.Add(-time.Duration(10-i) * time.Hour)
		os.Chtimes(dir, at, at)
	}
	os.WriteFile(filepath.Join(root, "categories.json"), []byte("[]"), 0o644)

	u := &UploadServer{Root: NewRootDir(root), Token: "secret-token-123456"}
	req := httptest.NewRequest(http.MethodGet, "/api/upload/albums", nil)
	req.Header.Set("Authorization", "Bearer secret-token-123456")
	rec := httptest.NewRecorder()
	u.auth(u.handleAlbums)(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("got %d", rec.Code)
	}
	var got struct{ Albums []string }
	json.Unmarshal(rec.Body.Bytes(), &got)
	if len(got.Albums) != 2 || got.Albums[0] != "Diwali" || got.Albums[1] != "Old Trip" {
		t.Fatalf("albums = %v, want [Diwali Old Trip]", got.Albums)
	}
}
