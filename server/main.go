// mydrived — Mydrive NAS 서버
//
// 환경 변수
//
//	MYDRIVE_ROOTS   이름=폴더 목록, 쉼표 구분   예) test=/data/MydriveTest,desktop=/data/DESKTOP
//	MYDRIVE_TOKEN   PC 앱이 보내야 하는 비밀 값 (Authorization: Bearer ...)
//	MYDRIVE_STATE   색인을 저장할 폴더 (기본 /state)
//	MYDRIVE_LISTEN  기본 :8642
//	MYDRIVE_RESCAN  주기적 전체 비교 간격 (기본 15m)
//
// API (모두 JSON, 경로는 공유 폴더 안의 상대 경로, 슬래시 구분)
//
//	GET    /v1/roots
//	GET    /v1/snapshot?root=                      전체 목록 + epoch + seq
//	GET    /v1/changes?root=&epoch=&since=&wait=   since 이후 변경 (wait초까지 기다렸다 답함)
//	GET    /v1/file?root=&path=                    내려받기 (Range 지원)
//	PUT    /v1/file?root=&path=&mtime=&if=         올리기 (if: none=없어야 함, s:m:i=그 상태여야 함, 생략=무조건)
//	DELETE /v1/file?root=&path=&if=                삭제 = #recycle 로 옮김
//	POST   /v1/move?root=&from=&to=&if=&toRoot=    이름 바꾸기·옮기기 (toRoot: 다른 연결로)
//	POST   /v1/mkdir?root=&path=
package main

