package main

// Categories the vision model sorts photos into.
//
// The defaults suit a Gujarati / Indian family library. To change them, put a
// categories.json in the upload folder:
//
//	[{"name": "Festival", "hint": "Diwali, Navratri, ..."}, ...]
//
// It is read again for every photo, so edits apply without a restart. The model
// picks exactly one name; "Everyday" (or the last entry) is the fallback.

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const categoriesFile = "categories.json"

// Category is one bucket; Hint tells the model what belongs in it.
type Category struct {
	Name string `json:"name"`
	Hint string `json:"hint"`
}

var defaultCategories = []Category{
	{"Festival", "Indian and Gujarati festivals: Diwali, Bestu Varas (Gujarati New Year), Bhai Dooj, Dhanteras, " +
		"Navratri and Garba/Dandiya, Dussehra, Sharad Purnima, Uttarayan (kite flying), Holi and Dhuleti, " +
		"Janmashtami, Ganesh Chaturthi, Raksha Bandhan, Maha Shivratri, Ram Navami, Christmas, Eid"},
	{"Baby", "baby and child milestones: newborn, hospital, first smile, naming ceremony (namkaran), chhathi, " +
		"annaprashan (first rice), mundan, crawling, first steps, first tooth, first birthday, first day of school"},
	{"Trip", "travel and outings: sightseeing, temples and pilgrimages, beaches, hills, road trips, hotels, flights"},
	{"Wedding & Function", "weddings, engagement, haldi, mehendi, sangeet, seemant (baby shower), puja, havan, " +
		"family gatherings and religious functions"},
	{"Celebration", "birthdays (not baby milestones), anniversaries, parties, cake cutting"},
	{"Documents", "screenshots, receipts, bills, documents, notes, whiteboards"},
	{"Everyday", "anything else: home, food, pets, selfies, daily life"},
}

// LoadCategories returns <root>/categories.json, or the defaults when it is missing or bad.
func LoadCategories(root string) []Category {
	b, err := os.ReadFile(filepath.Join(root, categoriesFile))
	if err != nil {
		return defaultCategories
	}
	var cats []Category
	if err := json.Unmarshal(b, &cats); err != nil || len(cats) == 0 {
		fmt.Printf("⚠️  %s is not valid, using built-in categories: %v\n", categoriesFile, err)
		return defaultCategories
	}
	out := cats[:0]
	for _, c := range cats {
		if c.Name = strings.TrimSpace(c.Name); c.Name != "" {
			out = append(out, c)
		}
	}
	if len(out) == 0 {
		return defaultCategories
	}
	return out
}

// visionHint is what we know about a photo before the model looks at it.
type visionHint struct {
	Album      string
	TakenAt    time.Time
	Categories []Category
}

// readPhotoHint pulls album and date from the upload sidecar, if there is one.
func readPhotoHint(root, albumDir, fileName string) visionHint {
	h := visionHint{Album: filepath.Base(albumDir), Categories: LoadCategories(root)}
	if b, err := os.ReadFile(filepath.Join(albumDir, sidecarDirName, fileName+".json")); err == nil {
		var m PhotoMeta
		if json.Unmarshal(b, &m) == nil {
			h.TakenAt = m.TakenAt
			if m.Album != "" {
				h.Album = m.Album
			}
		}
	}
	return h
}

// categoryPrompt explains the categories and gives the date and album as clues.
func (h visionHint) categoryPrompt() string {
	var sb strings.Builder
	sb.WriteString("Also return category (exactly one of the names below) and event " +
		"(a short name for the occasion, e.g. \"Diwali\", \"Goa trip\", \"First steps\", \"Riya's wedding\"; " +
		"empty string for an ordinary day).\nCategories:\n")
	for _, c := range h.Categories {
		fmt.Fprintf(&sb, "- %s: %s\n", c.Name, c.Hint)
	}
	if !h.TakenAt.IsZero() {
		fmt.Fprintf(&sb, "Taken on %s. Festivals follow the Hindu calendar; use the date only as a clue, "+
			"and trust what you see (diyas, rangoli, garba, kites, colours, rakhi).\n", h.TakenAt.Format("2 January 2006"))
	}
	if h.Album != "" && !strings.EqualFold(h.Album, defaultAlbumName) {
		fmt.Fprintf(&sb, "The user saved it in an album named %q.\n", h.Album)
	}
	return sb.String()
}

// matchCategory maps the model's answer to a known name (case-insensitive), else the last one.
func matchCategory(cats []Category, got string) string {
	got = strings.TrimSpace(got)
	for _, c := range cats {
		if strings.EqualFold(c.Name, got) {
			return c.Name
		}
	}
	if len(cats) == 0 {
		return ""
	}
	return cats[len(cats)-1].Name
}
