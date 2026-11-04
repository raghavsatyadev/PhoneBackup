package main

// One-time import of the old JSON sidecars into library.db. Runs when a folder's database
// is first opened; existing rows win. Once the import is committed, the JSON files that
// were read are deleted (a file that could not be read stays).

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

const (
	visionSuffix       = ".vision.json"
	legacyGroupsFile   = "groups.json"
	legacyFeaturesFile = "features.json"
	importedKey        = "imported_sidecars"
)

func importSidecars(db *sql.DB, root string) {
	var done string
	if db.QueryRow(`SELECT value FROM kv WHERE key = ?`, importedKey).Scan(&done) == nil {
		return
	}
	var read []string
	n := 0
	err := inTx(db, func(tx *sql.Tx) error {
		albums, _ := os.ReadDir(root)
		for _, a := range albums {
			if !a.IsDir() || !isAlbumName(a.Name()) {
				continue
			}
			n += importAlbum(tx, filepath.Join(root, a.Name()), a.Name(), &read)
		}
		importRootFiles(tx, root, &read)
		_, err := tx.Exec(`INSERT OR REPLACE INTO kv (key, value) VALUES (?, '1')`, importedKey)
		return err
	})
	if err != nil {
		fmt.Printf("⚠️  Library: cannot import old sidecars in %s: %v\n", root, err)
		return
	}
	for _, path := range read {
		_ = os.Remove(path)
	}
	if n > 0 {
		fmt.Printf("📚 Library: imported %d old sidecar files from %s\n", n, root)
	}
}

// importAlbum reads <album>/.phonebackup/*.json; only rows that are missing are added.
func importAlbum(tx *sql.Tx, albumDir, album string, read *[]string) int {
	dir := filepath.Join(albumDir, sidecarDirName)
	entries, _ := os.ReadDir(dir)
	n := 0
	for _, e := range entries {
		name := e.Name()
		if e.IsDir() || !strings.HasSuffix(name, ".json") {
			continue
		}
		b, err := os.ReadFile(filepath.Join(dir, name))
		if err != nil {
			continue
		}
		if file, ok := strings.CutSuffix(name, visionSuffix); ok {
			var v VisionMeta
			if json.Unmarshal(b, &v) != nil {
				continue
			}
			if rowExists(tx, "vision", album, file) {
				*read = append(*read, filepath.Join(dir, name))
			} else if putVision(tx, album, file, v) == nil {
				*read = append(*read, filepath.Join(dir, name))
				n++
			}
			continue
		}
		file := strings.TrimSuffix(name, ".json")
		var m PhotoMeta
		if json.Unmarshal(b, &m) != nil {
			continue
		}
		if rowExists(tx, "photos", album, file) {
			*read = append(*read, filepath.Join(dir, name))
			continue
		}
		m.FileName = file
		if putPhotoMeta(tx, album, &m) == nil {
			*read = append(*read, filepath.Join(dir, name))
			n++
		}
	}
	return n
}

func rowExists(tx *sql.Tx, table, album, file string) bool {
	var one int
	return tx.QueryRow(`SELECT 1 FROM `+table+` WHERE album = ? AND file = ?`, album, file).Scan(&one) == nil
}

// importRootFiles brings over features.json and groups.json.
func importRootFiles(tx *sql.Tx, root string, read *[]string) {
	dir := filepath.Join(root, sidecarDirName)
	if b, err := os.ReadFile(filepath.Join(dir, legacyFeaturesFile)); err == nil {
		var feats map[string]photoFeatures
		if json.Unmarshal(b, &feats) == nil {
			*read = append(*read, filepath.Join(dir, legacyFeaturesFile))
			for id, f := range feats {
				_, _ = tx.Exec(`INSERT OR IGNORE INTO features (id, size, mod_time, hash, sharp, exposure, error) VALUES (?, ?, ?, ?, ?, ?, ?)`,
					id, f.Size, dbTime(f.ModTime), int64(f.Hash), f.Sharp, f.Exposure, f.Error)
			}
		}
	}
	if b, err := os.ReadFile(filepath.Join(dir, legacyGroupsFile)); err == nil {
		var doc struct {
			Groups []PhotoGroup `json:"groups"`
		}
		var count int
		_ = tx.QueryRow(`SELECT COUNT(*) FROM photo_groups`).Scan(&count)
		if json.Unmarshal(b, &doc) == nil {
			*read = append(*read, filepath.Join(dir, legacyGroupsFile))
			for _, g := range doc.Groups {
				if count == 0 {
					_ = putGroup(tx, g)
				}
			}
		}
	}
}
