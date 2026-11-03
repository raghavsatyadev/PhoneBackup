package main

import (
	"encoding/json"
	"image"
	"image/color"
	"image/jpeg"
	"math/rand"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// scene draws a test picture; seed changes the layout so different seeds look different.
func scene(seed int64, noise int, blur bool) image.Image {
	r := rand.New(rand.NewSource(seed))
	const w, h = 320, 240
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			img.Set(x, y, color.RGBA{uint8(x * 255 / w), uint8(y * 255 / h), 128, 255})
		}
	}
	for k := 0; k < 12; k++ {
		x0, y0, s := r.Intn(w-40), r.Intn(h-40), 10+r.Intn(30)
		c := color.RGBA{uint8(r.Intn(256)), uint8(r.Intn(256)), uint8(r.Intn(256)), 255}
		for y := y0; y < y0+s; y++ {
			for x := x0; x < x0+s; x++ {
				img.Set(x, y, c)
			}
		}
	}
	if noise > 0 {
		n := rand.New(rand.NewSource(seed + 99))
		for i := range img.Pix {
			if i%4 != 3 {
				img.Pix[i] = uint8(max(0, min(255, int(img.Pix[i])+n.Intn(2*noise+1)-noise)))
			}
		}
	}
	if blur {
		return shrinkTo(shrink(img, 40), w) // lose detail, keep layout
	}
	return img
}

// shrinkTo scales a small image back up (nearest neighbour), which looks blurry.
func shrinkTo(src image.Image, w int) image.Image {
	b := src.Bounds()
	h := b.Dy() * w / b.Dx()
	dst := image.NewRGBA(image.Rect(0, 0, w, h))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			dst.Set(x, y, src.At(b.Min.X+x*b.Dx()/w, b.Min.Y+y*b.Dy()/h))
		}
	}
	return dst
}

func addPhoto(t *testing.T, root, album, name string, img image.Image, taken time.Time) {
	t.Helper()
	dir := filepath.Join(root, album)
	os.MkdirAll(dir, 0o755)
	f, err := os.Create(filepath.Join(dir, name))
	if err != nil {
		t.Fatal(err)
	}
	jpeg.Encode(f, img, &jpeg.Options{Quality: 92})
	f.Close()
	writeSidecar(dir, &PhotoMeta{FileName: name, Album: album, TakenAt: taken})
}

func TestGroupingFindsBurstAndPicksSharpest(t *testing.T) {
	root := t.TempDir()
	t0 := time.Date(2025, 10, 21, 19, 0, 0, 0, time.UTC)
	addPhoto(t, root, "Diwali", "a_blurry.jpg", scene(1, 0, true), t0)
	addPhoto(t, root, "Diwali", "b_sharp.jpg", scene(1, 6, false), t0.Add(3*time.Second))
	addPhoto(t, root, "Diwali", "c_other.jpg", scene(2, 0, false), t0.Add(5*time.Second))
	addPhoto(t, root, "Goa", "d_much_later.jpg", scene(3, 0, false), t0.Add(48*time.Hour))
	// A hard link made by organize must not pair with its own original.
	os.MkdirAll(filepath.Join(root, organizedDirName, "Festival"), 0o755)
	os.Link(filepath.Join(root, "Diwali", "c_other.jpg"), filepath.Join(root, organizedDirName, "Festival", "c_other.jpg"))

	groups, n, err := NewGroupIndex(root).Rebuild()
	if err != nil {
		t.Fatal(err)
	}
	if n != 4 {
		t.Fatalf("library has %d photos, want 4", n)
	}
	if len(groups) != 1 || len(groups[0].Members) != 2 {
		t.Fatalf("groups = %+v", groups)
	}
	if groups[0].Best != "Diwali/b_sharp.jpg" {
		t.Fatalf("best = %s, want the sharp one", groups[0].Best)
	}

	photos := galleryPhotos(root)
	byID := map[string]GalleryPhoto{}
	for _, p := range photos {
		byID[p.ID] = p
	}
	if p := byID["Diwali/a_blurry.jpg"]; p.IsBest || p.GroupSize != 2 || p.GroupID == "" {
		t.Fatalf("blurry photo: %+v", p)
	}
	if p := byID["Diwali/c_other.jpg"]; !p.IsBest || p.GroupSize != 1 {
		t.Fatalf("lone photo: %+v", p)
	}
	if photos[0].ID != "Goa/d_much_later.jpg" {
		t.Fatalf("list should be newest first, got %s", photos[0].ID)
	}
}

