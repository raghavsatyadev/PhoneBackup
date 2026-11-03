package main

import "testing"

func TestDashboardAddrIsLocalByDefault(t *testing.T) {
	if got := dashboardAddr(9876, false); got != "127.0.0.1:9876" {
		t.Fatalf("default addr = %q, want 127.0.0.1:9876", got)
	}
	if got := dashboardAddr(9876, true); got != ":9876" {
		t.Fatalf("-lan addr = %q, want :9876", got)
	}
}
