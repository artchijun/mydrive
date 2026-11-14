# 시험 도우미 — 실제 NAS(test 폴더), PC 두 대 흉내(A, B)
import ctypes, hashlib, json, os, shutil, subprocess, time, urllib.parse, urllib.request
from ctypes import wintypes

HERE = os.path.dirname(os.path.abspath(__file__))
CLI = os.path.join(HERE, "build", "mydrive-cli.exe")
SRV = "http://192.168.0.6:8642"
TOKEN = open(os.path.join(HERE, "..", "server", "deploy", "token.txt")).read().strip()
A, B = r"C:\MydriveFeatA", r"C:\MydriveFeatB"
cnt = {"ok": 0, "fail": 0}

def check(name, cond, info=""):
    if cond: cnt["ok"] += 1; print("  OK  ", name)
    else: cnt["fail"] += 1; print("  FAIL", name, info)

def run(pc, *extra):
    loc = A if pc == "A" else B
    t0 = time.time()
    r = subprocess.run([CLI, "--server", SRV, "--token", TOKEN, "--root", "test", "--local", loc, "--state", loc + "-state",
                        "--pc", "PC" + pc, "--once", *extra], capture_output=True, text=True, encoding="utf-8", errors="replace")
    res = {"_out": r.stdout, "_t": time.time() - t0}
    for line in r.stdout.splitlines():
        if line.startswith("RESULT"):
            for kv in line.split()[1:]:
                k, v = kv.split("="); res[k] = int(v)
    return res

def quiet(r): return all(r.get(k) == 0 for k in ("up", "down", "delL", "delR", "moved", "conflicts", "errors"))
def short(r): return {k: (round(v, 1) if isinstance(v, float) else v) for k, v in r.items() if k != "_out"}
def lp(root, rel): return "\\\\?\\" + os.path.join(root, rel.replace("/", "\\"))
def ex(root, rel): return os.path.exists(lp(root, rel))
def w(root, rel, data, mode="w"):
    p = lp(root, rel); os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, mode, **({} if "b" in mode else {"encoding": "utf-8"})) as f: f.write(data)
def rd(root, rel):
    with open(lp(root, rel), encoding="utf-8") as f: return f.read()

def api(method, ep, body=None, **q):
    r = urllib.request.Request(SRV + ep + "?" + urllib.parse.urlencode(q), data=body, method=method, headers={"Authorization": "Bearer " + TOKEN})
    try: return json.loads(urllib.request.urlopen(r, timeout=60).read() or b"{}")
    except urllib.error.HTTPError as e: return {"_status": e.code}
def nas_put(path, data): return api("PUT", "/v1/file", data, root="test", path=path, mtime=str(time.time_ns()), **{"if": "none"})
def nas_has(path): return any(e["p"] == path for e in api("GET", "/v1/snapshot", root="test")["entries"])

def reset():
    for e in api("GET", "/v1/snapshot", root="test")["entries"]:
        if "/" not in e["p"]: api("DELETE", "/v1/file", root="test", path=e["p"])
    time.sleep(1)
    for d in (A, B, A + "-state", B + "-state"): shutil.rmtree("\\\\?\\" + d, ignore_errors=True)
    os.makedirs(A); os.makedirs(B)

k32 = ctypes.WinDLL("kernel32", use_last_error=True)
k32.CreateFileW.restype = wintypes.HANDLE
k32.CreateFileW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD, ctypes.c_void_p, wintypes.DWORD, wintypes.DWORD, wintypes.HANDLE]
def lock(path): return k32.CreateFileW(path, 0xC0000000, 0, None, 3, 0x80, None)
def unlock(h): k32.CloseHandle(h)
