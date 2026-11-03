package main

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"image"
	"image/color"
	"image/jpeg"
	"image/png"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// fakeOllama answers /api/chat and records the decoded size of the image it got.
func fakeOllama(t *testing.T, reply string, gotSide *int) *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var req ollamaChatRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil || len(req.Messages) != 1 || len(req.Messages[0].Images) != 1 {
			t.Errorf("bad request: %v", err)
			http.Error(w, "bad", 400)
			return
		}
		raw, _ := base64.StdEncoding.DecodeString(req.Messages[0].Images[0])
		if cfg, _, err := image.DecodeConfig(bytes.NewReader(raw)); err == nil && gotSide != nil {
			*gotSide = max(cfg.Width, cfg.Height)
		}
		json.NewEncoder(w).Encode(map[string]any{"message": map[string]string{"role": "assistant", "content": reply}})
	}))
}

func writePNG(t *testing.T, path string, w, h int) {
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for x := 0; x < w; x++ {
		img.Set(x, h/2, color.RGBA{255, 0, 0, 255})
	}
	f, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	if err := png.Encode(f, img); err != nil {
		t.Fatal(err)
	}
}

// newAlbum makes <root>/Trip with one photo and its upload sidecar.
func newAlbum(t *testing.T, photo string, w, h int) (root, albumDir string) {
	root = t.TempDir()
	albumDir = filepath.Join(root, "Trip")
	os.MkdirAll(filepath.Join(albumDir, sidecarDirName), 0o755)
	if filepath.Ext(photo) == ".png" {
		writePNG(t, filepath.Join(albumDir, photo), w, h)
	} else {
		os.WriteFile(filepath.Join(albumDir, photo), []byte("not an image we read"), 0o644)
	}
	if err := writeSidecar(albumDir, &PhotoMeta{FileName: photo, Album: "Trip", Tags: []string{"mine"}}); err != nil {
		t.Fatal(err)
	}
	return root, albumDir
}

func readVision(t *testing.T, albumDir, photo string) (VisionMeta, bool) {
	b, err := os.ReadFile(visionPath(albumDir, photo))
	if err != nil {
		return VisionMeta{}, false
	}
	var m VisionMeta
	if err := json.Unmarshal(b, &m); err != nil {
		t.Fatal(err)
	}
	return m, true
}

func TestVisionProcessWritesSeparateFileAndShrinks(t *testing.T) {
	side := 0
	srv := fakeOllama(t, "```json\n{\"caption\":\"A beach.\",\"tags\":[\"Beach\",\"sea\",\"beach\"],\"text\":\"\"}\n```", &side)
	defer srv.Close()
	root, albumDir := newAlbum(t, "big.png", 3000, 1500)
	v := NewVisionWorker(root, srv.URL, "test-vl")
	v.process(visionJob{albumDir, "big.png"})

	m, ok := readVision(t, albumDir, "big.png")
	if !ok || m.Caption != "A beach." || len(m.Tags) != 2 || m.Tags[0] != "beach" || m.Model != "test-vl" || m.AnalyzedAt.IsZero() {
		t.Fatalf("unexpected vision result %+v (ok=%v)", m, ok)
	}
	if side != visionMaxSide {
		t.Fatalf("model got a %dpx image, want %d", side, visionMaxSide)
	}
	// The upload sidecar keeps the user's own tags untouched.
	b, _ := os.ReadFile(filepath.Join(albumDir, sidecarDirName, "big.png.json"))
	var pm PhotoMeta
	json.Unmarshal(b, &pm)
	if len(pm.Tags) != 1 || pm.Tags[0] != "mine" {
		t.Fatalf("user tags changed: %v", pm.Tags)
	}
}

func TestVisionUnsupportedIsRecorded(t *testing.T) {
	srv := fakeOllama(t, "{}", nil)
	defer srv.Close()
	root, albumDir := newAlbum(t, "clip.mp4", 0, 0)
	NewVisionWorker(root, srv.URL, "m").process(visionJob{albumDir, "clip.mp4"})
	if m, ok := readVision(t, albumDir, "clip.mp4"); !ok || m.Error == "" {
		t.Fatalf("want an error result for a video, got %+v ok=%v", m, ok)
	}
}

func TestVisionServerDownLeavesNoFile(t *testing.T) {
	srv := fakeOllama(t, "{}", nil)
	srv.Close() // nothing listening
	root, albumDir := newAlbum(t, "a.png", 50, 50)
	NewVisionWorker(root, srv.URL, "m").process(visionJob{albumDir, "a.png"})
	if _, ok := readVision(t, albumDir, "a.png"); ok {
		t.Fatal("a transient failure must not write a result, so the next start retries")
	}
}

func TestVisionBackfillAndUploadRetryKeepResult(t *testing.T) {
	srv := fakeOllama(t, `{"caption":"Cat.","tags":["cat"],"text":""}`, nil)
	defer srv.Close()
	root, albumDir := newAlbum(t, "cat.png", 40, 40)
	v := NewVisionWorker(root, srv.URL, "m")
	v.Start()
	deadline := time.Now().Add(5 * time.Second)
	for {
		if _, ok := readVision(t, albumDir, "cat.png"); ok {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("backfill did not analyse the existing upload")
		}
		time.Sleep(20 * time.Millisecond)
	}
	// A retried upload rewrites the main sidecar; the vision file must survive.
	if err := writeSidecar(albumDir, &PhotoMeta{FileName: "cat.png", Album: "Trip"}); err != nil {
		t.Fatal(err)
	}
	if m, ok := readVision(t, albumDir, "cat.png"); !ok || m.Caption != "Cat." {
		t.Fatalf("vision result lost after sidecar rewrite: %+v", m)
	}
}

func TestPrepareVisionImageKeepsSmallJPEG(t *testing.T) {
	path := filepath.Join(t.TempDir(), "s.jpg")
	var buf bytes.Buffer
	jpeg.Encode(&buf, image.NewRGBA(image.Rect(0, 0, 100, 80)), nil)
	os.WriteFile(path, buf.Bytes(), 0o644)
	got, err := prepareVisionImage(path)
	if err != nil || !bytes.Equal(got, buf.Bytes()) {
		t.Fatalf("small JPEG should pass through unchanged (err=%v)", err)
	}
}

func TestParseVisionReplyRejectsProse(t *testing.T) {
	if _, err := parseVisionReply("m", "I cannot see an image."); err == nil {
		t.Fatal("want an error for a reply without JSON")
	}
}