import (
	"bufio"
	"compress/gzip"
	"crypto/rand"
	"crypto/subtle"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

var roots = map[string]*Root{}
var token string

const version = "0.6.11"

func main() {
	stateDir = env("MYDRIVE_STATE", "/state")
	listen := env("MYDRIVE_LISTEN", ":8642")
	log.SetOutput(io.MultiWriter(os.Stderr, srvLog))
	rescan, err := time.ParseDuration(env("MYDRIVE_RESCAN", "15m"))
	if err != nil {
		rescan = 15 * time.Minute
	}
	os.MkdirAll(stateDir, 0700)
	loadCfg()
	if token == "" {
		log.Fatal("동기화 토큰이 비어 있음 (MYDRIVE_TOKEN)")
	}
	for _, rc := range srvCfg.Roots {
		r, err := OpenRoot(rc.Name, rc.Dir, stateDir)
		if err != nil {
			log.Printf("[%s] 열 수 없음: %v", rc.Name, err)
			continue
		}
		roots[rc.Name] = r
	}
	go func() {
		for range time.Tick(2 * time.Minute) { // 색인 통째 복사·기록은 무거워서 2분마다 (꺼졌다 켜지면 디스크를 다시 훑어 이어 붙이므로 잃는 것 없음)
			for _, r := range rootList() {
				r.Save()
			}
		}
	}()
	go func() {
		for range time.Tick(rescan) {
			for _, r := range rootList() {
				r.Rescan()
			}
		}
	}()
	for _, r := range rootList() {
		r.Save()
	}
	// Docker 가 끄거나 다시 켤 때(SIGTERM): 상태를 저장하고 깨끗하게 끝냄
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGTERM, os.Interrupt)
	go func() { <-sig; log.Printf("끝내라는 신호 — 상태 저장 뒤 끝냄"); shutdown() }()
	versionsStart()
	devicesStart()

	mux := http.NewServeMux()
	mux.HandleFunc("/v1/roots", auth(hRoots))
	mux.HandleFunc("/v1/snapshot", auth(hSnapshot))
	mux.HandleFunc("/v1/list", auth(hList)) // 폴더 하나만 (휴대폰 앱)
	mux.HandleFunc("/v1/search", auth(hSearch)) // 이름 검색 (휴대폰 탐색기)
	mux.HandleFunc("/v1/changes", auth(hChanges))
	mux.HandleFunc("/v1/rescan", auth(hRescan))     // PC 앱의 'NAS 다시 확인' — 실시간 감시가 못 보는 폴더까지 지금 전체 비교
	mux.HandleFunc("/v1/file", auth(logged(hFile))) // logged: 동기화 기록 (activity.go)
	mux.HandleFunc("/v1/batch", auth(hBatch))
	mux.HandleFunc("/v1/part", auth(hPart)) // 큰 파일 나눠 올리기 (Cloudflare 터널은 한 번에 100MB 까지)
	go partJanitor()                        // 작은 파일 묶어 올리기 (기록은 안에서 파일마다)
	mux.HandleFunc("/v1/move", auth(logged(hMove)))
	mux.HandleFunc("/v1/mkdir", auth(logged(hMkdir)))
	mux.HandleFunc("/v1/hash", auth(hHash))
	mux.HandleFunc("/v1/versions", auth(hVersions))
	mux.HandleFunc("/v1/version", auth(hVersion))
	mux.HandleFunc("/v1/restore", auth(logged(hRestore)))
	mux.HandleFunc("/v1/deleted", auth(hDeleted))
	mux.HandleFunc("/v1/update/latest", auth(hUpdateLatest)) // PC 앱 새 판 (update.go, 내려주기만)
	mux.HandleFunc("/v1/update/file", auth(hUpdateFile))
	mux.HandleFunc("/v1/admin/info", adminAuth(hAdminInfo))
	mux.HandleFunc("/v1/admin/activity", adminAuth(hAdminActivity))
	mux.HandleFunc("/v1/admin/browse", adminAuth(hAdminBrowse))
	mux.HandleFunc("/v1/admin/roots", adminAuth(hAdminRoots))
	mux.HandleFunc("/v1/admin/policy", adminAuth(hAdminPolicy))
	mux.HandleFunc("/v1/admin/token", adminAuth(hAdminToken))
	mux.HandleFunc("/v1/admin/devices", adminAuth(hAdminDevices))
	mux.HandleFunc("/v1/admin/whoami", adminAuth(hAdminWhoami))
	mux.HandleFunc("/v1/admin/login", hConsoleLogin) // DSM 계정으로 관리 콘솔 (토큰 없이)
	mux.HandleFunc("/v1/admin/logout", hConsoleLogout)
	mux.HandleFunc("/v1/pair", hPair) // DSM 계정으로 PC 연결 (토큰 없이)
	// 다시 켜기: 프로세스를 끝내면 Docker 가 다시 띄우면서 최신 실행 파일을 복사해 씀
	restart := func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, r, map[string]any{"ok": true})
		go func() { time.Sleep(300 * time.Millisecond); shutdown() }()
	}
	mux.HandleFunc("/v1/admin/restart", func(w http.ResponseWriter, r *http.Request) {
		// 관리 토큰 또는 (예전 방식) 동기화 토큰
		got := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		cfgMu.Lock()
		ok := (adminToken != "" && subtle.ConstantTimeCompare([]byte(got), []byte(adminToken)) == 1) || subtle.ConstantTimeCompare([]byte(got), []byte(token)) == 1
		cfgMu.Unlock()
		if !ok {
			adminAuth(restart)(w, r) // DSM 관리자 세션으로 연 콘솔
			return
		}
		restart(w, r)
	})
	mux.HandleFunc("/admin", hAdminPage)
	mux.HandleFunc("/", hRootRedirect)
	mux.HandleFunc("/v1/ping", func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("mydrived " + version + "\n")) })
	log.Printf("mydrived %s 시작: %s (공유 폴더 %d개)", version, listen, len(roots))
	srv := &http.Server{Addr: listen, Handler: tunnelGuard(mux), ReadHeaderTimeout: 30 * time.Second}
	log.Fatal(srv.ListenAndServe())
}
func env(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}

func auth(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		got := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		cfgMu.Lock()
		tk := token
		cfgMu.Unlock()
		if (got == "" || subtle.ConstantTimeCompare([]byte(got), []byte(tk)) != 1) && deviceByToken(got) == nil {
			httpErr(w, 401, "unauthorized", "토큰이 맞지 않음")
			return
		}
		noteClient(r)
		h(w, r)
	}
}

func httpErr(w http.ResponseWriter, code int, kind, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(map[string]string{"error": kind, "msg": msg})
}

func writeJSON(w http.ResponseWriter, r *http.Request, v any) {
	w.Header().Set("Content-Type", "application/json")
	if strings.Contains(r.Header.Get("Accept-Encoding"), "gzip") {
		w.Header().Set("Content-Encoding", "gzip")
		gz := gzip.NewWriter(w)
		json.NewEncoder(gz).Encode(v)
		gz.Close()
		return
	}
	json.NewEncoder(w).Encode(v)
}