func TestGalleryEndpoints(t *testing.T) {
	root := t.TempDir()
	addPhoto(t, root, "Trip", "p.jpg", scene(5, 0, false), time.Now())
	u := &UploadServer{Root: root, Token: "secret-token-123456"}
	srv := httptest.NewServer(u.Handler())
	defer srv.Close()
	get := func(path string, auth bool) *http.Response {
		req, _ := http.NewRequest("GET", srv.URL+path, nil)
		if auth {
			req.Header.Set("Authorization", "Bearer secret-token-123456")
		}
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		res.Body.Close()
		return res
	}
	if res := get("/api/gallery/photos", false); res.StatusCode != http.StatusUnauthorized {
		t.Fatalf("no token: %d", res.StatusCode)
	}
	req, _ := http.NewRequest("GET", srv.URL+"/api/gallery/photos", nil)
	req.Header.Set("Authorization", "Bearer secret-token-123456")
	res, _ := http.DefaultClient.Do(req)
	var body galleryResponse
	json.NewDecoder(res.Body).Decode(&body)
	res.Body.Close()
	if len(body.Photos) != 1 || body.Photos[0].ID != "Trip/p.jpg" || body.Photos[0].Tags == nil {
		t.Fatalf("photos = %+v", body.Photos)
	}
	if res := get("/api/gallery/thumb?id=Trip/p.jpg", true); res.StatusCode != 200 || res.Header.Get("Content-Type") != "image/jpeg" {
		t.Fatalf("thumb: %d %s", res.StatusCode, res.Header.Get("Content-Type"))
	}
	if _, err := os.Stat(thumbPath(filepath.Join(root, "Trip", "p.jpg"), thumbSmall)); err != nil {
		t.Fatal("thumbnail should be cached")
	}
	if res := get("/api/gallery/file?id=Trip/p.jpg", true); res.StatusCode != 200 {
		t.Fatalf("file: %d", res.StatusCode)
	}
	os.WriteFile(filepath.Join(root, "secret.txt"), []byte("x"), 0o644)
	for _, bad := range []string{"../secret.txt", "..%2Fsecret.txt", "%2e%2e/secret.txt", "Trip/..%5C..%5Csecret.txt",
		"Trip/.phonebackup", ".phonebackup/groups.json", organizedDirName + "/p.jpg", "Trip", "Trip/a/b.jpg", "C:/x"} {
		if res := get("/api/gallery/file?id="+bad, true); res.StatusCode != http.StatusBadRequest {
			t.Errorf("id %q: status %d, want 400", bad, res.StatusCode)
		}
	}
	if res := get("/api/gallery/file?id=Trip/missing.jpg", true); res.StatusCode != http.StatusNotFound {
		t.Fatalf("missing: %d", res.StatusCode)
	}
}

func TestEXIFOrientation(t *testing.T) {
	// Minimal JPEG header with an EXIF block saying "rotate 90° clockwise" (6), little endian.
	tiff := []byte{'I', 'I', 42, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0}
	seg := append([]byte("Exif\x00\x00"), tiff...)
	data := append([]byte{0xFF, 0xD8, 0xFF, 0xE1, byte((len(seg) + 2) >> 8), byte(len(seg) + 2)}, seg...)
	data = append(data, 0xFF, 0xDA, 0, 2)
	if o := jpegOrientation(data); o != 6 {
		t.Fatalf("orientation %d, want 6", o)
	}
	img := applyOrientation(image.NewRGBA(image.Rect(0, 0, 40, 10)), 6)
	if b := img.Bounds(); b.Dx() != 10 || b.Dy() != 40 {
		t.Fatalf("rotated size %v", b)
	}
}
