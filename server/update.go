// update.go — PC 앱 새 판 배포 (내려주기만 함)
//
//   GET /v1/update/latest   {"version","file","size","sha256","notes"}  (새 판이 없으면 version 이 빈 값)
//   GET /v1/update/file     latest.json 에 적힌 설치 파일 (Range 지원)
//
// 폴더: MYDRIVE_UPDATES (기본 /app/updates = NAS의 /docker/mydrive/bin/updates, 컨테이너에는 읽기 전용으로 붙음)
//   latest.json + 설치 파일 하나. 서버에는 올리는 기능이 없음 — 새 판은 File Station 으로 이 폴더에 넣음
//   (client/release.ps1 이 latest.json 과 설치 파일을 만들어 줌).
package main

import (
	"encoding/json"
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

type updateInfo struct {
	Version string `json:"version"`
	File    string `json:"file"`
	Size    int64  `json:"size"`
	SHA256  string `json:"sha256"`
	Notes   string `json:"notes,omitempty"`
}

var reUpdVer = regexp.MustCompile(`^\d+(\.\d+){1,3}$`)
var reSha = regexp.MustCompile(`^[0-9a-f]{64}$`)

func updatesDir() string { return env("MYDRIVE_UPDATES", "/app/updates") }

// latest.json 을 읽고 검사. 설치 파일은 같은 폴더 바로 아래의 .exe 하나만 허용
func readLatest() (*updateInfo, string, error) {
	dir := updatesDir()
	b, err := os.ReadFile(filepath.Join(dir, "latest.json"))
	if err != nil {
		return nil, "", err
	}
	var u updateInfo
	if err := json.Unmarshal(b, &u); err != nil {
		return nil, "", err
	}
	u.SHA256 = strings.ToLower(strings.TrimSpace(u.SHA256))
	if !reUpdVer.MatchString(u.Version) || !reSha.MatchString(u.SHA256) {
		return nil, "", errors.New("latest.json: version/sha256 형식이 맞지 않음")
	}
	if u.File == "" || filepath.Base(u.File) != u.File || strings.HasPrefix(u.File, ".") || !strings.EqualFold(filepath.Ext(u.File), ".exe") {
		return nil, "", errors.New("latest.json: file 이름이 맞지 않음")
	}
	p := filepath.Join(dir, u.File)
	fi, err := os.Stat(p)
	if err != nil || !fi.Mode().IsRegular() {
		return nil, "", errors.New("latest.json 에 적힌 설치 파일이 없음")
	}
	u.Size = fi.Size()
	return &u, p, nil
}

func hUpdateLatest(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		httpErr(w, 405, "method", "GET 만 됨")
		return
	}
	u, _, err := readLatest()
	if err != nil {
		if !errors.Is(err, os.ErrNotExist) {
			writeJSON(w, r, map[string]any{"version": "", "error": err.Error()})
			return
		}
		writeJSON(w, r, map[string]any{"version": ""})
		return
	}
	writeJSON(w, r, u)
}

func hUpdateFile(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		httpErr(w, 405, "method", "GET 만 됨")
		return
	}
	u, p, err := readLatest()
	if err != nil {
		httpErr(w, 404, "notfound", "새 판이 없음")
		return
	}
	f, err := os.Open(p)
	if err != nil {
		httpErr(w, 404, "notfound", "설치 파일을 열 수 없음")
		return
	}
	defer f.Close()
	fi, _ := f.Stat()
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("X-Mydrive-Sha256", u.SHA256)
	http.ServeContent(w, r, u.File, fi.ModTime(), f)
}
