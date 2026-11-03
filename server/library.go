package main

// The photo library: every uploaded file that has a sidecar, with what we know about it.
// Shared by the gallery API and photo grouping. _Organized (hard links made by
// `organize`) and dot-folders are skipped so no photo is listed twice.

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// libPhoto is one uploaded file.
type libPhoto struct {
	Album, File string
	Path        string
	Size        int64
	ModTime     time.Time
	Meta        PhotoMeta
	Vision      VisionMeta
}

// ID is how clients name a photo: "<album>/<file>".
func (p libPhoto) ID() string { return p.Album + "/" + p.File }

func (p libPhoto) IsVideo() bool { return videoExts[strings.ToLower(filepath.Ext(p.File))] }

// TakenAt is the photo date from the sidecar, else the upload time, else the file time.
func (p libPhoto) TakenAt() time.Time {
	switch {
	case !p.Meta.TakenAt.IsZero():
		return p.Meta.TakenAt
	case !p.Meta.UploadedAt.IsZero():
		return p.Meta.UploadedAt
	}
	return p.ModTime
}

// scanLibrary lists every uploaded file under root.
func scanLibrary(root string) []libPhoto {
	var out []libPhoto
	albums, _ := os.ReadDir(root)
	for _, a := range albums {
		if !a.IsDir() || !isAlbumName(a.Name()) {
			continue
		}
		albumDir := filepath.Join(root, a.Name())
		entries, _ := os.ReadDir(filepath.Join(albumDir, sidecarDirName))
		for _, e := range entries {
			name := e.Name()
			if e.IsDir() || !strings.HasSuffix(name, ".json") || strings.HasSuffix(name, visionSuffix) {
				continue
			}
			file := strings.TrimSuffix(name, ".json")
			fi, err := os.Stat(filepath.Join(albumDir, file))
			if err != nil || fi.IsDir() {
				continue
			}
			p := libPhoto{Album: a.Name(), File: file, Path: filepath.Join(albumDir, file), Size: fi.Size(), ModTime: fi.ModTime()}
			if b, err := os.ReadFile(filepath.Join(albumDir, sidecarDirName, name)); err == nil {
				_ = json.Unmarshal(b, &p.Meta)
			}
			if b, err := os.ReadFile(visionPath(albumDir, file)); err == nil {
				_ = json.Unmarshal(b, &p.Vision)
			}
			out = append(out, p)
		}
	}
	return out
}

func isAlbumName(name string) bool {
	return name != organizedDirName && !strings.HasPrefix(name, ".")
}

var errBadPhotoID = errors.New("invalid photo id")

// resolvePhotoID turns "<album>/<file>" into a path under root, refusing anything that
// is not exactly one safe album folder and one safe file name (no traversal, no
// _Organized, no dot-files such as sidecars).
func resolvePhotoID(root, id string) (string, error) {
	album, file, ok := strings.Cut(id, "/")
	if !ok {
		return "", errBadPhotoID
	}
	for _, part := range []string{album, file} {
		if part == "" || strings.HasPrefix(part, ".") || sanitizeName(part, "") != part {
			return "", errBadPhotoID
		}
	}
	if !isAlbumName(album) {
		return "", errBadPhotoID
	}
	path := filepath.Join(root, album, file)
	fi, err := os.Stat(path)
	if err != nil || fi.IsDir() {
		return "", os.ErrNotExist
	}
	return path, nil
}
