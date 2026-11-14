// root.go — 공유 폴더 하나(Root)의 색인과 변경 기록
//
// 색인: 상대 경로 → Entry(크기, 수정 시각, inode, 폴더 여부)
// 변경 기록: 번호(seq)가 붙은 Change 목록. PC 앱은 "since=N 이후 바뀐 것"만 받아 간다.
// 감지: inotify(fsnotify)로 바로 알고, 놓친 것은 주기적 전체 비교로 잡는다.
package main

import (
	"crypto/rand"
	"encoding/gob"
	"encoding/hex"
	"errors"
	"io/fs"
	"log"
	"os"
	"path"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/fsnotify/fsnotify"
)

type Entry struct {
	P string `json:"p"`           // 상대 경로 (슬래시 구분)
	D bool   `json:"d,omitempty"` // 폴더
	S int64  `json:"s"`           // 크기
	M int64  `json:"m"`           // 수정 시각 (ns)
	I uint64 `json:"i"`           // inode
}

type Change struct {
	Q  int64  `json:"q"`  // 번호
	Op string `json:"op"` // put | del
	E  Entry  `json:"e"`
}

type Root struct {
	Name string
	Dir  string

	rescanMu sync.Mutex // 전체 비교를 한 번에 하나만 (주기 비교 · PC 요청)

	mu      sync.Mutex
	idx     map[string]Entry
	epoch   string
	seq     int64
	log     []Change
	oldest  int64 // log[0].Q (비어 있으면 seq+1)
	notify  chan struct{}
	dirty   bool
	watcher *fsnotify.Watcher
	pending map[string]struct{}
	pmu     sync.Mutex
	state   string
	stop    chan struct{}
}

// 관리 창에서 공유 폴더를 뺄 때: 감시를 멈춤
func (r *Root) Close() {
	select {
	case <-r.stop:
	default:
		close(r.stop)
	}
	if r.watcher != nil {
		r.watcher.Close()
	}
}

const maxLog = 500000

// 색인에서 빼는 이름 (시놀로지 내부 폴더, 우리 임시 파일)
func skipName(name string) bool {
	switch {
	case name == "#recycle", name == "@eaDir", name == "#snapshot", name == "@tmp",
		name == ".SynologyWorkingDirectory", name == "@SynoDrive", name == "@SynoResource", name == verDirName:
		return true
	case strings.HasPrefix(name, ".mydrive-tmp-"):
		return true
	}
	return false
}

func skipRel(rel string) bool {
	for _, part := range strings.Split(rel, "/") {
		if skipName(part) {
			return true
		}
	}
	return false
}

func newEpoch() string {
	b := make([]byte, 8)
	rand.Read(b)
	return hex.EncodeToString(b)
}

func entryOf(rel string, fi fs.FileInfo) Entry {
	e := Entry{P: rel, D: fi.IsDir(), M: fi.ModTime().UnixNano()}
	if !e.D {
		e.S = fi.Size()
	}
	if st, ok := fi.Sys().(*syscall.Stat_t); ok {
		e.I = uint64(st.Ino)
	}
	return e
}

func same(a, b Entry) bool { return a.D == b.D && a.S == b.S && a.M == b.M && a.I == b.I }

// 폴더 전체를 훑어 색인을 만든다
func (r *Root) walk() (map[string]Entry, []string) {
	idx := map[string]Entry{}
	var dirs []string
	filepath.WalkDir(r.Dir, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		rel, _ := filepath.Rel(r.Dir, p)
		rel = filepath.ToSlash(rel)
		if rel == "." {
			dirs = append(dirs, p)
			return nil
		}
		if skipName(d.Name()) {
			if d.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		if d.Type()&fs.ModeSymlink != 0 {
			return nil
		}
		fi, err := d.Info()
		if err != nil {
			return nil
		}
		idx[rel] = entryOf(rel, fi)
		if d.IsDir() {
			dirs = append(dirs, p)
		}
		return nil
	})
	return idx, dirs
}

