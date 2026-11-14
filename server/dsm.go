// dsm.go — DSM 계정으로 들어오기 (토큰을 손으로 옮기지 않게)
//
//  1) PC 연결:  POST /v1/pair {"account","passwd","otp","pc"}
//     서버가 DSM 에 그 계정으로 로그인해 보고, DSM 관리자면 그 PC 전용 토큰을 만들어 돌려줌 (DSM 세션은 바로 닫음).
//     비밀번호는 서버에 남기지 않음. PC 전용 토큰은 해시만 저장 → 관리 콘솔에서 PC별로 끊을 수 있음.
//  2) 관리 콘솔: DSM 메뉴에서 열면 브라우저가 DSM 로그인 쿠키(id)를 함께 보냄 → 서버가 DSM 에 "이 세션이 관리자인가"를 물음.
//     쿠키로 들어올 때는 화면이 붙이는 X-Mydrive-Console 머리글이 있어야 함 (다른 사이트가 몰래 부르는 것을 막음).
//
// DSM 주소: MYDRIVE_DSM_URL (없으면 컨테이너의 기본 게이트웨이 = NAS 자신의 :5000)
//
//   GET  /v1/admin/devices                 PC 전용 토큰 목록
//   POST /v1/admin/devices {"revoke":id}   그 PC 끊기
package main

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

type Device struct {
	ID      string `json:"id"`
	PC      string `json:"pc"`
	User    string `json:"user"`
	Hash    string `json:"hash"` // sha256(token)
	Created int64  `json:"created"`
	Last    int64  `json:"last"`
}

var (
	devMu     sync.Mutex
	devLast   = map[string]int64{} // id → 마지막 사용 (메모리, 1분마다 저장)
	dsmClient = &http.Client{Timeout: 15 * time.Second}
	dsmURLv   string
	dsmOnce   sync.Once
)

// 컨테이너 안에서 본 NAS 자신 = 기본 게이트웨이
func dsmURL() string {
	dsmOnce.Do(func() {
		if v := os.Getenv("MYDRIVE_DSM_URL"); v != "" {
			dsmURLv = strings.TrimRight(v, "/")
			return
		}
		b, err := os.ReadFile("/proc/net/route")
		if err == nil {
			for _, ln := range strings.Split(string(b), "\n")[1:] {
				f := strings.Fields(ln)
				if len(f) > 2 && f[1] == "00000000" {
					if v, err := strconv.ParseUint(f[2], 16, 32); err == nil {
						ip := net.IPv4(byte(v), byte(v>>8), byte(v>>16), byte(v>>24))
						dsmURLv = "http://" + ip.String() + ":5000"
						return
					}
				}
			}
		}
		dsmURLv = "http://127.0.0.1:5000"
	})
	return dsmURLv
}

// DSM WebAPI 한 번 부르기 (_sid 로)
func dsmCall(params url.Values) (map[string]any, error) {
	resp, err := dsmClient.PostForm(dsmURL()+"/webapi/entry.cgi", params)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	var j map[string]any
	if err := json.NewDecoder(resp.Body).Decode(&j); err != nil {
		return nil, err
	}
	return j, nil
}

func dsmErrCode(j map[string]any) int {
	if e, ok := j["error"].(map[string]any); ok {
		if c, ok := e["code"].(float64); ok {
			return int(c)
		}
	}
	return 0
}

// 이 DSM 세션의 사용자 이름과 관리자 여부
func dsmWho(sid string) (user string, admin bool, err error) {
	j, err := dsmCall(url.Values{"api": {"SYNO.Core.NormalUser"}, "method": {"get"}, "version": {"1"}, "_sid": {sid}})
	if err != nil {
		return "", false, err
	}
	if ok, _ := j["success"].(bool); !ok {
		return "", false, fmt.Errorf("DSM 세션 확인 실패 (코드 %d)", dsmErrCode(j))
	}
	if d, ok := j["data"].(map[string]any); ok {
		user, _ = d["username"].(string)
	}
	// 관리자만 볼 수 있는 목록: administrators 그룹 구성원
	g, err := dsmCall(url.Values{"api": {"SYNO.Core.Group.Member"}, "method": {"list"}, "version": {"1"},
		"group": {`"administrators"`}, "ingroup": {"true"}, "_sid": {sid}})
	if err != nil {
		return user, false, err
	}
	if ok, _ := g["success"].(bool); ok {
		if d, ok := g["data"].(map[string]any); ok {
			if us, ok := d["users"].([]any); ok {
				for _, u := range us {
					if m, ok := u.(map[string]any); ok && m["name"] == user {
						return user, true, nil
					}
				}
			}
		}
	}
	return user, false, nil
}

