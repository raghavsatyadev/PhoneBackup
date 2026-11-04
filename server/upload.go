package main

// Photo upload API for the Android share client.
//
// It runs on its own listener (reachable from the LAN) and every request must carry
// "Authorization: Bearer <token>". One photo per request, multipart fields first:
//   album, tags (comma separated), takenAt (RFC 3339), fileName, then the "file" part.
// Files land in <uploadRoot>/<album>/<fileName>; metadata goes to
// <uploadRoot>/<album>/.phonebackup/<fileName>.json and the file's mtime is set to takenAt.

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

const (
	maxUploadBytes   = 4 << 30 // 4 GiB per photo/video
	maxFieldBytes    = 8 << 10
	sidecarDirName   = ".phonebackup"
	defaultAlbumName = "Unsorted"
)

// PhotoMeta is the sidecar written next to every uploaded photo.
type PhotoMeta struct {
	FileName     string    `json:"fileName"`
	OriginalName string    `json:"originalName"`
	Album        string    `json:"album"`
	Tags         []string  `json:"tags"`
	TakenAt      time.Time `json:"takenAt"`
	UploadedAt   time.Time `json:"uploadedAt"`
	SizeBytes    int64     `json:"sizeBytes"`
}

type uploadResult struct {
	FileName string `json:"fileName"`
	Album    string `json:"album"`
	Skipped  bool   `json:"skipped"`
	Size     int64  `json:"size"`
}

// UploadServer holds the upload root and the shared secret.
type UploadServer struct {
	Root   *RootDir
	Token  string
	Vision *VisionWorker // nil when vision tagging is off
	Groups *GroupIndex   // nil in tests; rebuilds similar-photo groups after uploads
	Pairs  *PairBook     // nil turns pairing off
}

// LoadOrCreateUploadToken keeps one random token per machine under the user config dir.
func LoadOrCreateUploadToken() (token, path string, err error) {
	dir, err := os.UserConfigDir()
	if err != nil {
		return "", "", err
	}
	path = filepath.Join(dir, "phonebackup", "upload_token")
	if b, rerr := os.ReadFile(path); rerr == nil {
		if t := strings.TrimSpace(string(b)); len(t) >= 16 {
			return t, path, nil
		}
	}
	buf := make([]byte, 16)
	if _, err = rand.Read(buf); err != nil {
		return "", "", err
	}
	token = hex.EncodeToString(buf)
	if err = os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return "", "", err
	}
	return token, path, os.WriteFile(path, []byte(token), 0o600)
}

// Handler returns the mux for the upload API.
func (u *UploadServer) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/api/upload/ping", u.auth(u.handlePing))
	mux.HandleFunc("/api/upload", u.auth(u.handleUpload))
	mux.HandleFunc("/api/upload/folder", u.auth(u.handleFolder))
	mux.HandleFunc("/api/upload/albums", u.auth(u.handleAlbums))
	u.registerGallery(mux)
	if u.Pairs != nil {
		u.Pairs.registerPhone(mux)
	}
	return mux
}

// StartUploadServer listens on all interfaces so phones on the LAN can reach it.
func StartUploadServer(port int, root *RootDir, token string, vision *VisionWorker, pairBook *PairBook) {
	u := &UploadServer{Root: root, Token: token, Vision: vision, Groups: NewGroupIndex(root), Pairs: pairBook}
	go u.Groups.Rebuild()
	srv := &http.Server{Addr: fmt.Sprintf(":%d", port), Handler: u.Handler(), ReadHeaderTimeout: 15 * time.Second}
	go func() {
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			fmt.Printf("⚠️  Photo upload server stopped: %v\n", err)
		}
	}()
}

