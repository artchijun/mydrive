// activity.go — 동기화 기록: PC 앱이 NAS 에 한 일(올림·받음·지움·이름 바꿈·폴더 만듦·되살림)을 NAS 에 남기고 관리 콘솔에서 봄
//
//	기록 파일: <MYDRIVE_STATE>/activity/YYYY-MM.jsonl (한 줄에 하나, 24개월 넘은 달은 지움)
//	GET /v1/admin/activity?root=&pc=&act=&q=&before=&limit=   최근 것부터 (before: 이 시각(ns)보다 앞의 것)
package main

import (
	"bufio"
	"encoding/json"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

type actEntry struct {
	T    int64  `json:"t"`
	PC   string `json:"pc"`
	IP   string `json:"ip,omitempty"`
	Root string `json:"root"`
	Act  string `json:"act"`
	Path string `json:"path"`
	To   string `json:"to,omitempty"`
	Size int64  `json:"size,omitempty"`
}

var actMu sync.Mutex
var actPruned string // 마지막으로 오래된 달을 정리한 달

func actDir() string { return filepath.Join(stateDir, "activity") }

func actAppend(e actEntry) {
	b, err := json.Marshal(e)
	if err != nil {
		return
	}
	now := time.Unix(0, e.T)
	month := now.Format("2006-01")
	actMu.Lock()
	defer actMu.Unlock()
	os.MkdirAll(actDir(), 0700)
	f, err := os.OpenFile(filepath.Join(actDir(), month+".jsonl"), os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0600)
	if err != nil {
		return
	}
	f.Write(append(b, '\n'))
	f.Close()
	if actPruned != month { // 달이 바뀌면 한 번: 24개월 넘은 기록 지움
		actPruned = month
		cut := now.AddDate(0, -24, 0).Format("2006-01")
		if ents, err := os.ReadDir(actDir()); err == nil {
			for _, d := range ents {
				if n := d.Name(); strings.HasSuffix(n, ".jsonl") && strings.TrimSuffix(n, ".jsonl") < cut {
					os.Remove(filepath.Join(actDir(), n))
				}
			}
		}
	}
}

// 응답을 지켜보는 감싸개: 처리 결과(상태 코드)와 보낸 바이트를 기억
type actWriter struct {
	http.ResponseWriter
	code int
	n    int64
}

func (w *actWriter) WriteHeader(c int) { w.code = c; w.ResponseWriter.WriteHeader(c) }
func (w *actWriter) Write(b []byte) (int, error) {
	if w.code == 0 {
		w.code = 200
	}
	n, err := w.ResponseWriter.Write(b)
	w.n += int64(n)
	return n, err
}

func reqPC(r *http.Request) string {
	pc := r.Header.Get("X-Mydrive-PC")
	if u, err := url.QueryUnescape(pc); err == nil {
		pc = u
	}
	if pc == "" {
		pc = "(이름 없음)"
	}
	return pc
}

// 파일을 바꾸는 요청을 감싸서, 잘 끝난 것만 기록
func logged(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		aw := &actWriter{ResponseWriter: w}
		h(aw, r)
		code := aw.code
		if code == 0 {
			code = 200
		}
		if code < 200 || code >= 300 {
			return
		}
		q := r.URL.Query()
		e := actEntry{T: time.Now().UnixNano(), PC: reqPC(r), Root: q.Get("root"), Path: q.Get("path")}
		e.IP = clientIP(r)
		if i := strings.LastIndex(e.IP, ":"); i > 0 {
			e.IP = strings.Trim(e.IP[:i], "[]")
		}
		switch {
		case strings.HasSuffix(r.URL.Path, "/v1/file"):
			switch r.Method {
			case http.MethodPut:
				e.Act, e.Size = "올림", r.ContentLength
			case http.MethodDelete:
				e.Act = "지움"
			case http.MethodGet:
				// 이어받기(중간부터)는 앞에서 이미 '받음'으로 남았으니 빼고, 처음부터 받은 것만
				if rg := r.Header.Get("Range"); rg != "" && !strings.HasPrefix(rg, "bytes=0-") {
					return
				}
				e.Act, e.Size = "받음", aw.n
			}
		case strings.HasSuffix(r.URL.Path, "/v1/move"):
			e.Act, e.Path, e.To = "이름 바꿈", q.Get("from"), q.Get("to")
			if tr := q.Get("toRoot"); tr != "" && tr != e.Root {
				e.Act, e.To = "옮김", tr+": "+e.To
			}
		case strings.HasSuffix(r.URL.Path, "/v1/mkdir"):
			e.Act = "폴더 만듦"
		case strings.HasSuffix(r.URL.Path, "/v1/restore"):
			e.Act = "되살림"
		}
		if e.Act != "" {
			actAppend(e)
		}
	}
}

func hAdminActivity(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	fRoot, fPC, fAct, fQ := q.Get("root"), q.Get("pc"), q.Get("act"), strings.ToLower(q.Get("q"))
	before, _ := strconv.ParseInt(q.Get("before"), 10, 64)
	limit, _ := strconv.Atoi(q.Get("limit"))
	if limit <= 0 || limit > 1000 {
		limit = 200
	}
	var files []string
	if ents, err := os.ReadDir(actDir()); err == nil {
		for _, d := range ents {
			if strings.HasSuffix(d.Name(), ".jsonl") {
				files = append(files, d.Name())
			}
		}
	}
	sort.Sort(sort.Reverse(sort.StringSlice(files))) // 최근 달부터
	out := []actEntry{}
	pcs := map[string]bool{}
	more := false
	scanned := 0
	for _, fn := range files {
		if more {
			break
		}
		f, err := os.Open(filepath.Join(actDir(), fn))
		if err != nil {
			continue
		}
		var lines []string
		sc := bufio.NewScanner(f)
		sc.Buffer(make([]byte, 64*1024), 1024*1024)
		for sc.Scan() {
			lines = append(lines, sc.Text())
		}
		f.Close()
		for i := len(lines) - 1; i >= 0; i-- {
			var e actEntry
			if json.Unmarshal([]byte(lines[i]), &e) != nil {
				continue
			}
			if scanned < 20000 {
				pcs[e.PC] = true
				scanned++
			}
			if before > 0 && e.T >= before {
				continue
			}
			if (fRoot != "" && e.Root != fRoot) || (fPC != "" && e.PC != fPC) || (fAct != "" && e.Act != fAct) {
				continue
			}
			if fQ != "" && !strings.Contains(strings.ToLower(e.Path+" "+e.To), fQ) {
				continue
			}
			if len(out) >= limit {
				more = true
				break
			}
			out = append(out, e)
		}
	}
	pl := []string{}
	for p := range pcs {
		pl = append(pl, p)
	}
	sort.Strings(pl)
	writeJSON(w, r, map[string]any{"items": out, "more": more, "pcs": pl})
}
