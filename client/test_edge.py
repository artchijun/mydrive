# 경계 상황 시험 — 실제 NAS(test 폴더), PC 두 대 흉내(A, B)
import ctypes, hashlib, json, os, shutil, subprocess, sys, time, urllib.parse, urllib.request
from ctypes import wintypes

HERE = os.path.dirname(os.path.abspath(__file__))
CLI = os.path.join(HERE, "build", "mydrive-cli.exe")
SRV = "http://192.168.0.6:8642"
TOKEN = open(os.path.join(HERE, "..", "server", "deploy", "token.txt")).read().strip()
A, B = r"C:\MydriveEdgeA", r"C:\MydriveEdgeB"
ok = fail = 0
ONLY = sys.argv[1:]   # 일부만: python test_edge.py E3 E4

def check(name, cond, info=""):
    global ok, fail
    if cond: ok += 1; print("  OK  ", name)
    else: fail += 1; print("  FAIL", name, info)

def run(pc, *extra):
    loc = A if pc == "A" else B
    r = subprocess.run([CLI, "--server", SRV, "--token", TOKEN, "--root", "test", "--local", loc, "--state", loc + "-state",
                        "--pc", "PC" + pc, "--once", *extra], capture_output=True, text=True, encoding="utf-8", errors="replace")
    res = {"_out": r.stdout}
    for line in r.stdout.splitlines():
        if line.startswith("RESULT"):
            for kv in line.split()[1:]:
                k, v = kv.split("="); res[k] = int(v)
    return res

def quiet(r): return all(r.get(k) == 0 for k in ("up", "down", "delL", "delR", "moved", "conflicts", "errors"))
def short(r): return {k: v for k, v in r.items() if k != "_out"}

def tree(root):
    out = {}
    for d, dirs, files in os.walk("\\\\?\\" + root):
        for n in dirs: out[os.path.relpath(os.path.join(d, n), "\\\\?\\" + root).replace("\\", "/")] = "DIR"
        for n in files:
            p = os.path.join(d, n)
            out[os.path.relpath(p, "\\\\?\\" + root).replace("\\", "/")] = hashlib.sha1(open(p, "rb").read()).hexdigest()
    return out

def same(name):
    a, b = tree(A), tree(B)
    diff = sorted(k for k in set(a) | set(b) if a.get(k) != b.get(k))
    check(name + " — A 와 B 가 같음", not diff, diff[:8])

def lp(root, rel): return "\\\\?\\" + os.path.join(root, rel.replace("/", "\\"))
def w(root, rel, data, mode="w"):
    p = lp(root, rel); os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, mode, **({} if "b" in mode else {"encoding": "utf-8"})) as f: f.write(data)
def rd(root, rel):
    with open(lp(root, rel), encoding="utf-8") as f: return f.read()

def api(method, ep, body=None, **q):
    r = urllib.request.Request(SRV + ep + "?" + urllib.parse.urlencode(q), data=body, method=method, headers={"Authorization": "Bearer " + TOKEN})
    try: return json.loads(urllib.request.urlopen(r, timeout=60).read() or b"{}")
    except urllib.error.HTTPError as e: return {"_status": e.code}

def reset():
    for e in api("GET", "/v1/snapshot", root="test")["entries"]:
        if "/" not in e["p"]: api("DELETE", "/v1/file", root="test", path=e["p"])
    time.sleep(1)
    for d in (A, B, A + "-state", B + "-state"): shutil.rmtree("\\\\?\\" + d, ignore_errors=True)
    os.makedirs(A); os.makedirs(B)

def want(tag): return not ONLY or tag in ONLY

# 다른 프로그램이 파일을 잡고 있는 상황 (공유 없이 열기)
k32 = ctypes.WinDLL("kernel32", use_last_error=True)
k32.CreateFileW.restype = wintypes.HANDLE
k32.CreateFileW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD, ctypes.c_void_p, wintypes.DWORD, wintypes.DWORD, wintypes.HANDLE]
def lock(path):
    return k32.CreateFileW(path, 0xC0000000, 0, None, 3, 0x80, None)   # GENERIC_READ|WRITE, 공유 없음, OPEN_EXISTING
def unlock(h): k32.CloseHandle(h)

reset()
w(A, "기본/보통 파일.txt", "base")
run("A"); run("B")

