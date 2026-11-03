package main

// Client for a local Ollama vision model (POST /api/chat with images and a JSON schema).

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	"image/color"
	_ "image/gif"
	"image/jpeg"
	_ "image/png"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const (
	DefaultVisionURL = "http://127.0.0.1:11434"
	visionMaxSide    = 1024     // big photos cost ~16k prompt tokens and a minute; 1024px is plenty
	visionMaxBytes   = 64 << 20 // skip anything larger than this
	visionNumCtx     = 8192     // one photo is ~1-2k tokens; Ollama's default context is huge and spills to CPU
	visionAsk        = "Return JSON with: caption (one plain sentence), " +
		"tags (3-10 short lowercase keywords: objects, place, people count, event, mood), " +
		"text (any readable text, empty string if none)."
	visionPrompt      = "Describe this photo for a photo library. " + visionAsk
	visionVideoPrompt = "These images are frames from one video, in order. Describe the video for a photo library. " + visionAsk
)

var errBadImage = errors.New("cannot decode image")

// OllamaVision talks to one model on one Ollama server.
type OllamaVision struct {
	URL   string
	Model string
	HTTP  *http.Client
}

func NewOllamaVision(url, model string) *OllamaVision {
	if url == "" {
		url = DefaultVisionURL
	}
	return &OllamaVision{URL: strings.TrimRight(url, "/"), Model: model, HTTP: &http.Client{Timeout: 5 * time.Minute}}
}

type ollamaChatRequest struct {
	Model     string          `json:"model"`
	Stream    bool            `json:"stream"`
	Think     bool            `json:"think"`
	KeepAlive string          `json:"keep_alive"`
	Format    json.RawMessage `json:"format"`
	Options   map[string]any  `json:"options,omitempty"`
	Messages  []ollamaMessage `json:"messages"`
}

type ollamaMessage struct {
	Role    string   `json:"role"`
	Content string   `json:"content"`
	Images  []string `json:"images,omitempty"`
}

// visionSchema is the JSON shape Ollama forces the reply into; category is limited to the known names.
func visionSchema(cats []Category) json.RawMessage {
	names := make([]string, len(cats))
	for i, c := range cats {
		names[i] = c.Name
	}
	str := map[string]any{"type": "string"}
	b, _ := json.Marshal(map[string]any{
		"type": "object",
		"properties": map[string]any{
			"caption":  str,
			"tags":     map[string]any{"type": "array", "items": str},
			"text":     str,
			"category": map[string]any{"type": "string", "enum": names},
			"event":    str,
		},
		"required": []string{"caption", "tags", "text", "category", "event"},
	})
	return b
}

