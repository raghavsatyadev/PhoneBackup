package main

// The photo library: every uploaded file recorded in library.db, with what we know about it.
// Shared by the gallery API and photo grouping. _Organized (hard links made by
// `organize`) and dot-folders are skipped so no photo is listed twice.

import (
	"errors"
	"os"
	"path/filepath"
	"sort"
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

// scanLibrary lists every uploaded file under root that is still on disk.
func scanLibrary(root string) []libPhoto {
	var out []libPhoto
	for _, p := range libraryRows(root) {
		if !isAlbumName(p.Album) {
			continue
		}
		p.Path = filepath.Join(root, p.Album, p.File)
		fi, err := os.Stat(p.Path)
		if err != nil || fi.IsDir() {
			continue
		}
		p.Size, p.ModTime = fi.Size(), fi.ModTime()
		out = append(out, p)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].ID() < out[j].ID() })
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

// listAlbums returns the album folder names under root, most recently changed first.
func listAlbums(root string) []string {
	entries, _ := os.ReadDir(root)
	type album struct {
		name string
		mod  time.Time
	}
	var found []album
	for _, e := range entries {
		if !e.IsDir() || !isAlbumName(e.Name()) {
			continue
		}
		var mod time.Time
		if fi, err := e.Info(); err == nil {
			mod = fi.ModTime()
		}
		found = append(found, album{e.Name(), mod})
	}
	sort.SliceStable(found, func(i, j int) bool { return found[i].mod.After(found[j].mod) })
	names := make([]string, 0, len(found))
	for _, a := range found {
		names = append(names, a.name)
	}
	return names
}
