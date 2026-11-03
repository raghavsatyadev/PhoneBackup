//go:build !windows

package main

import "os/exec"

// hideConsoleWindow is a no-op: only Windows opens console windows for child processes.
func hideConsoleWindow(cmd *exec.Cmd) {}