if want("E1"):
    print("E1) 대소문자만 바꾼 이름")
    w(A, "기본/Readme.txt", "readme"); run("A"); run("B")
    os.rename(lp(A, "기본/Readme.txt"), lp(A, "기본/README.txt"))
    r = run("A"); check("A 이름 바꿈 1, 올림 0", r.get("moved") == 1 and r.get("up") == 0, short(r))
    r = run("B"); check("B 이름 바꿈 1", r.get("moved") == 1 and r.get("down") == 0, short(r))
    check("B 에서 이름이 README.txt", "README.txt" in os.listdir(lp(B, "기본")), os.listdir(lp(B, "기본")))
    same("E1")

if want("E2"):
    print("E2) NAS 에 대소문자만 다른 두 파일 (Windows 에서는 같은 이름)")
    api("PUT", "/v1/file", b"lower", root="test", path="case/x.txt", mtime=str(time.time_ns()), **{"if": "none"})
    api("PUT", "/v1/file", b"UPPER", root="test", path="case/X.txt", mtime=str(time.time_ns()), **{"if": "none"})
    r1 = run("A"); r2 = run("A")
    files = os.listdir(lp(A, "case"))
    check("PC 에는 하나만, 오류 반복 없음", len(files) == 1 and r2.get("down") == 0 and r2.get("errors") == 0, (files, short(r1), short(r2)))
    snap = {e["p"]: e for e in api("GET", "/v1/snapshot", root="test")["entries"]}
    check("NAS 두 파일 모두 그대로", "case/x.txt" in snap and "case/X.txt" in snap)
    run("B"); r = run("B"); check("B 도 조용함", r.get("errors") == 0 and r.get("down") == 0, short(r))

if want("E3"):
    print("E3) 260자 넘는 긴 경로")
    deep = "/".join(["긴폴더이름_" + "가" * 20] * 12) + "/끝파일.txt"
    w(A, deep, "deep")
    r = run("A"); check(f"A 올림 (경로 {len(A) + len(deep)}자)", r.get("up") == 1 and r.get("errors") == 0, short(r))
    r = run("B"); check("B 받음", r.get("down") == 1 and r.get("errors") == 0, short(r))
    check("B 내용 같음", rd(B, deep) == "deep")
    os.remove(lp(A, deep))
    r = run("A"); check("A 에서 지움 → NAS 삭제", r.get("delR") == 1, short(r))
    r = run("B"); check("B 긴 경로 파일도 지움", r.get("delL") == 1 and r.get("errors") == 0, short(r))
    check("B 에서 파일 없어짐", not os.path.exists(lp(B, deep)))
    shutil.rmtree(lp(A, "긴폴더이름_" + "가" * 20), ignore_errors=True)
    run("A"); run("B"); run("A")

if want("E4"):
    print("E4) 다른 프로그램이 잡고 있는 파일")
    w(A, "잠금/엑셀.xlsx", "v1"); run("A"); run("B")
    time.sleep(1.1); w(A, "잠금/엑셀.xlsx", "v2 A에서 고침")
    h = lock(lp(A, "잠금/엑셀.xlsx"))
    r = run("A"); check("잠긴 동안은 못 올림 (오류로 끝나고 멈추지 않음)", r.get("up") == 0, short(r))
    print("     ", [l for l in r["_out"].splitlines() if "잠금" in l][:2])
    unlock(h)
    r = run("A"); check("풀린 뒤 올림", r.get("up") == 1, short(r))
    h = lock(lp(B, "잠금/엑셀.xlsx"))
    r = run("B"); check("B 에서 잠긴 동안 받기 실패, 내용 그대로", r.get("down") == 0, short(r))
    unlock(h)
    check("B 원래 내용 유지", rd(B, "잠금/엑셀.xlsx") == "v1")
    r = run("B"); check("풀린 뒤 받음", r.get("down") == 1, short(r)); same("E4")

if want("E5"):
    print("E5) NAS 서버가 다시 켜지는 동안")
    w(A, "재시작/a.txt", "a")
    try: api("POST", "/v1/admin/restart")
    except Exception: pass
    r1 = run("A")
    time.sleep(6)
    r2 = run("A"); r3 = run("B")
    check("결국 올라가고 B 에 옴", r3.get("errors") == 0 and os.path.exists(lp(B, "재시작/a.txt")), (short(r1), short(r2), short(r3)))
    same("E5")

if want("E6"):
    print("E6) 폴더를 다른 폴더 안으로 옮김")
    w(A, "옮김/원래/1.txt", "1"); w(A, "옮김/원래/2.txt", "2"); w(A, "옮김/새집/x.txt", "x"); run("A"); run("B")
    os.rename(lp(A, "옮김/원래"), lp(A, "옮김/새집/원래"))
    r = run("A"); check("A 옮김 1, 올림 0", r.get("moved") == 1 and r.get("up") == 0, short(r))
    r = run("B"); check("B 옮김 1, 받음 0", r.get("moved") == 1 and r.get("down") == 0, short(r)); same("E6")

