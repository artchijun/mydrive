// versions.go — 이전 버전 보관 (덮어쓰기·지우기 전에 옛 판을 남김)
//
// 자리: <공유 폴더>/#mydrive-versions/<파일 경로>/<바뀐 시각 ns>
//
//	같은 디스크 안의 하드 링크라서 복사하지 않고, 공간은 옛 판 크기만큼만 듦.
//
// 보관 규칙 (시놀로지 드라이브처럼 둘 중 고름):
//
//	지능형(intelli, 기본): 하루 안: 모두 / 30일 안: 하루에 하나 / 그 뒤: 한 주에 하나 → 오래된 시점도 골고루 남음
//	먼저 들어온 것부터(fifo): 가장 최근 판부터 maxCount 개
//	둘 다: keepDays 지나면 지움 / 파일마다 최대 maxCount 개
//
// API
//
//	GET  /v1/versions?root=&path=        그 파일의 이전 판 목록 (새것부터)
//	GET  /v1/version?root=&path=&id=     이전 판 내용 받기
//	POST /v1/restore?root=&path=&id=     이전 판을 지금 판으로 (지금 판은 또 하나의 버전으로 남김)
//	GET  /v1/deleted?root=               지워졌지만 버전이 남은 파일 목록
package main

import (
	"encoding/json"
	"io"
	"io/fs"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

const verDirName = "#mydrive-versions"

type VerPolicy struct {
	MaxCount int    `json:"maxCount"`       // 파일마다 최대 개수
	KeepDays int    `json:"keepDays"`       // 이보다 오래된 판은 지움
	Mode     string `json:"mode,omitempty"` // "intelli"(기본) 또는 "fifo"
}

var verPolicy = VerPolicy{MaxCount: 32, KeepDays: 90}
var verMu sync.Mutex

type Version struct {
	ID       string `json:"id"`       // 바뀐 시각(ns) — 파일 이름
	Replaced int64  `json:"replaced"` // 바뀐(덮이거나 지워진) 시각 ns
	S        int64  `json:"s"`        // 크기
	M        int64  `json:"m"`        // 그 판의 수정 시각 ns
}

func verDir(rt *Root, rel string) string {
	return filepath.Join(rt.Dir, verDirName, filepath.FromSlash(rel))
}

// 지금 abs 에 있는 판을 버전으로 남김. 덮어쓰기·지우기 직전에 부름.
func saveVersion(rt *Root, rel, abs string) {
	if dir := saveVersionNoPrune(rt, rel, abs); dir != "" {
		go pruneDir(dir)
	}
}

// 폴더를 지울 때: 안의 파일마다 버전을 남김
func saveVersionsUnder(rt *Root, rel, abs string, isDir bool) {
	if rt.Web {
		return
	}
	if !isDir {
		saveVersion(rt, rel, abs)
		return
	}
	var dirs []string
	filepath.WalkDir(abs, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if d.IsDir() && p != abs && skipName(d.Name()) {
			return filepath.SkipDir
		}
		if d.Type().IsRegular() && !skipName(d.Name()) {
			r, _ := filepath.Rel(rt.Dir, p)
			if dir := saveVersionNoPrune(rt, filepath.ToSlash(r), p); dir != "" {
				dirs = append(dirs, dir)
			}
		}
		return nil
	})
	go func() {
		for _, d := range dirs {
			pruneDir(d)
		}
	}()
}

func saveVersionNoPrune(rt *Root, rel, abs string) string {
	if rt.Web {
		return ""
	}
	fi, err := os.Lstat(abs)
	if err != nil || !fi.Mode().IsRegular() {
		return ""
	}
	dir := verDir(rt, rel)
	if err := os.MkdirAll(dir, 0777); err != nil {
		log.Printf("[%s] 버전 폴더를 못 만듦: %v", rt.Name, err)
		return ""
	}
	id := strconv.FormatInt(time.Now().UnixNano(), 10)
	dst := filepath.Join(dir, id)
	if err := os.Link(abs, dst); err != nil {
		// 하드 링크가 안 되면 복사
		if err := copyFile(abs, dst, fi.ModTime()); err != nil {
			log.Printf("[%s] 버전 저장 실패 %s: %v", rt.Name, rel, err)
			return ""
		}
	}
	return dir
}