// 경로 검사: 상대 경로, .. 없음, 시놀로지 내부 폴더 아님
func cleanRel(p string) (string, bool) {
	p = strings.Trim(strings.ReplaceAll(p, "\\", "/"), "/")
	if p == "" {
		return "", false
	}
	c := path.Clean(p)
	if c != p || c == "." || strings.HasPrefix(c, "../") || c == ".." || skipRel(c) {
		return "", false
	}
	return c, true
}

func getRoot(w http.ResponseWriter, r *http.Request) *Root {
	rootsMu.RLock()
	rt := roots[r.URL.Query().Get("root")]
	rootsMu.RUnlock()
	if rt == nil {
		httpErr(w, 404, "no_root", "그런 연결이 없음")
	}
	return rt
}

// hList: 폴더 하나의 바로 아래 항목 — GET /v1/list?root=&path=  (path 가 비면 맨 위)
func hList(w http.ResponseWriter, r *http.Request) {
	rt := getRoot(w, r)
	if rt == nil {
		return
	}
	dir := strings.Trim(r.URL.Query().Get("path"), "/")
	if strings.Contains(dir, "..") {
		httpErr(w, 400, "bad_path", "경로가 올바르지 않음")
		return
	}
	es, ok := rt.List(dir)
	if !ok {
		httpErr(w, 404, "not_found", "그런 폴더가 없음")
		return
	}
	writeJSON(w, r, map[string]any{"path": dir, "entries": es})
}

// hSearch: GET /v1/search?root=&q=&ext=jpg,pdf&path=&limit=  → {"entries":[...], "more":bool}
func hSearch(w http.ResponseWriter, r *http.Request) {
	rt := getRoot(w, r)
	if rt == nil {
		return
	}
	qv := r.URL.Query()
	under := strings.Trim(qv.Get("path"), "/")
	if strings.Contains(under, "..") {
		httpErr(w, 400, "bad_path", "경로가 올바르지 않음")
		return
	}
	var exts []string
	for _, x := range strings.Split(strings.ToLower(qv.Get("ext")), ",") {
		if x = strings.Trim(strings.TrimSpace(x), "."); x != "" {
			exts = append(exts, x)
		}
	}
	q := strings.TrimSpace(qv.Get("q"))
	if q == "" && len(exts) == 0 {
		httpErr(w, 400, "bad_request", "검색어나 확장자가 필요함")
		return
	}
	limit, _ := strconv.Atoi(qv.Get("limit"))
	if limit <= 0 || limit > 2000 {
		limit = 500
	}
	es, more := rt.Search(under, q, exts, limit)
	writeJSON(w, r, map[string]any{"entries": es, "more": more})
}

// 인터넷(Cloudflare 터널)으로 들어온 요청인가 — 터널은 요청마다 CF-Connecting-IP 를 붙여 보냄
func viaTunnel(r *http.Request) bool {
	return r.Header.Get("Cf-Connecting-Ip") != "" || r.Header.Get("Cf-Ray") != ""
}

// tunnelGuard: 터널로 들어온 요청은 관리 화면·관리 기능을 막음 (집 안이나 DSM 창에서만). 동기화·휴대폰 앱 기능은 그대로
func tunnelGuard(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if viaTunnel(r) && (r.URL.Path == "/admin" || strings.HasPrefix(r.URL.Path, "/v1/admin/")) {
			httpErr(w, 403, "forbidden", "관리 화면은 집 안에서나 DSM 에서만 열 수 있습니다")
			return
		}
		h.ServeHTTP(w, r)
	})
}

func hRoots(w http.ResponseWriter, r *http.Request) {
	out := []map[string]any{}
	for _, rt := range rootList() {
		e, s, n := rt.Info() // 예전에는 항목 수를 세려고 전체 목록(drive 19만 개)을 만들어 정렬했음
		// share: NAS 공유 폴더 기준 경로 (예: /DESKTOP/작업) — PC 앱의 'NAS 경로 복사'가 \\NAS\DESKTOP\작업 으로 만듦
		share := ""
		if d := filepath.Clean(rt.Dir); strings.HasPrefix(d, filepath.Clean(nasBase)+"/") {
			share = strings.TrimPrefix(d, filepath.Clean(nasBase))
		}
		total, free := diskSpace(rt.Dir)
		out = append(out, map[string]any{"name": rt.Name, "epoch": e, "seq": s, "items": n, "share": share, "total": total, "free": free})
	}
	writeJSON(w, r, out)
}

