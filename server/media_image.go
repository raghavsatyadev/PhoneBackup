package main

// Small, upright previews of any uploaded file, for thumbnails and photo grouping.
//
// JPEG/PNG/GIF are decoded with the standard library and turned upright using the
// EXIF orientation tag (phones often store photos sideways and set that tag).
// HEIC/AVIF, WebP and videos go through ffmpeg; it applies the rotation itself.

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"image"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// loadPreview returns the file as an upright image no larger than maxSide on the long edge.
func loadPreview(path string, maxSide int) (image.Image, error) {
	ext := strings.ToLower(filepath.Ext(path))
	switch {
	case videoExts[ext]:
		return videoPreview(path, maxSide)
	case ext == ".jpg" || ext == ".jpeg" || ext == ".png" || ext == ".gif":
		data, err := os.ReadFile(path)
		if err != nil {
			return nil, err
		}
		img, _, err := image.Decode(bytes.NewReader(data))
		if err != nil {
			return nil, fmt.Errorf("%w: %v", errBadImage, err)
		}
		return applyOrientation(shrink(img, maxSide), jpegOrientation(data)), nil
	case heicExts[ext] || ext == ".webp":
		return ffmpegPreview(path, maxSide, "")
	}
	return nil, fmt.Errorf("%w: %s", errUnsupported, ext)
}

// ffmpegPreview decodes one frame (at seek seconds, if given) scaled to fit maxSide.
func ffmpegPreview(path string, maxSide int, seek string) (image.Image, error) {
	args := []string{"-v", "error"}
	if seek != "" {
		args = append(args, "-ss", seek)
	}
	args = append(args, "-i", path, "-frames:v", "1")
	// ffmpeg 8+ joins HEIC tile grids with its own filter graph, and -vf on top of it
	// fails ("Simple and complex filtering cannot be used together"). Shrink those in Go.
	if !heicExts[strings.ToLower(filepath.Ext(path))] {
		args = append(args, "-vf", fmt.Sprintf("scale=%d:%d:force_original_aspect_ratio=decrease", maxSide, maxSide))
	}
	args = append(args, "-f", "image2pipe", "-c:v", "mjpeg", "-q:v", "3", "-")
	out, err := runTool("ffmpeg", args...)
	if err != nil {
		return nil, err
	}
	img, _, err := image.Decode(bytes.NewReader(out))
	if err != nil {
		return nil, fmt.Errorf("%w: %v", errBadImage, err)
	}
	return shrink(img, maxSide), nil
}

// videoPreview takes a frame from a third of the way in (past black intros).
func videoPreview(path string, maxSide int) (image.Image, error) {
	seek := "1"
	if out, err := runTool("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path); err == nil {
		if d, perr := strconv.ParseFloat(strings.TrimSpace(string(out)), 64); perr == nil && d > 0 {
			seek = strconv.FormatFloat(d/3, 'f', 2, 64)
		}
	}
	return ffmpegPreview(path, maxSide, seek)
}

// jpegOrientation reads the EXIF orientation (1-8) from JPEG bytes; 1 when absent.
func jpegOrientation(data []byte) int {
	if len(data) < 4 || data[0] != 0xFF || data[1] != 0xD8 {
		return 1
	}
	for i := 2; i+4 <= len(data); {
		if data[i] != 0xFF {
			return 1
		}
		marker := data[i+1]
		size := int(binary.BigEndian.Uint16(data[i+2:]))
		if marker == 0xDA || size < 2 || i+2+size > len(data) {
			return 1 // start of image data: no EXIF before it
		}
		seg := data[i+4 : i+2+size]
		if marker == 0xE1 && len(seg) > 14 && string(seg[:6]) == "Exif\x00\x00" {
			return tiffOrientation(seg[6:])
		}
		i += 2 + size
	}
	return 1
}

func tiffOrientation(t []byte) int {
	var bo binary.ByteOrder
	switch string(t[:2]) {
	case "II":
		bo = binary.LittleEndian
	case "MM":
		bo = binary.BigEndian
	default:
		return 1
	}
	off := int(bo.Uint32(t[4:]))
	if off+2 > len(t) {
		return 1
	}
	n := int(bo.Uint16(t[off:]))
	for k := 0; k < n; k++ {
		e := off + 2 + k*12
		if e+12 > len(t) {
			return 1
		}
		if bo.Uint16(t[e:]) == 0x0112 {
			if v := int(bo.Uint16(t[e+8:])); v >= 1 && v <= 8 {
				return v
			}
			return 1
		}
	}
	return 1
}

// applyOrientation turns an image upright for EXIF orientations 2-8.
func applyOrientation(src image.Image, o int) image.Image {
	if o <= 1 || o > 8 {
		return src
	}
	b := src.Bounds()
	w, h := b.Dx(), b.Dy()
	dw, dh := w, h
	if o >= 5 {
		dw, dh = h, w
	}
	dst := image.NewRGBA(image.Rect(0, 0, dw, dh))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			var dx, dy int
			switch o {
			case 2:
				dx, dy = w-1-x, y
			case 3:
				dx, dy = w-1-x, h-1-y
			case 4:
				dx, dy = x, h-1-y
			case 5:
				dx, dy = y, x
			case 6:
				dx, dy = h-1-y, x
			case 7:
				dx, dy = h-1-y, w-1-x
			case 8:
				dx, dy = y, w-1-x
			}
			dst.Set(dx, dy, src.At(b.Min.X+x, b.Min.Y+y))
		}
	}
	return dst
}