type persisted struct {
	Epoch string
	Seq   int64
	Idx   map[string]Entry
}

func OpenRoot(name, dir, stateDir string) (*Root, error) {
	fi, err := os.Stat(dir)
	if err != nil || !fi.IsDir() {
		return nil, errors.New("폴더가 없음: " + dir)
	}
	r := &Root{Name: name, Dir: dir, notify: make(chan struct{}), pending: map[string]struct{}{}, stop: make(chan struct{}),
		state: filepath.Join(stateDir, name+".state")}
	var old persisted
	if f, err := os.Open(r.state); err == nil {
		if gob.NewDecoder(f).Decode(&old) != nil {
			old = persisted{}
		}
		f.Close()
	}
	t0 := time.Now()
	cur, dirs := r.walk()
	if old.Epoch == "" || old.Idx == nil {
		r.epoch, r.seq, r.idx = newEpoch(), 0, cur
		r.oldest = 1
		r.dirty = true
	} else {
		// 꺼져 있던 동안 바뀐 것을 변경 기록으로 이어 붙인다 (PC 앱은 전체를 다시 받지 않아도 됨)
		r.epoch, r.seq, r.idx = old.Epoch, old.Seq, old.Idx
		r.oldest = r.seq + 1
		r.diffInto(cur)
	}
	log.Printf("[%s] %s: %d개 항목, 훑는 데 %v (epoch %s, seq %d)", name, dir, len(r.idx), time.Since(t0).Round(time.Millisecond), r.epoch, r.seq)
	if err := r.startWatch(dirs); err != nil {
		log.Printf("[%s] 실시간 감시 시작 실패 (%v) — 주기적 비교만 사용", name, err)
	}
	return r, nil
}

