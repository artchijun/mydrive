//go:build !linux

package main

import (
	"errors"
	"os"
)

func cloneFile(dst, src *os.File) error { return errors.ErrUnsupported }
