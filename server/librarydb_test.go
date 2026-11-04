package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func writeTestJSON(t *testing.T, path string, v any) {
	t.Helper()
	b, _ := json.Marshal(v)
	os.MkdirAll(filepath.Dir(path), 0o755)
	if err := os.WriteFile(path, b, 0o644); err != nil {
		t.Fatal(err)
	}
}

func TestImportOldSidecarsOnce(t *testing.T) {
	root := testDir(t)
	side := filepath.Join(root, "Trip", sidecarDirName)
	os.MkdirAll(side, 0o755)
	os.WriteFile(filepath.Join(root, "Trip", "a.jpg"), []byte("x"), 0o644)
	taken := time.Date(2024, 3, 1, 10, 0, 0, 0, time.UTC)
	writeTestJSON(t, filepath.Join(side, "a.jpg.json"), PhotoMeta{FileName: "a.jpg", Album: "Trip", Tags: []string{"mine"}, TakenAt: taken})
	writeTestJSON(t, filepath.Join(side, "a.jpg.vision.json"), VisionMeta{Model: "m", Caption: "Cat.", Category: "Pets"})
	writeTestJSON(t, filepath.Join(root, sidecarDirName, legacyFeaturesFile), map[string]photoFeatures{"Trip/a.jpg": {Size: 1, Hash: 1 << 63}})
	writeTestJSON(t, filepath.Join(root, sidecarDirName, legacyGroupsFile), map[string]any{"groups": []PhotoGroup{{ID: "g1", Best: "Trip/a.jpg", Members: []string{"Trip/a.jpg", "Trip/b.jpg"}}}})

	lib := scanLibrary(root)
	if len(lib) != 1 || lib[0].Meta.Tags[0] != "mine" || !lib[0].Meta.TakenAt.Equal(taken) || lib[0].Vision.Caption != "Cat." {
		t.Fatalf("import: %+v", lib)
	}
	if f := loadFeatures(root)["Trip/a.jpg"]; f.Hash != 1<<63 {
		t.Fatalf("hash round trip: %x", f.Hash)
	}
	if g := loadGroups(root); len(g) != 1 || len(g[0].Members) != 2 {
		t.Fatalf("groups: %+v", g)
	}

	// A newer upload record wins, and reopening never imports the old JSON again.
	savePhotoMeta(filepath.Join(root, "Trip"), &PhotoMeta{FileName: "a.jpg", Tags: []string{"new"}})
	closeLibraryDB(root)
	if m, _ := readPhotoMeta(root, "Trip", "a.jpg"); len(m.Tags) != 1 || m.Tags[0] != "new" {
		t.Fatalf("re-import overwrote newer data: %+v", m)
	}
	if _, err := os.Stat(filepath.Join(side, "a.jpg.json")); err != nil {
		t.Fatal("old sidecar files must be left in place")
	}
}

func TestUploadRetryKeepsVision(t *testing.T) {
	root := testDir(t)
	albumDir := filepath.Join(root, "Trip")
	savePhotoMeta(albumDir, &PhotoMeta{FileName: "a.jpg"})
	saveVision(root, "Trip", "a.jpg", VisionMeta{Model: "m", Category: "Pets"})
	savePhotoMeta(albumDir, &PhotoMeta{FileName: "a.jpg", Tags: []string{"retry"}})
	if v, ok := readVision(root, "Trip", "a.jpg"); !ok || v.Category != "Pets" {
		t.Fatalf("vision lost on retry: %+v", v)
	}
}
