package main

// Read-only gallery API for the viewer in the Android and desktop apps. Same listener
// and token as the upload API.
//
//	GET /api/gallery/photos                  every photo with caption, tags, category, event and group
//	GET /api/gallery/thumb?id=<id>&size=small|large   cached JPEG preview (512 / 1600 px)
//	GET /api/gallery/file?id=<id>            the original file (supports range requests)
//
// <id> is "<album>/<file>" exactly as the photo list returns it.

import (
	"errors"
	"net/http"
	"sort"
	"time"
)

// GalleryPhoto is one entry of /api/gallery/photos.
type GalleryPhoto struct {
	ID         string    `json:"id"`
	Album      string    `json:"album"`
	FileName   string    `json:"fileName"`
	TakenAt    time.Time `json:"takenAt"`
	UploadedAt time.Time `json:"uploadedAt"`
	Size       int64     `json:"size"`
	IsVideo    bool      `json:"isVideo"`
	Tags       []string  `json:"tags"`   // the user's own tags
	AITags     []string  `json:"aiTags"` // from the vision model
	Caption    string    `json:"caption"`
	Text       string    `json:"text"`
	Category   string    `json:"category"`
	Event      string    `json:"event"`
	GroupID    string    `json:"groupId"`   // "" when the photo has no similar shots
	GroupSize  int       `json:"groupSize"` // 1 when alone
	IsBest     bool      `json:"isBest"`    // true for the photo to show for its group (and for single photos)
}

type galleryResponse struct {
	Photos []GalleryPhoto `json:"photos"`
}

func (u *UploadServer) registerGallery(mux *http.ServeMux) {
	mux.HandleFunc("/api/gallery/photos", u.auth(u.handleGalleryPhotos))
	mux.HandleFunc("/api/gallery/thumb", u.auth(u.handleGalleryThumb))
	mux.HandleFunc("/api/gallery/file", u.auth(u.handleGalleryFile))
}

// galleryPhotos lists the library newest first, with grouping from groups.json.
func galleryPhotos(root string) []GalleryPhoto {
	type gi struct {
		id   string
		size int
		best bool
	}
	inGroup := map[string]gi{}
	for _, g := range loadGroups(root) {
		for _, m := range g.Members {
			inGroup[m] = gi{g.ID, len(g.Members), m == g.Best}
		}
	}
	lib := scanLibrary(root)
	out := make([]GalleryPhoto, 0, len(lib))
	for _, p := range lib {
		gp := GalleryPhoto{
			ID: p.ID(), Album: p.Album, FileName: p.File, TakenAt: p.TakenAt(), UploadedAt: p.Meta.UploadedAt,
			Size: p.Size, IsVideo: p.IsVideo(), Tags: nonNil(p.Meta.Tags), AITags: nonNil(p.Vision.Tags),
			Caption: p.Vision.Caption, Text: p.Vision.Text, Category: p.Vision.Category, Event: p.Vision.Event,
			GroupSize: 1, IsBest: true,
		}
		if g, ok := inGroup[p.ID()]; ok {
			gp.GroupID, gp.GroupSize, gp.IsBest = g.id, g.size, g.best
		}
		out = append(out, gp)
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].TakenAt.After(out[j].TakenAt) })
	return out
}

func nonNil(s []string) []string {
	if s == nil {
		return []string{}
	}
	return s
}

func (u *UploadServer) handleGalleryPhotos(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeUploadJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "GET only"})
		return
	}
	writeUploadJSON(w, http.StatusOK, galleryResponse{Photos: galleryPhotos(u.Root)})
}

func (u *UploadServer) handleGalleryThumb(w http.ResponseWriter, r *http.Request) {
	path, ok := u.photoFromQuery(w, r)
	if !ok {
		return
	}
	side := thumbSmall
	if r.URL.Query().Get("size") == "large" {
		side = thumbLarge
	}
	thumb, err := ensureThumb(path, side)
	if err != nil {
		code := http.StatusInternalServerError
		if errors.Is(err, errUnsupported) || errors.Is(err, errBadImage) || errors.Is(err, errNoFFmpeg) {
			code = http.StatusUnsupportedMediaType
		}
		writeUploadJSON(w, code, map[string]string{"error": err.Error()})
		return
	}
	w.Header().Set("Content-Type", "image/jpeg")
	w.Header().Set("Cache-Control", "private, max-age=86400")
	http.ServeFile(w, r, thumb)
}

func (u *UploadServer) handleGalleryFile(w http.ResponseWriter, r *http.Request) {
	path, ok := u.photoFromQuery(w, r)
	if !ok {
		return
	}
	w.Header().Set("Cache-Control", "private, max-age=86400")
	http.ServeFile(w, r, path)
}

// photoFromQuery validates ?id= and writes the error response when it is bad.
func (u *UploadServer) photoFromQuery(w http.ResponseWriter, r *http.Request) (string, bool) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		writeUploadJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "GET only"})
		return "", false
	}
	path, err := resolvePhotoID(u.Root, r.URL.Query().Get("id"))
	switch {
	case errors.Is(err, errBadPhotoID):
		writeUploadJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return "", false
	case err != nil:
		writeUploadJSON(w, http.StatusNotFound, map[string]string{"error": "photo not found"})
		return "", false
	}
	return path, true
}
