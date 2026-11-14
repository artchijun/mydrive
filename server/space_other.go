//go:build !linux

package main

func diskSpace(dir string) (total, free uint64) { return 0, 0 }
