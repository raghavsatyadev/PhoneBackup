package main

// Vision tagging for uploaded photos.
//
// When the server starts with -vision-model, every uploaded photo is sent to a local
// vision LLM (Ollama by default) in the background. The result goes to the vision table
// of library.db, apart from the upload metadata, so upload retries never erase it. The
// user's own tags stay with the upload metadata; model tags live only in the vision table.

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"time"
)

// VisionMeta is what the model said about one photo.
type VisionMeta struct {
	Model      string    `json:"model"`
	AnalyzedAt time.Time `json:"analyzedAt"`
	Caption    string    `json:"caption,omitempty"`
	Tags       []string  `json:"tags,omitempty"`
	Text       string    `json:"text,omitempty"`     // readable text in the image
	Category   string    `json:"category,omitempty"` // one of the names in categories.json (or the defaults)
	Event      string    `json:"event,omitempty"`    // e.g. "Diwali", "Goa trip", "First steps"
	Frames     int       `json:"frames,omitempty"`   // video: how many frames the model saw
	Error      string    `json:"error,omitempty"`
}

// errUnsupported marks files the model can never read (documents, huge files...).
var errUnsupported = errors.New("unsupported format")

type visionJob struct{ albumDir, fileName string }

// VisionWorker analyses photos one at a time (the GPU is the bottleneck).
type VisionWorker struct {
	Root   *RootDir
	Client *OllamaVision
	queue  chan visionJob
}

// NewVisionWorker returns nil when model is empty, which turns the feature off.
func NewVisionWorker(root *RootDir, url, model string) *VisionWorker {
	if model == "" {
		return nil
	}
	return &VisionWorker{Root: root, Client: NewOllamaVision(url, model), queue: make(chan visionJob, 256)}
}

// Start runs the worker and queues photos uploaded earlier that have no result yet.
func (v *VisionWorker) Start() {
	go v.run()
	go v.backfill()
}

// Enqueue never blocks an upload; a dropped job is picked up by the next backfill.
func (v *VisionWorker) Enqueue(albumDir, fileName string) {
	if v == nil {
		return
	}
	select {
	case v.queue <- visionJob{albumDir, fileName}:
	default:
	}
}

func (v *VisionWorker) run() {
	for job := range v.queue {
		v.process(job)
	}
}

func (v *VisionWorker) process(job visionJob) {
	root, album := filepath.Dir(job.albumDir), filepath.Base(job.albumDir)
	if !needsVision(root, album, job.fileName) {
		return // already analysed
	}
	hint := readPhotoHint(root, job.albumDir, job.fileName)
	meta, err := v.Client.Analyze(filepath.Join(job.albumDir, job.fileName), hint)
	if err != nil && !errors.Is(err, errUnsupported) && !errors.Is(err, errBadImage) {
		// Model or server problem: save nothing so a later start retries.
		fmt.Printf("⚠️  Vision: %s: %v\n", job.fileName, err)
		return
	}
	if err != nil {
		meta = VisionMeta{Model: v.Client.Model, Error: err.Error()}
	}
	meta.AnalyzedAt = time.Now()
	if werr := saveVision(root, album, job.fileName, meta); werr != nil {
		fmt.Printf("⚠️  Vision: cannot save result for %s: %v\n", job.fileName, werr)
	}
}

// backfill queues every uploaded photo that has no vision result.
func (v *VisionWorker) backfill() {
	root := v.Root.Get()
	for _, p := range scanLibrary(root) {
		if needsVision(root, p.Album, p.File) {
			v.queue <- visionJob{filepath.Join(root, p.Album), p.File}
		}
	}
}

// writeJSONAtomic writes via a temp file and rename so readers never see half a file.
func writeJSONAtomic(path string, v any) error {
	b, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(filepath.Dir(path), ".tmp-*")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name())
	if _, err := tmp.Write(b); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmp.Name(), path)
}
