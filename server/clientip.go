package main

import (
	"net"
	"os"
	"net/http"
	"strings"
)

// clientIP: 요청한 PC 의 IP.
//
//	DSM 창(md.cgi 중계)을 거쳐 오면 접속 주소가 NAS 자신(127.0.0.1 또는 도커 내부 172.16~31.x)이라
//	중계가 붙인 X-Forwarded-For 의 첫 주소를 씀. NAS 바깥에서 곧바로 온 요청은 이 머리글을 믿지 않음.
func clientIP(r *http.Request) string {
	ip, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		ip = r.RemoteAddr
	}
	if r.Header.Get("X-Mydrive-Via") == "dsm" {
		if p := net.ParseIP(ip); p != nil && (p.IsLoopback() || inNet(p, "172.16.0.0/12")) {
			if f := strings.TrimSpace(strings.Split(r.Header.Get("X-Forwarded-For"), ",")[0]); net.ParseIP(f) != nil {
				return f
			}
		}
	}
	return ip
}

func inNet(p net.IP, cidr string) bool {
	_, n, err := net.ParseCIDR(cidr)
	return err == nil && n.Contains(p)
}

// inotifyLimit: 지금 NAS 의 감시 한도 (fs.inotify.max_user_watches) — 기록에 함께 남겨 한도를 올렸는지 확인
func inotifyLimit() string {
	b, err := os.ReadFile("/proc/sys/fs/inotify/max_user_watches")
	if err != nil {
		return "?"
	}
	return strings.TrimSpace(string(b))
}