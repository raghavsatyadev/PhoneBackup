package main

// The library database: one SQLite file per photo folder, <root>/.phonebackup/library.db.
// It holds what used to be JSON sidecars: upload metadata, vision results, grouping
// features and similar-photo groups. Photos and thumbnails stay plain files; a row whose
// file is gone is ignored. Upload metadata and vision results live in separate tables,
// so an upload retry that rewrites the metadata never erases a vision result.
//
// The server, the vision worker and the `group`/`organize` commands may use the same
// file at once: WAL mode lets readers run during a write, and busy_timeout makes a second
// writer wait instead of failing.

import (
	"database/sql"
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
	"time"

	_ "modernc.org/sqlite"
)

const libraryDBFile = "library.db"

const librarySchema = `
CREATE TABLE IF NOT EXISTS photos (
	album TEXT NOT NULL, file TEXT NOT NULL,
	original_name TEXT NOT NULL DEFAULT '', tags TEXT NOT NULL DEFAULT '[]',
	taken_at TEXT NOT NULL DEFAULT '', uploaded_at TEXT NOT NULL DEFAULT '',
	size_bytes INTEGER NOT NULL DEFAULT 0,
	PRIMARY KEY (album, file));
CREATE TABLE IF NOT EXISTS vision (
	album TEXT NOT NULL, file TEXT NOT NULL,
	model TEXT NOT NULL DEFAULT '', analyzed_at TEXT NOT NULL DEFAULT '',
	caption TEXT NOT NULL DEFAULT '', tags TEXT NOT NULL DEFAULT '[]', text TEXT NOT NULL DEFAULT '',
	category TEXT NOT NULL DEFAULT '', event TEXT NOT NULL DEFAULT '',
	frames INTEGER NOT NULL DEFAULT 0, error TEXT NOT NULL DEFAULT '',
	PRIMARY KEY (album, file));
CREATE TABLE IF NOT EXISTS features (
	id TEXT PRIMARY KEY, size INTEGER NOT NULL, mod_time TEXT NOT NULL,
	hash INTEGER NOT NULL, sharp REAL NOT NULL, exposure REAL NOT NULL, error TEXT NOT NULL DEFAULT '');
CREATE TABLE IF NOT EXISTS photo_groups (
	id TEXT PRIMARY KEY, best TEXT NOT NULL, members TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY, value TEXT NOT NULL);
`

var (
	libraryDBsMu sync.Mutex
	libraryDBs   = map[string]*sql.DB{}
)

// libraryDB opens (once) the database for a photo folder, importing (then deleting) old
// sidecars the first time.
func libraryDB(root string) (*sql.DB, error) {
	root = filepath.Clean(root)
	libraryDBsMu.Lock()
	defer libraryDBsMu.Unlock()
	if db, ok := libraryDBs[root]; ok {
		return db, nil
	}
	dir := filepath.Join(root, sidecarDirName)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, err
	}
	dsn := "file:" + filepath.ToSlash(filepath.Join(dir, libraryDBFile)) +
		"?_pragma=journal_mode(WAL)&_pragma=busy_timeout(10000)&_pragma=synchronous(NORMAL)&_txlock=immediate"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	if _, err := db.Exec(librarySchema); err != nil {
		db.Close()
		return nil, err
	}
	importSidecars(db, root)
	libraryDBs[root] = db
	return db, nil
}

// closeLibraryDB closes the database for root (tests, and before a folder is removed).
func closeLibraryDB(root string) {
	root = filepath.Clean(root)
	libraryDBsMu.Lock()
	defer libraryDBsMu.Unlock()
	if db, ok := libraryDBs[root]; ok {
		db.Close()
		delete(libraryDBs, root)
	}
}

func dbTime(t time.Time) string {
	if t.IsZero() {
		return ""
	}
	return t.Format(time.RFC3339Nano)
}

func parseDBTime(s string) time.Time {
	t, _ := time.Parse(time.RFC3339Nano, s)
	return t
}

func dbStrings(v []string) string {
	if len(v) == 0 {
		return "[]"
	}
	b, _ := json.Marshal(v)
	return string(b)
}

func parseDBStrings(s string) []string {
	var v []string
	_ = json.Unmarshal([]byte(s), &v)
	return v
}

