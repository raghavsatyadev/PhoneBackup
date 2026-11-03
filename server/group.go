package main

// Similar-photo grouping (bursts, retakes, the same photo uploaded twice), like
// Google Photos' stacks. Each photo gets a 64-bit difference hash (dHash) plus a
// sharpness and exposure score, all from its 512 px preview. Two photos are similar when
//   - they were taken within groupWindow of each other and differ in ≤ groupNearBits bits, or
//   - they differ in ≤ groupSameBits bits whatever their dates (photos without a date
//     get the import time, so the date alone can't be trusted).
// The best photo of a group is the sharpest, well-exposed one. Eyes-open is not checked
// (that needs a face model).
//
// Results: <root>/.phonebackup/groups.json. Per-photo numbers are cached in
// <root>/.phonebackup/features.json, keyed by size and mtime, so only new photos are read.

import (
	"encoding/json"
	"flag"
	"fmt"
	"image"
	"image/jpeg"
	"math"
	"math/bits"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"
)

const (
	groupWindow   = 2 * time.Minute
	groupNearBits = 10
	groupSameBits = 4
	groupsFile    = "groups.json"
	featuresFile  = "features.json"
)

// photoFeatures are the numbers grouping needs for one photo.
type photoFeatures struct {
	Size     int64     `json:"size"`
	ModTime  time.Time `json:"modTime"`
	Hash     uint64    `json:"hash"`
	Sharp    float64   `json:"sharp"`    // variance of the Laplacian at 512 px
	Exposure float64   `json:"exposure"` // 0 (dark, blown out) .. 1 (well exposed)
	Error    string    `json:"error,omitempty"`
}

// PhotoGroup is a set of similar photos, best first.
type PhotoGroup struct {
	ID      string   `json:"id"`
	Best    string   `json:"best"`
	Members []string `json:"members"`
}

type groupsDoc struct {
	GeneratedAt time.Time    `json:"generatedAt"`
	Groups      []PhotoGroup `json:"groups"`
}

// GroupIndex rebuilds groups.json in the background, a little after uploads stop.
type GroupIndex struct {
	Root  string
	mu    sync.Mutex
	timer *time.Timer
	run   sync.Mutex
}

func NewGroupIndex(root string) *GroupIndex { return &GroupIndex{Root: root} }

// Schedule rebuilds after 20 s without new uploads.
func (g *GroupIndex) Schedule() {
	if g == nil {
		return
	}
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.timer != nil {
		g.timer.Stop()
	}
	g.timer = time.AfterFunc(20*time.Second, func() { g.Rebuild() })
}

// Rebuild computes missing features and rewrites groups.json.
func (g *GroupIndex) Rebuild() (groups []PhotoGroup, photos int, err error) {
	g.run.Lock()
	defer g.run.Unlock()
	lib := scanLibrary(g.Root)
	feats := loadFeatures(g.Root)
	for _, p := range lib {
		if p.IsVideo() {
			continue
		}
		f, ok := feats[p.ID()]
		if ok && f.Size == p.Size && f.ModTime.Equal(p.ModTime) {
			continue
		}
		feats[p.ID()] = computeFeatures(p)
	}
	saveFeatures(g.Root, feats, lib)
	groups = groupPhotos(lib, feats)
	err = writeJSONAtomic(filepath.Join(g.Root, sidecarDirName, groupsFile), groupsDoc{time.Now(), groups})
	return groups, len(lib), err
}

func computeFeatures(p libPhoto) photoFeatures {
	f := photoFeatures{Size: p.Size, ModTime: p.ModTime}
	thumb, err := ensureThumb(p.Path, thumbSmall)
	if err != nil {
		f.Error = err.Error()
		return f
	}
	file, err := os.Open(thumb)
	if err != nil {
		f.Error = err.Error()
		return f
	}
	defer file.Close()
	img, err := jpeg.Decode(file)
	if err != nil {
		f.Error = err.Error()
		return f
	}
	gray := toGray(img)
	f.Hash = dHash(gray)
	f.Sharp = laplacianVariance(gray)
	f.Exposure = exposureScore(gray)
	return f
}

// grayImage is luminance 0..1, row by row.
type grayImage struct {
	w, h int
	px   []float64
}

func toGray(img image.Image) grayImage {
	b := img.Bounds()
	g := grayImage{w: b.Dx(), h: b.Dy(), px: make([]float64, b.Dx()*b.Dy())}
	for y := 0; y < g.h; y++ {
		for x := 0; x < g.w; x++ {
			r, gg, bl, _ := img.At(b.Min.X+x, b.Min.Y+y).RGBA()
			g.px[y*g.w+x] = (0.299*float64(r) + 0.587*float64(gg) + 0.114*float64(bl)) / 65535
		}
	}
	return g
}

// dHash compares each of 8 rows of 9 averaged cells with its right neighbour.
func dHash(g grayImage) uint64 {
	var cells [8][9]float64
	for cy := 0; cy < 8; cy++ {
		for cx := 0; cx < 9; cx++ {
			y0, y1 := cy*g.h/8, max((cy+1)*g.h/8, cy*g.h/8+1)
			x0, x1 := cx*g.w/9, max((cx+1)*g.w/9, cx*g.w/9+1)
			var sum float64
			for y := y0; y < y1 && y < g.h; y++ {
				for x := x0; x < x1 && x < g.w; x++ {
					sum += g.px[y*g.w+x]
				}
			}
			cells[cy][cx] = sum / float64((y1-y0)*(x1-x0))
		}
	}
	var h uint64
	for cy := 0; cy < 8; cy++ {
		for cx := 0; cx < 8; cx++ {
			h <<= 1
			if cells[cy][cx] > cells[cy][cx+1] {
				h |= 1
			}
		}
	}
	return h
}