if want("E7"):
    print("E7) A 는 폴더를 지우고 B 는 그 안에 새 파일을 넣음")
    w(A, "지움/old1.txt", "o1"); w(A, "지움/old2.txt", "o2"); run("A"); run("B")
    shutil.rmtree(lp(A, "지움")); w(B, "지움/새파일.txt", "new")
    run("A"); r = run("B"); run("A"); run("B")
    check("새 파일은 양쪽에 살아 있음", os.path.exists(lp(A, "지움/새파일.txt")) and os.path.exists(lp(B, "지움/새파일.txt")))
    check("옛 파일은 양쪽에서 지워짐", not os.path.exists(lp(B, "지움/old1.txt")) and not os.path.exists(lp(A, "지움/old1.txt")))
    same("E7")

if want("E8"):
    print("E8) 빈 파일·특수문자 이름, Windows 에서 못 쓰는 NAS 이름")
    w(A, "이름/빈파일.txt", ""); w(A, "이름/# % & + 😀 [1] ;,=.txt", "special"); w(A, "이름/.숨김", "h")
    api("PUT", "/v1/file", b"bad", root="test", path="이름/콜론:이름.txt", mtime=str(time.time_ns()), **{"if": "none"})
    api("PUT", "/v1/file", b"bad", root="test", path="이름/끝점.", mtime=str(time.time_ns()), **{"if": "none"})
    r = run("A"); check("A 올림 3", r.get("up") == 3 and r.get("errors") == 0, short(r))
    r = run("B"); check("B 받음 3, 못 쓰는 이름은 건너뜀", r.get("down") == 3 and r.get("errors") == 0, short(r))
    r = run("B"); check("다시 돌려도 조용함", quiet(r), short(r)); same("E8")

if want("E9"):
    print("E9) A 는 고치고 B 는 지움 → 고친 판이 살아남")
    w(A, "살림/문서.txt", "v1"); run("A"); run("B")
    time.sleep(1.1); w(A, "살림/문서.txt", "v2 고침"); os.remove(lp(B, "살림/문서.txt"))
    run("B"); run("A"); run("B")
    check("양쪽 다 v2", rd(A, "살림/문서.txt") == "v2 고침" and rd(B, "살림/문서.txt") == "v2 고침"); same("E9")

if want("E10"):
    print("E10) 폴더를 지우고 같은 이름 파일을 만듦")
    w(A, "바뀜/안/f.txt", "f"); run("A"); run("B")
    shutil.rmtree(lp(A, "바뀜/안")); w(A, "바뀜/안", "이제 파일")
    r = run("A"); r2 = run("B"); run("A")
    check("B 에 '안'이 파일로", os.path.isfile(lp(B, "바뀜/안")) and rd(B, "바뀜/안") == "이제 파일", (short(r), short(r2)))
    same("E10")

if want("E11"):
    print("E11) 이름 바꾸고 바로 고침")
    w(A, "바꿈고침/원래.txt", "v1"); run("A"); run("B")
    os.rename(lp(A, "바꿈고침/원래.txt"), lp(A, "바꿈고침/새이름.txt")); time.sleep(1.1); w(A, "바꿈고침/새이름.txt", "v2")
    run("A"); run("B")
    check("B 에 새이름 v2 만", os.listdir(lp(B, "바꿈고침")) == ["새이름.txt"] and rd(B, "바꿈고침/새이름.txt") == "v2", os.listdir(lp(B, "바꿈고침")))
    same("E11")

if want("E12"):
    print("E12) 양쪽에서 같은 파일을 서로 다른 이름으로 바꿈")
    w(A, "두이름/x.txt", "x"); run("A"); run("B")
    os.rename(lp(A, "두이름/x.txt"), lp(A, "두이름/A가바꿈.txt")); os.rename(lp(B, "두이름/x.txt"), lp(B, "두이름/B가바꿈.txt"))
    run("A"); run("B"); run("A"); run("B")
    names = sorted(os.listdir(lp(A, "두이름")))
    check("내용은 잃지 않음 (한 이름 이상 남음)", len(names) >= 1 and all(rd(A, "두이름/" + n) == "x" for n in names), names)
    same("E12")

print("마지막) 다시 돌려도 할 일 없음")
for pc in "AB":
    r = run(pc); check(pc + " 할 일 없음", quiet(r), short(r))
print(f"\n결과: {ok} 통과, {fail} 실패")