// hRescan: 그 연결 전체를 지금 디스크와 비교해 바뀐 것을 변경 기록에 넣음
//
//	(File Station·SMB 로 바꾼 것 중 inotify 한도 때문에 감시하지 못하는 폴더의 것 — 평소에는 MYDRIVE_RESCAN 주기마다)
func hRescan(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		httpErr(w, 405, "method", "POST 만")
		return
	}
	rt := getRoot(w, r)
	if rt == nil {
		return
	}
	rt.rescanMu.Lock() // 여러 PC 가 한꺼번에 눌러도 한 번씩만
	defer rt.rescanMu.Unlock()
	t0 := time.Now()
	cur, _ := rt.walk()
	n := rt.diffInto(cur)
	if n > 0 {
		log.Printf("[%s] 다시 확인(PC 요청): %d개 변경 반영 (%v)", rt.Name, n, time.Since(t0).Round(time.Millisecond))
	}
	writeJSON(w, r, map[string]any{"changed": n, "ms": time.Since(t0).Milliseconds()})
}

// 전체 목록·내용 해시는 무거워서 동시에 몇 개만 (PC 여럿이 한꺼번에 처음 맞출 때 NAS 가 버티게)
var heavySem = make(chan struct{}, 2)

func hSnapshot(w http.ResponseWriter, r *http.Request) {
	rt := getRoot(w, r)
	if rt == nil {
		return
	}
	heavySem <- struct{}{}
	defer func() { <-heavySem }()
	ep, seq, es := rt.Snapshot()
	writeJSON(w, r, map[string]any{"epoch": ep, "seq": seq, "entries": es})
}

func hChanges(w http.ResponseWriter, r *http.Request) {
	rt := getRoot(w, r)
	if rt == nil {
		return
	}
	q := r.URL.Query()
	since, _ := strconv.ParseInt(q.Get("since"), 10, 64)
	wait, _ := strconv.Atoi(q.Get("wait"))
	if wait > 55 {
		wait = 55
	}
	deadline := time.After(time.Duration(wait) * time.Second)
	for {
		ch, seq, reset, notify := rt.Changes(q.Get("epoch"), since, 20000)
		if reset || len(ch) > 0 || wait == 0 {
			writeJSON(w, r, map[string]any{"reset": reset, "seq": seq, "changes": ch})
			return
		}
		select {
		case <-notify:
			time.Sleep(150 * time.Millisecond) // 한꺼번에 생기는 변경을 묶어서 보냄
		case <-deadline:
			writeJSON(w, r, map[string]any{"reset": false, "seq": seq, "changes": []Change{}})
			return
		case <-r.Context().Done():
			return
		}
	}
}

// if 조건 검사. cur = 지금 상태
func checkIf(cond string, cur Entry, exists bool) bool {
	switch cond {
	case "":
		return true
	case "none":
		return !exists
	case "dir":
		return exists && cur.D
	}
	if !exists {
		return false
	}
	parts := strings.Split(cond, ":")
	if len(parts) < 2 {
		return false
	}
	s, _ := strconv.ParseInt(parts[0], 10, 64)
	m, _ := strconv.ParseInt(parts[1], 10, 64)
	if cur.D || cur.S != s || cur.M != m {
		return false
	}
	if len(parts) >= 3 && parts[2] != "" {
		i, _ := strconv.ParseUint(parts[2], 10, 64)
		if i != cur.I {
			return false
		}
	}
	return true
}

// 디스크의 지금 상태 (색인보다 정확)
func statRel(rt *Root, rel string) (Entry, bool) {
	fi, err := os.Lstat(filepath.Join(rt.Dir, filepath.FromSlash(rel)))
	if err != nil {
		return Entry{}, false
	}
	return entryOf(rel, fi), true
}

func randHex() string {
	b := make([]byte, 6)
	rand.Read(b)
	return hex.EncodeToString(b)
}

