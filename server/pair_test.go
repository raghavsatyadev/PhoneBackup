package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

type fakeClock struct{ t time.Time }

func (c *fakeClock) now() time.Time { return c.t }

func newTestBook() (*PairBook, *fakeClock, *[]string) {
	clock := &fakeClock{t: time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)}
	b := NewPairBook("tok-test-0123456789")
	b.Now = clock.now
	var sent []string
	b.Notify = func(event string, v PairView) {
		raw, _ := json.Marshal(v)
		sent = append(sent, event+" "+string(raw))
	}
	return b, clock, &sent
}

func postJSON(t *testing.T, h http.Handler, path, body, remote string) (int, map[string]any) {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, path, strings.NewReader(body))
	req.RemoteAddr = remote
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	var out map[string]any
	_ = json.Unmarshal(rec.Body.Bytes(), &out)
	return rec.Code, out
}

func TestPairAllowHandsTokenOverOnce(t *testing.T) {
	b, _, sent := newTestBook()
	mux := http.NewServeMux()
	b.registerPhone(mux)

	code, out := postJSON(t, mux, "/api/pair/request", `{"name":"Pixel 9"}`, "192.168.1.5:5000")
	if code != 200 || len(out["code"].(string)) != 6 {
		t.Fatalf("request: %d %v", code, out)
	}
	secret := out["secret"].(string)
	statusBody := `{"secret":"` + secret + `"}`

	if _, st := postJSON(t, mux, "/api/pair/status", statusBody, "192.168.1.5:5000"); st["status"] != "pending" || st["token"] != nil {
		t.Fatalf("before allow: %v", st)
	}
	pending := b.Pending()
	if len(pending) != 1 || pending[0].Name != "Pixel 9" || pending[0].Code != out["code"] {
		t.Fatalf("pending: %+v", pending)
	}
	if err := b.Decide(pending[0].ID, true); err != nil {
		t.Fatal(err)
	}
	_, st := postJSON(t, mux, "/api/pair/status", statusBody, "192.168.1.5:5000")
	if st["status"] != "allowed" || st["token"] != "tok-test-0123456789" {
		t.Fatalf("after allow: %v", st)
	}
	if _, st := postJSON(t, mux, "/api/pair/status", statusBody, "192.168.1.5:5000"); st["status"] != "expired" || st["token"] != nil {
		t.Fatalf("second read must not repeat the token: %v", st)
	}
	for _, s := range *sent {
		if strings.Contains(s, secret) || strings.Contains(s, "tok-test") {
			t.Fatalf("dashboard event leaks a secret: %s", s)
		}
	}
}

func TestPairDenyAndWrongSecret(t *testing.T) {
	b, _, _ := newTestBook()
	secret, _, _ := b.Request("Phone", "10.0.0.2")
	if st, tok := b.Status("nope"); st != "expired" || tok != "" {
		t.Fatalf("wrong secret: %s %s", st, tok)
	}
	_ = b.Decide(b.Pending()[0].ID, false)
	if st, tok := b.Status(secret); st != "denied" || tok != "" {
		t.Fatalf("deny: %s %s", st, tok)
	}
	if err := b.Decide("missing", true); err == nil {
		t.Fatal("deciding an unknown id should fail")
	}
}

func TestPairExpiresAndLimits(t *testing.T) {
	b, clock, _ := newTestBook()
	secret, _, _ := b.Request("Phone", "10.0.0.2")
	clock.t = clock.t.Add(pairTTL + time.Second)
	if st, _ := b.Status(secret); st != "expired" {
		t.Fatalf("want expired, got %s", st)
	}

	// One request per IP: a new one replaces the old.
	old, _, _ := b.Request("Phone", "10.0.0.2")
	_, _, _ = b.Request("Phone", "10.0.0.2")
	if st, _ := b.Status(old); st != "expired" || len(b.Pending()) != 1 {
		t.Fatalf("replace: %s, %d pending", st, len(b.Pending()))
	}
	for i := 3; i < 3+maxPairPending-1; i++ {
		if _, _, err := b.Request("Phone", "10.0.0."+string(rune('0'+i))); err != nil {
			t.Fatal(err)
		}
	}
	if _, _, err := b.Request("Phone", "10.0.0.99"); err != errPairBusy {
		t.Fatalf("want busy, got %v", err)
	}
}

func TestPairNameIsCleaned(t *testing.T) {
	if got := cleanPairName("Bad\x1b[31m\nname"); strings.ContainsAny(got, "\x1b\n") {
		t.Fatalf("control chars kept: %q", got)
	}
	if got := cleanPairName(strings.Repeat("x", 200)); len(got) != maxPairNameRune {
		t.Fatalf("not capped: %d", len(got))
	}
	if cleanPairName("  ") != "Android phone" {
		t.Fatal("empty name needs a default")
	}
}

func TestPairDecideOnlyFromThisPC(t *testing.T) {
	b, _, _ := newTestBook()
	pairs = b
	defer func() { pairs = nil }()
	_, _, _ = b.Request("Phone", "10.0.0.2")
	id := b.Pending()[0].ID
	mux := http.NewServeMux()
	registerPairDashboard(mux)
	body := `{"id":"` + id + `","allow":true}`

	try := func(remote, host, origin string) int {
		req := httptest.NewRequest(http.MethodPost, "/api/pair/decide", strings.NewReader(body))
		req.RemoteAddr, req.Host = remote, host
		if origin != "" {
			req.Header.Set("Origin", origin)
		}
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, req)
		return rec.Code
	}
	if c := try("192.168.1.9:4000", "192.168.1.2:9876", ""); c != http.StatusForbidden {
		t.Fatalf("LAN caller: %d", c)
	}
	if c := try("127.0.0.1:4000", "evil.example:9876", ""); c != http.StatusForbidden {
		t.Fatalf("rebinding host: %d", c)
	}
	if c := try("127.0.0.1:4000", "localhost:9876", "http://evil.example"); c != http.StatusForbidden {
		t.Fatalf("other site: %d", c)
	}
	if c := try("127.0.0.1:4000", "localhost:9876", "http://localhost:9876"); c != http.StatusOK {
		t.Fatalf("dashboard: %d", c)
	}
	if b.Pending() != nil && len(b.Pending()) != 0 {
		t.Fatal("request should be decided")
	}
}
