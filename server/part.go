package main

// 큰 파일 나눠 올리기 — 휴대폰 앱이 Cloudflare 터널(요청 하나에 100MB 까지)을 거쳐도 큰 파일을 올릴 수 있게.
//
//	PUT  /v1/part?root=&path=&upload=ID&offset=N   몸통 = 조각. 받아 둔 크기가 N 과 같아야 이어 붙임 → {"have": 받은 크기}
//	GET  /v1/part?root=&path=&upload=ID            → {"have": 받은 크기}  (끊겼다가 이어 올릴 때)
//	POST /v1/part?root=&path=&upload=ID&size=&mtime=&if=   다 받았으면 제자리로 (보통 올리기와 같은 조건·이전 버전 처리)
//	DELETE /v1/part?root=&path=&upload=ID          취소 — 받아 둔 조각을 버림
//
// 조각은 연결 폴더 맨 위의 숨은 폴더(.mydrive-tmp-up)에 모음 — 색인·감시에서 빠지고, 같은 디스크라 옮길 때 복사하지 않음.
// 하루 넘게 손대지 않은 조각은 지움.

import (
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"time"
)

const partDir = ".mydrive-tmp-up"

var partID = regexp.MustCompile(`^[0-9a-f]{16,64}$`)

func hPart(w http.ResponseWriter, r *http.Request) {
	rt := getRoot(w, r)
	if rt == nil {
		return
	}
	q := r.URL.Query()
	rel, ok := cleanRel(q.Get("path"))
	id := q.Get("upload")
	if !ok || rel == "" || !partID.MatchString(id) {
		httpErr(w, 400, "bad_request", "경로나 올리기 번호가 올바르지 않음")
		return
	}
	stage := filepath.Join(rt.Dir, partDir)
	tmp := filepath.Join(stage, id)
	have := func() int64 {
		if fi, err := os.Stat(tmp); err == nil {
			return fi.Size()
		}
		return 0
	}
	switch r.Method {
	case http.MethodGet:
		writeJSON(w, r, map[string]any{"have": have()})
	case http.MethodPut:
		off, err := strconv.ParseInt(q.Get("offset"), 10, 64)
		if err != nil || off < 0 {
			httpErr(w, 400, "bad_offset", "offset 필요")
			return
		}
		if err := os.MkdirAll(stage, 0777); err != nil {
			httpErr(w, 500, "io", err.Error())
			return
		}
		flag := os.O_CREATE | os.O_WRONLY
		if off == 0 {
			flag |= os.O_TRUNC
		} else if h := have(); h != off {
			io.Copy(io.Discard, r.Body)
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(409)
			writeJSONRaw(w, map[string]any{"error": "offset", "msg": "받아 둔 크기와 다름", "have": h})
			return
		}
		f, err := os.OpenFile(tmp, flag, 0666)
		if err != nil {
			httpErr(w, 500, "io", err.Error())
			return
		}
		if off > 0 {
			if _, err := f.Seek(off, io.SeekStart); err != nil {
				f.Close()
				httpErr(w, 500, "io", err.Error())
				return
			}
		}
		n, err := io.CopyBuffer(f, r.Body, make([]byte, 1<<20))
		f.Close()
		if err != nil || (r.ContentLength >= 0 && n != r.ContentLength) {
			// 끊긴 조각은 잘라 내서 다음에 같은 자리부터 다시 받게
			os.Truncate(tmp, off)
			httpErr(w, 400, "upload", "조각 올리기가 끊김")
			return
		}
		writeJSON(w, r, map[string]any{"have": off + n})
	case http.MethodPost:
		size, err1 := strconv.ParseInt(q.Get("size"), 10, 64)
		mt, err2 := strconv.ParseInt(q.Get("mtime"), 10, 64)
		if err1 != nil || err2 != nil {
			httpErr(w, 400, "bad_request", "size·mtime 필요")
			return
		}
		if h := have(); h != size {
			httpErr(w, 409, "incomplete", "아직 다 받지 못함 ("+strconv.FormatInt(h, 10)+" / "+strconv.FormatInt(size, 10)+")")
			return
		}
		abs := filepath.Join(rt.Dir, filepath.FromSlash(rel))
		if err := os.MkdirAll(filepath.Dir(abs), 0777); err != nil {
			httpErr(w, 500, "io", err.Error())
			return
		}
		code, e, kind, msg := commitTmp(rt, rel, abs, tmp, mt, q.Get("if"))
		if code != 200 {
			if kind == "conflict" || kind == "is_dir" {
				os.Remove(tmp) // 조건이 안 맞음: 받아 둔 조각은 버림 (다른 이름으로 처음부터)
			}
			httpErr(w, code, kind, msg)
			return
		}
		writeJSON(w, r, e)
	case http.MethodDelete: // 취소: 받아 둔 조각을 버림
		os.Remove(tmp)
		writeJSON(w, r, map[string]any{"ok": true})
	default:
		httpErr(w, 405, "method", "GET·PUT·POST·DELETE 만")
	}
}

// 하루 넘게 손대지 않은 조각을 지움 (휴대폰이 올리다 말고 사라진 경우)
func partJanitor() {
	for {
		time.Sleep(time.Hour)
		for _, rt := range rootList() {
			stage := filepath.Join(rt.Dir, partDir)
			des, err := os.ReadDir(stage)
			if err != nil {
				continue
			}
			for _, d := range des {
				if fi, err := d.Info(); err == nil && time.Since(fi.ModTime()) > 24*time.Hour {
					os.Remove(filepath.Join(stage, d.Name()))
				}
			}
		}
	}
}

func writeJSONRaw(w io.Writer, v any) { json.NewEncoder(w).Encode(v) }