// ---------- 관리 콘솔: DSM 세션 쿠키 ----------
type sidCache struct {
	user  string
	admin bool
	until time.Time
	why   string
}

var (
	sidMu  sync.Mutex
	sidMap = map[string]sidCache{}
)

// 돌려주는 why: 안 된 까닭 (화면에 그대로 보여 줌)
func dsmConsoleAdmin(r *http.Request) (string, bool, string) {
	if r.Header.Get("X-Mydrive-Console") != "1" {
		return "", false, ""
	}
	c, err := r.Cookie("id")
	if err != nil || c.Value == "" || len(c.Value) > 512 {
		return "", false, "DSM 로그인 쿠키가 오지 않았습니다"
	}
	sidMu.Lock()
	sc, ok := sidMap[c.Value]
	sidMu.Unlock()
	if ok && time.Now().Before(sc.until) {
		return sc.user, sc.admin, sc.why
	}
	user, admin, err := dsmWho(c.Value)
	why := ""
	if err != nil {
		log.Printf("DSM 세션 확인: %v", err)
		why = "DSM 세션을 확인하지 못했습니다: " + err.Error()
	} else if !admin {
		why = "DSM 관리자 계정이 아닙니다 (" + user + ")"
	}
	sidMu.Lock()
	if len(sidMap) > 200 {
		sidMap = map[string]sidCache{}
	}
	sidMap[c.Value] = sidCache{user, admin && err == nil, time.Now().Add(2 * time.Minute), why}
	sidMu.Unlock()
	return user, admin && err == nil, why
}

// GET /v1/admin/whoami — 화면이 처음에 부름: 어떤 방법으로 들어왔는지
func hAdminWhoami(w http.ResponseWriter, r *http.Request) {
	user, _ := r.Context().Value(ctxUser{}).(string)
	writeJSON(w, r, map[string]any{"user": user})
}

type ctxUser struct{}

// ---------- PC 전용 토큰 ----------
func tokHash(t string) string { h := sha256.Sum256([]byte(t)); return hex.EncodeToString(h[:]) }

// 동기화 요청의 토큰이 PC 전용 토큰이면 그 기기
func deviceByToken(t string) *Device {
	if t == "" {
		return nil
	}
	h := tokHash(t)
	cfgMu.Lock()
	defer cfgMu.Unlock()
	for i := range srvCfg.Devices {
		if subtle.ConstantTimeCompare([]byte(srvCfg.Devices[i].Hash), []byte(h)) == 1 {
			d := srvCfg.Devices[i]
			devMu.Lock()
			devLast[d.ID] = time.Now().UnixNano()
			devMu.Unlock()
			return &d
		}
	}
	return nil
}

// 1분마다 마지막 사용 시각을 설정 파일에
func devicesStart() {
	go func() {
		for range time.Tick(time.Minute) {
			devMu.Lock()
			upd := devLast
			devLast = map[string]int64{}
			devMu.Unlock()
			if len(upd) == 0 {
				continue
			}
			cfgMu.Lock()
			for i := range srvCfg.Devices {
				if t, ok := upd[srvCfg.Devices[i].ID]; ok {
					srvCfg.Devices[i].Last = t
				}
			}
			cfgMu.Unlock()
			saveCfg()
		}
	}()
}

// 비밀번호를 계속 넣어 보는 것을 막음: IP마다 10분에 실패 5번
var (
	failMu sync.Mutex
	fails  = map[string][]time.Time{}
)

func tooManyFails(ip string) bool {
	failMu.Lock()
	defer failMu.Unlock()
	cut := time.Now().Add(-10 * time.Minute)
	v := fails[ip][:0]
	for _, t := range fails[ip] {
		if t.After(cut) {
			v = append(v, t)
		}
	}
	fails[ip] = v
	return len(v) >= 5
}
func noteFail(ip string) { failMu.Lock(); fails[ip] = append(fails[ip], time.Now()); failMu.Unlock() }

