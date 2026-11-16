package main

// photoproxy.go — 휴대폰 사진 앱(Myphoto)이 밖에서도 시놀로지 포토를 쓰게 이어 줌
//
//	밖(Cloudflare 터널)에서는 DSM(5000)이 열려 있지 않으므로, 이 서버가 /webapi/* 를 NAS 의 DSM 으로 넘겨 줌.
//	  - 사진 API 만 넘김: SYNO.Foto.* · SYNO.FotoTeam.* · SYNO.API.Auth · SYNO.API.Info  (나머지 DSM 기능은 403)
//	  - 로그인 시도는 접속 주소마다 10분에 10번까지 (DSM 의 자동 차단이 이 서버 주소만 보게 되므로 여기서 막음)
//	  - 인증은 DSM 이 그대로 함 (이 서버의 토큰은 쓰지 않음)
//	큰 파일 나눠 올리기 (터널은 요청 하나에 100MB 까지):
//	  POST /webapi/myphoto/part?id=&off=&_sid=&SynoToken=   본문 = 조각 (64MB 까지, 차례대로)
//	  POST /webapi/myphoto/finish?id=&api=&method=&version=&_sid=&SynoToken=   본문(폼) = name·mtime·duplicate·target_folder_id
//	       → 이 서버가 모은 파일을 NAS 안에서 DSM 의 upload_to_folder 로 한 번에 올리고 DSM 의 답을 그대로 돌려줌
//	  조각을 받기 전에 그 DSM 세션이 살아 있는지 확인함 (아무나 NAS 에 임시 파일을 쓰지 못하게)

