// admin.go — 관리 기능 (PC 앱의 'NAS 관리' 창이 씀). 관리 토큰은 동기화 토큰과 따로.
//
// 설정 파일: <MYDRIVE_STATE>/mydrive.json  (처음에는 환경 변수로 만들고, 그 뒤로는 이 파일이 기준)
// NAS 폴더 고르기 범위: MYDRIVE_NAS_BASE (기본 /nas — compose 에서 /volume1 을 여기에 붙임)
//
//   GET  /v1/admin/info                   서버 상태, 연결(공유 폴더) 목록, 버전 보관량, 접속한 PC, 최근 기록
//   GET  /v1/admin/browse?path=           NAS 폴더 안의 하위 폴더 목록 (폴더 고르기)
//   POST /v1/admin/roots   {"add":{"name":"","dir":""}} | {"remove":"이름"}
//   POST /v1/admin/policy  {"maxCount":32,"keepDays":90}
//   POST /v1/admin/token   {"which":"sync"|"admin"}  → 새 토큰
//   POST /v1/admin/restart
package main

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"
)

type RootCfg struct {
	Name string `json:"name"`
	Dir  string `json:"dir"`
}

type ServerCfg struct {
	Roots      []RootCfg `json:"roots"`
	Versions   VerPolicy `json:"versions"`
	SyncToken  string    `json:"syncToken"`
	AdminToken string    `json:"adminToken"`
	Devices    []Device  `json:"devices"`
	Consoles   []ConsoleSess `json:"consoles"` // DSM 계정으로 들어온 관리 콘솔 (해시, 30일) // DSM 계정으로 연결한 PC 마다의 토큰 (해시)
}

var (
	cfgMu      sync.Mutex
	srvCfg     ServerCfg
	cfgPath    string
	stateDir   string
	nasBase    string
	adminToken string
	started    = time.Now()
	rootsMu    sync.RWMutex
)

func rootList() []*Root {
	rootsMu.RLock()
	defer rootsMu.RUnlock()
	out := make([]*Root, 0, len(roots))
	for _, r := range roots {
		out = append(out, r)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name < out[j].Name })
	return out
}

func saveCfg() error {
	cfgMu.Lock()
	b, _ := json.MarshalIndent(srvCfg, "", "  ")
	cfgMu.Unlock()
	tmp := cfgPath + ".tmp"
	if err := os.WriteFile(tmp, b, 0600); err != nil {
		return err
	}
	return os.Rename(tmp, cfgPath)
}

// 설정 읽기: 파일이 없으면 환경 변수로 처음 만듦
func loadCfg() {
	cfgPath = filepath.Join(stateDir, "mydrive.json")
	nasBase = env("MYDRIVE_NAS_BASE", "/nas")
	if b, err := os.ReadFile(cfgPath); err == nil && json.Unmarshal(b, &srvCfg) == nil {
		log.Printf("설정 파일 사용: %s (공유 폴더 %d개)", cfgPath, len(srvCfg.Roots))
	} else {
		for _, kv := range strings.Split(os.Getenv("MYDRIVE_ROOTS"), ",") {
			if name, dir, ok := strings.Cut(strings.TrimSpace(kv), "="); ok {
				srvCfg.Roots = append(srvCfg.Roots, RootCfg{name, dir})
			}
		}
		srvCfg.Versions = verPolicy
		srvCfg.SyncToken = os.Getenv("MYDRIVE_TOKEN")
	}
	if t := os.Getenv("MYDRIVE_ADMIN_TOKEN"); t != "" && srvCfg.AdminToken == "" {
		srvCfg.AdminToken = t
	}
	if srvCfg.SyncToken == "" {
		srvCfg.SyncToken = os.Getenv("MYDRIVE_TOKEN")
	}
	if srvCfg.Versions.MaxCount == 0 && srvCfg.Versions.KeepDays == 0 {
		srvCfg.Versions = VerPolicy{MaxCount: 32, KeepDays: 90}
	}
	token, adminToken, verPolicy = srvCfg.SyncToken, srvCfg.AdminToken, srvCfg.Versions
	saveCfg()
}

// ---------- 접속한 PC ----------
type clientInfo struct {
	PC    string `json:"pc"`
	IP    string `json:"ip"`
	Last  int64  `json:"last"` // ns
	Calls int64  `json:"calls"`
}

var clients = map[string]*clientInfo{}
var clientsMu sync.Mutex