// savePhotoMeta records an upload (insert or replace); albumDir is <root>/<album>.
func savePhotoMeta(albumDir string, m *PhotoMeta) error {
	db, err := libraryDB(filepath.Dir(albumDir))
	if err != nil {
		return err
	}
	return putPhotoMeta(db, filepath.Base(albumDir), m)
}

func putPhotoMeta(db execer, album string, m *PhotoMeta) error {
	_, err := db.Exec(`INSERT OR REPLACE INTO photos (album, file, original_name, tags, taken_at, uploaded_at, size_bytes)
		VALUES (?, ?, ?, ?, ?, ?, ?)`, album, m.FileName, m.OriginalName, dbStrings(m.Tags),
		dbTime(m.TakenAt), dbTime(m.UploadedAt), m.SizeBytes)
	return err
}

// readPhotoMeta returns the upload metadata for one photo.
func readPhotoMeta(root, album, file string) (PhotoMeta, bool) {
	db, err := libraryDB(root)
	if err != nil {
		return PhotoMeta{}, false
	}
	m := PhotoMeta{FileName: file, Album: album}
	var tags, taken, uploaded string
	err = db.QueryRow(`SELECT original_name, tags, taken_at, uploaded_at, size_bytes FROM photos WHERE album = ? AND file = ?`,
		album, file).Scan(&m.OriginalName, &tags, &taken, &uploaded, &m.SizeBytes)
	if err != nil {
		return PhotoMeta{}, false
	}
	m.Tags, m.TakenAt, m.UploadedAt = parseDBStrings(tags), parseDBTime(taken), parseDBTime(uploaded)
	return m, true
}

// saveVision stores the model's result for one photo.
func saveVision(root, album, file string, v VisionMeta) error {
	db, err := libraryDB(root)
	if err != nil {
		return err
	}
	return putVision(db, album, file, v)
}

func putVision(db execer, album, file string, v VisionMeta) error {
	_, err := db.Exec(`INSERT OR REPLACE INTO vision (album, file, model, analyzed_at, caption, tags, text, category, event, frames, error)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`, album, file, v.Model, dbTime(v.AnalyzedAt), v.Caption,
		dbStrings(v.Tags), v.Text, v.Category, v.Event, v.Frames, v.Error)
	return err
}

// readVision returns the vision result for one photo, if there is one.
func readVision(root, album, file string) (VisionMeta, bool) {
	db, err := libraryDB(root)
	if err != nil {
		return VisionMeta{}, false
	}
	var v VisionMeta
	var at, tags string
	err = db.QueryRow(`SELECT model, analyzed_at, caption, tags, text, category, event, frames, error FROM vision WHERE album = ? AND file = ?`,
		album, file).Scan(&v.Model, &at, &v.Caption, &tags, &v.Text, &v.Category, &v.Event, &v.Frames, &v.Error)
	if err != nil {
		return VisionMeta{}, false
	}
	v.AnalyzedAt, v.Tags = parseDBTime(at), parseDBStrings(tags)
	return v, true
}

// needsVision is true when there is no result yet, or an older one made before categories.
// Permanent errors (unsupported files) are never retried.
func needsVision(root, album, file string) bool {
	v, ok := readVision(root, album, file)
	return !ok || (v.Error == "" && v.Category == "")
}

// libraryRows reads every upload record with its vision result in one query.
func libraryRows(root string) []libPhoto {
	db, err := libraryDB(root)
	if err != nil {
		return nil
	}
	rows, err := db.Query(`SELECT p.album, p.file, p.original_name, p.tags, p.taken_at, p.uploaded_at, p.size_bytes,
		v.model, v.analyzed_at, v.caption, v.tags, v.text, v.category, v.event, v.frames, v.error
		FROM photos p LEFT JOIN vision v ON v.album = p.album AND v.file = p.file`)
	if err != nil {
		return nil
	}
	defer rows.Close()
	var out []libPhoto
	for rows.Next() {
		var p libPhoto
		var tags, taken, uploaded string
		var vModel, vAt, vCaption, vTags, vText, vCat, vEvent, vErr sql.NullString
		var vFrames sql.NullInt64
		if rows.Scan(&p.Album, &p.File, &p.Meta.OriginalName, &tags, &taken, &uploaded, &p.Meta.SizeBytes,
			&vModel, &vAt, &vCaption, &vTags, &vText, &vCat, &vEvent, &vFrames, &vErr) != nil {
			continue
		}
		p.Meta.FileName, p.Meta.Album = p.File, p.Album
		p.Meta.Tags, p.Meta.TakenAt, p.Meta.UploadedAt = parseDBStrings(tags), parseDBTime(taken), parseDBTime(uploaded)
		if vModel.Valid {
			p.Vision = VisionMeta{Model: vModel.String, AnalyzedAt: parseDBTime(vAt.String), Caption: vCaption.String,
				Tags: parseDBStrings(vTags.String), Text: vText.String, Category: vCat.String, Event: vEvent.String,
				Frames: int(vFrames.Int64), Error: vErr.String}
		}
		out = append(out, p)
	}
	return out
}

