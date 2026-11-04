package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// TestCategoryPromptAndSchema checks the model gets the categories, the date and the album,
// and that its answer is mapped onto a known category.
func TestCategoryPromptAndSchema(t *testing.T) {
	var gotPrompt string
	var gotSchema map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var req ollamaChatRequest
		json.NewDecoder(r.Body).Decode(&req)
		gotPrompt = req.Messages[0].Content
		json.Unmarshal(req.Format, &gotSchema)
		reply := `{"caption":"Diyas on a rangoli.","tags":["diya"],"text":"","category":"festival","event":"Diwali"}`
		json.NewEncoder(w).Encode(map[string]any{"message": map[string]string{"content": reply}})
	}))
	defer srv.Close()

	root, albumDir := newAlbum(t, "d.png", 30, 30)
	taken := time.Date(2025, 10, 21, 19, 0, 0, 0, time.UTC)
	savePhotoMeta(albumDir, &PhotoMeta{FileName: "d.png", Album: "Trip", TakenAt: taken})
	NewVisionWorker(NewRootDir(root), srv.URL, "m").process(visionJob{albumDir, "d.png"})

	for _, want := range []string{"Festival:", "Baby:", "Navratri", "21 October 2025", `album named "Trip"`} {
		if !strings.Contains(gotPrompt, want) {
			t.Errorf("prompt is missing %q:\n%s", want, gotPrompt)
		}
	}
	enum := gotSchema["properties"].(map[string]any)["category"].(map[string]any)["enum"].([]any)
	if len(enum) != len(defaultCategories) {
		t.Errorf("schema enum = %v", enum)
	}
	m, _ := testVision(t, albumDir, "d.png")
	if m.Category != "Festival" || m.Event != "Diwali" {
		t.Fatalf("got category %q event %q", m.Category, m.Event)
	}
}

func TestLoadCategoriesFromFile(t *testing.T) {
	root := testDir(t)
	if got := LoadCategories(root); len(got) != len(defaultCategories) {
		t.Fatalf("want defaults without a file, got %d", len(got))
	}
	os.WriteFile(filepath.Join(root, categoriesFile), []byte(`[{"name":"Kites","hint":"Uttarayan"},{"name":" "},{"name":"Other"}]`), 0o644)
	got := LoadCategories(root)
	if len(got) != 2 || got[0].Name != "Kites" {
		t.Fatalf("got %+v", got)
	}
	if matchCategory(got, "kites") != "Kites" || matchCategory(got, "nonsense") != "Other" {
		t.Fatal("matchCategory should be case-insensitive and fall back to the last category")
	}
	os.WriteFile(filepath.Join(root, categoriesFile), []byte(`not json`), 0o644)
	if len(LoadCategories(root)) != len(defaultCategories) {
		t.Fatal("a broken file should fall back to the defaults")
	}
}

func TestNeedsVisionRetagsOldResults(t *testing.T) {
	root := testDir(t)
	if !needsVision(root, "A", "a.jpg") {
		t.Fatal("missing result needs analysis")
	}
	saveVision(root, "A", "a.jpg", VisionMeta{Model: "m", Caption: "old, before categories"})
	if !needsVision(root, "A", "a.jpg") {
		t.Fatal("a result without a category should be redone")
	}
	saveVision(root, "A", "a.jpg", VisionMeta{Model: "m", Error: "unsupported format"})
	if needsVision(root, "A", "a.jpg") {
		t.Fatal("permanent errors must not be retried")
	}
	saveVision(root, "A", "a.jpg", VisionMeta{Model: "m", Category: "Trip"})
	if needsVision(root, "A", "a.jpg") {
		t.Fatal("a categorised result is done")
	}
}

func TestOrganizePlanAndLink(t *testing.T) {
	root, albumDir := newAlbum(t, "g.png", 20, 20)
	savePhotoMeta(albumDir, &PhotoMeta{FileName: "g.png", Album: "Trip", TakenAt: time.Date(2024, 10, 5, 0, 0, 0, 0, time.UTC)})
	saveVision(root, "Trip", "g.png", VisionMeta{Model: "m", Category: "Festival", Event: "Navratri / Garba"})
	writePNG(t, filepath.Join(albumDir, "u.png"), 5, 5) // no vision result yet
	savePhotoMeta(albumDir, &PhotoMeta{FileName: "u.png", Album: "Trip"})

	items, untagged := planOrganize(root)
	if len(items) != 1 || untagged != 1 {
		t.Fatalf("items %+v untagged %d", items, untagged)
	}
	want := filepath.Join(root, organizedDirName, "Festival", "2024 Navratri - Garba", "g.png")
	if items[0].Dst != want {
		t.Fatalf("dst %s, want %s", items[0].Dst, want)
	}
	if err := linkOrCopy(items[0].Src, items[0].Dst); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(items[0].Src); err != nil {
		t.Fatal("original must stay where it was")
	}
	if err := linkOrCopy(items[0].Src, items[0].Dst); !os.IsExist(err) {
		t.Fatalf("second run should report the file is already there, got %v", err)
	}
	// The organized folder is not an album: a second plan must not pick it up.
	if items2, _ := planOrganize(root); len(items2) != 1 {
		t.Fatalf("second plan found %d items", len(items2))
	}
}