func noteClient(r *http.Request) {
	pc := r.Header.Get("X-Mydrive-PC")
	if u, err := url.QueryUnescape(pc); err == nil { // 한글 이름은 %XX 로 옴
		pc = u
	}
	ip, _, _ := net.SplitHostPort(r.RemoteAddr)
	if pc == "" {
		pc = "(이름 없음)"
	}
	k := pc + "|" + ip
	clientsMu.Lock()
	c := clients[k]
	if c == nil {
		c = &clientInfo{PC: pc, IP: ip}
		clients[k] = c
	}
	c.Last = time.Now().UnixNano()
	c.Calls++
	clientsMu.Unlock()
}

// ---------- 최근 서버 기록 (관리 창에서 봄) ----------
type ringLog struct {
	mu    sync.Mutex
	lines []string
}

func (l *ringLog) Write(p []byte) (int, error) {
	l.mu.Lock()
	for _, s := range strings.Split(strings.TrimRight(string(p), "\n"), "\n") {
		l.lines = append(l.lines, s)
	}
	if len(l.lines) > 500 {
		l.lines = append([]string(nil), l.lines[len(l.lines)-500:]...)
	}
	l.mu.Unlock()
	return len(p), nil
}

func (l *ringLog) Tail(n int) []string {
	l.mu.Lock()
	defer l.mu.Unlock()
	if len(l.lines) < n {
		n = len(l.lines)
	}
	return append([]string(nil), l.lines[len(l.lines)-n:]...)
}

var srvLog = &ringLog{}

func adminAuth(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		got := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		cfgMu.Lock()
		at := adminToken
		cfgMu.Unlock()
		if at != "" && got != "" && subtle.ConstantTimeCompare([]byte(got), []byte(at)) == 1 {
			h(w, r.WithContext(context.WithValue(r.Context(), ctxUser{}, "관리 토큰")))
			return
		}
		// DSM 계정으로 로그인한 콘솔 (쿠키)
		if user, ok := consoleUser(r); ok {
			h(w, r.WithContext(context.WithValue(r.Context(), ctxUser{}, "DSM "+user)))
			return
		}
		// DSM 메뉴에서 연 콘솔: DSM 관리자 세션
		user, ok, why := dsmConsoleAdmin(r)
		if ok {
			h(w, r.WithContext(context.WithValue(r.Context(), ctxUser{}, "DSM "+user)))
			return
		}
		if got == "" && why != "" {
			httpErr(w, 401, "unauthorized", why)
			return
		}
		httpErr(w, 401, "unauthorized", "관리 토큰이 맞지 않음")
	}
}

func hAdminInfo(w http.ResponseWriter, r *http.Request) {
	type rootInfo struct {
		Name     string `json:"name"`
		Dir      string `json:"dir"`
		Items    int    `json:"items"`
		VerFiles int    `json:"verFiles"`
		VerBytes int64  `json:"verBytes"`
	}
	ris := []rootInfo{}
	for _, rt := range rootList() {
		_, _, es := rt.Snapshot()
		vf, vb := versionsSize(rt)
		ris = append(ris, rootInfo{rt.Name, rt.Dir, len(es), vf, vb})
	}
	clientsMu.Lock()
	cs := []clientInfo{}
	for _, c := range clients {
		cs = append(cs, *c)
	}
	clientsMu.Unlock()
	sort.Slice(cs, func(i, j int) bool { return cs[i].Last > cs[j].Last })
	verMu.Lock()
	pol := verPolicy
	verMu.Unlock()
	writeJSON(w, r, map[string]any{
		"version": version, "started": started.UnixNano(), "nasBase": nasBase,
		"roots": ris, "policy": pol, "clients": cs, "log": srvLog.Tail(200),
	})
}

// NAS 폴더 고르기: nasBase 안만
func insideBase(p string) (string, bool) {
	c := filepath.Clean("/" + strings.TrimPrefix(filepath.ToSlash(p), "/"))
	base := filepath.Clean(nasBase)
	if c != base && !strings.HasPrefix(c, base+"/") {
		return "", false
	}
	if real, err := filepath.EvalSymlinks(c); err == nil {
		if real != base && !strings.HasPrefix(real, base+"/") {
			return "", false
		}
	}
	return c, true
}

func hAdminBrowse(w http.ResponseWriter, r *http.Request) {
	p := r.URL.Query().Get("path")
	if p == "" {
		p = nasBase
	}
	dir, ok := insideBase(p)
	if !ok {
		httpErr(w, 400, "outside", "NAS 공유 폴더 밖")
		return
	}
	ents, err := os.ReadDir(dir)
	if err != nil {
		httpErr(w, 404, "not_found", err.Error())
		return
	}
	dirs := []string{}
	for _, e := range ents {
		n := e.Name()
		if !e.IsDir() || strings.HasPrefix(n, "@") || strings.HasPrefix(n, "#") || strings.HasPrefix(n, ".") {
			continue
		}
		dirs = append(dirs, n)
	}
	sort.Strings(dirs)
	writeJSON(w, r, map[string]any{"path": dir, "base": nasBase, "dirs": dirs})
}

