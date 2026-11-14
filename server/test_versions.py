# 버전 관리 + 관리 API 시험 — WSL 서버(localhost:8642, 동기화 토큰 test, 관리 토큰 admintest)
import json, sys, time, urllib.error, urllib.parse, urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8642"
TOK = sys.argv[2] if len(sys.argv) > 2 else "test"
ADM = sys.argv[3] if len(sys.argv) > 3 else "admintest"
ROOT = sys.argv[4] if len(sys.argv) > 4 else "test"
ok = fail = 0

def req(method, ep, data=None, tok=None, **q):
    url = BASE + ep + ("?" + urllib.parse.urlencode(q) if q else "")
    h = {"Authorization": "Bearer " + (tok or TOK), "X-Mydrive-PC": urllib.parse.quote("시험PC")}
    if isinstance(data, (dict, list)): data = json.dumps(data).encode(); h["Content-Type"] = "application/json"
    r = urllib.request.Request(url, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(r, timeout=60) as resp:
            body = resp.read()
            return resp.status, (json.loads(body) if resp.headers.get("Content-Type", "").startswith("application/json") else body)
    except urllib.error.HTTPError as e:
        b = e.read(); return e.code, (json.loads(b) if b[:1] == b"{" else b)

def check(name, cond, info=""):
    global ok, fail
    if cond: ok += 1; print("  OK  ", name)
    else: fail += 1; print("  FAIL", name, info)

def put(p, data, cond=None):
    q = {"root": ROOT, "path": p, "mtime": str(time.time_ns())}
    if cond: q["if"] = cond
    return req("PUT", "/v1/file", data, **q)

P = "버전시험/보고서.txt"
req("DELETE", "/v1/file", root=ROOT, path="버전시험")
time.sleep(0.5)
print("1) 고칠 때마다 옛 판이 남음")
put(P, b"v1"); time.sleep(0.05); put(P, b"v2 longer"); time.sleep(0.05); st, e = put(P, b"v3 the latest")
st, vs = req("GET", "/v1/versions", root=ROOT, path=P)
check("버전 2개 (v1, v2)", st == 200 and len(vs) == 2, vs)
check("새것부터, 크기 맞음", [v["s"] for v in vs] == [9, 2], vs)
st, body = req("GET", "/v1/version", root=ROOT, path=P, id=vs[1]["id"])
check("옛 판 내용 받기 = v1", body == b"v1", body)

print("2) 되돌리기 — 지금 판도 버전으로 남음")
st, e = req("POST", "/v1/restore", root=ROOT, path=P, id=vs[1]["id"])
st2, cur = req("GET", "/v1/file", root=ROOT, path=P)
check("지금 판 = v1", st == 200 and cur == b"v1", (st, e, cur))
st, vs2 = req("GET", "/v1/versions", root=ROOT, path=P)
check("버전 3개 (v3 가 새로 남음)", len(vs2) == 3 and vs2[0]["s"] == 13, vs2)

print("3) 이름 바꾸면 버전도 따라감")
st, _ = req("POST", "/v1/move", root=ROOT, **{"from": P, "to": "버전시험/새이름.txt"})
st, vs3 = req("GET", "/v1/versions", root=ROOT, path="버전시험/새이름.txt")
check("새 이름에 버전 3개", st == 200 and len(vs3) == 3, vs3)

print("4) 지운 파일 되살리기")
put("버전시험/폴더/a.txt", b"aaa"); put("버전시험/폴더/b.txt", b"bbbb")
st, _ = req("DELETE", "/v1/file", root=ROOT, path="버전시험/새이름.txt")
st, _ = req("DELETE", "/v1/file", root=ROOT, path="버전시험/폴더")
st, dl = req("GET", "/v1/deleted", root=ROOT)
names = sorted(d["p"] for d in dl if d["p"].startswith("버전시험/"))
check("지운 파일 목록에 3개 (폴더째 지운 것 포함)", names == ["버전시험/새이름.txt", "버전시험/폴더/a.txt", "버전시험/폴더/b.txt"], names)
d = [x for x in dl if x["p"] == "버전시험/폴더/b.txt"][0]
st, e = req("POST", "/v1/restore", root=ROOT, path=d["p"], id=d["v"]["id"])
st2, cur = req("GET", "/v1/file", root=ROOT, path="버전시험/폴더/b.txt")
check("되살림 (폴더도 다시 생김)", cur == b"bbbb", (st, e))

print("5) 버전 폴더는 목록·변경 기록에 안 보임")
st, snap = req("GET", "/v1/snapshot", root=ROOT)
check("#mydrive-versions 없음", not any("#mydrive-versions" in x["p"] for x in snap["entries"]))

print("6) 관리 API")
st, _ = req("GET", "/v1/admin/info")
check("동기화 토큰으로는 관리 거절", st == 401, st)
st, info = req("GET", "/v1/admin/info", tok=ADM)
check("관리 정보", st == 200 and info["version"] and any(r["name"] == ROOT for r in info["roots"]), info.keys() if isinstance(info, dict) else info)
check("접속 PC 기록", any(c["pc"] == "시험PC" for c in info["clients"]), info.get("clients"))
check("버전 보관량", any(r["verFiles"] > 0 for r in info["roots"]), info["roots"])
st, br = req("GET", "/v1/admin/browse", tok=ADM)
check("NAS 폴더 둘러보기", st == 200 and isinstance(br.get("dirs"), list), br)
st, br2 = req("GET", "/v1/admin/browse", tok=ADM, path="/etc")
check("공유 폴더 밖은 거절", st == 400, br2)
if "--roots" in sys.argv:
    base = br["base"]
    st, r = req("POST", "/v1/admin/roots", {"add": {"name": "other", "dir": base + "/other"}}, tok=ADM)
    check("공유 폴더 추가", st == 200, r)
    st, roots = req("GET", "/v1/roots")
    check("목록에 나옴", any(x["name"] == "other" for x in roots), roots)
    st, r = req("POST", "/v1/admin/roots", {"add": {"name": "겹침", "dir": base + "/other/sub"}}, tok=ADM)
    check("겹치는 폴더는 거절", st == 400, r)
    st, r = req("POST", "/v1/admin/roots", {"remove": "other"}, tok=ADM)
    st, roots = req("GET", "/v1/roots")
    check("공유 폴더 빼기", not any(x["name"] == "other" for x in roots), roots)
st, r = req("POST", "/v1/admin/policy", {"maxCount": 2, "keepDays": 90}, tok=ADM)
put("버전시험/많이.txt", b"1"); put("버전시험/많이.txt", b"2"); put("버전시험/많이.txt", b"3"); put("버전시험/많이.txt", b"4"); put("버전시험/많이.txt", b"5")
time.sleep(0.5)
st, vs = req("GET", "/v1/versions", root=ROOT, path="버전시험/많이.txt")
check("보관 규칙: 최대 2개", len(vs) == 2, vs)
req("POST", "/v1/admin/policy", {"maxCount": 32, "keepDays": 90}, tok=ADM)
req("DELETE", "/v1/file", root=ROOT, path="버전시험")
print(f"\n결과: {ok} 통과, {fail} 실패")
