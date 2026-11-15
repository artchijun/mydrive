# 재발 방지 시험 — 2026-11-14~15 에 생긴 문제들과, 그 수정이 새로 만들 수 있는 문제를 일부러 다시 만들어 봄
#   NAS 쪽 = WSL 의 ~/mdr/data (이 폴더의 build\..\build\mydrived 를 localhost:8642 로 띄움)
#   PC 쪽  = C:\MydriveRT  (mydrive-cli 를 '계속 맞추기'로 돌림 → 폴더 감시·바뀐 폴더만 읽기까지 실제와 같게)
#   실행: python client\test_regress.py   (서버를 먼저 빌드: build\mydrived, 앱: client\build\mydrive-cli.exe)
import hashlib, json, os, random, re, shutil, subprocess, sys, threading, time, urllib.parse, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
CLI = os.path.join(HERE, "build", "mydrive-cli.exe")
SRVBIN = os.path.abspath(os.path.join(HERE, "..", "build", "mydrived"))
LOCAL, STATE, ROOT = r"C:\MydriveRT", r"C:\MydriveRT-state", "rt"
CLILOG = os.path.join(os.environ.get("TEMP", "."), "mydrive_rt_cli.log")
BASE = "http://localhost:8642"
ok = fail = 0
srv = None


def check(name, cond, info=""):
    global ok, fail
    if cond: ok += 1; print("  OK  ", name, flush=True)
    else: fail += 1; print("  FAIL", name, str(info)[:400], flush=True)


def wsl(cmd):
    r = subprocess.run(["wsl.exe", "-e", "bash", "-lc", cmd], capture_output=True, text=True, encoding="utf-8", errors="replace")
    return r.stdout


def req(method, ep, data=None, timeout=60, token="test", body=None, **q):
    url = BASE + ep + ("?" + urllib.parse.urlencode(q) if q else "")
    h = {"Authorization": "Bearer " + token, "X-Mydrive-Console": "1"}
    if body is not None: data = json.dumps(body).encode(); h["Content-Type"] = "application/json"
    rq = urllib.request.Request(url, data=data, method=method, headers=h)
    with urllib.request.urlopen(rq, timeout=timeout) as r:
        b = r.read()
    try: return json.loads(b)
    except Exception: return b


def ping():
    try: return urllib.request.urlopen(BASE + "/v1/ping", timeout=2).status == 200
    except Exception: return False


