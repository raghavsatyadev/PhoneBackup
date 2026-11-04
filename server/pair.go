package main

// Pairing lets the Android app get the upload token without typing it.
//
// The app posts its name to /api/pair/request on the upload port and gets back a private
// poll secret and a 6-digit code it shows on screen. The PhoneBackup PC app shows the same code
// with "Allow this phone to connect?". After Allow, the app's next /api/pair/status call
// returns the token once.
//
// Two handles keep the token safe: the poll secret only ever goes to the phone that asked,
// and the PC app (through the backup API, which may be open to the LAN with -lan) only sees a
// separate decision id. Allow/Deny is accepted only from this PC.

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"math/big"
	"net"
	"net/http"
	"os"
	"sort"
	"strings"
	"sync"
	"time"
	"unicode"
)

const (
	pairTTL         = 2 * time.Minute
	maxPairPending  = 5
	maxPairNameRune = 64
)

var (
	errPairBusy    = errors.New("too many pairing requests, try again in a minute")
	errPairUnknown = errors.New("no such pairing request")
)

type pairRequest struct {
	id, secret     string
	name, code, ip string
	state          string // pending, allowed, denied
	expires        time.Time
}

// PairView is what the PC app sees: never the poll secret or the token.
type PairView struct {
	ID        string    `json:"id"`
	Name      string    `json:"name"`
	Code      string    `json:"code"`
	IP        string    `json:"ip"`
	State     string    `json:"state"`
	ExpiresAt time.Time `json:"expiresAt"`
}

// PairBook holds the open pairing requests in memory.
type PairBook struct {
	Token string
	Now   func() time.Time
	// Notify is told about every new, decided or expired request (nil in tests).
	Notify func(event string, v PairView)

	mu   sync.Mutex
	reqs map[string]*pairRequest
}

func NewPairBook(token string) *PairBook {
	return &PairBook{Token: token, Now: time.Now, reqs: map[string]*pairRequest{}}
}

// pairs is set when the upload API starts; the PC app's Allow button uses it.
var pairs *PairBook

func (b *PairBook) view(r *pairRequest) PairView {
	return PairView{ID: r.id, Name: r.name, Code: r.code, IP: r.ip, State: r.state, ExpiresAt: r.expires}
}

func (b *PairBook) notify(event string, r *pairRequest) {
	if b.Notify != nil {
		b.Notify(event, b.view(r))
	}
}

// sweep drops expired requests. Caller holds mu.
func (b *PairBook) sweep() {
	now := b.Now()
	for id, r := range b.reqs {
		if now.After(r.expires) {
			delete(b.reqs, id)
			b.notify("expired", r)
		}
	}
}

// Request opens a pairing request. One per IP: a new one replaces the old.
func (b *PairBook) Request(name, ip string) (secret, code string, err error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.sweep()
	for id, r := range b.reqs {
		if r.ip == ip && r.state == "pending" {
			delete(b.reqs, id)
			b.notify("expired", r)
		}
	}
	if len(b.reqs) >= maxPairPending {
		return "", "", errPairBusy
	}
	r := &pairRequest{
		id: randHex(8), secret: randHex(16), code: sixDigits(),
		name: cleanPairName(name), ip: ip, state: "pending", expires: b.Now().Add(pairTTL),
	}
	b.reqs[r.id] = r
	b.notify("request", r)
	return r.secret, r.code, nil
}

// Status reports a request's state to the phone holding its secret. The token is handed
// over on the first read after Allow, and a decided request is forgotten once read.
func (b *PairBook) Status(secret string) (state, token string) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.sweep()
	for id, r := range b.reqs {
		if subtle.ConstantTimeCompare([]byte(r.secret), []byte(secret)) != 1 {
			continue
		}
		if r.state != "pending" {
			delete(b.reqs, id)
		}
		if r.state == "allowed" {
			return r.state, b.Token
		}
		return r.state, ""
	}
	return "expired", ""
}

// Decide allows or denies a pending request by its dashboard id.
func (b *PairBook) Decide(id string, allow bool) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.sweep()
	r, ok := b.reqs[id]
	if !ok || r.state != "pending" {
		return errPairUnknown
	}
	r.state = "denied"
	if allow {
		r.state = "allowed"
	}
	// Give the phone time to pick up the answer.
	r.expires = b.Now().Add(pairTTL)
	b.notify(r.state, r)
	return nil
}

// Pending lists requests still waiting for Allow or Deny, oldest first.
func (b *PairBook) Pending() []PairView {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.sweep()
	out := []PairView{}
	for _, r := range b.reqs {
		if r.state == "pending" {
			out = append(out, b.view(r))
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].ExpiresAt.Before(out[j].ExpiresAt) })
	return out
}

