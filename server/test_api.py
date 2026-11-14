# mydrived API 시험 — WSL 에서 돌고 있는 서버(localhost:8642)에 대고 확인
import json, subprocess, threading, time, urllib.request, urllib.error, urllib.parse

BASE = "http://localhost:8642"
H = {"Authorization": "Bearer test"}
ok = fail = 0

def req(method, ep, data=None, **q):
    url = BASE + ep + ("?" + urllib.parse.urlencode(q) if q else "")
    r = urllib.request.Request(url, data=data, method=method, headers=H)
    try:
        with urllib.request.urlopen(r, timeout=60) as resp:
            body = resp.read()
            return resp.status, (json.loads(body) if resp.headers.get("Content-Type", "").startswith("application/json") else body)
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")

def check(name, cond, info=""):
    global ok, fail
    if cond: ok += 1; print("  OK  ", name)
    else: fail += 1; print("  FAIL", name, info)

def wsl(cmd):
    subprocess.run(["wsl.exe", "-e", "bash", "-lc", cmd], check=True)

wsl("rm -rf ~/mdt/data/big ~/mdt/data/big2 ~/mdt/data/sub/c.txt ~/mdt/data/up ~/mdt/data/#recycle"); time.sleep(1)
st, snap = req("GET", "/v1/snapshot", root="test")
check("snapshot", st == 200 and len(snap["entries"]) == 3, snap)
epoch, seq = snap["epoch"], snap["seq"]

# 토큰 없으면 거절
try:
    urllib.request.urlopen(BASE + "/v1/roots", timeout=5); check("토큰 없으면 거절", False)
except urllib.error.HTTPError as e:
    check("토큰 없으면 거절", e.code == 401)

# 기다리는 변경 요청 (long-poll) — 그 사이 NAS 쪽에서 파일을 만들면 바로 답이 와야 함
res = {}
def poll():
    t0 = time.time(); res["r"] = req("GET", "/v1/changes", root="test", epoch=epoch, since=seq, wait=20); res["t"] = time.time() - t0
th = threading.Thread(target=poll); th.start()
time.sleep(1.0)
wsl("echo new > ~/mdt/data/sub/c.txt")
th.join()
st, ch = res["r"]
paths = [c["e"]["p"] for c in ch["changes"]]
check("NAS 쪽 새 파일을 바로 알림", st == 200 and "sub/c.txt" in paths and res["t"] < 3, (paths, round(res["t"], 2)))
seq = ch["seq"]

# 올리기 (없어야 함)
st, e = req("PUT", "/v1/file", data=b"from pc", root="test", path="up/d.txt", mtime=1700000000123456789, **{"if": "none"})
check("새 파일 올리기", st == 200 and e["s"] == 7 and e["m"] == 1700000000123456789, e)
st, e2 = req("PUT", "/v1/file", data=b"again", root="test", path="up/d.txt", mtime=1700000000000000000, **{"if": "none"})
check("이미 있으면 if=none 거절(409)", st == 409, e2)
# 조건부 덮기
cond = f'{e["s"]}:{e["m"]}:{e["i"]}'
st, e3 = req("PUT", "/v1/file", data=b"edited on pc", root="test", path="up/d.txt", mtime=1700000001000000000, **{"if": cond})
check("같은 판이면 덮기", st == 200 and e3["s"] == 12, e3)
st, e4 = req("PUT", "/v1/file", data=b"stale", root="test", path="up/d.txt", mtime=1700000002000000000, **{"if": cond})
check("옛 판 기준이면 거절(충돌)", st == 409, e4)

# 내려받기 + Range
st, body = req("GET", "/v1/file", root="test", path="up/d.txt")
check("내려받기", st == 200 and body == b"edited on pc", body)
r = urllib.request.Request(BASE + "/v1/file?root=test&path=up/d.txt", headers={**H, "Range": "bytes=0-5"})
with urllib.request.urlopen(r) as resp:
    check("부분 내려받기(Range)", resp.status == 206 and resp.read() == b"edited")

# 이름 바꾸기
st, m = req("POST", "/v1/move", root="test", **{"from": "up/d.txt", "to": "up/e.txt"})
check("이름 바꾸기", st == 200 and m["p"] == "up/e.txt" and m["i"] == e3["i"], m)

# 삭제 → #recycle
st, d = req("DELETE", "/v1/file", root="test", path="up/e.txt", **{"if": f'{m["s"]}:{m["m"]}'})
check("삭제는 휴지통으로", st == 200 and d.get("recycle") == "#recycle/up/e.txt", d)

# 변경 기록에 다 들어왔는지 (휴지통은 빠져야 함)
time.sleep(0.6)
st, ch = req("GET", "/v1/changes", root="test", epoch=epoch, since=seq)
ops = [(c["op"], c["e"]["p"]) for c in ch["changes"]]
check("변경 기록: 올림·이름 변경·삭제", ("del", "up/e.txt") in ops and ("put", "up") in ops, ops)
check("휴지통은 기록에서 빠짐", not any("#recycle" in p for _, p in ops), ops)

# 폴더째 옮기기 (NAS 쪽에서)
wsl("mkdir -p ~/mdt/data/big && for i in $(seq 1 50); do echo $i > ~/mdt/data/big/f$i.txt; done")
time.sleep(1.0)
seq2 = ch["seq"]
wsl("mv ~/mdt/data/big ~/mdt/data/big2")
time.sleep(1.0)
st, ch = req("GET", "/v1/changes", root="test", epoch=epoch, since=seq2)
dels = sum(1 for c in ch["changes"] if c["op"] == "del" and (c["e"]["p"] == "big" or c["e"]["p"].startswith("big/")))
puts = sum(1 for c in ch["changes"] if c["op"] == "put" and (c["e"]["p"] == "big2" or c["e"]["p"].startswith("big2/")))
check("NAS 쪽 폴더 이동: 옛 자리 51개(폴더+50) 삭제, 새 자리 51개 생김", dels == 51 and puts == 51, (dels, puts))
wsl("rm -rf ~/mdt/data/big2 ~/mdt/data/sub/c.txt ~/mdt/data/up ~/mdt/data/#recycle")

# 잘못된 경로
st, _ = req("GET", "/v1/file", root="test", path="../etc/passwd")
check("../ 경로 거절", st == 400)
st, _ = req("GET", "/v1/changes", root="test", epoch="wrong", since=0)
check("epoch 다르면 reset", st == 200 and _["reset"] is True)

print(f"\n결과: {ok} 통과, {fail} 실패")
