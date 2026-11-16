package main

// NAS 다른 폴더 (웹 폴더): Mydrive 동기화 폴더가 아닌 NAS 폴더를 휴대폰에서 필요할 때만 열어 봄.
//   색인·감시·이전 버전 없음 — 열 때마다 디스크를 바로 읽음 (큰 공유 폴더를 붙여도 NAS 가 무겁지 않게).
//   PC 앱의 /v1/roots 목록에는 나오지 않음 (동기화 대상이 아님). 요청할 때는 root=~이름.
//
//	GET  /v1/webs                         → [{name, share, total, free}]
//	POST /v1/webs {"add":{"name","dir"}} | {"remove":"이름"}   dir = NAS 공유 폴더 기준 (/photo/2024)
//	GET  /v1/nasdirs?path=/photo          → {"path", "dirs":[...]}  (웹 폴더로 고를 폴더 둘러보기)

import (
	"encoding/json"
	"errors"
	"io/fs"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

var (
	webs   = map[string]*Root{}
	websMu sync.RWMutex
)

const webPrefix = "~"

func openWeb(name, dir string) (*Root, error) {
	fi, err := os.Stat(dir)
	if err != nil || !fi.IsDir() {
		return nil, errors.New("폴더가 없음: " + dir)
	}
	return &Root{Name: name, Dir: dir, Web: true, notify: make(chan struct{}), pending: map[string]struct{}{}, stop: make(chan struct{})}, nil
}

func loadWebs() {
	for _, rc := range srvCfg.Webs {
		r, err := openWeb(rc.Name, rc.Dir)
		if err != nil {
			log.Printf("[웹 %s] 열 수 없음: %v", rc.Name, err)
		}
		if r == nil { // 지금 없어도 목록에서는 보이게 (나중에 생길 수 있음)
			r = &Root{Name: rc.Name, Dir: rc.Dir, Web: true, notify: make(chan struct{}), pending: map[string]struct{}{}, stop: make(chan struct{})}
		}
		webs[rc.Name] = r
	}
}

func getWeb(name string) *Root {
	websMu.RLock()
	defer websMu.RUnlock()
	return webs[name]
}

func shareOf(dir string) string {
	if d := filepath.Clean(dir); strings.HasPrefix(d, filepath.Clean(nasBase)+"/") {
		return strings.TrimPrefix(d, filepath.Clean(nasBase))
	}
	return ""
}

func hWebs(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodPost {
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
			err = addWeb(strings.TrimSpace(req.Add.Name), req.Add.Dir)
		} else if req.Remove != "" {
			err = removeWeb(req.Remove)
		}
		if err != nil {
			httpErr(w, 400, "rejected", err.Error())
			return
		}
	}
	websMu.RLock()
	list := make([]*Root, 0, len(webs))
	for _, rt := range webs {
		list = append(list, rt)
	}
	websMu.RUnlock()
	sort.Slice(list, func(i, j int) bool { return list[i].Name < list[j].Name })
	out := []map[string]any{}
	for _, rt := range list {
		total, free := diskSpace(rt.Dir)
		out = append(out, map[string]any{"name": rt.Name, "share": shareOf(rt.Dir), "total": total, "free": free})
	}
	writeJSON(w, r, out)
}

func addWeb(name, dir string) error {
	if !rootName.MatchString(name) {
		return errors.New("이름은 글자·숫자·_·- 로 40자까지")
	}
	d, ok := insideBase(filepath.Join(nasBase, dir))
	if !ok {
		return errors.New("NAS 공유 폴더 안의 폴더만 고를 수 있음")
	}
	// d == nasBase 이면 NAS 전체 (모든 공유 폴더) — 서버 자신의 폴더는 webBlocked 로 막음
	rt, err := openWeb(name, d)
	if err != nil {
		return err
	}
	websMu.Lock()
	if _, dup := webs[name]; dup {
		websMu.Unlock()
		return errors.New("같은 이름이 이미 있음")
	}
	webs[name] = rt
	websMu.Unlock()
	cfgMu.Lock()
	srvCfg.Webs = append(srvCfg.Webs, RootCfg{name, d})
	cfgMu.Unlock()
	log.Printf("웹 폴더 추가: %s = %s", name, d)
	return saveCfg()
}

func removeWeb(name string) error {
	websMu.Lock()
	if _, ok := webs[name]; !ok {
		websMu.Unlock()
		return errors.New("그런 웹 폴더가 없음")
	}
	delete(webs, name)
	websMu.Unlock()
	cfgMu.Lock()
	keep := srvCfg.Webs[:0]
	for _, rc := range srvCfg.Webs {
		if rc.Name != name {
			keep = append(keep, rc)
		}
	}
	srvCfg.Webs = keep
	cfgMu.Unlock()
	log.Printf("웹 폴더 뺌: %s", name)
	return saveCfg()
}

