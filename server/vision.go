package main

// Vision tagging for uploaded photos.
//
// When the server starts with -vision-model, every uploaded photo is sent to a local
// vision LLM (Ollama by default) in the background. The result goes to its own file,
// <album>/.phonebackup/<file>.vision.json, so upload retries that rewrite the main
// sidecar never erase it. The user's own tags stay in the main sidecar; model tags
// live only here.

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const visionSuffix = ".vision.json"

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

// VisionWorker analyses photos one at a time (the GPU is the bottleneck). The model is loaded
// by the first request and unloaded once the queue has been empty for IdleUnload, so it only
// holds GPU memory while there is work.
type VisionWorker struct {
	Root       *RootDir
	Client     *OllamaVision
	IdleUnload time.Duration
	queue      chan visionJob
}

// defaultIdleUnload is short, but long enough that photos arriving one after another from a
// phone don't reload the model for each one.
const defaultIdleUnload = 10 * time.Second

// NewVisionWorker returns nil when model is empty, which turns the feature off.
func NewVisionWorker(root *RootDir, url, model string) *VisionWorker {
	if model == "" {
		return nil
	}
	return &VisionWorker{Root: root, Client: NewOllamaVision(url, model), IdleUnload: defaultIdleUnload, queue: make(chan visionJob, 256)}
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
	idle := time.NewTimer(time.Hour)
	idle.Stop()
	for {
		select {
		case job := <-v.queue:
			idle.Stop()
			v.process(job)
			if len(v.queue) == 0 {
				idle.Reset(v.IdleUnload)
			}
		case <-idle.C:
			v.Client.Unload()
		}
	}
}

func (v *VisionWorker) process(job visionJob) {
	out := visionPath(job.albumDir, job.fileName)
	if !needsVision(out) {
		return // already analysed
	}
	hint := readPhotoHint(filepath.Dir(job.albumDir), job.albumDir, job.fileName)
	meta, err := v.Client.Analyze(filepath.Join(job.albumDir, job.fileName), hint)
	if err != nil && !errors.Is(err, errUnsupported) && !errors.Is(err, errBadImage) {
		// Model or server problem: leave no file so a later start retries.
		fmt.Printf("⚠️  Vision: %s: %v\n", job.fileName, err)
		return
	}
	if err != nil {
		meta = VisionMeta{Model: v.Client.Model, Error: err.Error()}
	}
	meta.AnalyzedAt = time.Now()
	if werr := writeJSONAtomic(out, meta); werr != nil {
		fmt.Printf("⚠️  Vision: cannot save result for %s: %v\n", job.fileName, werr)
	}
}

// backfill queues every uploaded photo (one with a sidecar) that has no vision result.
func (v *VisionWorker) backfill() {
	root := v.Root.Get()
	albums, err := os.ReadDir(root)
	if err != nil {
		return
	}
	for _, a := range albums {
		if !a.IsDir() {
			continue
		}
		albumDir := filepath.Join(root, a.Name())
		entries, _ := os.ReadDir(filepath.Join(albumDir, sidecarDirName))
		for _, e := range entries {
			name := e.Name()
			if e.IsDir() || !strings.HasSuffix(name, ".json") || strings.HasSuffix(name, visionSuffix) {
				continue
			}
			photo := strings.TrimSuffix(name, ".json")
			if !needsVision(visionPath(albumDir, photo)) {
				continue
			}
			if _, err := os.Stat(filepath.Join(albumDir, photo)); err != nil {
				continue
			}
			v.queue <- visionJob{albumDir, photo}
		}
	}
}

// needsVision is true when there is no result yet, or an older one made before categories.
// Permanent errors (unsupported files) are never retried.
func needsVision(path string) bool {
	b, err := os.ReadFile(path)
	if err != nil {
		return true
	}
	var m VisionMeta
	if json.Unmarshal(b, &m) != nil {
		return true
	}
	return m.Error == "" && m.Category == ""
}

func visionPath(albumDir, fileName string) string {
	return filepath.Join(albumDir, sidecarDirName, fileName+visionSuffix)
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
