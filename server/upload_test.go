package main

import (
	"bytes"
	"encoding/json"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func newUploadRequest(t *testing.T, token string, fields map[string]string, fileName string, data []byte) *http.Request {
	t.Helper()
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	for _, k := range []string{"album", "tags", "takenAt", "fileName"} {
		if v, ok := fields[k]; ok {
			_ = mw.WriteField(k, v)
		}
	}
	fw, _ := mw.CreateFormFile("file", fileName)
	_, _ = fw.Write(data)
	_ = mw.Close()
	req := httptest.NewRequest(http.MethodPost, "/api/upload", &body)
	req.Header.Set("Content-Type", mw.FormDataContentType())
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	return req
}

func TestUploadRejectsBadToken(t *testing.T) {
	u := &UploadServer{Root: NewRootDir(t.TempDir()), Token: "secret-token-123456"}
	for _, tok := range []string{"", "wrong"} {
		rec := httptest.NewRecorder()
		u.Handler().ServeHTTP(rec, newUploadRequest(t, tok, nil, "a.jpg", []byte("x")))
		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("token %q: got %d, want 401", tok, rec.Code)
		}
	}
}

func TestUploadStoresFileMetaAndMtime(t *testing.T) {
	root := t.TempDir()
	u := &UploadServer{Root: NewRootDir(root), Token: "secret-token-123456"}
	taken := time.Date(2021, 7, 4, 10, 30, 0, 0, time.UTC)
	fields := map[string]string{"album": "Goa Trip", "tags": "beach, family ,", "takenAt": taken.Format(time.RFC3339), "fileName": "IMG_1.jpg"}

	rec := httptest.NewRecorder()
	u.Handler().ServeHTTP(rec, newUploadRequest(t, u.Token, fields, "ignored.jpg", []byte("photo-bytes")))
	if rec.Code != http.StatusOK {
		t.Fatalf("got %d: %s", rec.Code, rec.Body.String())
	}

	target := filepath.Join(root, "Goa Trip", "IMG_1.jpg")
	fi, err := os.Stat(target)
	if err != nil {
		t.Fatal(err)
	}
	if !fi.ModTime().Equal(taken) {
		t.Errorf("mtime = %v, want %v", fi.ModTime().UTC(), taken)
	}
	var meta PhotoMeta
	b, err := os.ReadFile(filepath.Join(root, "Goa Trip", sidecarDirName, "IMG_1.jpg.json"))
	if err != nil {
		t.Fatal(err)
	}
	_ = json.Unmarshal(b, &meta)
	if len(meta.Tags) != 2 || meta.Tags[0] != "beach" || meta.Tags[1] != "family" {
		t.Errorf("tags = %v", meta.Tags)
	}

	// Same name + same size is a retry: skipped. Different content gets a suffix.
	rec = httptest.NewRecorder()
	u.Handler().ServeHTTP(rec, newUploadRequest(t, u.Token, fields, "x.jpg", []byte("photo-bytes")))
	var res uploadResult
	_ = json.Unmarshal(rec.Body.Bytes(), &res)
	if !res.Skipped || res.FileName != "IMG_1.jpg" {
		t.Errorf("retry result = %+v", res)
	}
	rec = httptest.NewRecorder()
	u.Handler().ServeHTTP(rec, newUploadRequest(t, u.Token, fields, "x.jpg", []byte("other-bytes!")))
	_ = json.Unmarshal(rec.Body.Bytes(), &res)
	if res.Skipped || res.FileName != "IMG_1 (1).jpg" {
		t.Errorf("collision result = %+v", res)
	}
}

func TestSanitizeName(t *testing.T) {
	cases := map[string]string{
		"../../etc/passwd": "passwd",
		"..":               "fb",
		"a:b*c?.jpg":       "a_b_c_.jpg",
		"CON.txt":          "fb",
		"Trip.  ":          "Trip",
		`C:\Users\x\y.png`: "y.png",
		"":                 "fb",
	}
	for in, want := range cases {
		if got := sanitizeName(in, "fb"); got != want {
			t.Errorf("sanitizeName(%q) = %q, want %q", in, got, want)
		}
	}
}