var rootName = regexp.MustCompile(`^[\p{L}\p{N}_\-]{1,40}$`)

func addRoot(name, dir string) error {
	if !rootName.MatchString(name) {
		return errors.New("이름은 글자·숫자·_·- 로 40자까지")
	}
	d, ok := insideBase(dir)
	if !ok {
		return errors.New("NAS 공유 폴더(" + nasBase + ") 안의 폴더만 고를 수 있음")
	}
	if fi, err := os.Stat(d); err != nil || !fi.IsDir() {
		return errors.New("폴더가 없음: " + d)
	}
	rootsMu.RLock()
	for n, rt := range roots {
		if n == name {
			rootsMu.RUnlock()
			return errors.New("같은 이름이 이미 있음")
		}
		a, b := filepath.Clean(rt.Dir), d
		if a == b || strings.HasPrefix(a, b+"/") || strings.HasPrefix(b, a+"/") {
			rootsMu.RUnlock()
			return errors.New("'" + n + "' 폴더와 겹침 (서로 안쪽·바깥쪽)")
		}
	}
	rootsMu.RUnlock()
	rt, err := OpenRoot(name, d, stateDir)
	if err != nil {
		return err
	}
	rootsMu.Lock()
	roots[name] = rt
	rootsMu.Unlock()
	rt.Save()
	cfgMu.Lock()
	srvCfg.Roots = append(srvCfg.Roots, RootCfg{name, d})
	cfgMu.Unlock()
	log.Printf("공유 폴더 추가: %s = %s", name, d)
	return saveCfg()
}

func removeRoot(name string) error {
	rootsMu.Lock()
	rt := roots[name]
	delete(roots, name)
	rootsMu.Unlock()
	if rt == nil {
		return errors.New("그런 이름이 없음")
	}
	rt.Save()
	rt.Close()
	cfgMu.Lock()
	var keep []RootCfg
	for _, c := range srvCfg.Roots {
		if c.Name != name {
			keep = append(keep, c)
		}
	}
	srvCfg.Roots = keep
	cfgMu.Unlock()
	log.Printf("공유 폴더 뺌: %s (파일은 그대로)", name)
	return saveCfg()
}

func hAdminRoots(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		httpErr(w, 405, "method", "POST 만")
		return
	}
	var req struct {
		Add    *RootCfg `json:"add"`
		Remove string   `json:"remove"`
	}
	if json.NewDecoder(r.Body).Decode(&req) != nil {
		httpErr(w, 400, "bad_json", "요청 형식 오류")
		return
	}
	var err error
	if req.Add != nil {
		err = addRoot(req.Add.Name, req.Add.Dir)
	} else if req.Remove != "" {
		err = removeRoot(req.Remove)
	}
	if err != nil {
		httpErr(w, 400, "rejected", err.Error())
		return
	}
	writeJSON(w, r, map[string]any{"ok": true})
}

func hAdminPolicy(w http.ResponseWriter, r *http.Request) {
	var p VerPolicy
	if r.Method != http.MethodPost || json.NewDecoder(r.Body).Decode(&p) != nil || p.MaxCount < 0 || p.KeepDays < 0 {
		httpErr(w, 400, "bad_json", "요청 형식 오류")
		return
	}
	verMu.Lock()
	verPolicy = p
	verMu.Unlock()
	cfgMu.Lock()
	srvCfg.Versions = p
	cfgMu.Unlock()
	log.Printf("버전 보관 규칙: 파일마다 %d개, %d일", p.MaxCount, p.KeepDays)
	if err := saveCfg(); err != nil {
		httpErr(w, 500, "io", err.Error())
		return
	}
	writeJSON(w, r, map[string]any{"ok": true})
}

func hAdminToken(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Which string `json:"which"`
	}
	if r.Method != http.MethodPost || json.NewDecoder(r.Body).Decode(&req) != nil {
		httpErr(w, 400, "bad_json", "요청 형식 오류")
		return
	}
	nt := randHex() + randHex() + randHex()
	cfgMu.Lock()
	switch req.Which {
	case "sync":
		srvCfg.SyncToken, token = nt, nt
	case "admin":
		srvCfg.AdminToken, adminToken = nt, nt
	default:
		cfgMu.Unlock()
		httpErr(w, 400, "bad_which", "sync 또는 admin")
		return
	}
	cfgMu.Unlock()
	log.Printf("%s 토큰을 새로 만듦", req.Which)
	if err := saveCfg(); err != nil {
		httpErr(w, 500, "io", err.Error())
		return
	}
	writeJSON(w, r, map[string]any{"ok": true, "token": nt})
}
