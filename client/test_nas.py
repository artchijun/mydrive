# 실제 NAS(192.168.0.6:8642)로 시험 — PC 두 대(A, B)가 같은 NAS 폴더를 쓰는 상황
import hashlib, os, shutil, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
CLI = os.path.join(HERE, "build", "mydrive-cli.exe")
TOKEN = open(os.path.join(HERE, "..", "server", "deploy", "token.txt")).read().strip()
A, B = r"C:\MydriveNasA", r"C:\MydriveNasB"
ok = fail = 0

def check(name, cond, info=""):
    global ok, fail
    if cond: ok += 1; print("  OK  ", name)
    else: fail += 1; print("  FAIL", name, info)

def run(pc, *extra):
    loc = A if pc == "A" else B
    t0 = time.time()
    r = subprocess.run([CLI, "--server", "http://192.168.0.6:8642", "--token", TOKEN, "--root", "test", "--local", loc,
                        "--state", loc + "-state", "--pc", "PC" + pc, "--once", *extra],
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    res = {"_t": time.time() - t0}
    for line in r.stdout.splitlines():
        if line.startswith("RESULT"):
            for kv in line.split()[1:]:
                k, v = kv.split("="); res[k] = int(v)
    return res

def tree(root):
    out = {}
    for d, dirs, files in os.walk(root):
        for n in dirs: out[os.path.relpath(os.path.join(d, n), root).replace("\\", "/")] = "DIR"
        for n in files:
            p = os.path.join(d, n)
            out[os.path.relpath(p, root).replace("\\", "/")] = hashlib.sha1(open(p, "rb").read()).hexdigest()
    return out

def same(name):
    a, b = tree(A), tree(B)
    diff = sorted(k for k in set(a) | set(b) if a.get(k) != b.get(k))
    check(name + " — A 와 B 가 같음", not diff, diff[:8])

def w(root, rel, data, mode="w"):
    p = os.path.join(root, rel.replace("/", "\\")); os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, mode, **({} if "b" in mode else {"encoding": "utf-8"})) as f: f.write(data)

# NAS 시험 폴더 비우기 (맨 위 항목을 NAS 휴지통으로)
import json, urllib.request, urllib.parse
def api(method, ep, **q):
    r = urllib.request.Request("http://192.168.0.6:8642" + ep + "?" + urllib.parse.urlencode(q), method=method, headers={"Authorization": "Bearer " + TOKEN})
    return json.loads(urllib.request.urlopen(r, timeout=60).read())
for e in api("GET", "/v1/snapshot", root="test")["entries"]:
    if "/" not in e["p"]: api("DELETE", "/v1/file", root="test", path=e["p"])
time.sleep(1)
for d in (A, B, A + "-state", B + "-state"): shutil.rmtree(d, ignore_errors=True)
os.makedirs(A); os.makedirs(B)

print("1) A 에서 만든 것이 NAS 를 거쳐 B 로")
w(A, "문서/보고서 초안.txt", "초안 v1"); w(A, "사진/2026/img 001.txt", "img")
for i in range(200): w(A, f"small/f{i:03}.txt", f"file {i}")
big = os.urandom(200 * 1024 * 1024); w(A, "big/200MB.bin", big, "wb")
r = run("A"); check(f"A 올림 {r.get('up')}개 ({r['_t']:.1f}초)", r.get("up") == 203 and r.get("errors") == 0, r)
r = run("B"); check(f"B 받음 {r.get('down')}개 ({r['_t']:.1f}초)", r.get("down") == 203 and r.get("errors") == 0, r)
same("1")

print("2) B 에서 고친 것이 A 로")
time.sleep(1.1); w(B, "문서/보고서 초안.txt", "초안 v2 (B에서 고침)")
r = run("B"); check("B 올림 1", r.get("up") == 1, r)
r = run("A"); check("A 받음 1", r.get("down") == 1, r); same("2")

print("3) A 에서 폴더 이름 바꿈 → B 도 이름만 바뀜")
os.rename(os.path.join(A, "사진"), os.path.join(A, "사진 정리"))
r = run("A"); check("A 이름 바꿈 1, 올림 0", r.get("moved") == 1 and r.get("up") == 0, r)
r = run("B"); check("B 이름 바꿈 1, 받음 0", r.get("moved") == 1 and r.get("down") == 0, r); same("3")

print("4) A 와 B 가 같은 파일을 고침 → 둘 다 남음")
time.sleep(1.1); w(A, "문서/보고서 초안.txt", "A 판"); w(B, "문서/보고서 초안.txt", "B 판")
run("A"); r = run("B"); check("B 충돌 1", r.get("conflicts") == 1, r)
run("A"); same("4")
docs = sorted(os.listdir(os.path.join(A, "문서")))
check("원본 + 충돌 사본 2개 파일", len(docs) == 2, docs)

print("5) A 에서 3개 지움 → B 에서도 휴지통으로")
for i in range(3): os.remove(os.path.join(A, "small", f"f{i:03}.txt"))
r = run("A"); check("NAS 삭제 3", r.get("delR") == 3, r)
r = run("B"); check("B 삭제 3", r.get("delL") == 3, r); same("5")

print("6) A 에서 100개 지움 → 멈추고 확인 기다림")
for i in range(3, 103): os.remove(os.path.join(A, "small", f"f{i:03}.txt"))
r = run("A"); check("보류", r.get("held", 0) == 100 and r.get("delR") == 0, r)
r = run("B"); check("B 는 아무것도 안 지움", r.get("delL") == 0, r)
r = run("A", "--confirm-delete"); check("확인 뒤 NAS 삭제 100", r.get("delR") == 100, r)
r = run("B", "--confirm-delete"); check("B 도 100 삭제", r.get("delL") == 100, r); same("6")

print("7) 다시 돌려도 할 일 없음")
for pc in "AB":
    r = run(pc); check(pc + " 할 일 없음", all(r.get(k) == 0 for k in ("up", "down", "delL", "delR", "moved", "conflicts", "errors")), r)
print(f"\n결과: {ok} 통과, {fail} 실패")
