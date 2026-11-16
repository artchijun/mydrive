package main

// 서버 스스로 새 판으로 바꾸기 — DSM 에 로그인해 파일을 바꿔 끼우지 않아도 됨.
//
//	두 가지 길 (둘 다 서명 확인: 우리 서명 열쇠로 서명한 실행 파일만 받아들임)
//	1) 밀어 넣기: POST /v1/server/update?sig=<base64>  (본문 = 새 실행 파일, 연결 토큰 필요)
//	2) 스스로 확인: GitHub 의 server-v* 릴리스에 mydrived + mydrived.sig 가 있으면 6시간마다 받아서 바꿈
//	   (MYDRIVE_AUTOUPDATE=0 이면 끔)
//	바꾸는 법: 컨테이너는 /app/mydrived(읽기 전용)를 /tmp 로 복사해 실행하므로, 같은 파일을 /nas 쪽 경로
//	(/nas/docker/mydrive/bin/mydrived, MYDRIVE_SELF)로 바꿔 쓰고 끝냄 → Docker(restart: unless-stopped)가 새 판으로 다시 켬.
//	예전 판은 옆에 mydrived.bak_<시각> 으로 남김 (최근 3개).
//
//	GET /v1/server/update → {version, self, writable, auto, last}

import (
	"bytes"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

const updatePubKey = "R7CfCY7xoVx6MDDqQXFxLRc5BKY52pLAMiUUqw31L/g="

var (
	selfPath   = env("MYDRIVE_SELF", "/nas/docker/mydrive/bin/mydrived")
	autoUpdate = env("MYDRIVE_AUTOUPDATE", "1") != "0"
	updMu      sync.Mutex
	updLast    string
)

var verRe = regexp.MustCompile(`mydrived (\d+\.\d+\.\d+)`)

func verOf(bin []byte) string {
	if m := verRe.FindSubmatch(bin); m != nil {
		return string(m[1])
	}
	return ""
}

func newerVer(a, b string) bool { // a > b ?
	pa, pb := strings.Split(a, "."), strings.Split(b, ".")
	for i := 0; i < 3; i++ {
		var x, y int
		if i < len(pa) {
			x, _ = strconv.Atoi(pa[i])
		}
		if i < len(pb) {
			y, _ = strconv.Atoi(pb[i])
		}
		if x != y {
			return x > y
		}
	}
	return false
}

// 서명·형식 확인 후 실행 파일을 바꿔 둠 (끝내기는 부른 쪽에서)
func installUpdate(bin, sig []byte) (string, error) {
	pub, _ := base64.StdEncoding.DecodeString(updatePubKey)
	if len(sig) != ed25519.SignatureSize || !ed25519.Verify(ed25519.PublicKey(pub), bin, sig) {
		return "", errors.New("서명이 맞지 않음 — 받아들이지 않음")
	}
	if len(bin) < 1<<20 || !bytes.HasPrefix(bin, []byte("\x7fELF")) {
		return "", errors.New("리눅스 실행 파일이 아님")
	}
	nv := verOf(bin)
	if nv == "" {
		return "", errors.New("판 번호를 찾지 못함")
	}
	updMu.Lock()
	defer updMu.Unlock()
	dir := filepath.Dir(selfPath)
	tmp := selfPath + ".new"
	if err := os.WriteFile(tmp, bin, 0755); err != nil {
		return "", fmt.Errorf("새 판을 쓰지 못함 (%s): %v", dir, err)
	}
	if f, err := os.Open(tmp); err == nil {
		f.Sync()
		f.Close()
	}
	if _, err := os.Stat(selfPath); err == nil {
		bak := fmt.Sprintf("%s.bak_%s_%s", selfPath, time.Now().Format("20060102_1504"), version)
		if err := os.Rename(selfPath, bak); err != nil {
			os.Remove(tmp)
			return "", fmt.Errorf("예전 판을 옮기지 못함: %v", err)
		}
	}
	if err := os.Rename(tmp, selfPath); err != nil {
		return "", fmt.Errorf("새 판으로 바꾸지 못함: %v", err)
	}
	// 예전 판은 최근 3개만
	if olds, _ := filepath.Glob(selfPath + ".bak_*_*"); len(olds) > 3 {
		sort.Strings(olds)
		for _, o := range olds[:len(olds)-3] {
			os.Remove(o)
		}
	}
	updLast = time.Now().Format("2006-01-02 15:04") + " → " + nv
	log.Printf("서버 새 판으로 바꿈: %s → %s (다시 켜짐)", version, nv)
	return nv, nil
}

func restartSoon() { go func() { time.Sleep(800 * time.Millisecond); shutdown() }() }

func hServerUpdate(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodGet {
		writable := false
		if f, err := os.OpenFile(filepath.Join(filepath.Dir(selfPath), ".mydrive-w"), os.O_CREATE|os.O_WRONLY, 0644); err == nil {
			f.Close()
			os.Remove(f.Name())
			writable = true
		}
		writeJSON(w, r, map[string]any{"version": version, "self": selfPath, "writable": writable, "auto": autoUpdate, "last": updLast})
		return
	}
	if r.Method != http.MethodPost {
		httpErr(w, 405, "method", "GET·POST 만")
		return
	}
	sig, err := base64.StdEncoding.DecodeString(r.URL.Query().Get("sig"))
	if err != nil {
		httpErr(w, 400, "bad_sig", "서명 형식 오류")
		return
	}
	bin, err := io.ReadAll(io.LimitReader(r.Body, 64<<20))
	if err != nil {
		httpErr(w, 400, "io", err.Error())
		return
	}
	if nv := verOf(bin); nv != "" && !newerVer(nv, version) && r.URL.Query().Get("force") != "1" {
		httpErr(w, 409, "not_newer", "지금 판("+version+")보다 새 판이 아님: "+nv)
		return
	}
	nv, err := installUpdate(bin, sig)
	if err != nil {
		httpErr(w, 400, "rejected", err.Error())
		return
	}
	writeJSON(w, r, map[string]any{"ok": true, "from": version, "to": nv})
	restartSoon()
}

// ---------- GitHub 릴리스에서 스스로 확인 ----------
const releasesURL = "https://api.github.com/repos/artchijun/mydrive/releases?per_page=30"

func autoUpdateLoop() {
	if !autoUpdate {
		return
	}
	time.Sleep(2 * time.Minute) // 켜자마자 바로 받지 않음 (켜진 직후 문제 확인할 틈)
	for {
		if err := checkGitHub(); err != nil {
			log.Printf("서버 새 판 확인: %v", err)
		}
		time.Sleep(6 * time.Hour)
	}
}

func checkGitHub() error {
	c := &http.Client{Timeout: 60 * time.Second}
	resp, err := c.Get(releasesURL)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return fmt.Errorf("GitHub %d", resp.StatusCode)
	}
	var rels []struct {
		Tag    string `json:"tag_name"`
		Draft  bool   `json:"draft"`
		Assets []struct {
			Name string `json:"name"`
			URL  string `json:"browser_download_url"`
		} `json:"assets"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&rels); err != nil {
		return err
	}
	best, binURL, sigURL := version, "", ""
	for _, rl := range rels {
		if rl.Draft || !strings.HasPrefix(rl.Tag, "server-v") {
			continue
		}
		v := strings.TrimPrefix(rl.Tag, "server-v")
		if !newerVer(v, best) {
			continue
		}
		var b, s string
		for _, a := range rl.Assets {
			switch a.Name {
			case "mydrived":
				b = a.URL
			case "mydrived.sig":
				s = a.URL
			}
		}
		if b != "" && s != "" {
			best, binURL, sigURL = v, b, s
		}
	}
	if binURL == "" {
		return nil
	}
	get := func(u string, max int64) ([]byte, error) {
		r, err := (&http.Client{Timeout: 10 * time.Minute}).Get(u)
		if err != nil {
			return nil, err
		}
		defer r.Body.Close()
		if r.StatusCode != 200 {
			return nil, fmt.Errorf("받기 %d", r.StatusCode)
		}
		return io.ReadAll(io.LimitReader(r.Body, max))
	}
	bin, err := get(binURL, 64<<20)
	if err != nil {
		return err
	}
	sigTxt, err := get(sigURL, 4096)
	if err != nil {
		return err
	}
	sig, err := base64.StdEncoding.DecodeString(strings.TrimSpace(string(sigTxt)))
	if err != nil {
		return errors.New("서명 파일 형식 오류")
	}
	if _, err := installUpdate(bin, sig); err != nil {
		return err
	}
	restartSoon()
	return nil
}