def srv_start(rescan="15m"):
    global srv
    wsl(f"cp \"$(wslpath -u '{SRVBIN}')\" ~/mdr/mydrived && chmod +x ~/mdr/mydrived")
    srv = subprocess.Popen(["wsl.exe", "-e", "bash", "-lc",
                            "cd ~/mdr && MYDRIVE_TOKEN=test MYDRIVE_ADMIN_TOKEN=admintest "
                            "MYDRIVE_ROOTS=rt=$HOME/mdr/data,other=$HOME/mdr/other MYDRIVE_STATE=$HOME/mdr/state "
                            f"MYDRIVE_NAS_BASE=$HOME/mdr MYDRIVE_RESCAN={rescan} exec ./mydrived >> ~/mdr/log 2>&1"],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for _ in range(100):
        if ping(): return True
        time.sleep(0.2)
    return False


def srv_stop(sig="TERM"):
    wsl(f"pkill -{sig} -x mydrived; for i in $(seq 1 100); do pgrep -x mydrived >/dev/null || break; sleep 0.1; done")


def snap(root=ROOT): return req("GET", "/v1/snapshot", root=root)


def put(rel, data, root=ROOT):
    return req("PUT", "/v1/file", data=data, root=root, path=rel, mtime=str(time.time_ns()))


# ---------- 양쪽 비교 ----------
OFFLINE = 0x1000 | 0x400000   # FILE_ATTRIBUTE_OFFLINE | RECALL_ON_DATA_ACCESS — 읽으면 받아지므로 크기만 봄
SKIP = re.compile(r"(^|/)(#recycle|#mydrive-versions|\.mydrive-tmp-)")


def local_tree():
    out = {}
    for d, dirs, files in os.walk(LOCAL):
        for n in dirs:
            rel = os.path.relpath(os.path.join(d, n), LOCAL).replace("\\", "/")
            if not SKIP.search(rel): out[rel] = ("D",)
        for n in files:
            p = os.path.join(d, n); rel = os.path.relpath(p, LOCAL).replace("\\", "/")
            if SKIP.search(rel): continue
            st = os.stat(p)
            if st.st_file_attributes & OFFLINE: out[rel] = ("S", st.st_size)
            else:
                try: out[rel] = ("F", st.st_size, hashlib.sha1(open(p, "rb").read()).hexdigest())
                except OSError: out[rel] = ("?",)
    return out


def remote_tree():
    s = wsl("cd ~/mdr/data && find . -mindepth 1 -type d -printf 'D\\t%P\\n'; find . -type f -printf 'S\\t%s\\t%P\\n'; "
            "find . -type f -print0 | xargs -0 -r sha1sum")
    sizes, out = {}, {}
    for line in s.splitlines():
        if line.startswith("D\t"):
            rel = line[2:]
            if not SKIP.search(rel): out[rel] = ("D",)
        elif line.startswith("S\t"):
            _, sz, rel = line.split("\t", 2); sizes[rel] = int(sz)
        elif line.strip():
            h, p = line.split(None, 1); rel = p[2:] if p.startswith("./") else p
            if not SKIP.search(rel): out[rel] = ("F", sizes.get(rel, -1), h)
    return out


def diff_trees():
    l, r = local_tree(), remote_tree()
    d = []
    for k in set(l) | set(r):
        a, b = l.get(k), r.get(k)
        if a == b: continue
        if a and b and a[0] == "S" and b[0] == "F" and a[1] == b[1]: continue   # 온라인 전용: 크기만 같으면 됨
        d.append((k, a, b))
    return sorted(d)


def wait_same(sec, name):
    t0 = time.time(); d = None
    while time.time() - t0 < sec:
        d = diff_trees()
        if not d: break
        time.sleep(2)
    check(f"{name} — 양쪽 같음 ({time.time() - t0:.0f}초)", not d, d[:6])
    return not d


def lw(rel, data):
    p = os.path.join(LOCAL, rel.replace("/", "\\")); os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "wb") as f: f.write(data if isinstance(data, bytes) else data.encode())


def conflicts_local(): return [k for k in local_tree() if "충돌" in k]


# ---------- PC 앱 (mydrive-cli 계속 맞추기) ----------
class Cli:
    p = None
    def start(self, *extra):
        self.f = open(CLILOG, "ab")
        self.p = subprocess.Popen([CLI, "--server", BASE, "--token", "test", "--root", ROOT, "--local", LOCAL, "--state", STATE,
                                   "--pc", "RT", *extra], stdout=self.f, stderr=subprocess.STDOUT)
        time.sleep(1.5)
    def kill(self):   # 갑자기 꺼짐 (저장할 틈 없음)
        if self.p and self.p.poll() is None:
            subprocess.run(["taskkill", "/F", "/PID", str(self.p.pid)], capture_output=True); self.p.wait()
        if self.p: self.f.close()


def clilog(): return open(CLILOG, encoding="utf-8", errors="replace").read() if os.path.exists(CLILOG) else ""


def events(kind, path=None):
    n = 0
    for line in clilog().splitlines():
        if f"[{ROOT}] {kind}" in line and (path is None or f"  {path}  " in line + "  "): n += 1
    return n


def unregister_root():
    sid = subprocess.run(["powershell", "-NoProfile", "-Command", "[Security.Principal.WindowsIdentity]::GetCurrent().User.Value"],
                         capture_output=True, text=True).stdout.strip()
    subprocess.run(["powershell", "-NoProfile", "-Command",
                    "$null=[Windows.Storage.Provider.StorageProviderSyncRootManager,Windows.Storage.Provider,ContentType=WindowsRuntime];"
                    f"try {{ [Windows.Storage.Provider.StorageProviderSyncRootManager]::Unregister('Mydrive!{sid}!{ROOT}') }} catch {{}}"],
                   capture_output=True)