// registerPhone adds the phone-facing endpoints to the upload mux (no token needed).
func (b *PairBook) registerPhone(mux *http.ServeMux) {
	mux.HandleFunc("/api/pair/request", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Name string `json:"name"`
		}
		if !decodePairBody(w, r, &body) {
			return
		}
		secret, code, err := b.Request(body.Name, remoteIP(r))
		if err != nil {
			writeUploadJSON(w, http.StatusTooManyRequests, map[string]string{"error": err.Error()})
			return
		}
		writeUploadJSON(w, http.StatusOK, map[string]any{"secret": secret, "code": code, "expiresIn": int(pairTTL.Seconds())})
	})
	mux.HandleFunc("/api/pair/status", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Secret string `json:"secret"`
		}
		if !decodePairBody(w, r, &body) {
			return
		}
		state, token := b.Status(body.Secret)
		resp := map[string]any{"status": state}
		if token != "" {
			host, _ := os.Hostname()
			resp["token"], resp["serverId"], resp["host"] = token, serverIDOrEmpty(), host
		}
		writeUploadJSON(w, http.StatusOK, resp)
	})
}

// registerPairDashboard adds Allow/Deny to the backup API, for this PC only (the PC app asks).
func registerPairDashboard(mux *http.ServeMux) {
	mux.HandleFunc("/api/pair/pending", func(w http.ResponseWriter, r *http.Request) {
		if !fromThisPC(w, r) {
			return
		}
		list := []PairView{}
		if pairs != nil {
			list = pairs.Pending()
		}
		writeUploadJSON(w, http.StatusOK, list)
	})
	mux.HandleFunc("/api/pair/decide", func(w http.ResponseWriter, r *http.Request) {
		if !fromThisPC(w, r) {
			return
		}
		var body struct {
			ID    string `json:"id"`
			Allow bool   `json:"allow"`
		}
		if !decodePairBody(w, r, &body) {
			return
		}
		if pairs == nil || pairs.Decide(body.ID, body.Allow) != nil {
			writeUploadJSON(w, http.StatusNotFound, map[string]string{"error": "This request has expired."})
			return
		}
		writeUploadJSON(w, http.StatusOK, map[string]bool{"ok": true})
	})
}

// fromThisPC rejects other machines (even with -lan), DNS-rebinding hosts and other sites.
func fromThisPC(w http.ResponseWriter, r *http.Request) bool {
	ip := net.ParseIP(remoteIP(r))
	host := r.Host
	if h, _, err := net.SplitHostPort(host); err == nil {
		host = h
	}
	host = strings.Trim(host, "[]")
	okHost := host == "localhost" || host == "127.0.0.1" || host == "::1"
	origin := r.Header.Get("Origin")
	okOrigin := origin == "" || origin == "http://"+r.Host
	if ip == nil || !ip.IsLoopback() || !okHost || !okOrigin {
		writeUploadJSON(w, http.StatusForbidden, map[string]string{"error": "Only this PC can allow phones."})
		return false
	}
	return true
}

func decodePairBody(w http.ResponseWriter, r *http.Request, v any) bool {
	if r.Method != http.MethodPost {
		writeUploadJSON(w, http.StatusMethodNotAllowed, map[string]string{"error": "POST only"})
		return false
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4<<10)).Decode(v); err != nil {
		writeUploadJSON(w, http.StatusBadRequest, map[string]string{"error": "bad request"})
		return false
	}
	return true
}

func remoteIP(r *http.Request) string {
	if h, _, err := net.SplitHostPort(r.RemoteAddr); err == nil {
		return h
	}
	return r.RemoteAddr
}

// cleanPairName keeps the phone's name printable and short; it comes from the network.
func cleanPairName(s string) string {
	var b strings.Builder
	n := 0
	for _, c := range strings.TrimSpace(s) {
		if n >= maxPairNameRune {
			break
		}
		if unicode.IsControl(c) || !unicode.IsPrint(c) && c != ' ' {
			continue
		}
		b.WriteRune(c)
		n++
	}
	if b.Len() == 0 {
		return "Android phone"
	}
	return b.String()
}

func randHex(n int) string {
	buf := make([]byte, n)
	_, _ = rand.Read(buf)
	return hex.EncodeToString(buf)
}

func sixDigits() string {
	n, _ := rand.Int(rand.Reader, big.NewInt(1_000_000))
	return fmt.Sprintf("%06d", n.Int64())
}

// announcePair prints requests to the console and pushes them to WebSocket clients.
func announcePair(event string, v PairView) {
	if event == "request" {
		fmt.Printf("📱 %q (%s) wants to send photos. Code %s %s. Allow it in the PhoneBackup PC app.\n",
			v.Name, v.IP, v.Code[:3], v.Code[3:])
	}
	wsHub.Broadcast(map[string]any{"type": "pair_" + event, "pair": v})
}