func hNasDirs(w http.ResponseWriter, r *http.Request) {
	p := strings.TrimSpace(r.URL.Query().Get("path"))
	dir, ok := insideBase(filepath.Join(nasBase, p))
	if !ok {
		httpErr(w, 400, "outside", "NAS 공유 폴더 밖")
		return
	}
	ents, err := os.ReadDir(dir)
	if err != nil {
		httpErr(w, 404, "not_found", "폴더를 읽을 수 없음")
		return
	}
	dirs := []string{}
	for _, e := range ents {
		n := e.Name()
		if !e.IsDir() || strings.HasPrefix(n, "@") || strings.HasPrefix(n, "#") || strings.HasPrefix(n, ".") || skipName(n) {
			continue
		}
		dirs = append(dirs, n)
	}
	sort.Strings(dirs)
	writeJSON(w, r, map[string]any{"path": "/" + strings.Trim(shareOf(dir), "/"), "dirs": dirs})
}

// ---------- 웹 폴더의 Root 동작 (디스크를 바로 읽음) ----------
func (r *Root) webAbs(rel string) string { return filepath.Join(r.Dir, filepath.FromSlash(rel)) }

func (r *Root) webList(dir string) ([]Entry, bool) {
	if skipRel(dir) {
		return nil, false
	}
	ents, err := os.ReadDir(r.webAbs(dir))
	if err != nil {
		return nil, false
	}
	out := make([]Entry, 0, len(ents))
	for _, e := range ents {
		n := e.Name()
		if skipName(n) || strings.HasPrefix(n, "@") || strings.HasPrefix(n, "#") {
			continue
		}
		if webBlocked(r, filepath.Join(dir, n)) {
			continue
		}
		fi, err := e.Info()
		if err != nil {
			continue
		}
		rel := n
		if dir != "" {
			rel = dir + "/" + n
		}
		out = append(out, entryOf(rel, fi))
	}
	sort.Slice(out, func(i, j int) bool { return out[i].P < out[j].P })
	return out, true
}

func (r *Root) webGet(rel string) (Entry, bool) {
	if rel == "" || skipRel(rel) {
		return Entry{}, false
	}
	fi, err := os.Lstat(r.webAbs(rel))
	if err != nil {
		return Entry{}, false
	}
	return entryOf(rel, fi), true
}

// 검색: 디스크를 훑음 — 오래 걸리지 않게 15초·50만 항목까지
func (r *Root) webSearch(under, q string, exts []string, limit int) ([]Entry, bool) {
	q = strings.ToLower(q)
	out := []Entry{}
	more := false
	deadline := time.Now().Add(15 * time.Second)
	seen := 0
	stop := errors.New("stop")
	filepath.WalkDir(r.webAbs(under), func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		n := d.Name()
		if p != r.webAbs(under) && (skipName(n) || strings.HasPrefix(n, "@")) {
			if d.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		seen++
		if seen%2000 == 0 && time.Now().After(deadline) || seen > 500000 {
			more = true
			return stop
		}
		rel, _ := filepath.Rel(r.Dir, p)
		rel = filepath.ToSlash(rel)
		if rel == "." || rel == under {
			return nil
		}
		ln := strings.ToLower(n)
		if q != "" && !strings.Contains(ln, q) {
			return nil
		}
		if len(exts) > 0 {
			if d.IsDir() {
				return nil
			}
			ok := false
			for _, x := range exts {
				if strings.HasSuffix(ln, "."+x) {
					ok = true
					break
				}
			}
			if !ok {
				return nil
			}
		}
		if len(out) >= limit {
			more = true
			return stop
		}
		if fi, err := d.Info(); err == nil {
			out = append(out, entryOf(rel, fi))
		}
		return nil
	})
	sort.Slice(out, func(i, j int) bool { return out[i].P < out[j].P })
	return out, more
}

// 웹 폴더 휴지통: 그 공유 폴더 맨 위의 #recycle (시놀로지 휴지통과 같은 자리)
func webRecycleBase(rt *Root, rel string) string {
	abs := rt.webAbs(rel)
	if s := shareOf(abs); s != "" {
		top := strings.SplitN(strings.Trim(s, "/"), "/", 2)[0]
		shareRoot := filepath.Join(nasBase, top)
		if r, err := filepath.Rel(shareRoot, abs); err == nil && r != "." {
			return filepath.Join(shareRoot, "#recycle", r)
		}
	}
	return filepath.Join(rt.Dir, "#recycle", filepath.FromSlash(rel))
}

// 웹 폴더로 건드리면 안 되는 곳: 서버 자신의 폴더 (실행 파일·설정·토큰) — NAS 전체를 웹 폴더로 붙였을 때
func webBlocked(rt *Root, rel string) bool {
	abs := filepath.Clean(rt.webAbs(rel))
	for _, p := range []string{filepath.Dir(selfPath), filepath.Dir(filepath.Dir(selfPath))} {
		if p == "/" || p == "." || len(p) < 6 {
			continue
		}
		if p == filepath.Join(nasBase, "docker") { // docker 공유 폴더 전체가 아니라 mydrive 폴더만
			continue
		}
		if abs == p || strings.HasPrefix(abs, p+"/") {
			return true
		}
	}
	return false
}
