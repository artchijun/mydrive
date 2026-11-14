# Mydrive 전체 기능 점검 (C:\MydriveApp\_e2e ↔ NAS test/_e2e)
#   python e2e_test.py            → 항목마다 PASS/FAIL 과 걸린 시간
import os, sys, time, json, hashlib, shutil, subprocess, urllib.request, urllib.parse, urllib.error

sys.stdout.reconfigure(encoding="utf-8")
CFG = json.load(open(os.path.join(os.environ["LOCALAPPDATA"], "Mydrive", "config.json"), encoding="utf-8-sig"))
SERVER, TOK = CFG["server"], CFG["token"]
ROOT, PRE = "test", "_e2e/"
LOCAL = r"C:\MydriveApp\_e2e"
EXE = os.path.join(os.environ["LOCALAPPDATA"], "Programs", "Mydrive", "Mydrive.exe")
results = []

def req(method, path, q=None, data=None, timeout=120):
    url = SERVER + path + ("?" + urllib.parse.urlencode(q) if q else "")
    r = urllib.request.Request(url, data=data, method=method, headers={"Authorization": "Bearer " + TOK})
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()

def snap():
    st, b = req("GET", "/v1/snapshot", {"root": ROOT})
    j = json.loads(b)
    return {e["p"][len(PRE):]: e for e in j["entries"] if e["p"].startswith(PRE)}

def nas_get(rel):
    st, b = req("GET", "/v1/file", {"root": ROOT, "path": PRE + rel})
    return b if st == 200 else None

def nas_put(rel, data):
    return req("PUT", "/v1/file", {"root": ROOT, "path": PRE + rel, "mtime": str(time.time_ns())}, data)[0]

def lp(rel): return os.path.join(LOCAL, rel.replace("/", "\\"))

def lread(rel):
    try:
        with open(lp(rel), "rb") as f: return f.read()   # 온라인 전용이면 이때 받아 옴
    except OSError:
        return None

def lwrite(rel, data):
    os.makedirs(os.path.dirname(lp(rel)), exist_ok=True)
    with open(lp(rel), "wb") as f: f.write(data)

def wait(fn, limit=60):
    t = time.time()
    while time.time() - t < limit:
        try:
            if fn(): return round(time.time() - t, 1)
        except Exception:
            pass
        time.sleep(0.3)
    return None

def check(name, sec, note=""):
    ok = sec is not None
    results.append((name, ok, sec, note))
    print(f"{'PASS' if ok else 'FAIL'}  {name}  {'' if sec is None else str(sec) + '초'}  {note}", flush=True)
    return ok

def cmd(arg, path):
    p = subprocess.run([EXE, arg, path], timeout=30)
    return p.returncode

def rnd(n): return os.urandom(n)

# ── 준비: 이전 시험 흔적 정리 ──
if os.path.isdir(LOCAL):
    shutil.rmtree(LOCAL, ignore_errors=True)
    wait(lambda: not snap(), 60)
for rel in list(snap().keys()):
    req("DELETE", "/v1/file", {"root": ROOT, "path": PRE + rel})
os.makedirs(LOCAL, exist_ok=True)
time.sleep(3)

# 1. PC → NAS: 새 파일
a1 = rnd(50_000)
lwrite("a.bin", a1)
check("PC→NAS 새 파일", wait(lambda: nas_get("a.bin") == a1))

# 2. PC → NAS: 고친 파일 + 이전 버전 남음
a2 = rnd(60_000)
lwrite("a.bin", a2)
s = check("PC→NAS 고친 파일", wait(lambda: nas_get("a.bin") == a2))
vers = json.loads(req("GET", "/v1/versions", {"root": ROOT, "path": PRE + "a.bin"})[1])
check("NAS에 이전 버전 저장", 0 if len(vers) >= 1 else None, f"버전 {len(vers)}개")

# 3. 이전 버전으로 되돌리기 → PC에도 반영
if vers:
    old = vers[-1]["id"]
    st, _ = req("POST", "/v1/restore", {"root": ROOT, "path": PRE + "a.bin", "id": old})
    check("이전 버전 되돌리기 → PC 반영", wait(lambda: lread("a.bin") == a1), f"restore {st}")

# 4. 한글·공백 이름, 새 하위 폴더
k = "가나다".encode() * 1000
lwrite("새 폴더/한글 파일 (1).txt", k)
check("PC→NAS 한글 이름·새 폴더", wait(lambda: nas_get("새 폴더/한글 파일 (1).txt") == k))

# 5. PC에서 이름 바꾸기
os.rename(lp("새 폴더/한글 파일 (1).txt"), lp("새 폴더/바뀐 이름.txt"))
check("PC→NAS 이름 바꾸기", wait(lambda: "새 폴더/바뀐 이름.txt" in (sn := snap()) and "새 폴더/한글 파일 (1).txt" not in sn))

