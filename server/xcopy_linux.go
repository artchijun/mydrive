package main

import (
	"os"

	"golang.org/x/sys/unix"
)

// cloneFile: btrfs reflink (내용을 복사하지 않고 같은 블록을 가리킴)
func cloneFile(dst, src *os.File) error {
	return unix.IoctlFileClone(int(dst.Fd()), int(src.Fd()))
}