func copyFile(src, dst string, mt time.Time) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	tmp := dst + ".part"
	out, err := os.Create(tmp)
	if err != nil {
		return err
	}
	if _, err := io.CopyBuffer(out, in, make([]byte, 1<<20)); err != nil {
		out.Close()
		os.Remove(tmp)
		return err
	}
	out.Close()
	os.Chtimes(tmp, mt, mt)
	return os.Rename(tmp, dst)
}

func listVersions(dir string) []Version {
	ents, err := os.ReadDir(dir)
	if err != nil {
		return nil
	}
	var out []Version
	for _, e := range ents {
		if !e.Type().IsRegular() {
			continue
		}
		ns, err := strconv.ParseInt(e.Name(), 10, 64)
		if err != nil {
			continue
		}
		fi, err := e.Info()
		if err != nil {
			continue
		}
		out = append(out, Version{ID: e.Name(), Replaced: ns, S: fi.Size(), M: fi.ModTime().UnixNano()})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Replaced > out[j].Replaced })
	return out
}

// 보관 규칙에 따라 정리
func pruneDir(dir string) {
	verMu.Lock()
	pol := verPolicy
	verMu.Unlock()
	vs := listVersions(dir)
	now := time.Now()
	keepDay := map[string]bool{}
	keepWeek := map[string]bool{}
	kept := 0
	for _, v := range vs { // 새것부터
		t := time.Unix(0, v.Replaced)
		age := now.Sub(t)
		keep := true
		switch {
		case pol.KeepDays > 0 && age > time.Duration(pol.KeepDays)*24*time.Hour:
			keep = false
		case pol.Mode == "fifo": // 먼저 들어온 것부터: 솎지 않고 최근 것부터 개수만큼
		case age <= 24*time.Hour:
		case age <= 30*24*time.Hour:
			d := t.Format("2006-01-02")
			keep = !keepDay[d]
			keepDay[d] = true
		default:
			y, w := t.ISOWeek()
			k := strconv.Itoa(y) + "-" + strconv.Itoa(w)
			keep = !keepWeek[k]
			keepWeek[k] = true
		}
		if keep && pol.MaxCount > 0 && kept >= pol.MaxCount {
			keep = false
		}
		if keep {
			kept++
		} else {
			os.Remove(filepath.Join(dir, v.ID))
		}
	}
	if kept == 0 {
		os.Remove(dir) // 비었으면 (하위에 다른 파일의 버전 폴더가 있으면 안 지워짐)
	}
}

// 하루에 한 번 전체 정리
func pruneAll(rt *Root) {
	base := filepath.Join(rt.Dir, verDirName)
	var dirs []string
	filepath.WalkDir(base, func(p string, d fs.DirEntry, err error) error {
		if err == nil && d.IsDir() {
			dirs = append(dirs, p)
		}
		return nil
	})
	sort.Sort(sort.Reverse(sort.StringSlice(dirs))) // 깊은 것 먼저 (빈 폴더 정리)
	for _, d := range dirs {
		if d != base {
			pruneDir(d)
		}
	}
}

// 이름이 바뀌거나 옮겨진 파일·폴더의 버전도 따라 옮김
func moveVersions(rt *Root, from, to string) { moveVersionsTo(rt, from, rt, to) }

// moveVersionsTo: 파일을 다른 연결로 옮길 때 지난 판도 함께 옮김
func moveVersionsTo(sr *Root, from string, dr *Root, to string) {
	src, dst := verDir(sr, from), verDir(dr, to)
	if _, err := os.Lstat(src); err != nil {
		return
	}
	if _, err := os.Lstat(dst); err == nil {
		return // 이미 있으면 섞지 않음
	}
	os.MkdirAll(filepath.Dir(dst), 0777)
	if os.Rename(src, dst) != nil && sr != dr {
		moveAcross(src, dst) // 다른 공유 폴더: 복사(reflink) 뒤 지움
	}
}

// 버전 폴더의 크기 합 (관리 창용)
func versionsSize(rt *Root) (files int, bytes int64) {
	filepath.WalkDir(filepath.Join(rt.Dir, verDirName), func(p string, d fs.DirEntry, err error) error {
		if err == nil && d.Type().IsRegular() {
			if fi, err := d.Info(); err == nil {
				files++
				bytes += fi.Size()
			}
		}
		return nil
	})
	return
}