func hFile(w http.ResponseWriter, r *http.Request) {
	rt := getRoot(w, r)
	if rt == nil {
		return
	}
	rel, ok := cleanRel(r.URL.Query().Get("path"))
	if !ok {
		httpErr(w, 400, "bad_path", "경로가 올바르지 않음")
		return
	}
	abs := filepath.Join(rt.Dir, filepath.FromSlash(rel))
	switch r.Method {
	case http.MethodGet:
		f, err := os.Open(abs)
		if err != nil {
			httpErr(w, 404, "not_found", "파일이 없음")
			return
		}
		defer f.Close()
		fi, _ := f.Stat()
		if fi.IsDir() {
			httpErr(w, 400, "is_dir", "폴더임")
			return
		}
		e := entryOf(rel, fi)
		w.Header().Set("X-Mydrive-Entry", fmt.Sprintf("%d:%d:%d", e.S, e.M, e.I))
		http.ServeContent(w, r, "", fi.ModTime(), f)
	case http.MethodPut:
		putFile(w, r, rt, rel, abs)
	case http.MethodDelete:
		cur, exists := statRel(rt, rel)
		if !exists {
			rt.Apply(rel)
			writeJSON(w, r, map[string]any{"ok": true, "gone": true})
			return
		}
		if !checkIf(r.URL.Query().Get("if"), cur, exists) {
			httpErr(w, 409, "conflict", "그사이 NAS에서 바뀜")
			return
		}
		saveVersionsUnder(rt, rel, abs, cur.D) // 지운 파일도 '이전 버전'으로 되살릴 수 있게
		dst := recyclePath(rt, rel)
		os.MkdirAll(filepath.Dir(dst), 0777)
		if err := os.Rename(abs, dst); err != nil {
			httpErr(w, 500, "io", err.Error())
			return
		}
		rt.Apply(rel)
		writeJSON(w, r, map[string]any{"ok": true, "recycle": strings.TrimPrefix(filepath.ToSlash(dst), filepath.ToSlash(rt.Dir)+"/")})
	default:
		httpErr(w, 405, "method", "지원하지 않음")
	}
}

// 시놀로지 휴지통(#recycle)과 같은 자리 — 이미 있으면 이름 뒤에 번호
func recyclePath(rt *Root, rel string) string {
	base := filepath.Join(rt.Dir, "#recycle", filepath.FromSlash(rel))
	if _, err := os.Lstat(base); err != nil {
		return base
	}
	ext := filepath.Ext(base)
	stem := strings.TrimSuffix(base, ext)
	for n := 1; ; n++ {
		p := fmt.Sprintf("%s (%d)%s", stem, n, ext)
		if _, err := os.Lstat(p); err != nil {
			return p
		}
	}
}

func putFile(w http.ResponseWriter, r *http.Request, rt *Root, rel, abs string) {
	q := r.URL.Query()
	mt, err := strconv.ParseInt(q.Get("mtime"), 10, 64)
	if err != nil {
		httpErr(w, 400, "bad_mtime", "mtime(ns) 필요")
		return
	}
	code, e, kind, msg := putOne(rt, rel, abs, mt, q.Get("if"), r.Body, r.ContentLength)
	if code != 200 {
		httpErr(w, code, kind, msg)
		return
	}
	writeJSON(w, r, e)
}

// 파일 하나 받아 넣기 (/v1/file PUT 과 /v1/batch 공통). size<0 이면 길이를 모름.
// 조건이 맞지 않아도 내용은 끝까지 읽음 (묶음에서 다음 파일 자리가 어긋나지 않게).
func putOne(rt *Root, rel, abs string, mt int64, cond string, body io.Reader, size int64) (int, Entry, string, string) {
	dir := filepath.Dir(abs)
	if err := os.MkdirAll(dir, 0777); err != nil {
		if size >= 0 {
			io.CopyN(io.Discard, body, size)
		}
		return 500, Entry{}, "io", err.Error()
	}
	tmp := filepath.Join(dir, ".mydrive-tmp-"+randHex())
	f, err := os.OpenFile(tmp, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0666)
	if err != nil {
		if size >= 0 {
			io.CopyN(io.Discard, body, size)
		}
		return 500, Entry{}, "io", err.Error()
	}
	n, err := io.CopyBuffer(f, body, make([]byte, 1<<20))
	if err == nil && size >= 0 && n != size {
		err = fmt.Errorf("받은 크기 %d ≠ %d", n, size)
	}
	if err == nil && os.Getenv("MYDRIVE_FSYNC") == "1" {
		err = f.Sync()
	} else {
		needSync.Store(true) // 파일마다 하면 NAS 디스크가 느려서, 2초에 한 번 몰아서 디스크에 기록
	}
	f.Close()
	if err != nil {
		os.Remove(tmp)
		return 400, Entry{}, "upload", "올리기가 끊김: " + err.Error()
	}
	return commitTmp(rt, rel, abs, tmp, mt, cond)
}