// startPhotoUploads loads the token, starts the listener and prints what to type into the app.
// dashboardPort is where the phone gets allowed (printed with each pairing request).
func startPhotoUploads(port int, root *RootDir, vision *VisionWorker, dashboardPort int) {
	token, tokenPath, err := LoadOrCreateUploadToken()
	if err != nil {
		fmt.Printf("⚠️  Photo uploads disabled: cannot create token: %v\n", err)
		return
	}
	pairs = NewPairBook(token)
	dashURL := fmt.Sprintf("http://localhost:%d", dashboardPort)
	pairs.Notify = func(event string, v PairView) { announcePair(event, v, dashURL) }
	StartUploadServer(port, root, token, vision, pairs)
	fmt.Println("📷 Photo uploads from the Android app:")
	for _, ip := range lanIPv4s() {
		fmt.Printf("     Server address: http://%s:%d\n", ip, port)
	}
	fmt.Printf("     Token:          %s   (stored in %s)\n", token, tokenPath)
	fmt.Printf("     Saving to:      %s   (change it in the PC app)\n", root.Get())
	if vision != nil {
		vision.Start()
		fmt.Printf("     Vision tagging: %s at %s\n", vision.Client.Model, vision.Client.URL)
	}
	if announceUploads(port) != nil {
		host, _ := os.Hostname()
		fmt.Printf("     Announced on the network as %q, so the app finds this PC by itself\n", host)
	}
}

func lanIPv4s() []string {
	var out []string
	ifaces, _ := net.Interfaces()
	for _, ifc := range ifaces {
		if ifc.Flags&net.FlagUp == 0 || ifc.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, _ := ifc.Addrs()
		for _, a := range addrs {
			if ipn, ok := a.(*net.IPNet); ok && ipn.IP.To4() != nil && ipn.IP.IsPrivate() {
				out = append(out, ipn.IP.String())
			}
		}
	}
	return out
}

func (u *UploadServer) auth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		got := strings.TrimSpace(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "))
		if u.Token == "" || subtle.ConstantTimeCompare([]byte(got), []byte(u.Token)) != 1 {
			h := r.Header.Get("Authorization")
			log.Printf("upload: refused %s %s from %s (Authorization %q..., token length %d)", r.Method, r.URL.Path, r.RemoteAddr, prefix(h, 7), len(got))
			writeUploadJSON(w, http.StatusUnauthorized, map[string]string{"error": "invalid or missing token"})
			return
		}
		next(w, r)
	}
}

func (u *UploadServer) handlePing(w http.ResponseWriter, r *http.Request) {
	host, _ := os.Hostname()
	writeUploadJSON(w, http.StatusOK, map[string]any{"ok": true, "host": host, "id": serverIDOrEmpty(), "uploadRoot": u.Root.Get(), "canChangeFolder": fromSameMachine(r)})
}

// handleAlbums lists the album folders so the app can offer them (newest first).
func (u *UploadServer) handleAlbums(w http.ResponseWriter, r *http.Request) {
	writeUploadJSON(w, http.StatusOK, map[string]any{"albums": listAlbums(u.Root.Get())})
}

func (u *UploadServer) handleUpload(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeUploadJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "POST only"})
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxUploadBytes)
	mr, err := r.MultipartReader()
	if err != nil {
		writeUploadJSON(w, http.StatusBadRequest, map[string]string{"error": "expected multipart/form-data"})
		return
	}

	meta := PhotoMeta{UploadedAt: time.Now()}
	for {
		part, perr := mr.NextPart()
		if perr == io.EOF {
			writeUploadJSON(w, http.StatusBadRequest, map[string]string{"error": "missing file part"})
			return
		}
		if perr != nil {
			writeUploadJSON(w, http.StatusBadRequest, map[string]string{"error": perr.Error()})
			return
		}
		if part.FormName() != "file" {
			b, _ := io.ReadAll(io.LimitReader(part, maxFieldBytes))
			applyUploadField(&meta, part.FormName(), string(b))
			continue
		}
		if meta.OriginalName == "" {
			meta.OriginalName = part.FileName()
		}
		root := u.Root.Get()
		res, serr := u.store(root, part, &meta)
		if serr != nil {
			writeUploadJSON(w, http.StatusInternalServerError, map[string]string{"error": serr.Error()})
			return
		}
		u.Vision.Enqueue(filepath.Join(root, res.Album), res.FileName)
		u.Groups.Schedule()
		writeUploadJSON(w, http.StatusOK, res)
		return
	}
}

func applyUploadField(m *PhotoMeta, name, value string) {
	value = strings.TrimSpace(value)
	switch name {
	case "album":
		m.Album = value
	case "fileName":
		m.OriginalName = value
	case "takenAt":
		if t, err := time.Parse(time.RFC3339, value); err == nil {
			m.TakenAt = t
		}
	case "tags":
		for _, t := range strings.Split(value, ",") {
			if t = strings.TrimSpace(t); t != "" {
				m.Tags = append(m.Tags, t)
			}
		}
	}
}

