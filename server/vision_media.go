package main

// HEIC and video input for vision tagging, through ffmpeg/ffprobe on PATH.
//
// HEIC (and AVIF): ffmpeg decodes the full image, including iPhone/Android tile grids,
// to JPEG; Go then shrinks it like any other photo.
// Video: a few frames spread over the clip, sent together in one request:
//   under 2 s      → 1 frame from the middle
//   up to 60 s     → 3 frames at 20%, 50%, 80% (skips black intros and fade-outs)
//   longer         → 5 frames at 10%, 30%, 50%, 70%, 90%

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os/exec"
	"strconv"
	"strings"
	"time"
)

const videoFrameSide = 768 // several frames per request, so each is smaller than a photo

var (
	heicExts  = map[string]bool{".heic": true, ".heif": true, ".avif": true}
	videoExts = map[string]bool{".mp4": true, ".mov": true, ".m4v": true, ".3gp": true, ".mkv": true, ".webm": true, ".avi": true}
	// errNoFFmpeg is not permanent: once ffmpeg is installed, the next start retries.
	errNoFFmpeg = errors.New("ffmpeg/ffprobe not found on PATH (needed for HEIC and video)")
)

// runTool runs ffmpeg/ffprobe without a console window and returns stdout.
func runTool(name string, args ...string) ([]byte, error) {
	bin, err := exec.LookPath(name)
	if err != nil {
		return nil, errNoFFmpeg
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	cmd := exec.CommandContext(ctx, bin, args...)
	hideConsoleWindow(cmd)
	var out, stderr bytes.Buffer
	cmd.Stdout, cmd.Stderr = &out, &stderr
	if err := cmd.Run(); err != nil {
		return nil, fmt.Errorf("%w: %s %v: %s", errBadImage, name, err, strings.TrimSpace(lastLine(stderr.String())))
	}
	return out.Bytes(), nil
}

func lastLine(s string) string {
	s = strings.TrimSpace(s)
	if i := strings.LastIndex(s, "\n"); i >= 0 {
		return s[i+1:]
	}
	return s
}

// heicToJPEG decodes a HEIC/HEIF/AVIF file to a full-size JPEG.
func heicToJPEG(path string) ([]byte, error) {
	out, err := runTool("ffmpeg", "-v", "error", "-i", path, "-frames:v", "1", "-f", "image2pipe", "-c:v", "mjpeg", "-q:v", "3", "-")
	if err == nil && len(out) == 0 {
		err = fmt.Errorf("%w: ffmpeg returned no image", errBadImage)
	}
	return out, err
}

// videoFrameTimes picks where to grab frames (seconds) for a clip of the given length.
func videoFrameTimes(duration float64) []float64 {
	var fractions []float64
	switch {
	case duration < 2:
		fractions = []float64{0.5}
	case duration <= 60:
		fractions = []float64{0.2, 0.5, 0.8}
	default:
		fractions = []float64{0.1, 0.3, 0.5, 0.7, 0.9}
	}
	times := make([]float64, len(fractions))
	for i, f := range fractions {
		times[i] = duration * f
	}
	return times
}

// videoFrames returns JPEG frames (long edge ≤ videoFrameSide) spread over the clip.
func videoFrames(path string) ([][]byte, error) {
	out, err := runTool("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path)
	if err != nil {
		return nil, err
	}
	duration, perr := strconv.ParseFloat(strings.TrimSpace(string(out)), 64)
	if perr != nil || duration <= 0 {
		return nil, fmt.Errorf("%w: unknown video length", errBadImage)
	}
	scale := fmt.Sprintf("scale=%d:%d:force_original_aspect_ratio=decrease", videoFrameSide, videoFrameSide)
	var frames [][]byte
	for _, t := range videoFrameTimes(duration) {
		f, err := runTool("ffmpeg", "-v", "error", "-ss", strconv.FormatFloat(t, 'f', 2, 64), "-i", path,
			"-frames:v", "1", "-vf", scale, "-f", "image2pipe", "-c:v", "mjpeg", "-q:v", "4", "-")
		if err != nil {
			return nil, err
		}
		if len(f) > 0 {
			frames = append(frames, f)
		}
	}
	if len(frames) == 0 {
		return nil, fmt.Errorf("%w: no frames extracted", errBadImage)
	}
	return frames, nil
}