// execer is *sql.DB or *sql.Tx.
type execer interface {
	Exec(query string, args ...any) (sql.Result, error)
}

func loadFeatures(root string) map[string]photoFeatures {
	m := map[string]photoFeatures{}
	db, err := libraryDB(root)
	if err != nil {
		return m
	}
	rows, err := db.Query(`SELECT id, size, mod_time, hash, sharp, exposure, error FROM features`)
	if err != nil {
		return m
	}
	defer rows.Close()
	for rows.Next() {
		var id, mod string
		var hash int64
		var f photoFeatures
		if rows.Scan(&id, &f.Size, &mod, &hash, &f.Sharp, &f.Exposure, &f.Error) == nil {
			f.ModTime, f.Hash = parseDBTime(mod), uint64(hash)
			m[id] = f
		}
	}
	return m
}

// saveFeatures replaces the cache, keeping only entries for photos that still exist.
func saveFeatures(root string, feats map[string]photoFeatures, lib []libPhoto) error {
	db, err := libraryDB(root)
	if err != nil {
		return err
	}
	return inTx(db, func(tx *sql.Tx) error {
		if _, err := tx.Exec(`DELETE FROM features`); err != nil {
			return err
		}
		for _, p := range lib {
			if f, ok := feats[p.ID()]; ok {
				if err := putFeature(tx, p.ID(), f); err != nil {
					return err
				}
			}
		}
		return nil
	})
}

func putFeature(db execer, id string, f photoFeatures) error {
	_, err := db.Exec(`INSERT OR REPLACE INTO features (id, size, mod_time, hash, sharp, exposure, error) VALUES (?, ?, ?, ?, ?, ?, ?)`,
		id, f.Size, dbTime(f.ModTime), int64(f.Hash), f.Sharp, f.Exposure, f.Error)
	return err
}

// saveGroups replaces the similar-photo groups.
func saveGroups(root string, groups []PhotoGroup) error {
	db, err := libraryDB(root)
	if err != nil {
		return err
	}
	return inTx(db, func(tx *sql.Tx) error {
		if _, err := tx.Exec(`DELETE FROM photo_groups`); err != nil {
			return err
		}
		for _, g := range groups {
			if err := putGroup(tx, g); err != nil {
				return err
			}
		}
		_, err := tx.Exec(`INSERT OR REPLACE INTO kv (key, value) VALUES ('groups_generated_at', ?)`, dbTime(time.Now()))
		return err
	})
}

func putGroup(db execer, g PhotoGroup) error {
	_, err := db.Exec(`INSERT OR REPLACE INTO photo_groups (id, best, members) VALUES (?, ?, ?)`, g.ID, g.Best, dbStrings(g.Members))
	return err
}

// loadGroups returns the saved groups; none means no groups yet.
func loadGroups(root string) []PhotoGroup {
	db, err := libraryDB(root)
	if err != nil {
		return nil
	}
	rows, err := db.Query(`SELECT id, best, members FROM photo_groups ORDER BY rowid`)
	if err != nil {
		return nil
	}
	defer rows.Close()
	var out []PhotoGroup
	for rows.Next() {
		var g PhotoGroup
		var members string
		if rows.Scan(&g.ID, &g.Best, &members) == nil {
			g.Members = parseDBStrings(members)
			out = append(out, g)
		}
	}
	return out
}

func inTx(db *sql.DB, fn func(*sql.Tx) error) error {
	tx, err := db.Begin()
	if err != nil {
		return err
	}
	if err := fn(tx); err != nil {
		tx.Rollback()
		return err
	}
	return tx.Commit()
}
