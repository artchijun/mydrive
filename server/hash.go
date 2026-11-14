package main

import (
	"crypto/sha256"
	"encoding/hex"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sync/atomic"
	"syscall"
	"time"
)

var needSync atomic.Bool

// 올린 파일을 2초 안에 디스크에 확실히 기록 (여러 파일을 한 번에)
func init() {
	go func() {
		for range time.Tick(2 * time.Second) {
			if needSync.Swap(false) {
				syscall.Sync()
			}
		}
	}()
}

// GET /v1/hash?root=&path= — 파일 내용의 SHA-256 (크기·시각이 같아도 내용이 다른지 확인할 때)
func hHash(w http.ResponseWriter, r *http.Request) {
	rt := getRoot(w, r)
	if rt == nil {
		return
	}
	rel, ok := cleanRel(r.URL.Query().Get("path"))
	if !ok {
		httpErr(w, 400, "bad_path", "경로가 올바르지 않음")
		return
	}
	f, err := os.Open(filepath.Join(rt.Dir, filepath.FromSlash(rel)))
	if err != nil {
		httpErr(w, 404, "not_found", "파일이 없음")
		return
	}
	defer f.Close()
	fi, _ := f.Stat()
	h := sha256.New()
	if _, err := io.CopyBuffer(h, f, make([]byte, 1<<20)); err != nil {
		httpErr(w, 500, "io", err.Error())
		return
	}
	e := entryOf(rel, fi)
	writeJSON(w, r, map[string]any{"sha256": hex.EncodeToString(h.Sum(nil)), "s": e.S, "m": e.M, "i": e.I})
}