# 6. PC에서 폴더 이름 바꾸기 (안의 파일까지)
os.rename(lp("새 폴더"), lp("옮긴 폴더"))
check("PC→NAS 폴더 이름 바꾸기", wait(lambda: "옮긴 폴더/바뀐 이름.txt" in (sn := snap()) and not any(p.startswith("새 폴더") for p in sn)))

# 7. 큰 파일 (40MB) 무결성
big = rnd(40 * 1024 * 1024)
lwrite("big40.bin", big)
h = hashlib.sha256(big).hexdigest()
check("PC→NAS 큰 파일 40MB 내용 일치", wait(lambda: (snap().get("big40.bin") or {}).get("s") == len(big) and hashlib.sha256(nas_get("big40.bin") or b"").hexdigest() == h, 180))

# 8. 빼는 이름 (*.bak, ~*.tmp) 은 올라가지 않음
lwrite("x.bak", b"bak"); lwrite("~y.tmp", b"tmp"); lwrite("ok.txt", b"ok")
wait(lambda: nas_get("ok.txt") == b"ok")
time.sleep(5)
sn = snap()
check("빼는 이름은 안 올림 (*.bak, ~*.tmp)", 0 if "x.bak" not in sn and "~y.tmp" not in sn and "ok.txt" in sn else None)

# 9. PC에서 지우기 → NAS #recycle, '지운 파일' 목록에 나옴
os.remove(lp("ok.txt"))
check("PC→NAS 지우기", wait(lambda: "ok.txt" not in snap()))
dels = json.loads(req("GET", "/v1/deleted", {"root": ROOT})[1])
check("지운 파일 목록에 나옴", 0 if any(d["p"] == PRE + "ok.txt" for d in dels) else None)

# 10. NAS → PC: 새 파일 / 고친 파일 / 이름 바꾸기 / 지우기
n1 = rnd(30_000)
nas_put("nas.bin", n1)
check("NAS→PC 새 파일", wait(lambda: lread("nas.bin") == n1))
n2 = rnd(31_000)
nas_put("nas.bin", n2)
check("NAS→PC 고친 파일", wait(lambda: lread("nas.bin") == n2))
req("POST", "/v1/move", {"root": ROOT, "from": PRE + "nas.bin", "to": PRE + "nas2.bin"})
check("NAS→PC 이름 바꾸기", wait(lambda: os.path.exists(lp("nas2.bin")) and not os.path.exists(lp("nas.bin"))))
req("POST", "/v1/mkdir", {"root": ROOT, "path": PRE + "nasdir/sub"})
nas_put("nasdir/sub/f.txt", b"deep")
check("NAS→PC 새 폴더 안 파일", wait(lambda: lread("nasdir/sub/f.txt") == b"deep"))
req("DELETE", "/v1/file", {"root": ROOT, "path": PRE + "nas2.bin"})
check("NAS→PC 지우기", wait(lambda: not os.path.exists(lp("nas2.bin"))))

# 11. 일시 중지 중 양쪽에서 고침 → 다시 시작하면 둘 다 남김 (충돌)
lwrite("c.txt", b"base")
wait(lambda: nas_get("c.txt") == b"base")
time.sleep(2)
rc = cmd("--pause30", LOCAL)
time.sleep(2)
lwrite("c.txt", b"from-pc")
nas_put("c.txt", b"from-nas")
time.sleep(5)
paused_ok = nas_get("c.txt") == b"from-nas"
check("일시 중지 중에는 안 올림", 0 if paused_ok else None, f"pause exit {rc}")
cmd("--resume", LOCAL)
def conflict_kept():
    names = os.listdir(LOCAL)
    cf = [n for n in names if n.startswith("c") and "충돌" in n]
    if not cf: return False
    vals = {lread("c.txt"), lread(cf[0])}
    sn = snap()
    return vals == {b"from-pc", b"from-nas"} and any("충돌" in p for p in sn)
check("충돌 시 둘 다 남김 (양쪽)", wait(conflict_kept, 90), str([n for n in os.listdir(LOCAL) if "충돌" in n]))

# 12. 대량 삭제 안전장치 (기본 50개 넘으면 멈춤)
for i in range(55): lwrite(f"mass/m{i:02d}.txt", b"m")
wait(lambda: sum(1 for p in snap() if p.startswith("mass/m")) == 55, 120)
shutil.rmtree(lp("mass"))
time.sleep(20)
left = sum(1 for p in snap() if p.startswith("mass/m"))
check("대량 삭제는 멈추고 묻기 (NAS 쪽 그대로)", 0 if left == 55 else None, f"NAS에 남은 것 {left}/55")
# 정리: NAS 쪽도 지워서 양쪽을 같게
for i in range(55): req("DELETE", "/v1/file", {"root": ROOT, "path": PRE + f"mass/m{i:02d}.txt"})

print()
fails = [r for r in results if not r[1]]
print(f"결과: {len(results) - len(fails)}/{len(results)} 통과")
for r in fails: print("  실패:", r[0], r[3])