# =====================================================================
print("준비: 시험용 서버·폴더 새로", flush=True)
cli = Cli()
subprocess.run(["taskkill", "/F", "/IM", "mydrive-cli.exe"], capture_output=True)
srv_stop("KILL")
wsl("rm -rf ~/mdr; mkdir -p ~/mdr/data/a/b ~/mdr/state ~/mdr/other")
wsl("cd ~/mdr/data && for i in 1 2 3; do echo x$i > a/b/f$i.txt; done; echo y > a/top.txt; "
    "for d in $(seq -w 0 199); do mkdir -p big/d$d/sub; for f in $(seq -w 0 99); do echo x > big/d$d/sub/f$f.txt; done; done")
unregister_root()
shutil.rmtree(LOCAL, ignore_errors=True); shutil.rmtree(STATE, ignore_errors=True)
if os.path.exists(CLILOG): os.remove(CLILOG)
check("서버 켜짐", srv_start())

# ---------- 서버 ----------
print("S1) NAS 에서 폴더 통째로 지우기 (20,000개) — 예전: 파일 하나마다 색인 전체를 뒤져 CPU 를 다 씀", flush=True)
n0 = len(snap()["entries"])
pid = wsl("pgrep -x mydrived").strip().split()[0]
c0 = int(wsl(f"awk '{{print $14+$15}}' /proc/{pid}/stat").strip() or 0)
t0 = time.time(); wsl("rm -rf ~/mdr/data/big")
while time.time() - t0 < 30 and len(snap()["entries"]) > 10: time.sleep(0.3)
dt = time.time() - t0; c1 = int(wsl(f"awk '{{print $14+$15}}' /proc/{pid}/stat").strip() or 0)
check(f"20,200개 지움 반영 {dt:.1f}초, 서버 CPU {(c1 - c0) / 100:.2f}초", dt < 5 and (c1 - c0) < 300, (n0, dt, c1 - c0))

print("S2) 폴더 옮기기·지우기 뒤 목록이 정확한지 (새 색인 구조)", flush=True)
wsl("cd ~/mdr/data && mkdir -p n/m && echo z > n/m/z.txt && mv a/b n/bb && sleep 0.5 && mkdir n2 && mv n/m n2/m && rm a/top.txt")
time.sleep(1.2)
got = sorted(e["p"] for e in snap()["entries"])
check("목록 = 디스크", got == ["a", "n", "n/bb", "n/bb/f1.txt", "n/bb/f2.txt", "n/bb/f3.txt", "n2", "n2/m", "n2/m/z.txt"], got)
wsl("rm -rf ~/mdr/data/n ~/mdr/data/n2 ~/mdr/data/a"); time.sleep(1)
check("다 지우면 빈 목록", snap()["entries"] == [], snap()["entries"][:5])

print("S3) 전체 비교(재검사)가 도는 중에 올린 파일을 '지워짐'으로 잘못 알리지 않음", flush=True)
srv_stop(); srv_start(rescan="1s")
seq0 = snap()["seq"]
for i in range(300): put(f"up/f{i:03d}.txt", os.urandom(512))
for i in range(0, 300, 5): put(f"up/f{i:03d}.txt", os.urandom(700))   # 덮어쓰기
time.sleep(3)
ch = req("GET", "/v1/changes", root=ROOT, epoch=snap()["epoch"], since=str(seq0), wait="0")
dels = [c for c in ch.get("changes", []) if c["op"] == "del"]
check("잘못된 '지움' 알림 없음", not dels, dels[:3])
check("300개 모두 목록에", sum(1 for e in snap()["entries"] if e["p"].startswith("up/f")) == 300)
srv_stop(); srv_start()

print("S4) 내용 확인(해시) 요청이 한꺼번에 몰려도 모두 처리 (동시 2개로 묶음)", flush=True)
blob = os.urandom(64 << 20); put("hash/big.bin", blob); want = hashlib.sha256(blob).hexdigest()
res = []; t0 = time.time()
th = [threading.Thread(target=lambda: res.append(req("GET", "/v1/hash", root=ROOT, path="hash/big.bin", timeout=120).get("sha256"))) for _ in range(10)]
[t.start() for t in th]; [t.join() for t in th]
check(f"10개 모두 맞음 ({time.time() - t0:.1f}초)", len(res) == 10 and all(r == want for r in res), res[:2])
wsl("rm -rf ~/mdr/data/hash ~/mdr/data/up"); time.sleep(1)

