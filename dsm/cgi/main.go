// md.cgi — DSM 안의 Mydrive 창이 관리 콘솔을 DSM 주소 그대로 불러오게 하는 중계 프로그램.
//
//	브라우저는 DSM 페이지(5000/5001, 또는 QuickConnect 주소) 안에 다른 포트(8642)를 넣으면 막는 경우가 많음
//	(https 와 http 섞임, 바깥에서 8642 포트가 안 열림 등). 그래서 DSM 웹 서버가 이 CGI 를 실행하고,
//	CGI 가 NAS 안에서 http://127.0.0.1:8642 로 요청을 넘겨 줌. 브라우저 입장에서는 모두 DSM 주소.
//
//	주소: /webman/3rdparty/Mydrive/md.cgi?u=<콘솔 경로>   (예: u=/admin, u=/v1/admin/info)
//	관리 콘솔 경로(/admin, /v1/admin/...)만 넘김. 동기화 API 는 넘기지 않음.
package main

import (
	"net/http"
	"net/http/cgi"
	"net/http/httputil"
	"net/url"
	"strings"
)

var backendHost = "127.0.0.1:8642" // 시험할 때만 -ldflags -X main.backendHost=... 로 바꿈

var backend = &url.URL{Scheme: "http", Host: backendHost}

func allowed(p string) bool {
	return p == "/admin" || strings.HasPrefix(p, "/v1/admin/")
}

func main() {
	rp := &httputil.ReverseProxy{
		Rewrite: func(pr *httputil.ProxyRequest) {
			u, _ := url.Parse(pr.In.URL.Query().Get("u"))
			pr.Out.URL = &url.URL{Scheme: backend.Scheme, Host: backend.Host, Path: u.Path, RawQuery: u.RawQuery}
			pr.Out.Host = backend.Host
			pr.SetXForwarded() // 서버가 진짜 접속 주소를 알도록 (로그인 실패 막기, 기록)
			pr.Out.Header.Set("X-Mydrive-Via", "dsm")
		},
		ErrorHandler: func(w http.ResponseWriter, r *http.Request, err error) {
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			w.WriteHeader(http.StatusBadGateway)
			w.Write([]byte(`<body style="background:#121418;color:#e6e8eb;font:14px/1.6 sans-serif;padding:28px">` +
				`Mydrive 서버(컨테이너 mydrived, 포트 8642)에 연결하지 못했습니다.<br>` +
				`Container Manager 에서 mydrived 가 실행 중인지 확인해 주세요.</body>`))
		},
	}
	cgi.Serve(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw := r.URL.Query().Get("u")
		if raw == "" {
			raw = "/admin?embed=1"
			q := r.URL.Query()
			q.Set("u", raw)
			r.URL.RawQuery = q.Encode()
		}
		u, err := url.Parse(raw)
		if err != nil || u.Host != "" || u.Scheme != "" || !allowed(u.Path) || strings.Contains(u.Path, "..") {
			http.Error(w, "허용되지 않는 경로", http.StatusForbidden)
			return
		}
		rp.ServeHTTP(w, r)
	}))
}