// commitTmp: 다 받은 임시 파일을 제자리로 (조건 확인 → 덮을 판은 이전 버전으로 → 바꿔 넣기)
func commitTmp(rt *Root, rel, abs, tmp string, mt int64, cond string) (int, Entry, string, string) {
	t := time.Unix(0, mt)
	os.Chtimes(tmp, t, t)
	// 조건 확인과 교체는 짧게 붙여서 (그 사이에 NAS 쪽이 바뀌면 덮지 않음)
	cur, exists := statRel(rt, rel)
	if exists && cur.D {
		os.Remove(tmp)
		return 409, Entry{}, "is_dir", "같은 이름의 폴더가 있음"
	}
	if !checkIf(cond, cur, exists) {
		os.Remove(tmp)
		return 409, Entry{}, "conflict", "그사이 NAS에서 바뀜"
	}
	if exists {
		saveVersion(rt, rel, abs) // 덮기 전 판을 '이전 버전'으로 남김
	}
	if err := os.Rename(tmp, abs); err != nil {
		os.Remove(tmp)
		return 500, Entry{}, "io", err.Error()
	}
	// 새로 생긴 위 폴더도 바로 목록에 (감시가 알아채기까지 0.3초쯤 걸려, 그 사이 목록에 파일만 있고 폴더가 없었음)
	var newDirs []string
	for d := parentOf(rel); d != ""; d = parentOf(d) {
		if _, ok := rt.Get(d); ok {
			break
		}
		newDirs = append(newDirs, d)
	}
	for i := len(newDirs) - 1; i >= 0; i-- {
		rt.Apply(newDirs[i])
	}
	rt.Apply(rel)
	e, _ := statRel(rt, rel)
	return 200, e, "", ""
}

// PUT /v1/batch?root= — 작은 파일 여러 개를 한 요청으로 올림 (PC 앱 0.5.25부터).
// 몸통: [4바이트(little-endian) 머리 길이][머리 JSON {"p","m","if","s"}][내용 s바이트] … 길이 0 으로 끝.
// 파일마다 /v1/file PUT 과 같은 처리를 하고 {"results":[{"status":200,"e":{…}} | {"status":409,"err":{…}}]} 를 차례대로 돌려줌.
// 한 파일이 실패해도 나머지는 계속. 몸통이 중간에 끊기면 남은 파일은 결과에서 빠짐 (PC 앱이 다음 차례에 다시 올림).
func hBatch(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPut && r.Method != http.MethodPost {
		httpErr(w, 405, "method", "지원하지 않음")
		return
	}
	rt := getRoot(w, r)
	if rt == nil {
		return
	}
	const maxFiles, maxHead = 256, 64 * 1024
	type head struct {
		P  string `json:"p"`
		M  int64  `json:"m"`
		If string `json:"if"`
		S  int64  `json:"s"`
	}
	br := bufio.NewReaderSize(r.Body, 1<<20)
	pc, ip := reqPC(r), clientIP(r)
	if i := strings.LastIndex(ip, ":"); i > 0 {
		ip = strings.Trim(ip[:i], "[]")
	}
	results := []map[string]any{}
	for len(results) < maxFiles {
		var lb [4]byte
		if _, err := io.ReadFull(br, lb[:]); err != nil {
			break
		}
		hl := binary.LittleEndian.Uint32(lb[:])
		if hl == 0 {
			break
		}
		if hl > maxHead {
			break
		}
		hb := make([]byte, hl)
		if _, err := io.ReadFull(br, hb); err != nil {
			break
		}
		var h head
		if json.Unmarshal(hb, &h) != nil || h.S < 0 {
			break // 머리를 못 읽으면 내용 길이를 몰라 뒤를 이어 읽을 수 없음
		}
		rel, ok := cleanRel(h.P)
		if !ok {
			io.CopyN(io.Discard, br, h.S)
			results = append(results, map[string]any{"status": 400, "err": map[string]string{"error": "bad_path", "msg": "경로가 올바르지 않음"}})
			continue
		}
		abs := filepath.Join(rt.Dir, filepath.FromSlash(rel))
		code, e, kind, msg := putOne(rt, rel, abs, h.M, h.If, io.LimitReader(br, h.S), h.S)
		if code != 200 {
			results = append(results, map[string]any{"status": code, "err": map[string]string{"error": kind, "msg": msg}})
			if kind == "upload" {
				break // 내용이 덜 왔음 → 뒤는 자리가 어긋남
			}
			continue
		}
		actAppend(actEntry{T: time.Now().UnixNano(), PC: pc, IP: ip, Root: rt.Name, Path: rel, Act: "올림", Size: h.S})
		results = append(results, map[string]any{"status": 200, "e": e})
	}
	writeJSON(w, r, map[string]any{"results": results})
}

