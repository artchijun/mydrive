# 탐색기 연동(필요할 때 받기)·선택 동기화 점검
import os, sys, time, json, subprocess, urllib.request, urllib.parse, urllib.error
sys.stdout.reconfigure(encoding="utf-8")
CFG = json.load(open(os.path.join(os.environ["LOCALAPPDATA"], "Mydrive", "config.json"), encoding="utf-8-sig"))
SERVER, TOK, ROOT, BASE = CFG["server"], CFG["token"], "test", r"C:\MydriveApp"
RECALL, PINNED, UNPINNED, OFFLINE = 0x400000, 0x80000, 0x100000, 0x1000
res = []

def req(method, path, q=None, data=None):
    url = SERVER + path + ("?" + urllib.parse.urlencode(q) if q else "")
    r = urllib.request.Request(url, data=data, method=method, headers={"Authorization": "Bearer " + TOK})
    try:
        with urllib.request.urlopen(r, timeout=60) as resp: return resp.status, resp.read()
    except urllib.error.HTTPError as e: return e.code, e.read()
def put(rel, data): return req("PUT", "/v1/file", {"root": ROOT, "path": rel, "mtime": str(time.time_ns())}, data)[0]
def lp(rel): return os.path.join(BASE, rel.replace("/", "\\"))
def attrs(rel):
    try: return os.stat(lp(rel)).st_file_attributes
    except OSError: return None
def wait(fn, limit=60):
    t = time.time()
    while time.time() - t < limit:
        try:
            if fn(): return round(time.time() - t, 1)
        except Exception: pass
        time.sleep(0.3)
    return None
def check(name, ok, note=""):
    res.append((name, ok)); print(("PASS" if ok else "FAIL"), name, note, flush=True)

data = os.urandom(200_000)
# 1. NAS 새 파일 → PC에는 '온라인 전용'(자리만) 으로
put("_e2e/od/online.bin", data)
t = wait(lambda: attrs("_e2e/od/online.bin") is not None)
a = attrs("_e2e/od/online.bin") or 0
check("NAS 새 파일은 온라인 전용으로 생김", t is not None and bool(a & (RECALL | OFFLINE)), f"{t}초 attr=0x{a:x}")
# 2. 열면 받아 옴
with open(lp("_e2e/od/online.bin"), "rb") as f: got = f.read()
time.sleep(1)
a2 = attrs("_e2e/od/online.bin") or 0
check("열면 내용을 받아 옴", got == data, f"attr=0x{a2:x}")
# 3. 공간 확보 (attrib +U -P) → 다시 온라인 전용
subprocess.run(["attrib", "-P", "+U", lp("_e2e/od/online.bin")], capture_output=True)
t = wait(lambda: (attrs("_e2e/od/online.bin") or 0) & (RECALL | OFFLINE), 30)
check("공간 확보 → 온라인 전용으로 돌아감", t is not None, f"{t}초 attr=0x{attrs('_e2e/od/online.bin') or 0:x}")
# 4. 항상 유지 (attrib +P) → 내용까지 받아 둠
subprocess.run(["attrib", "+P", "-U", lp("_e2e/od/online.bin")], capture_output=True)
t = wait(lambda: not ((attrs("_e2e/od/online.bin") or 0) & (RECALL | OFFLINE)), 30)
check("항상 유지 → 내용까지 받아 둠", t is not None, f"{t}초 attr=0x{attrs('_e2e/od/online.bin') or 0:x}")
subprocess.run(["attrib", "-P", lp("_e2e/od/online.bin")], capture_output=True)
# 5. '항상 이 PC에 둘 폴더'(항상 시험) 안 새 파일 → 바로 내용까지
keep = (CFG["connections"][0].get("keepDirs") or [""])[0]
if keep:
    put(f"{keep}/_e2e_keep.bin", data)
    t = wait(lambda: attrs(f"{keep}/_e2e_keep.bin") is not None and not (attrs(f"{keep}/_e2e_keep.bin") & (RECALL | OFFLINE)), 60)
    check(f"항상 둘 폴더({keep})는 내용까지 받음", t is not None, f"{t}초 attr=0x{attrs(f'{keep}/_e2e_keep.bin') or 0:x}")
    req("DELETE", "/v1/file", {"root": ROOT, "path": f"{keep}/_e2e_keep.bin"})
# 6. 선택 동기화에서 뺀 폴더(big) 의 새 파일은 PC로 안 옴
skip = (CFG["connections"][0].get("skipDirs") or [""])[0]
if skip:
    put(f"{skip}/_e2e_skip.bin", b"skip")
    time.sleep(10)
    check(f"뺀 폴더({skip}) 새 파일은 안 받음", attrs(f"{skip}/_e2e_skip.bin") is None)
    req("DELETE", "/v1/file", {"root": ROOT, "path": f"{skip}/_e2e_skip.bin"})
print(f"\n결과: {sum(1 for r in res if r[1])}/{len(res)} 통과")