// cur(새로 훑은 결과)와 색인의 차이를 변경 기록으로 남긴다. 호출 측이 잠금 없이 부름.
func (r *Root) diffInto(cur map[string]Entry) int {
	// 전체 훑기는 몇 분 걸릴 수 있고, 그동안 올라온 파일(덮어쓰기 포함)은 훑은 결과에 옛 상태로 남는다.
	// 그대로 반영하면 멀쩡히 있는 파일을 '지움'으로 알려 PC 들이 대량 삭제로 보게 됨 → 다른 곳만 지금 디스크에서 다시 확인.
	r.mu.Lock()
	var cand []string
	for p, e := range cur {
		if o, ok := r.idx[p]; !ok || !same(o, e) {
			cand = append(cand, p)
		}
	}
	for p := range r.idx {
		if _, ok := cur[p]; !ok {
			cand = append(cand, p)
		}
	}
	r.mu.Unlock()
	if len(cand) == 0 {
		return 0
	}
	fresh := make(map[string]*Entry, len(cand))
	for _, p := range cand {
		fi, err := os.Lstat(filepath.Join(r.Dir, filepath.FromSlash(p)))
		if err != nil || fi.Mode()&fs.ModeSymlink != 0 {
			fresh[p] = nil
			continue
		}
		e := entryOf(p, fi)
		fresh[p] = &e
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	n := 0
	var gone []string
	for _, p := range cand {
		e := fresh[p]
		if e == nil {
			if _, ok := r.idx[p]; ok {
				gone = append(gone, p)
			}
			continue
		}
		if o, ok := r.idx[p]; !ok || !same(o, *e) {
			r.emitLocked("put", *e)
			n++
		}
	}
	sort.Sort(sort.Reverse(sort.StringSlice(gone))) // 자식 먼저
	for _, p := range gone {
		if o, ok := r.idx[p]; ok {
			r.emitLocked("del", o)
			n++
		}
	}
	return n
}

func (r *Root) emitLocked(op string, e Entry) {
	r.seq++
	if op == "put" {
		r.idx[e.P] = e
	} else {
		delete(r.idx, e.P)
	}
	r.log = append(r.log, Change{Q: r.seq, Op: op, E: e})
	if len(r.log) > maxLog+maxLog/4 { // 넘칠 때마다 통째로 복사하지 않고, 1/4 더 쌓이면 한 번에 잘라 냄
		cut := len(r.log) - maxLog
		r.log = append([]Change(nil), r.log[cut:]...)
	}
	r.oldest = r.log[0].Q
	r.dirty = true
	close(r.notify)
	r.notify = make(chan struct{})
}

// 한 경로의 현재 상태를 디스크에서 읽어 색인에 반영 (폴더면 그 아래 전체)
func (r *Root) Apply(rel string) {
	if rel == "" || rel == "." || skipRel(rel) {
		return
	}
	abs := filepath.Join(r.Dir, filepath.FromSlash(rel))
	fi, err := os.Lstat(abs)
	r.mu.Lock()
	defer r.mu.Unlock()
	if err != nil || fi.Mode()&fs.ModeSymlink != 0 {
		// 없어짐: 그 경로와 아래 항목 모두 삭제
		var gone []string
		pre := rel + "/"
		for p := range r.idx {
			if p == rel || strings.HasPrefix(p, pre) {
				gone = append(gone, p)
			}
		}
		sort.Sort(sort.Reverse(sort.StringSlice(gone)))
		for _, p := range gone {
			r.emitLocked("del", r.idx[p])
		}
		return
	}
	e := entryOf(rel, fi)
	if o, ok := r.idx[rel]; !ok || !same(o, e) {
		r.emitLocked("put", e)
	}
	if fi.IsDir() {
		// 폴더 안을 비교 (새로 생긴 폴더, 옮겨 온 폴더)
		// 그 폴더 자체도 감시에 넣는다 (빠뜨리면 빈 폴더가 먼저 생긴 뒤 들어온 파일을 다음 전체 비교까지 모름).
		// 감시를 먼저 걸고 나서 훑어야, 그 사이에 생긴 파일도 놓치지 않음.
		if r.watcher != nil {
			r.watcher.Add(abs)
		}
		sub := map[string]Entry{}
		filepath.WalkDir(abs, func(p string, d fs.DirEntry, err error) error {
			if err != nil || p == abs {
				return nil
			}
			if skipName(d.Name()) {
				if d.IsDir() {
					return filepath.SkipDir
				}
				return nil
			}
			if d.Type()&fs.ModeSymlink != 0 {
				return nil
			}
			fi, err := d.Info()
			if err != nil {
				return nil
			}
			rp, _ := filepath.Rel(r.Dir, p)
			rp = filepath.ToSlash(rp)
			sub[rp] = entryOf(rp, fi)
			if d.IsDir() && r.watcher != nil {
				r.watcher.Add(p)
			}
			return nil
		})
		for p, e := range sub {
			if o, ok := r.idx[p]; !ok || !same(o, e) {
				r.emitLocked("put", e)
			}
		}
		pre := rel + "/"
		var gone []string
		for p := range r.idx {
			if strings.HasPrefix(p, pre) {
				if _, ok := sub[p]; !ok {
					gone = append(gone, p)
				}
			}
		}
		sort.Sort(sort.Reverse(sort.StringSlice(gone)))
		for _, p := range gone {
			r.emitLocked("del", r.idx[p])
		}
	}
}

func (r *Root) startWatch(dirs []string) error {
	w, err := fsnotify.NewWatcher()
	if err != nil {
		return err
	}
	r.watcher = w
	failed := 0
	for _, d := range dirs {
		if w.Add(d) != nil {
			failed++
		}
	}
	if failed > 0 {
		log.Printf("[%s] 폴더 %d개는 실시간 감시를 못 함 (inotify 한도 %s) — 주기적 비교로 보완", r.Name, failed, inotifyLimit())
	}
	go func() {
		for {
			select {
			case ev, ok := <-w.Events:
				if !ok {
					return
				}
				rel, err := filepath.Rel(r.Dir, ev.Name)
				if err != nil {
					continue
				}
				rel = filepath.ToSlash(rel)
				r.pmu.Lock()
				r.pending[rel] = struct{}{}
				if ev.Op&(fsnotify.Remove|fsnotify.Rename) != 0 {
					// 폴더가 사라지거나 옮겨지면 부모도 다시 봄
					r.pending[path.Dir(rel)] = struct{}{}
				}
				r.pmu.Unlock()
			case err, ok := <-w.Errors:
				if !ok {
					return
				}
				log.Printf("[%s] 감시 오류: %v", r.Name, err)
			}
		}
	}()
	// 0.3초마다 모아서 처리 (같은 파일을 여러 번 쓰는 것을 한 번으로)
	go func() {
		tk := time.NewTicker(300 * time.Millisecond)
		defer tk.Stop()
		for {
			select {
			case <-r.stop:
				return
			case <-tk.C:
			}
			r.pmu.Lock()
			if len(r.pending) == 0 {
				r.pmu.Unlock()
				continue
			}
			ps := make([]string, 0, len(r.pending))
			for p := range r.pending {
				ps = append(ps, p)
			}
			r.pending = map[string]struct{}{}
			r.pmu.Unlock()
			sort.Strings(ps)
			for _, p := range ps {
				r.Apply(p)
			}
		}
	}()
	return nil
}

// 주기적 전체 비교 (실시간 감시가 놓친 것 보완)
func (r *Root) Rescan() {
	r.rescanMu.Lock()
	defer r.rescanMu.Unlock()
	t0 := time.Now()
	cur, _ := r.walk()
	n := r.diffInto(cur)
	if n > 0 {
		log.Printf("[%s] 주기적 비교: %d개 변경 반영 (%v)", r.Name, n, time.Since(t0).Round(time.Millisecond))
	}
}

func (r *Root) Save() {
	r.mu.Lock()
	if !r.dirty {
		r.mu.Unlock()
		return
	}
	p := persisted{Epoch: r.epoch, Seq: r.seq, Idx: make(map[string]Entry, len(r.idx))}
	for k, v := range r.idx {
		p.Idx[k] = v
	}
	r.dirty = false
	r.mu.Unlock()
	tmp := r.state + ".tmp"
	f, err := os.Create(tmp)
	if err != nil {
		log.Printf("[%s] 상태 저장 실패: %v", r.Name, err)
		return
	}
	if err := gob.NewEncoder(f).Encode(&p); err != nil {
		f.Close()
		return
	}
	f.Sync()
	f.Close()
	os.Rename(tmp, r.state)
}

// since 이후 변경. reset=true 면 PC 앱은 전체 목록(snapshot)을 다시 받아야 한다.
func (r *Root) Changes(epoch string, since int64, limit int) (ch []Change, seq int64, reset bool, wait <-chan struct{}) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if epoch != r.epoch || since > r.seq || (since < r.oldest-1 && since < r.seq) {
		return nil, r.seq, true, nil
	}
	i := sort.Search(len(r.log), func(i int) bool { return r.log[i].Q > since })
	end := len(r.log)
	if end-i > limit {
		end = i + limit
	}
	ch = append([]Change(nil), r.log[i:end]...)
	return ch, r.seq, false, r.notify
}

func (r *Root) Snapshot() (string, int64, []Entry) {
	r.mu.Lock()
	defer r.mu.Unlock()
	es := make([]Entry, 0, len(r.idx))
	for _, e := range r.idx {
		es = append(es, e)
	}
	sort.Slice(es, func(i, j int) bool { return es[i].P < es[j].P })
	return r.epoch, r.seq, es
}

func (r *Root) Get(rel string) (Entry, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	e, ok := r.idx[rel]
	return e, ok
}