print("S5) 관리 화면 [빼기] — 확인 창이 페이지 안에 뜨고, 뺀 폴더는 다시 켜도 빠져 있음", flush=True)
html = req("GET", "/admin", token="admintest")
html = html.decode() if isinstance(html, bytes) else str(html)
check("페이지 안 확인 창 있음", 'id="mAsk"' in html and html.count("await ask(") >= 4, html.count("await ask("))
check("브라우저 confirm() 안 씀", re.search(r"!\s*confirm\(", html) is None)
req("POST", "/v1/admin/roots", token="admintest", body={"remove": "other"})
names = [r["name"] for r in req("GET", "/v1/admin/info", token="admintest")["roots"]]
check("빼기 됨", "other" not in names, names)
srv_stop(); srv_start()
names = [r["name"] for r in req("GET", "/v1/admin/info", token="admintest")["roots"]]
check("다시 켜도 빠져 있음", "other" not in names and "rt" in names, names)

print("S6) 서버를 끄고 켤 때: 깨끗하게 끄면 번호를 이어 쓰고, 갑자기 꺼지면 PC 들이 전체 목록을 다시 받게 함", flush=True)
put("s6/a.txt", b"a"); e1 = snap()["epoch"]; q1 = snap()["seq"]
srv_stop("TERM"); srv_start()
check("깨끗하게 끈 뒤: 같은 목록 번호, 변경 번호 이어짐", snap()["epoch"] == e1 and snap()["seq"] >= q1, (e1, snap()["epoch"]))
put("s6/b.txt", b"b"); time.sleep(0.3)
srv_stop("KILL"); srv_start()
check("갑자기 꺼진 뒤: 목록 번호가 바뀜 (PC 가 다시 받음)", snap()["epoch"] != e1)
check("서버 기록에 남김", "깨끗하게 끝나지 않음" in wsl("cat ~/mdr/log"))
wsl("rm -rf ~/mdr/data/s6"); time.sleep(1)

# ---------- PC 앱 ----------
print("C0) 처음 맞추기 (PC 6,000개)", flush=True)
for d in range(300):
    for f in range(20): lw(f"d{d:03d}/sub/f{f}.txt", f"x{d}-{f}")
cli.start()
wait_same(180, "C0")

print("C1) 바뀐 폴더만 읽기 — 여러 가지 바꾸기를 한꺼번에", flush=True)
R = LOCAL
lw("d001/sub/new1.txt", "new"); lw("d002/sub/f3.txt", "changed content longer")
os.remove(os.path.join(R, r"d003\sub\f4.txt"))
for i in range(5): lw(f"d004/nd/a/b/n{i}.txt", f"n{i}"); lw(f"d004/nd/n{i}.txt", f"n{i}")
os.rename(os.path.join(R, "d005"), os.path.join(R, "d005_renamed"))
os.makedirs(r"C:\MydriveRT-out\moved_in\deep", exist_ok=True)
for i in range(5): open(rf"C:\MydriveRT-out\moved_in\m{i}.txt", "w").write(f"m{i}"); open(rf"C:\MydriveRT-out\moved_in\deep\q{i}.txt", "w").write(f"q{i}")
shutil.move(r"C:\MydriveRT-out\moved_in", os.path.join(R, r"d006\moved_in")); shutil.rmtree(r"C:\MydriveRT-out", ignore_errors=True)
shutil.rmtree(os.path.join(R, "d007"))
os.rename(os.path.join(R, r"d008\sub\f1.txt"), os.path.join(R, r"d008\sub\f1_renamed.txt"))
lw("rootfile.txt", "root")
shutil.move(os.path.join(R, r"d009\sub"), os.path.join(R, r"d010\sub2"))
os.rename(os.path.join(R, "d011"), os.path.join(R, "d011x")); os.rename(os.path.join(R, "d011x"), os.path.join(R, "d011"))   # 바꿨다 되돌림
wsl("cd ~/mdr/data && mkdir -p d012/fromnas && echo nas > d012/fromnas/n.txt && echo nas2 > d013/sub/f5.txt && rm d014/sub/f6.txt")
wait_same(90, "C1")

