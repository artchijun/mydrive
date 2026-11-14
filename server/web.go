// web.go — 웹 관리 콘솔 (DSM 에서 여는 관리 화면). 페이지는 실행 파일 안에 들어 있음.
//
//   GET /admin   관리 콘솔 (로그인은 관리 토큰 — 화면이 /v1/admin/* 를 부름)
//   GET /        → /admin 으로
package main

import (
	_ "embed"
	"net/http"
	"strings"
)

//go:embed web/admin.html
var adminHTML string

// 아이콘 (선 그림, currentColor)
var adminIcons = map[string]string{
	"SYNC":   `<path d="M20 11a8 8 0 0 0-14.3-4.9L4 8"/><path d="M4 4v4h4"/><path d="M4 13a8 8 0 0 0 14.3 4.9L20 16"/><path d="M20 20v-4h-4"/>`,
	"HOME":   `<path d="M3 11l9-7 9 7"/><path d="M5 10v10h14V10"/>`,
	"FOLDER": `<path d="M3 6.5A1.5 1.5 0 0 1 4.5 5H9l2 2h8.5A1.5 1.5 0 0 1 21 8.5v9a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 17.5z"/>`,
	"HIST":   `<path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 3v5h5"/><path d="M12 7v5l3 2"/>`,
	"PC":     `<rect x="3" y="4" width="18" height="12" rx="1.5"/><path d="M8 20h8M12 16v4"/>`,
	"LOG":    `<path d="M6 3h9l4 4v14H6z"/><path d="M14 3v5h5M9 13h7M9 17h7"/>`,
	"KEY":    `<circle cx="8" cy="15" r="4"/><path d="M11 12l9-9M17 6l3 3M15 8l2 2"/>`,
	"OUT":    `<path d="M15 4h4v16h-4"/><path d="M10 8l-4 4 4 4M6 12h11"/>`,
	"ACT":    `<path d="M4 6h16M4 12h16M4 18h10"/><path d="M18 16l2 2 3-4"/>`,
}

var adminPage = func() string {
	s := adminHTML
	for k, v := range adminIcons {
		s = strings.ReplaceAll(s, "{{"+k+"}}", `<svg class="i" viewBox="0 0 24 24">`+v+`</svg>`)
	}
	return s
}()

func hAdminPage(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	// DSM 기본 메뉴의 Mydrive 창(iframe)에서 열림. 다른 사이트가 몰래 감싸지 못하게 DSM 포트(5000/5001)만 허용
	w.Header().Set("Content-Security-Policy", "frame-ancestors 'self' http://*:5000 https://*:5001")
	w.Write([]byte(adminPage))
}

func hRootRedirect(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" {
		http.NotFound(w, r)
		return
	}
	http.Redirect(w, r, "/admin", http.StatusFound)
}