// Analyze prepares the photo (or video frames) and asks the model for caption, tags, text,
// category and event. The hint adds the category list, date and album as clues.
func (o *OllamaVision) Analyze(path string, hint visionHint) (VisionMeta, error) {
	if len(hint.Categories) == 0 {
		hint.Categories = defaultCategories
	}
	images, isVideo, err := prepareVisionInput(path)
	if err != nil {
		return VisionMeta{}, err
	}
	prompt := visionPrompt
	if isVideo {
		prompt = visionVideoPrompt
	}
	prompt += "\n" + hint.categoryPrompt()
	encoded := make([]string, len(images))
	for i, img := range images {
		encoded[i] = base64.StdEncoding.EncodeToString(img)
	}
	body, _ := json.Marshal(ollamaChatRequest{
		Model: o.Model, KeepAlive: "5m", Format: visionSchema(hint.Categories),
		Options:  map[string]any{"num_ctx": visionNumCtx},
		Messages: []ollamaMessage{{Role: "user", Content: prompt, Images: encoded}},
	})
	resp, err := o.HTTP.Post(o.URL+"/api/chat", "application/json", bytes.NewReader(body))
	if err != nil {
		return VisionMeta{}, err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode != http.StatusOK {
		return VisionMeta{}, fmt.Errorf("ollama %s: %s", resp.Status, strings.TrimSpace(string(raw)))
	}
	var chat struct {
		Message struct {
			Content string `json:"content"`
		} `json:"message"`
	}
	if err := json.Unmarshal(raw, &chat); err != nil {
		return VisionMeta{}, fmt.Errorf("bad ollama reply: %w", err)
	}
	m, err := parseVisionReply(o.Model, chat.Message.Content)
	if err == nil {
		m.Category = matchCategory(hint.Categories, m.Category)
		if isVideo {
			m.Frames = len(images)
		}
	}
	return m, err
}

// prepareVisionInput returns the images to send and whether they are video frames.
func prepareVisionInput(path string) ([][]byte, bool, error) {
	ext := strings.ToLower(filepath.Ext(path))
	switch {
	case videoExts[ext]:
		frames, err := videoFrames(path)
		return frames, true, err
	case heicExts[ext]:
		data, err := heicToJPEG(path)
		if err != nil {
			return nil, false, err
		}
		img, err := shrinkJPEGBytes(data, true)
		return [][]byte{img}, false, err
	}
	img, err := prepareVisionImage(path)
	return [][]byte{img}, false, err
}

// parseVisionReply accepts the model's JSON, even when wrapped in text or a code fence.
func parseVisionReply(model, content string) (VisionMeta, error) {
	start, end := strings.Index(content, "{"), strings.LastIndex(content, "}")
	if start < 0 || end <= start {
		return VisionMeta{}, fmt.Errorf("model reply has no JSON: %.200q", content)
	}
	var out struct {
		Caption  string   `json:"caption"`
		Tags     []string `json:"tags"`
		Text     string   `json:"text"`
		Category string   `json:"category"`
		Event    string   `json:"event"`
	}
	if err := json.Unmarshal([]byte(content[start:end+1]), &out); err != nil {
		return VisionMeta{}, fmt.Errorf("model reply is not valid JSON: %w", err)
	}
	m := VisionMeta{Model: model, Caption: strings.TrimSpace(out.Caption), Text: strings.TrimSpace(out.Text),
		Category: strings.TrimSpace(out.Category), Event: strings.TrimSpace(out.Event)}
	seen := map[string]bool{}
	for _, t := range out.Tags {
		t = strings.ToLower(strings.TrimSpace(t))
		if t != "" && !seen[t] {
			seen[t] = true
			m.Tags = append(m.Tags, t)
		}
	}
	return m, nil
}

// prepareVisionImage returns JPEG bytes no larger than visionMaxSide on the long edge.
// WebP is passed through as is (Ollama reads it; Go's standard library can't).
func prepareVisionImage(path string) ([]byte, error) {
	ext := strings.ToLower(filepath.Ext(path))
	switch ext {
	case ".jpg", ".jpeg", ".png", ".gif", ".webp":
	default:
		return nil, fmt.Errorf("%w: %s", errUnsupported, ext)
	}
	fi, err := os.Stat(path)
	if err != nil {
		return nil, err
	}
	if fi.Size() > visionMaxBytes {
		return nil, fmt.Errorf("%w: file larger than %d MB", errUnsupported, visionMaxBytes>>20)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	if ext == ".webp" {
		return data, nil
	}
	return shrinkJPEGBytes(data, ext == ".jpg" || ext == ".jpeg")
}

// shrinkJPEGBytes decodes an image and re-encodes it as JPEG within visionMaxSide.
// A small image that is already JPEG (keepSmall) is returned unchanged.
func shrinkJPEGBytes(data []byte, keepSmall bool) ([]byte, error) {
	src, _, err := image.Decode(bytes.NewReader(data))
	if err != nil {
		return nil, fmt.Errorf("%w: %v", errBadImage, err)
	}
	b := src.Bounds()
	if max(b.Dx(), b.Dy()) <= visionMaxSide && keepSmall {
		return data, nil
	}
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, shrink(src, visionMaxSide), &jpeg.Options{Quality: 85}); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

// shrink box-filters src so its long edge is at most maxSide.
func shrink(src image.Image, maxSide int) image.Image {
	b := src.Bounds()
	w, h := b.Dx(), b.Dy()
	if w <= maxSide && h <= maxSide {
		return src
	}
	nw, nh := maxSide, h*maxSide/w
	if h > w {
		nw, nh = w*maxSide/h, maxSide
	}
	nw, nh = max(nw, 1), max(nh, 1)
	dst := image.NewRGBA(image.Rect(0, 0, nw, nh))
	for y := 0; y < nh; y++ {
		y0, y1 := b.Min.Y+y*h/nh, b.Min.Y+max((y+1)*h/nh, y*h/nh+1)
		for x := 0; x < nw; x++ {
			x0, x1 := b.Min.X+x*w/nw, b.Min.X+max((x+1)*w/nw, x*w/nw+1)
			var r, g, bl, n uint64
			for sy := y0; sy < y1; sy++ {
				for sx := x0; sx < x1; sx++ {
					cr, cg, cb, _ := src.At(sx, sy).RGBA()
					r, g, bl, n = r+uint64(cr), g+uint64(cg), bl+uint64(cb), n+1
				}
			}
			dst.Set(x, y, color.RGBA64{uint16(r / n), uint16(g / n), uint16(bl / n), 0xffff})
		}
	}
	return dst
}