print("C2) 한꺼번에 15,000개 — 폴더 감시 알림이 넘쳐도 전체 읽기로 잡음", flush=True)
for d in range(150):
    for f in range(100): lw(f"burst/b{d:03d}/g{f}.txt", f"g{d}-{f}")
wait_same(300, "C2")

print("C3) 올리는 도중 같은 크기로 다시 저장 — 예전: 최신 판이 충돌 사본으로 가고 원래 파일은 옛 판으로 돌아감", flush=True)
cli.kill(); cli.start("--up-kbps", "3000")
lw("mid/a.bin", os.urandom(24 << 20)); time.sleep(3.0)
newa = os.urandom(24 << 20); lw("mid/a.bin", newa)
wait_same(90, "C3")
check("충돌 사본 없음", not conflicts_local(), conflicts_local())
check("NAS 판 = 마지막 저장", wsl("sha1sum ~/mdr/data/mid/a.bin").split()[0] == hashlib.sha1(newa).hexdigest())
print("       (참고) a.bin 올림 횟수:", events("↑ 올림", "mid/a.bin"), "· 실패:", events("올리기 실패", "mid/a.bin"), flush=True)

print("C4) 올리는 도중 크기가 바뀌게 다시 저장", flush=True)
lw("mid/b.bin", os.urandom(20 << 20)); time.sleep(2.5)
newb = os.urandom(26 << 20); lw("mid/b.bin", newb)
wait_same(90, "C4")
check("충돌 사본 없음", not conflicts_local(), conflicts_local())

print("C5) 올리는 도중 NAS 에서도 같은 파일을 고침 → 둘 다 남기고, 같은 올리기를 되풀이하지 않음 (예전: 409 되풀이)", flush=True)
lw("mid/c.bin", os.urandom(24 << 20)); time.sleep(2.5)
wsl("head -c 1000 /dev/urandom > ~/mdr/data/mid/c.bin")
wait_same(120, "C5")
cs = [k for k in conflicts_local() if k.startswith("mid/c")]
check("충돌 사본 1개 (둘 다 남김)", len(cs) == 1, cs)
check("같은 파일 올리기 되풀이 안 함", events("↑ 올림", "mid/c.bin") <= 2 and events("충돌", "mid/c.bin") <= 2,
      (events("↑ 올림", "mid/c.bin"), events("충돌", "mid/c.bin")))
cli.kill(); cli.start()
for k in conflicts_local(): os.remove(os.path.join(LOCAL, k.replace("/", "\\")))
wait_same(60, "C5 정리")

print("C6) NAS 에서 70개를 한꺼번에 지움 → PC 쪽 삭제는 멈추고 확인을 기다림", flush=True)
wsl("mkdir -p ~/mdr/data/mass && for i in $(seq 1 70); do echo m$i > ~/mdr/data/mass/m$i.txt; done")
wait_same(60, "C6 준비")
wsl("rm -rf ~/mdr/data/mass"); time.sleep(15)
left = len([k for k in local_tree() if k.startswith("mass/")])
check("PC 파일 70개 그대로 (보류)", left == 70, left)
check("보류 알림", "대량 삭제 보류" in clilog())
shutil.rmtree(os.path.join(LOCAL, "mass"))
wait_same(60, "C6 정리")

print("C7) 바쁠 때 상태 저장은 30초에 한 번만 / 저장 전에 갑자기 꺼져도 다시 켜면 맞춤", flush=True)
sf = os.path.join(STATE, ROOT + ".state"); seen = set(); stop = time.time() + 40; i = 0
while time.time() < stop:
    lw(f"busy/f{i:04d}.txt", f"b{i}"); i += 1
    if os.path.exists(sf): seen.add(os.stat(sf).st_mtime_ns)
    time.sleep(0.25)
check(f"40초 동안 상태 저장 {len(seen)}번 (예전: 차례마다)", len(seen) <= 4, len(seen))
for j in range(60): lw(f"busy2/f{j:03d}.txt", f"c{j}")
time.sleep(1.0); cli.kill(); time.sleep(1); cli.start()
wait_same(120, "C7 다시 켠 뒤")
check("충돌 사본 없음", not conflicts_local(), conflicts_local())

