package main

// Cached JPEG previews: <album>/.phonebackup/thumbs/<side>/<file>.jpg
// "small" (512 px) feeds the grid and photo grouping; "large" (1600 px) is what the
// viewer shows full screen, so HEIC and huge photos display on any device.

import (
	"image/jpeg"
	"os"
	"path/filepath"
	"strconv"
	"sync"
)

const (
	thumbSmall = 512
	thumbLarge = 1600
)

// thumbSlots limits how many previews are decoded at once (each can take ~100 MB for a big photo).
var thumbSlots = make(chan struct{}, 2)

// thumbLocks stops two requests from building the same preview at the same time.
var thumbLocks sync.Map

func thumbPath(photoPath string, side int) string {
	dir, file := filepath.Split(photoPath)
	return filepath.Join(dir, sidecarDirName, "thumbs", strconv.Itoa(side), file+".jpg")
}

// ensureThumb returns the cached preview, building it when missing or older than the photo.
func ensureThumb(photoPath string, side int) (string, error) {
	out := thumbPath(photoPath, side)
	src, err := os.Stat(photoPath)
	if err != nil {
		return "", err
	}
	if fi, err := os.Stat(out); err == nil && !fi.ModTime().Before(src.ModTime()) {
		return out, nil
	}
	mu, _ := thumbLocks.LoadOrStore(out, &sync.Mutex{})
	mu.(*sync.Mutex).Lock()
	defer mu.(*sync.Mutex).Unlock()
	if fi, err := os.Stat(out); err == nil && !fi.ModTime().Before(src.ModTime()) {
		return out, nil // built while we waited
	}

	thumbSlots <- struct{}{}
	img, err := loadPreview(photoPath, side)
	<-thumbSlots
	if err != nil {
		return "", err
	}
	if err := os.MkdirAll(filepath.Dir(out), 0o755); err != nil {
		return "", err
	}
	tmp, err := os.CreateTemp(filepath.Dir(out), ".tmp-*")
	if err != nil {
		return "", err
	}
	defer os.Remove(tmp.Name())
	if err := jpeg.Encode(tmp, img, &jpeg.Options{Quality: 82}); err != nil {
		tmp.Close()
		return "", err
	}
	if err := tmp.Close(); err != nil {
		return "", err
	}
	if err := os.Rename(tmp.Name(), out); err != nil {
		return "", err
	}
	return out, nil
}