func relPath(w http.ResponseWriter, r *http.Request) (*Root, string, bool) {
	rt := getRoot(w, r)
	if rt == nil {
		return nil, "", false
	}
	rel, ok := cleanRel(r.URL.Query().Get("path"))
	if !ok {
		httpErr(w, 400, "bad_path", "경로가 올바르지 않음")
		return nil, "", false
	}
	return rt, rel, true
}

func verFile(rt *Root, rel, id string) (string, bool) {
	if _, err := strconv.ParseInt(id, 10, 64); err != nil {
		return "", false
	}
	p := filepath.Join(verDir(rt, rel), id)
	fi, err := os.Lstat(p)
	return p, err == nil && fi.Mode().IsRegular()
}

func hVersions(w http.ResponseWriter, r *http.Request) {
	rt, rel, ok := relPath(w, r)
	if !ok {
		return
	}
	vs := listVersions(verDir(rt, rel))
	if vs == nil {
		vs = []Version{}
	}
	writeJSON(w, r, vs)
}

func hVersion(w http.ResponseWriter, r *http.Request) {
	rt, rel, ok := relPath(w, r)
	if !ok {
		return
	}
	p, ok := verFile(rt, rel, r.URL.Query().Get("id"))
	if !ok {
		httpErr(w, 404, "not_found", "그 버전이 없음")
		return
	}
	f, err := os.Open(p)
	if err != nil {
		httpErr(w, 404, "not_found", "그 버전이 없음")
		return
	}
	defer f.Close()
	fi, _ := f.Stat()
	http.ServeContent(w, r, "", fi.ModTime(), f)
}

func hRestore(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		httpErr(w, 405, "method", "POST 만")
		return
	}
	rt, rel, ok := relPath(w, r)
	if !ok {
		return
	}
	src, ok := verFile(rt, rel, r.URL.Query().Get("id"))
	if !ok {
		httpErr(w, 404, "not_found", "그 버전이 없음")
		return
	}
	abs := filepath.Join(rt.Dir, filepath.FromSlash(rel))
	if cur, exists := statRel(rt, rel); exists && cur.D {
		httpErr(w, 409, "is_dir", "같은 이름의 폴더가 있음")
		return
	}
	fi, _ := os.Lstat(src)
	tmp := filepath.Join(filepath.Dir(abs), ".mydrive-tmp-"+randHex())
	os.MkdirAll(filepath.Dir(abs), 0777)
	// 버전 파일은 그대로 두고 복사본을 만들어 바꿔 끼움 (버전이 나중에 바뀌지 않게)
	if err := copyFile(src, tmp, fi.ModTime()); err != nil {
		os.Remove(tmp)
		httpErr(w, 500, "io", err.Error())
		return
	}
	saveVersion(rt, rel, abs) // 지금 판도 남김 → 되돌리기를 되돌릴 수 있음
	if err := os.Rename(tmp, abs); err != nil {
		os.Remove(tmp)
		httpErr(w, 500, "io", err.Error())
		return
	}
	needSync.Store(true)
	rt.Apply(rel)
	e, _ := statRel(rt, rel)
	writeJSON(w, r, e)
}

// 지워졌지만 버전이 남아 있는 파일 (가장 최근 판 기준)
func hDeleted(w http.ResponseWriter, r *http.Request) {
	rt := getRoot(w, r)
	if rt == nil {
		return
	}
	base := filepath.Join(rt.Dir, verDirName)
	type Del struct {
		P string  `json:"p"`
		V Version `json:"v"`
		N int     `json:"n"` // 남은 버전 수
	}
	out := []Del{}
	filepath.WalkDir(base, func(p string, d fs.DirEntry, err error) error {
		if err != nil || !d.IsDir() || p == base {
			return nil
		}
		vs := listVersions(p)
		if len(vs) == 0 {
			return nil
		}
		rel, _ := filepath.Rel(base, p)
		rel = filepath.ToSlash(rel)
		if _, exists := statRel(rt, rel); exists {
			return nil
		}
		out = append(out, Del{P: rel, V: vs[0], N: len(vs)})
		return nil
	})
	sort.Slice(out, func(i, j int) bool { return out[i].V.Replaced > out[j].V.Replaced })
	if len(out) > 5000 {
		out = out[:5000]
	}
	writeJSON(w, r, out)
}

