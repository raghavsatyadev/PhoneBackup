package main

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strings"

	"github.com/libp2p/zeroconf/v2"
)

// The Android app looks for this service type to find PCs on the same Wi-Fi.
const (
	mdnsService = "_phonebackup._tcp"
	mdnsDomain  = "local."
)

// LoadOrCreateServerID returns a random id stored next to the upload token. It never
// changes, so the app recognises this PC after its IP address changes. It is not a
// secret: the token stays the only credential and is never announced.
func LoadOrCreateServerID() (string, error) {
	path := serverIDPath()
	if b, rerr := os.ReadFile(path); rerr == nil {
		if id := strings.TrimSpace(string(b)); len(id) >= 8 {
			return id, nil
		}
	}
	buf := make([]byte, 8)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	id := hex.EncodeToString(buf)
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return "", err
	}
	return id, os.WriteFile(path, []byte(id), 0o600)
}

// serverIDOrEmpty lets the ping answer carry the id, so the app can tell that an address
// typed in by hand is the same PC it found on the network.
func serverIDOrEmpty() string {
	b, err := os.ReadFile(serverIDPath())
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(b))
}

func serverIDPath() string {
	dir, _ := os.UserConfigDir()
	return filepath.Join(dir, "phonebackup", "server_id")
}

// announceUploads advertises the upload API (never the dashboard) over mDNS.
// The TXT record holds the server id and the PC name, never the token.
// Returns nil when announcing fails; the app can still add the PC by address.
func announceUploads(port int) *zeroconf.Server {
	id, err := LoadOrCreateServerID()
	if err != nil {
		fmt.Printf("⚠️  Not announcing on the network: %v\n", err)
		return nil
	}
	host, _ := os.Hostname()
	if host == "" {
		host = "PhoneBackup"
	}
	text := []string{"id=" + id, "name=" + host, "v=1"}
	srv, err := zeroconf.Register(host, mdnsService, mdnsDomain, port, text, lanInterfaces())
	if err != nil {
		fmt.Printf("⚠️  Not announcing on the network: %v\n", err)
		return nil
	}
	return srv
}

// lanInterfaces are the up, multicast interfaces with a private IPv4 address, so
// virtual adapters without a LAN address aren't announced on.
func lanInterfaces() []net.Interface {
	var out []net.Interface
	ifaces, _ := net.Interfaces()
	for _, ifc := range ifaces {
		if ifc.Flags&net.FlagUp == 0 || ifc.Flags&net.FlagLoopback != 0 || ifc.Flags&net.FlagMulticast == 0 {
			continue
		}
		addrs, _ := ifc.Addrs()
		for _, a := range addrs {
			if ipn, ok := a.(*net.IPNet); ok && ipn.IP.To4() != nil && ipn.IP.IsPrivate() {
				out = append(out, ifc)
				break
			}
		}
	}
	return out
}