print("C8) 서버가 갑자기 꺼졌다 켜짐 (그 사이 양쪽에서 바뀜)", flush=True)
srv_stop("KILL")
wsl("mkdir -p ~/mdr/data/c8 && for i in $(seq 1 20); do echo s$i > ~/mdr/data/c8/s$i.txt; done")
for j in range(20): lw(f"c8pc/p{j}.txt", f"p{j}")
time.sleep(3); srv_start()
wsl("for i in $(seq 21 30); do echo s$i > ~/mdr/data/c8/s$i.txt; done")
wait_same(120, "C8")

print("C9) 서버를 깨끗하게 다시 켬 (그 뒤 새 파일)", flush=True)
srv_stop("TERM"); srv_start()
wsl("mkdir -p ~/mdr/data/c9 && echo hi > ~/mdr/data/c9/a.txt")
wait_same(60, "C9")

print("C10) 온라인 전용 파일 — NAS 에서 바뀌어도, 지워도 내려받지 않음 (예전: 통째로 받음)", flush=True)
for nm in ("on1.bin", "on2.bin"): lw(f"cloud/{nm}", os.urandom(30 << 20))
wait_same(60, "C10 준비")
for nm in ("on1.bin", "on2.bin"): subprocess.run(["attrib", "+U", "-P", os.path.join(LOCAL, "cloud", nm)], capture_output=True)
t0 = time.time()
while time.time() - t0 < 30 and not all(os.stat(os.path.join(LOCAL, "cloud", n)).st_file_attributes & OFFLINE for n in ("on1.bin", "on2.bin")): time.sleep(1)
p1 = os.path.join(LOCAL, "cloud", "on1.bin")
check("온라인 전용이 됨", os.stat(p1).st_file_attributes & OFFLINE)
down0 = events("↓ 받음", "cloud/on1.bin")
nb = os.urandom(33 << 20); put("cloud/on1.bin", nb); time.sleep(10)
st = os.stat(p1)
check("NAS 에서 바뀐 뒤에도 온라인 전용 그대로 (받지 않음)", st.st_file_attributes & OFFLINE and events("↓ 받음", "cloud/on1.bin") == down0,
      (hex(st.st_file_attributes), events("↓ 받음", "cloud/on1.bin")))
check("크기는 NAS 에 맞춰짐", st.st_size == len(nb), st.st_size)
check("열면 NAS 내용", hashlib.sha1(open(p1, "rb").read()).hexdigest() == hashlib.sha1(nb).hexdigest())
p2 = os.path.join(LOCAL, "cloud", "on2.bin")
subprocess.run(["powershell", "-NoProfile", "-Command",
                f"Add-Type -AssemblyName Microsoft.VisualBasic; [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteFile('{p2}','OnlyErrorDialogs','SendToRecycleBin')"], capture_output=True)
time.sleep(8)
check("휴지통으로 지워도 받지 않음", events("↓ 받음", "cloud/on2.bin") == 0)
check("NAS 에서도 지워짐 (NAS 휴지통)", "cloud/on2.bin" not in [e["p"] for e in snap()["entries"]])
wait_same(60, "C10")

print("C11) 다 끝난 뒤: 조용할 때 아무것도 옮기지 않음 (되풀이 없음)", flush=True)
time.sleep(5); n1 = events("↑ 올림") + events("↓ 받음"); time.sleep(20); n2 = events("↑ 올림") + events("↓ 받음")
check("20초 동안 새로 옮긴 것 없음", n1 == n2, (n1, n2))
cnt = {}
for line in clilog().splitlines():
    m = re.search(rf"\[{ROOT}\] (↑ 올림|↓ 받음)  (.+?)  \(", line)
    if m: cnt[m.group(2)] = cnt.get(m.group(2), 0) + 1
rep = {k: v for k, v in cnt.items() if v > 3 and not k.startswith("mid/")}
check("같은 파일을 4번 넘게 옮긴 것 없음", not rep, list(rep.items())[:5])

# ---------- 정리 ----------
cli.kill(); srv_stop("KILL"); unregister_root()
shutil.rmtree(LOCAL, ignore_errors=True); shutil.rmtree(STATE, ignore_errors=True); wsl("rm -rf ~/mdr")
print(f"\n결과: {ok} 통과, {fail} 실패", flush=True)
sys.exit(1 if fail else 0)