func hMove(w http.ResponseWriter, r *http.Request) {
	rt := getRoot(w, r)
	if rt == nil {
		return
	}
	q := r.URL.Query()
	from, ok1 := cleanRel(q.Get("from"))
	to, ok2 := cleanRel(q.Get("to"))
	if !ok1 || !ok2 {
		httpErr(w, 400, "bad_path", "경로가 올바르지 않음")
		return
	}
	cur, exists := statRel(rt, from)
	if !exists {
		httpErr(w, 404, "not_found", "옮길 것이 없음")
		return
	}
	if !checkIf(q.Get("if"), cur, exists) {
		httpErr(w, 409, "conflict", "그사이 NAS에서 바뀜")
		return
	}
	// toRoot: 다른 연결(공유 폴더)로 옮김 — PC 에서 동기화 작업 사이로 옮긴 것. 받기·올리기 없이 NAS 안에서 바로 옮김
	dt := rt
	if tr := q.Get("toRoot"); tr != "" && tr != rt.Name {
		rootsMu.RLock()
		dt = roots[tr]
		rootsMu.RUnlock()
		if dt == nil {
			httpErr(w, 404, "no_root", "옮길 연결이 없음")
			return
		}
	}
	if _, ex := statRel(dt, to); ex {
		httpErr(w, 409, "exists", "옮길 자리에 이미 있음")
		return
	}
	dst := filepath.Join(dt.Dir, filepath.FromSlash(to))
	os.MkdirAll(filepath.Dir(dst), 0777)
	srcAbs := filepath.Join(rt.Dir, filepath.FromSlash(from))
	if err := os.Rename(srcAbs, dst); err != nil {
		if !errors.Is(err, syscall.EXDEV) {
			httpErr(w, 500, "io", err.Error())
			return
		}
		// 다른 공유 폴더(btrfs 하위 볼륨)는 rename 이 안 됨 → 복사(reflink) 뒤 원본 지움 (xcopy.go)
		if err := moveAcross(srcAbs, dst); err != nil {
			rt.Apply(from)
			dt.Apply(to)
			httpErr(w, 500, "io", "다른 공유 폴더로 옮기기: "+err.Error())
			return
		}
	}
	moveVersionsTo(rt, from, dt, to)
	rt.Apply(from)
	dt.Apply(to)
	e, _ := statRel(dt, to)
	writeJSON(w, r, e)
}

func hMkdir(w http.ResponseWriter, r *http.Request) {
	rt := getRoot(w, r)
	if rt == nil {
		return
	}
	rel, ok := cleanRel(r.URL.Query().Get("path"))
	if !ok {
		httpErr(w, 400, "bad_path", "경로가 올바르지 않음")
		return
	}
	if err := os.MkdirAll(filepath.Join(rt.Dir, filepath.FromSlash(rel)), 0777); err != nil {
		httpErr(w, 500, "io", err.Error())
		return
	}
	rt.Apply(rel)
	e, _ := statRel(rt, rel)
	writeJSON(w, r, e)
}

// 끝내기: 감시를 멈추고(더 바뀌지 않게) 깨끗하게 끝났다는 표시와 함께 저장한 뒤 끝냄
var shutdownOnce sync.Once

func shutdown() {
	shutdownOnce.Do(func() {
		for _, r := range rootList() {
			r.Close()
			r.SaveClean()
		}
		os.Exit(0)
	})
}
