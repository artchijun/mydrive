package main

// 휴대폰 폴더를 PC 에서 열기 (중계): 휴대폰 Mydrive 앱이 NAS 에 늘 물어보고(긴 기다림), PC 의 요청을 받아 처리해 돌려줌.
//   휴대폰과 PC 가 같은 와이파이든 밖이든 둘 다 NAS 에만 닿으면 됨 (휴대폰이 NAS 로 먼저 연결하므로 휴대폰 쪽 포트를 열 필요 없음).
//
//	휴대폰: GET  /v1/phone/poll?name=이름&total=&free=   → 기다리다 할 일 {id, method, path, query, body}  (없으면 204)
//	        GET  /v1/phone/body?id=                     → PC 가 보낸 본문 (올리기)
//	        POST /v1/phone/reply?id=  (머리글 X-Status·X-Type·X-Range·X-Entry, 본문 = 응답) → PC 에게 그대로
//	PC   : 보통 요청에 root=~phone:이름 → 그 휴대폰으로 넘김 (list·file·move·mkdir·thumb)
//	       GET  /v1/phones → [{name, online, total, free, last}]

import (
	"crypto/rand"
	"encoding/hex"
	"io"
	"net/http"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

const phonePrefix = "~phone:"

type phoneJob struct {
	id     string
	method string
	path   string
	query  string
	body   io.Reader // PC 가 보낸 본문 (없으면 nil)
	bodyOK chan struct{}
	reply  chan *http.Request // 휴대폰의 답 (본문은 PC 에게 다 보낼 때까지 살아 있어야 함)
	done   chan struct{}      // PC 에게 다 보냄 → 휴대폰 reply 요청을 끝내도 됨
}

type phone struct {
	name        string
	jobs        chan *phoneJob
	last        time.Time
	total, free int64
}

var (
	phones    = map[string]*phone{}
	phoneJob_ = map[string]*phoneJob{}
	phoneMu   sync.Mutex
)

var phoneName = regexp.MustCompile(`^[\p{L}\p{N}_ .-]{1,40}$`)

func getPhone(name string, create bool) *phone {
	phoneMu.Lock()
	defer phoneMu.Unlock()
	p := phones[name]
	if p == nil && create {
		p = &phone{name: name, jobs: make(chan *phoneJob, 64)}
		phones[name] = p
	}
	return p
}

func newID() string { b := make([]byte, 12); rand.Read(b); return hex.EncodeToString(b) }

// PC 쪽: root=~phone:이름 인 요청을 휴대폰으로 넘기고 답을 그대로 돌려줌
func phoneProxy(w http.ResponseWriter, r *http.Request, name string) {
	switch r.URL.Path {
	case "/v1/list", "/v1/file", "/v1/move", "/v1/mkdir", "/v1/thumb":
	default:
		httpErr(w, 400, "phone", "휴대폰 폴더에서는 쓸 수 없는 기능")
		return
	}
	p := getPhone(name, false)
	if p == nil || time.Since(p.last) > 90*time.Second {
		httpErr(w, 503, "phone_offline", "휴대폰이 연결되어 있지 않음 — 휴대폰 Mydrive 의 'PC 에서 이 휴대폰 열기'를 켜 주세요")
		return
	}
	j := &phoneJob{id: newID(), method: r.Method, path: r.URL.Path, query: r.URL.RawQuery,
		bodyOK: make(chan struct{}), reply: make(chan *http.Request, 1), done: make(chan struct{})}
	if r.Method == http.MethodPut || r.Method == http.MethodPost {
		j.body = r.Body
	}
	if rg := r.Header.Get("Range"); rg != "" {
		j.query += "&_range=" + strings.ReplaceAll(rg, "=", "%3D")
	}
	phoneMu.Lock()
	phoneJob_[j.id] = j
	phoneMu.Unlock()
	defer func() { phoneMu.Lock(); delete(phoneJob_, j.id); phoneMu.Unlock(); close(j.done) }()
	select {
	case p.jobs <- j:
	default:
		httpErr(w, 503, "phone_busy", "휴대폰이 바쁨 — 잠시 뒤 다시")
		return
	}
	wait := 60 * time.Second
	if j.body != nil {
		wait = 30 * time.Minute // 큰 파일 올리기
	}
	select {
	case rr := <-j.reply:
		st, _ := strconv.Atoi(rr.Header.Get("X-Status"))
		if st == 0 {
			st = 200
		}
		if t := rr.Header.Get("X-Type"); t != "" {
			w.Header().Set("Content-Type", t)
		}
		if v := rr.Header.Get("X-Range"); v != "" {
			w.Header().Set("Content-Range", v)
		}
		if v := rr.Header.Get("X-Entry"); v != "" {
			w.Header().Set("X-Mydrive-Entry", v)
		}
		if v := rr.Header.Get("X-Length"); v != "" {
			w.Header().Set("Content-Length", v)
		}
		w.WriteHeader(st)
		io.Copy(w, rr.Body)
	case <-time.After(wait):
		httpErr(w, 504, "phone_timeout", "휴대폰이 답하지 않음")
	case <-r.Context().Done():
	}
}

// 휴대폰: 할 일 기다리기
func hPhonePoll(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	name := strings.TrimSpace(q.Get("name"))
	if !phoneName.MatchString(name) {
		httpErr(w, 400, "bad_name", "휴대폰 이름은 글자·숫자·공백·_·-·. 로 40자까지")
		return
	}
	p := getPhone(name, true)
	phoneMu.Lock()
	p.last = time.Now()
	if v, err := strconv.ParseInt(q.Get("total"), 10, 64); err == nil {
		p.total = v
	}
	if v, err := strconv.ParseInt(q.Get("free"), 10, 64); err == nil {
		p.free = v
	}
	phoneMu.Unlock()
	select {
	case j := <-p.jobs:
		writeJSON(w, r, map[string]any{"id": j.id, "method": j.method, "path": j.path, "query": j.query, "body": j.body != nil})
	case <-time.After(45 * time.Second):
		w.WriteHeader(204)
	case <-r.Context().Done():
	}
	phoneMu.Lock()
	p.last = time.Now()
	phoneMu.Unlock()
}

func findJob(id string) *phoneJob {
	phoneMu.Lock()
	defer phoneMu.Unlock()
	return phoneJob_[id]
}

// 휴대폰: PC 가 보낸 본문 받기
func hPhoneBody(w http.ResponseWriter, r *http.Request) {
	j := findJob(r.URL.Query().Get("id"))
	if j == nil || j.body == nil {
		httpErr(w, 404, "no_job", "그런 일이 없음")
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	io.Copy(w, j.body)
}

// 휴대폰: 답 보내기 (본문을 PC 에게 그대로 흘려보냄)
func hPhoneReply(w http.ResponseWriter, r *http.Request) {
	j := findJob(r.URL.Query().Get("id"))
	if j == nil {
		httpErr(w, 404, "no_job", "그런 일이 없음 (PC 가 기다리다 그만둠)")
		return
	}
	j.reply <- r
	select {
	case <-j.done:
	case <-time.After(30 * time.Minute):
	}
	w.WriteHeader(204)
}

// 휴대폰 목록 (PC 앱이 웹드라이브로 붙일 휴대폰 고르기)
func hPhones(w http.ResponseWriter, r *http.Request) {
	phoneMu.Lock()
	out := []map[string]any{}
	for _, p := range phones {
		out = append(out, map[string]any{"name": p.name, "online": time.Since(p.last) < 90*time.Second, "total": p.total, "free": p.free, "last": p.last.UnixNano()})
	}
	phoneMu.Unlock()
	sort.Slice(out, func(i, j int) bool { return out[i]["name"].(string) < out[j]["name"].(string) })
	writeJSON(w, r, out)
}