func hPair(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		httpErr(w, 405, "method", "POST 만")
		return
	}
	ip, _, _ := net.SplitHostPort(r.RemoteAddr)
	if tooManyFails(ip) {
		httpErr(w, 429, "too_many", "실패가 많아 10분 동안 막았습니다. 잠시 뒤 다시 해 보세요.")
		return
	}
	var req struct{ Account, Passwd, Otp, PC string }
	if json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&req) != nil || req.Account == "" || req.Passwd == "" {
		httpErr(w, 400, "bad_json", "아이디와 비밀번호를 넣어 주세요")
		return
	}
	user, st, kind, msg := dsmVerifyAdmin(ip, req.Account, req.Passwd, req.Otp)
	if st != 0 {
		httpErr(w, st, kind, msg)
		return
	}
	pc := strings.TrimSpace(req.PC)
	if pc == "" {
		pc = "(이름 없음)"
	}
	t := randHex() + randHex() + randHex()
	d := Device{ID: randHex()[:12], PC: pc, User: user, Hash: tokHash(t), Created: time.Now().UnixNano()}
	cfgMu.Lock()
	srvCfg.Devices = append(srvCfg.Devices, d)
	cfgMu.Unlock()
	if err := saveCfg(); err != nil {
		httpErr(w, 500, "io", err.Error())
		return
	}
	log.Printf("PC 연결: %s (DSM 사용자 %s, %s)", pc, user, ip)
	writeJSON(w, r, map[string]any{"ok": true, "token": t, "user": user, "id": d.ID})
}

func hAdminDevices(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodPost {
		var req struct{ Revoke string }
		if json.NewDecoder(r.Body).Decode(&req) != nil || req.Revoke == "" {
			httpErr(w, 400, "bad_json", "요청 형식 오류")
			return
		}
		cfgMu.Lock()
		keep := srvCfg.Devices[:0]
		gone := ""
		for _, d := range srvCfg.Devices {
			if d.ID == req.Revoke {
				gone = d.PC
				continue
			}
			keep = append(keep, d)
		}
		srvCfg.Devices = keep
		cfgMu.Unlock()
		saveCfg()
		log.Printf("PC 연결 끊음: %s", gone)
		writeJSON(w, r, map[string]any{"ok": true})
		return
	}
	cfgMu.Lock()
	out := make([]Device, len(srvCfg.Devices))
	copy(out, srvCfg.Devices)
	cfgMu.Unlock()
	devMu.Lock()
	for i := range out {
		if t, ok := devLast[out[i].ID]; ok {
			out[i].Last = t
		}
		out[i].Hash = ""
	}
	devMu.Unlock()
	sort.Slice(out, func(i, j int) bool { return out[i].Created > out[j].Created })
	writeJSON(w, r, map[string]any{"devices": out, "dsm": dsmURL()})
}

// DSM 에 그 계정으로 로그인해 보고 관리자인지 확인 (세션은 바로 닫음). st=0 이면 성공
func dsmVerifyAdmin(ip, account, passwd, otp string) (user string, st int, kind, msg string) {
	p := url.Values{"api": {"SYNO.API.Auth"}, "method": {"login"}, "version": {"6"}, "account": {account},
		"passwd": {passwd}, "session": {"Mydrive"}, "format": {"sid"}}
	if otp != "" {
		p.Set("otp_code", otp)
	}
	j, err := dsmCall(p)
	if err != nil {
		return "", 502, "dsm", "DSM에 닿지 않음: "+err.Error()
	}
	if ok, _ := j["success"].(bool); !ok {
		code := dsmErrCode(j)
		msg := fmt.Sprintf("DSM 로그인 실패 (코드 %d)", code)
		kind := "login"
		switch code {
		case 400:
			msg = "아이디 또는 비밀번호가 맞지 않습니다"
		case 401:
			msg = "비활성화된 계정입니다"
		case 402:
			msg = "이 계정은 권한이 없습니다"
		case 403:
			msg, kind = "2단계 인증 코드가 필요합니다", "otp"
		case 404:
			msg, kind = "2단계 인증 코드가 맞지 않습니다", "otp"
		case 407:
			msg = "DSM이 이 IP를 막았습니다 (자동 차단)"
		}
		if code != 403 {
			noteFail(ip)
		}
		return "", 401, kind, msg
	}
	sid := ""
	if d, ok := j["data"].(map[string]any); ok {
		sid, _ = d["sid"].(string)
	}
	user, admin, err := dsmWho(sid)
	dsmCall(url.Values{"api": {"SYNO.API.Auth"}, "method": {"logout"}, "version": {"6"}, "session": {"Mydrive"}, "_sid": {sid}})
	if err != nil || !admin {
		noteFail(ip)
		return "", 403, "not_admin", "DSM 관리자 계정만 연결할 수 있습니다 (NAS 전체 폴더를 다루기 때문)"
	}
	return user, 0, "", ""
}