import (
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

var (
	photoProxyOnce sync.Once
	photoProxy     *httputil.ReverseProxy
	loginMu        sync.Mutex
	loginTries     = map[string][]time.Time{}
	sidOK          = map[string]time.Time{} // 확인한 DSM 세션 → 언제까지 믿을지
	reUpID         = regexp.MustCompile(`^[0-9a-f]{16,64}$`)
)

func photoAPIAllowed(api string) bool {
	return strings.HasPrefix(api, "SYNO.Foto.") || strings.HasPrefix(api, "SYNO.FotoTeam.") || api == "SYNO.API.Auth" || api == "SYNO.API.Info"
}

func upDir() string { return filepath.Join(os.TempDir(), "myphoto-up") }

// /webapi/... 전부 여기로
func hPhotoProxy(w http.ResponseWriter, r *http.Request) {
	switch r.URL.Path {
	case "/webapi/myphoto/part":
		hPhotoPart(w, r)
		return
	case "/webapi/myphoto/finish":
		hPhotoFinish(w, r)
		return
	case "/webapi/entry.cgi", "/webapi/query.cgi", "/webapi/auth.cgi":
	default:
		if !strings.HasPrefix(r.URL.Path, "/webapi/entry.cgi/") {
			http.Error(w, "not allowed", 403)
			return
		}
	}
	// 어떤 API 인지: 주소의 api= 또는 (작은) 폼 본문의 api=
	api := r.URL.Query().Get("api")
	if api == "" && r.Method == http.MethodPost && strings.HasPrefix(r.Header.Get("Content-Type"), "application/x-www-form-urlencoded") {
		b, err := io.ReadAll(io.LimitReader(r.Body, 4<<20))
		if err != nil {
			http.Error(w, "bad body", 400)
			return
		}
		r.Body.Close()
		vals, _ := url.ParseQuery(string(b))
		api = vals.Get("api")
		r.Body = io.NopCloser(strings.NewReader(string(b)))
		r.ContentLength = int64(len(b))
	}
	if api == "" && strings.HasPrefix(r.URL.Path, "/webapi/entry.cgi/") {
		api = strings.TrimPrefix(r.URL.Path, "/webapi/entry.cgi/")
	}
	if !photoAPIAllowed(api) {
		httpErr(w, 403, "not_photo_api", "사진 앱이 쓰는 API 만 넘겨 줌")
		return
	}
	if api == "SYNO.API.Auth" && !loginAllowed(clientIP(r)) {
		httpErr(w, 429, "too_many_logins", "로그인 시도가 너무 많음 — 10분 뒤에 다시")
		return
	}
	photoProxyOnce.Do(func() {
		target, _ := url.Parse(dsmURL())
		photoProxy = httputil.NewSingleHostReverseProxy(target)
		photoProxy.FlushInterval = -1 // 동영상 등은 받는 대로 흘려보냄
	})
	r.Host = ""
	photoProxy.ServeHTTP(w, r)
}

func loginAllowed(ip string) bool {
	loginMu.Lock()
	defer loginMu.Unlock()
	now := time.Now()
	keep := loginTries[ip][:0]
	for _, t := range loginTries[ip] {
		if now.Sub(t) < 10*time.Minute {
			keep = append(keep, t)
		}
	}
	if len(keep) >= 10 {
		loginTries[ip] = keep
		return false
	}
	loginTries[ip] = append(keep, now)
	return true
}

// 중계가 DSM 에 보내는 X-Forwarded-For 와 같은 값 (DSM 은 세션을 만든 접속 주소로 세션을 확인하므로 똑같이 보내야 함)
func fwdFor(r *http.Request) string {
	ip := r.RemoteAddr
	if h, _, err := net.SplitHostPort(ip); err == nil {
		ip = h
	}
	if prior := r.Header.Values("X-Forwarded-For"); len(prior) > 0 {
		return strings.Join(prior, ", ") + ", " + ip
	}
	return ip
}

// 이 DSM 세션이 살아 있고 사진 앱을 쓸 수 있나 (10분 동안 기억)
func photoSessionOK(r *http.Request, sid, token string) bool {
	if sid == "" {
		return false
	}
	loginMu.Lock()
	until, ok := sidOK[sid]
	loginMu.Unlock()
	if ok && time.Now().Before(until) {
		return true
	}
	v := url.Values{"api": {"SYNO.Foto.UserInfo"}, "method": {"me"}, "version": {"1"}, "_sid": {sid}}
	if token != "" {
		v.Set("SynoToken", token)
	}
	req, _ := http.NewRequest(http.MethodPost, dsmURL()+"/webapi/entry.cgi", strings.NewReader(v.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("X-Forwarded-For", fwdFor(r))
	if token != "" {
		req.Header.Set("X-SYNO-TOKEN", token)
	}
	resp, err := dsmClient.Do(req)
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	var j map[string]any
	if json.NewDecoder(resp.Body).Decode(&j) != nil {
		return false
	}
	if s, _ := j["success"].(bool); !s {
		return false
	}
	loginMu.Lock()
	sidOK[sid] = time.Now().Add(10 * time.Minute)
	loginMu.Unlock()
	return true
}

func cleanUploads() {
	es, _ := os.ReadDir(upDir())
	for _, e := range es {
		if fi, err := e.Info(); err == nil && time.Since(fi.ModTime()) > 24*time.Hour {
			os.Remove(filepath.Join(upDir(), e.Name()))
		}
	}
}

func hPhotoPart(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	if r.Method != http.MethodPost || !reUpID.MatchString(q.Get("id")) {
		httpErr(w, 400, "bad_request", "id 가 올바르지 않음")
		return
	}
	if !photoSessionOK(r, q.Get("_sid"), q.Get("SynoToken")) {
		httpErr(w, 401, "session", "시놀로지 로그인이 필요함")
		return
	}
	off, err := strconv.ParseInt(q.Get("off"), 10, 64)
	if err != nil || off < 0 {
		httpErr(w, 400, "bad_offset", "off 가 올바르지 않음")
		return
	}
	os.MkdirAll(upDir(), 0o700)
	cleanUploads()
	p := filepath.Join(upDir(), q.Get("id"))
	f, err := os.OpenFile(p, os.O_CREATE|os.O_WRONLY, 0o600)
	if err != nil {
		httpErr(w, 500, "io", err.Error())
		return
	}
	defer f.Close()
	fi, _ := f.Stat()
	if fi.Size() != off { // 조각은 차례대로만 (빠진 조각이 있으면 어디서부터 다시 보낼지 알려 줌)
		writeJSON(w, r, map[string]any{"ok": false, "have": fi.Size()})
		return
	}
	if _, err := f.Seek(off, 0); err != nil {
		httpErr(w, 500, "io", err.Error())
		return
	}
	n, err := io.Copy(f, io.LimitReader(r.Body, 64<<20+1))
	if err != nil {
		f.Truncate(off)
		httpErr(w, 500, "io", "조각을 다 받지 못함")
		return
	}
	if n > 64<<20 {
		f.Truncate(off)
		httpErr(w, 413, "too_big", "조각은 64MB 까지")
		return
	}
	writeJSON(w, r, map[string]any{"ok": true, "have": off + n})
}

func hPhotoFinish(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	id := q.Get("id")
	if r.Method != http.MethodPost || !reUpID.MatchString(id) || !photoAPIAllowed(q.Get("api")) || !strings.HasSuffix(q.Get("api"), ".Upload.Item") {
		httpErr(w, 400, "bad_request", "요청이 올바르지 않음")
		return
	}
	if !photoSessionOK(r, q.Get("_sid"), q.Get("SynoToken")) {
		httpErr(w, 401, "session", "시놀로지 로그인이 필요함")
		return
	}
	if err := r.ParseForm(); err != nil {
		httpErr(w, 400, "bad_form", err.Error())
		return
	}
	p := filepath.Join(upDir(), id)
	f, err := os.Open(p)
	if err != nil {
		httpErr(w, 404, "no_parts", "받은 조각이 없음")
		return
	}
	defer func() { f.Close(); os.Remove(p) }()
	fi, _ := f.Stat()
	if want := r.PostForm.Get("size"); want != "" && want != strconv.FormatInt(fi.Size(), 10) {
		httpErr(w, 409, "size_mismatch", fmt.Sprintf("받은 크기 %d 가 %s 와 다름", fi.Size(), want))
		return
	}
	// NAS 안에서 DSM 으로 한 번에 (요청 크기 제한 없음)
	pr, pw := io.Pipe()
	mw := multipart.NewWriter(pw)
	go func() {
		var err error
		for _, k := range []string{"duplicate", "name", "mtime", "target_folder_id"} {
			if v := r.PostForm.Get(k); v != "" {
				if err = mw.WriteField(k, v); err != nil {
					break
				}
			}
		}
		if err == nil {
			var part io.Writer
			name := strings.Trim(r.PostForm.Get("name"), `"`)
			if part, err = mw.CreateFormFile("file", name); err == nil {
				_, err = io.Copy(part, f)
			}
		}
		if err == nil {
			err = mw.Close()
		}
		pw.CloseWithError(err)
	}()
	u := dsmURL() + "/webapi/entry.cgi?" + url.Values{"api": {q.Get("api")}, "method": {q.Get("method")}, "version": {q.Get("version")}, "_sid": {q.Get("_sid")}, "SynoToken": {q.Get("SynoToken")}}.Encode()
	req, _ := http.NewRequestWithContext(r.Context(), http.MethodPost, u, pr)
	req.Header.Set("Content-Type", mw.FormDataContentType())
	req.Header.Set("X-Forwarded-For", fwdFor(r))
	if t := q.Get("SynoToken"); t != "" {
		req.Header.Set("X-SYNO-TOKEN", t)
	}
	resp, err := (&http.Client{Timeout: 60 * time.Minute}).Do(req)
	if err != nil {
		httpErr(w, 502, "dsm", err.Error())
		return
	}
	defer resp.Body.Close()
	w.Header().Set("Content-Type", resp.Header.Get("Content-Type"))
	w.WriteHeader(resp.StatusCode)
	io.Copy(w, resp.Body)
}