// laplacianVariance is a standard focus measure: blurry photos have weak edges.
func laplacianVariance(g grayImage) float64 {
	var sum, sq float64
	n := 0
	for y := 1; y < g.h-1; y++ {
		for x := 1; x < g.w-1; x++ {
			i := y*g.w + x
			l := g.px[i-1] + g.px[i+1] + g.px[i-g.w] + g.px[i+g.w] - 4*g.px[i]
			sum += l
			sq += l * l
			n++
		}
	}
	if n == 0 {
		return 0
	}
	mean := sum / float64(n)
	return sq/float64(n) - mean*mean
}

// exposureScore is high for mid-tone photos and low for dark or blown-out ones.
func exposureScore(g grayImage) float64 {
	var sum float64
	clipped := 0
	for _, v := range g.px {
		sum += v
		if v < 0.02 || v > 0.98 {
			clipped++
		}
	}
	if len(g.px) == 0 {
		return 0
	}
	mean := sum / float64(len(g.px))
	s := 1 - 2*math.Abs(mean-0.5) - 2*float64(clipped)/float64(len(g.px))
	return math.Max(0, math.Min(1, s))
}

// groupPhotos joins similar photos (union-find) and orders each group best first.
func groupPhotos(lib []libPhoto, feats map[string]photoFeatures) []PhotoGroup {
	type item struct {
		id string
		t  time.Time
		f  photoFeatures
	}
	var items []item
	for _, p := range lib {
		if f, ok := feats[p.ID()]; ok && f.Error == "" && !p.IsVideo() {
			items = append(items, item{p.ID(), p.TakenAt(), f})
		}
	}
	sort.Slice(items, func(i, j int) bool { return items[i].t.Before(items[j].t) })
	parent := make([]int, len(items))
	for i := range parent {
		parent[i] = i
	}
	var find func(int) int
	find = func(i int) int {
		for parent[i] != i {
			parent[i] = parent[parent[i]]
			i = parent[i]
		}
		return i
	}
	for i := range items {
		for j := i + 1; j < len(items); j++ {
			d := bits.OnesCount64(items[i].f.Hash ^ items[j].f.Hash)
			near := items[j].t.Sub(items[i].t) <= groupWindow
			if d <= groupSameBits || (near && d <= groupNearBits) {
				parent[find(j)] = find(i)
			}
		}
	}
	sets := map[int][]item{}
	for i := range items {
		r := find(i)
		sets[r] = append(sets[r], items[i])
	}
	var groups []PhotoGroup
	for _, set := range sets {
		if len(set) < 2 {
			continue
		}
		maxSharp := 0.0
		for _, it := range set {
			maxSharp = math.Max(maxSharp, it.f.Sharp)
		}
		score := func(f photoFeatures) float64 {
			s := 0.0
			if maxSharp > 0 {
				s = f.Sharp / maxSharp
			}
			return 0.65*s + 0.35*f.Exposure
		}
		sort.SliceStable(set, func(a, b int) bool { return score(set[a].f) > score(set[b].f) })
		g := PhotoGroup{Best: set[0].id}
		for _, it := range set {
			g.Members = append(g.Members, it.id)
		}
		groups = append(groups, g)
	}
	sort.Slice(groups, func(a, b int) bool { return groups[a].Best < groups[b].Best })
	for i := range groups {
		groups[i].ID = fmt.Sprintf("g%d", i+1)
	}
	return groups
}

func loadFeatures(root string) map[string]photoFeatures {
	m := map[string]photoFeatures{}
	if b, err := os.ReadFile(filepath.Join(root, sidecarDirName, featuresFile)); err == nil {
		_ = json.Unmarshal(b, &m)
	}
	return m
}

// saveFeatures keeps only entries for photos that still exist.
func saveFeatures(root string, feats map[string]photoFeatures, lib []libPhoto) {
	keep := make(map[string]photoFeatures, len(lib))
	for _, p := range lib {
		if f, ok := feats[p.ID()]; ok {
			keep[p.ID()] = f
		}
	}
	_ = writeJSONAtomic(filepath.Join(root, sidecarDirName, featuresFile), keep)
}

// loadGroups reads groups.json; missing means no groups yet.
func loadGroups(root string) []PhotoGroup {
	var doc groupsDoc
	if b, err := os.ReadFile(filepath.Join(root, sidecarDirName, groupsFile)); err == nil {
		_ = json.Unmarshal(b, &doc)
	}
	return doc.Groups
}

// handleGroup is `phonebackup group [-dir]`: rebuild groups now and print them.
func handleGroup(args []string) {
	fs := flag.NewFlagSet("group", flag.ExitOnError)
	dir := fs.String("dir", DefaultUploadRoot(), "Upload folder")
	fs.Parse(args)
	groups, n, err := NewGroupIndex(*dir).Rebuild()
	if err != nil {
		fmt.Printf("⚠️  Cannot save groups: %v\n", err)
		return
	}
	hidden := 0
	for _, g := range groups {
		fmt.Printf("%s  best: %s\n", g.ID, g.Best)
		for _, m := range g.Members[1:] {
			fmt.Printf("      %s\n", m)
		}
		hidden += len(g.Members) - 1
	}
	fmt.Printf("\n%d photos, %d groups of similar photos (%d extra shots hidden in the viewer).\n", n, len(groups), hidden)
}