// store streams the file into the album folder, never overwriting a different file.
func (u *UploadServer) store(root string, src io.Reader, m *PhotoMeta) (uploadResult, error) {
	m.Album = sanitizeName(m.Album, defaultAlbumName)
	name := sanitizeName(m.OriginalName, "photo")
	albumDir := filepath.Join(root, m.Album)
	if err := os.MkdirAll(albumDir, 0o755); err != nil {
		return uploadResult{}, err
	}

	tmp, err := os.CreateTemp(albumDir, ".upload-*")
	if err != nil {
		return uploadResult{}, err
	}
	tmpName := tmp.Name()
	defer os.Remove(tmpName) // no-op once renamed
	size, err := io.Copy(tmp, src)
	if cerr := tmp.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		return uploadResult{}, err
	}
	m.SizeBytes = size

	final, skipped := pickTargetName(albumDir, name, size)
	if !skipped {
		if err := os.Rename(tmpName, filepath.Join(albumDir, final)); err != nil {
			return uploadResult{}, err
		}
	}
	m.FileName = final
	target := filepath.Join(albumDir, final)
	if !m.TakenAt.IsZero() {
		_ = os.Chtimes(target, m.TakenAt, m.TakenAt)
	} else {
		m.TakenAt = m.UploadedAt
	}
	if err := writeSidecar(albumDir, m); err != nil {
		return uploadResult{}, err
	}
	return uploadResult{FileName: final, Album: m.Album, Skipped: skipped, Size: size}, nil
}

// pickTargetName returns name if free, the existing name when it holds a same-size file
// (a retried upload), or name with a " (n)" suffix otherwise.
func pickTargetName(dir, name string, size int64) (string, bool) {
	ext := filepath.Ext(name)
	base := strings.TrimSuffix(name, ext)
	for i := 0; i < 10000; i++ {
		candidate := name
		if i > 0 {
			candidate = fmt.Sprintf("%s (%d)%s", base, i, ext)
		}
		fi, err := os.Stat(filepath.Join(dir, candidate))
		if os.IsNotExist(err) {
			return candidate, false
		}
		if err == nil && fi.Size() == size {
			return candidate, true
		}
	}
	return fmt.Sprintf("%s-%d%s", base, time.Now().UnixNano(), ext), false
}

func writeSidecar(albumDir string, m *PhotoMeta) error {
	dir := filepath.Join(albumDir, sidecarDirName)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	b, err := json.MarshalIndent(m, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(dir, m.FileName+".json"), b, 0o644)
}

var (
	unsafeNameChars  = regexp.MustCompile(`[<>:"/\\|?*\x00-\x1f]`)
	reservedWinNames = map[string]bool{"CON": true, "PRN": true, "AUX": true, "NUL": true,
		"COM1": true, "COM2": true, "COM3": true, "COM4": true, "COM5": true, "COM6": true, "COM7": true, "COM8": true, "COM9": true,
		"LPT1": true, "LPT2": true, "LPT3": true, "LPT4": true, "LPT5": true, "LPT6": true, "LPT7": true, "LPT8": true, "LPT9": true}
)

// sanitizeName turns user input into one safe path segment (no traversal, valid on Windows).
func sanitizeName(s, fallback string) string {
	s = strings.ReplaceAll(s, "\\", "/")
	if i := strings.LastIndex(s, "/"); i >= 0 {
		s = s[i+1:] // keep the last segment only; filepath.Base would treat "a:" as a drive on Windows
	}
	s = unsafeNameChars.ReplaceAllString(s, "_")
	s = strings.TrimRight(strings.TrimSpace(s), ". ")
	s = strings.TrimLeft(s, ".")
	if len(s) > 150 {
		ext := filepath.Ext(s)
		if len(ext) > 10 {
			ext = ""
		}
		s = s[:150-len(ext)] + ext
	}
	stem := strings.ToUpper(strings.TrimSuffix(s, filepath.Ext(s)))
	if s == "" || reservedWinNames[stem] {
		return fallback
	}
	return s
}

func writeUploadJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

// prefix returns at most the first n bytes of s (for logs that must not show secrets).
func prefix(s string, n int) string {
	if len(s) > n {
		return s[:n]
	}
	return s
}
