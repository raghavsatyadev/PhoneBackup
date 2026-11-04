package main

// `phonebackup organize`: sorts uploaded photos by the vision model's category and event.
//
//	<upload-dir>/_Organized/<Category>/<YYYY>[ <Event>]/<file>
//
// Dry run by default (prints the plan). With -apply it hard-links each file (no extra
// disk space; falls back to a copy across drives). Originals are never moved or deleted,
// so running it again is safe: files already in place are skipped.

import (
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

const organizedDirName = "_Organized"

type organizeItem struct {
	Src, Dst string
}

func handleOrganize(args []string) {
	fs := flag.NewFlagSet("organize", flag.ExitOnError)
	dir := fs.String("dir", DefaultUploadRoot(), "Upload folder to organize")
	apply := fs.Bool("apply", false, "Create the links/copies (default only prints the plan)")
	fs.Parse(args)

	items, untagged := planOrganize(*dir)
	if len(items) == 0 {
		fmt.Printf("Nothing to organize in %s (%d photos have no vision result yet; run the server with -vision-model).\n", *dir, untagged)
		return
	}
	done, skipped, failed := 0, 0, 0
	for _, it := range items {
		rel, _ := filepath.Rel(*dir, it.Dst)
		if !*apply {
			fmt.Println(rel)
			continue
		}
		switch err := linkOrCopy(it.Src, it.Dst); {
		case errors.Is(err, os.ErrExist):
			skipped++
		case err != nil:
			failed++
			fmt.Printf("⚠️  %s: %v\n", rel, err)
		default:
			done++
		}
	}
	if !*apply {
		fmt.Printf("\n%d files would be placed under %s. Run again with -apply to do it.\n", len(items), filepath.Join(*dir, organizedDirName))
	} else {
		fmt.Printf("Organized %d, already there %d, failed %d → %s\n", done, skipped, failed, filepath.Join(*dir, organizedDirName))
	}
	if untagged > 0 {
		fmt.Printf("%d photos have no category yet and were left out.\n", untagged)
	}
}

// planOrganize lists where each analysed photo goes, sorted by destination.
func planOrganize(root string) (items []organizeItem, untagged int) {
	cats := LoadCategories(root)
	for _, p := range scanLibrary(root) {
		if p.Vision.Category == "" {
			untagged++
			continue
		}
		hint := visionHint{Album: p.Album, TakenAt: p.Meta.TakenAt, Categories: cats}
		items = append(items, organizeItem{Src: p.Path, Dst: filepath.Join(root, organizedDirName,
			organizeFolder(p.Vision, hint), p.File)})
	}
	sort.Slice(items, func(i, j int) bool { return items[i].Dst < items[j].Dst })
	return items, untagged
}

// organizeFolder is "<Category>/<YYYY>[ <Event>]"; the year comes from the photo date.
func organizeFolder(vm VisionMeta, hint visionHint) string {
	year := "Unknown year"
	if !hint.TakenAt.IsZero() {
		year = hint.TakenAt.Format("2006")
	}
	sub := year
	if ev := sanitizeName(strings.NewReplacer("/", "-", "\\", "-").Replace(vm.Event), ""); ev != "" {
		sub = year + " " + ev
	}
	return filepath.Join(sanitizeName(vm.Category, "Everyday"), sub)
}

// linkOrCopy hard-links src to dst, copying when linking isn't possible.
// Returns os.ErrExist when dst is already there.
func linkOrCopy(src, dst string) error {
	if _, err := os.Lstat(dst); err == nil {
		return os.ErrExist
	}
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return err
	}
	if os.Link(src, dst) == nil {
		return nil
	}
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.OpenFile(dst, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o644)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		os.Remove(dst)
		return err
	}
	if err := out.Close(); err != nil {
		return err
	}
	if fi, err := os.Stat(src); err == nil {
		os.Chtimes(dst, fi.ModTime(), fi.ModTime())
	}
	return nil
}