// ---------- 관리 콘솔: DSM 계정으로 로그인 → 30일 쿠키 ----------
//   POST /v1/admin/login  {"account","passwd","otp"}   (토큰 없이 들어오는 길)
//   POST /v1/admin/logout
type ConsoleSess struct {
	Hash  string `json:"hash"`
	User  string `json:"user"`
	Until int64  `json:"until"` // ns
}

const consoleCookie = "mydrive_console"

func consoleUser(r *http.Request) (string, bool) {
	if r.Header.Get("X-Mydrive-Console") != "1" {
		return "", false
	}
	c, err := r.Cookie(consoleCookie)
	if err != nil || c.Value == "" || len(c.Value) > 256 {
		return "", false
	}
	h := tokHash(c.Value)
	now := time.Now().UnixNano()
	cfgMu.Lock()
	defer cfgMu.Unlock()
	for _, cs := range srvCfg.Consoles {
		if cs.Until > now && subtle.ConstantTimeCompare([]byte(cs.Hash), []byte(h)) == 1 {
			return cs.User, true
		}
	}
	return "", false
}

func hConsoleLogin(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost || r.Header.Get("X-Mydrive-Console") != "1" {
		httpErr(w, 405, "method", "POST 만")
		return
	}
	ip, _, _ := net.SplitHostPort(r.RemoteAddr)
	if tooManyFails(ip) {
		httpErr(w, 429, "too_many", "실패가 많아 10분 동안 막았습니다. 잠시 뒤 다시 해 보세요.")
		return
	}
	var req struct{ Account, Passwd, Otp string }
	if json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&req) != nil || req.Account == "" || req.Passwd == "" {
		httpErr(w, 400, "bad_json", "아이디와 비밀번호를 넣어 주세요")
		return
	}
	user, st, kind, msg := dsmVerifyAdmin(ip, req.Account, req.Passwd, req.Otp)
	if st != 0 {
		httpErr(w, st, kind, msg)
		return
	}
	t := randHex() + randHex() + randHex()
	now := time.Now()
	until := now.Add(30 * 24 * time.Hour)
	cfgMu.Lock()
	keep := srvCfg.Consoles[:0]
	for _, cs := range srvCfg.Consoles {
		if cs.Until > now.UnixNano() {
			keep = append(keep, cs)
		}
	}
	if len(keep) > 50 {
		keep = keep[len(keep)-50:]
	}
	srvCfg.Consoles = append(keep, ConsoleSess{Hash: tokHash(t), User: user, Until: until.UnixNano()})
	cfgMu.Unlock()
	if err := saveCfg(); err != nil {
		httpErr(w, 500, "io", err.Error())
		return
	}
	http.SetCookie(w, &http.Cookie{Name: consoleCookie, Value: t, Path: "/", Expires: until, MaxAge: 30 * 24 * 3600,
		HttpOnly: true, SameSite: http.SameSiteStrictMode})
	log.Printf("관리 콘솔 로그인: DSM 사용자 %s (%s)", user, ip)
	writeJSON(w, r, map[string]any{"ok": true, "user": "DSM " + user})
}

func hConsoleLogout(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost || r.Header.Get("X-Mydrive-Console") != "1" {
		httpErr(w, 405, "method", "POST 만")
		return
	}
	if c, err := r.Cookie(consoleCookie); err == nil && c.Value != "" {
		h := tokHash(c.Value)
		cfgMu.Lock()
		keep := srvCfg.Consoles[:0]
		for _, cs := range srvCfg.Consoles {
			if cs.Hash != h {
				keep = append(keep, cs)
			}
		}
		srvCfg.Consoles = keep
		cfgMu.Unlock()
		saveCfg()
	}
	http.SetCookie(w, &http.Cookie{Name: consoleCookie, Value: "", Path: "/", MaxAge: -1, HttpOnly: true, SameSite: http.SameSiteStrictMode})
	writeJSON(w, r, map[string]any{"ok": true})
}