func versionsStart() {
	go func() {
		for {
			time.Sleep(time.Hour)
			if time.Now().Hour() != 4 {
				continue
			}
			for _, rt := range rootList() {
				pruneAll(rt)
			}
			time.Sleep(time.Hour)
		}
	}()
}

func isVerRel(rel string) bool { return rel == verDirName || strings.HasPrefix(rel, verDirName+"/") }

// ---------- 관리 콘솔: 지운 파일의 버전 비우기 ----------
//
//	GET  /v1/admin/deleted?root=        지운 파일마다 남은 판 수·판 크기 합 (큰 것부터)
//	POST /v1/admin/purge?root=  {paths} 그 지운 파일들의 판을 모두 지움 (되돌릴 수 없음)
//	지금 있는 파일의 판은 지우지 않음 — 그 판은 보관 규칙대로만 정리됨

func hAdminDeleted(w http.ResponseWriter, r *http.Request) {
	rt := getRoot(w, r)
	if rt == nil {
		return
	}
	base := filepath.Join(rt.Dir, verDirName)
	type Del struct {
		P     string `json:"p"`
		N     int    `json:"n"`     // 남은 판 수
		Bytes int64  `json:"bytes"` // 판 크기 합
		Last  int64  `json:"last"`  // 가장 최근 판이 바뀐(지워진) 시각 ns
	}
	out := []Del{}
	var total int64
	filepath.WalkDir(base, func(p string, d fs.DirEntry, err error) error {
		if err != nil || !d.IsDir() || p == base {
			return nil
		}
		vs := listVersions(p)
		if len(vs) == 0 {
			return nil
		}
		rel, _ := filepath.Rel(base, p)
		rel = filepath.ToSlash(rel)
		if _, exists := statRel(rt, rel); exists {
			return nil
		}
		e := Del{P: rel, N: len(vs), Last: vs[0].Replaced}
		for _, v := range vs {
			e.Bytes += v.S
		}
		total += e.Bytes
		out = append(out, e)
		return nil
	})
	sort.Slice(out, func(i, j int) bool { return out[i].Bytes > out[j].Bytes })
	writeJSON(w, r, map[string]any{"files": out, "bytes": total})
}

func hAdminPurge(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Paths []string `json:"paths"`
	}
	if r.Method != http.MethodPost || json.NewDecoder(r.Body).Decode(&req) != nil || len(req.Paths) == 0 {
		httpErr(w, 400, "bad_json", "요청 형식 오류")
		return
	}
	rt := getRoot(w, r)
	if rt == nil {
		return
	}
	base := filepath.Join(rt.Dir, verDirName)
	files, skipped := 0, 0
	var bytes int64
	verMu.Lock()
	defer verMu.Unlock()
	for _, p := range req.Paths {
		rel, ok := cleanRel(p)
		if !ok || isVerRel(rel) {
			skipped++
			continue
		}
		if _, exists := statRel(rt, rel); exists { // 지금 있는 파일의 판은 그대로
			skipped++
			continue
		}
		dir := verDir(rt, rel)
		for _, v := range listVersions(dir) { // 판 파일(이름이 시각)만 지움 — 하위의 다른 파일 판 폴더는 그대로
			if os.Remove(filepath.Join(dir, v.ID)) == nil {
				files++
				bytes += v.S
			}
		}
		for d := dir; d != base && strings.HasPrefix(d, base+string(filepath.Separator)); d = filepath.Dir(d) {
			if os.Remove(d) != nil { // 비었을 때만 지워짐
				break
			}
		}
	}
	who, _ := r.Context().Value(ctxUser{}).(string)
	log.Printf("[%s] 지운 파일의 버전 비움: 파일 %d개의 판 %d개, %s (%s)", rt.Name, len(req.Paths)-skipped, files, humanSize(bytes), who)
	writeJSON(w, r, map[string]any{"ok": true, "versions": files, "bytes": bytes, "skipped": skipped})
}

func humanSize(b int64) string {
	f, u := float64(b), []string{"B", "KB", "MB", "GB", "TB"}
	i := 0
	for f >= 1024 && i < len(u)-1 {
		f /= 1024
		i++
	}
	return strconv.FormatFloat(f, 'f', 1, 64) + " " + u[i]
}
