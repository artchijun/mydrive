# Mydrive 동기화 시나리오 시험
#   NAS 쪽 = WSL 의 ~/mdt/data (mydrived 가 localhost:8642 로 제공)
#   PC 쪽  = C:\MydriveTest
# 각 시나리오: 양쪽에 변화를 주고 → mydrive-cli --once → 기대한 결과와 "양쪽 내용이 같은지" 확인
import hashlib, os, shutil, subprocess, sys, time, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
CLI = os.path.join(HERE, "build", "mydrive-cli.exe")
LOCAL = r"C:\MydriveTest"
STATE = r"C:\MydriveTest-state"
SERVER_DIR = os.path.join(HERE, "..", "server")
ok = fail = 0

def wsl(cmd, capture=False):
    r = subprocess.run(["wsl.exe", "-e", "bash", "-lc", cmd], capture_output=True, text=True, encoding="utf-8")
    if r.returncode != 0 and not capture: print("WSL 오류:", r.stderr)
    return r.stdout

def check(name, cond, info=""):
    global ok, fail
    if cond: ok += 1; print("  OK  ", name)
    else: fail += 1; print("  FAIL", name, info)

def run(*extra, limit=50):
    r = subprocess.run([CLI, "--server", "http://localhost:8642", "--token", "test", "--root", "test", "--local", LOCAL,
                        "--state", STATE, "--limit", str(limit), "--pc", "TESTPC", "--once", *extra],
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    res = {}
    for line in r.stdout.splitlines():
        if line.startswith("RESULT"):
            for kv in line.split()[1:]:
                k, v = kv.split("="); res[k] = int(v)
    res["_out"] = r.stdout
    return res

def local_tree():
    out = {}
    for d, dirs, files in os.walk(LOCAL):
        for n in dirs:
            out[os.path.relpath(os.path.join(d, n), LOCAL).replace("\\", "/")] = "DIR"
        for n in files:
            p = os.path.join(d, n)
            out[os.path.relpath(p, LOCAL).replace("\\", "/")] = hashlib.sha1(open(p, "rb").read()).hexdigest()
    return out

def remote_tree():
    s = wsl("cd ~/mdt/data && find . -mindepth 1 -not -path './#recycle*' -not -path './#mydrive-versions*' -type d -printf 'DIR %P\\n'; "
            "find . -not -path './#recycle*' -not -path './#mydrive-versions*' -type f -exec sha1sum {} +", capture=True)
    out = {}
    for line in s.splitlines():
        if line.startswith("DIR "): out[line[4:]] = "DIR"
        elif line.strip():
            h, p = line.split(None, 1); out[p[2:] if p.startswith("./") else p] = h
    return out

def same_both(name):
    l, r = local_tree(), remote_tree()
    diff = {k for k in set(l) | set(r) if l.get(k) != r.get(k)}
    check(name + " — 양쪽 내용 같음", not diff, sorted(diff)[:10])

def lw(rel, data):
    p = os.path.join(LOCAL, rel.replace("/", "\\")); os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w", encoding="utf-8") as f: f.write(data)

def rw(rel, data):
    wsl(f"mkdir -p \"$(dirname ~/mdt/data/'{rel}')\" && printf '%s' '{data}' > ~/mdt/data/'{rel}'")

def ping():
    try:
        return urllib.request.urlopen("http://localhost:8642/v1/ping", timeout=2).status == 200
    except Exception:
        return False

# ---------- 준비: 서버와 양쪽 폴더를 새로 ----------
print("준비: 서버·폴더 초기화")
wsl("pkill -x mydrived; sleep 0.5; rm -rf ~/mdt/data ~/mdt/state; mkdir -p ~/mdt/data/sub ~/mdt/state; "
    "cp \"$(wslpath -u '" + os.path.join(SERVER_DIR, "mydrived") + "')\" ~/mdt/mydrived")
rw("a.txt", "remote a"); rw("sub/b.txt", "remote b"); rw("한글 폴더/사진 1.txt", "korean")
srv = subprocess.Popen(["wsl.exe", "-e", "bash", "-lc",
                        "cd ~/mdt && MYDRIVE_TOKEN=test MYDRIVE_ROOTS=test=$HOME/mdt/data MYDRIVE_STATE=$HOME/mdt/state exec ./mydrived"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
for _ in range(50):
    if ping(): break
    time.sleep(0.2)
shutil.rmtree(LOCAL, ignore_errors=True); shutil.rmtree(STATE, ignore_errors=True)
os.makedirs(LOCAL)
lw("local1.txt", "pc 1"); lw("sub2/c.txt", "pc c")

print("1) 처음 맞추기: 양쪽에만 있던 파일을 서로 채움")
r = run(); check("올림 2, 받음 3", r.get("up") == 2 and r.get("down") == 3, r); same_both("1")
r = run(); check("다시 돌리면 할 일 없음", r.get("up") == 0 and r.get("down") == 0 and r.get("errors") == 0, r)

print("2) PC 에서 고침 → NAS 로")
time.sleep(1.1); lw("local1.txt", "pc 1 edited")
r = run(); check("올림 1", r.get("up") == 1, r); same_both("2")

print("3) NAS 에서 고침 → PC 로")
rw("a.txt", "remote a edited"); time.sleep(0.5)
r = run(); check("받음 1", r.get("down") == 1, r); same_both("3")

print("4) PC 에서 지움 → NAS 휴지통으로")
os.remove(os.path.join(LOCAL, "sub2", "c.txt"))
r = run(); check("NAS 삭제 1", r.get("delR") == 1, r); same_both("4")
check("NAS 휴지통에 남음", "remote" not in wsl("test -f ~/mdt/data/#recycle/sub2/c.txt && echo yes", capture=True) and
      wsl("test -f ~/mdt/data/#recycle/sub2/c.txt && echo yes", capture=True).strip() == "yes")

print("5) NAS 에서 지움 → PC 휴지통으로")
wsl("rm ~/mdt/data/sub/b.txt"); time.sleep(0.5)
r = run(); check("PC 삭제 1", r.get("delL") == 1, r); same_both("5")

print("6) 양쪽에서 같은 파일을 고침 → 둘 다 남김")
time.sleep(1.1); lw("a.txt", "pc version"); rw("a.txt", "nas version"); time.sleep(0.5)
r = run(); check("충돌 1", r.get("conflicts") == 1, r)
names = [n for n in os.listdir(LOCAL) if n.startswith("a (TESTPC 충돌")]
check("PC 판은 충돌 사본으로", len(names) == 1 and open(os.path.join(LOCAL, names[0]), encoding="utf-8").read() == "pc version", names)
check("원래 이름은 NAS 판", open(os.path.join(LOCAL, "a.txt"), encoding="utf-8").read() == "nas version")
same_both("6")

print("6b) 크기·수정 시각이 똑같은데 내용만 다름 → 둘 다 남김 (시각만 보고 같다고 하면 안 됨)")
lw("same_meta.txt", "AAAA"); run()
lw("same_meta.txt", "BBBB"); rw("same_meta.txt", "CCCC")
os.utime(os.path.join(LOCAL, "same_meta.txt"), ns=(1800000000000000000, 1800000000000000000))
wsl("touch -d @1800000000 ~/mdt/data/same_meta.txt"); time.sleep(0.5)
r = run(); check("충돌로 처리", r.get("conflicts") == 1, r); same_both("6b")

print("7) PC 에서 이름 바꿈 → NAS 는 이름만 바꿈 (다시 올리지 않음)")
os.rename(os.path.join(LOCAL, "local1.txt"), os.path.join(LOCAL, "renamed1.txt"))
r = run(); check("이름 바꿈 1, 올림 0", r.get("moved") == 1 and r.get("up") == 0, r); same_both("7")

print("8) NAS 에서 폴더 이름 바꿈 → PC 도 폴더째 이름만 바꿈")
wsl("mv ~/mdt/data/'한글 폴더' ~/mdt/data/'한글 폴더 2'"); time.sleep(0.5)
r = run(); check("이름 바꿈 1, 받음 0", r.get("moved") == 1 and r.get("down") == 0, r); same_both("8")

print("9) PC 에서 60개 한꺼번에 지움 → 멈추고 확인 기다림")
for i in range(60): lw(f"many/f{i}.txt", str(i))
run()
shutil.rmtree(os.path.join(LOCAL, "many"))
r = run(); check("보류(NAS 그대로)", r.get("held", 0) > 50 and r.get("delR") == 0, r)
check("NAS 에 아직 60개", wsl("ls ~/mdt/data/many | wc -l", capture=True).strip() == "60")
r = run("--confirm-delete"); check("확인 뒤 삭제 진행", r.get("delR", 0) >= 1 and r.get("held") == 0, r); same_both("9")

print("10) NAS 에서 폴더를 지웠는데 PC 에서 그 안에 새 파일을 만듦 → 새 파일은 살림")
lw("keep/old.txt", "old"); run()
wsl("rm -rf ~/mdt/data/keep"); time.sleep(0.3)
lw("keep/new.txt", "new on pc")
r = run(); same_both("10")
check("새 파일이 NAS 에 올라감", wsl("cat ~/mdt/data/keep/new.txt", capture=True) == "new on pc")

print("11) NAS 에서 지웠는데 PC 에서 고친 파일 → 고친 판을 살림")
lw("edit_vs_del.txt", "v1"); run()
wsl("rm ~/mdt/data/edit_vs_del.txt"); time.sleep(1.1); lw("edit_vs_del.txt", "v2 edited"); time.sleep(0.3)
r = run(); same_both("11")
check("고친 판이 NAS 에", wsl("cat ~/mdt/data/edit_vs_del.txt", capture=True) == "v2 edited")

print("12) 큰 파일 (64 MB) 올리고 받기")
data = os.urandom(64 * 1024 * 1024)
with open(os.path.join(LOCAL, "big.bin"), "wb") as f: f.write(data)
t0 = time.time(); r = run(); t1 = time.time() - t0
check(f"올림 ({t1:.1f}초)", r.get("up") == 1, r)
wsl("head -c 1048576 /dev/urandom >> ~/mdt/data/big.bin"); time.sleep(0.5)
r = run(); check("NAS 에서 늘린 판을 받음", r.get("down") == 1, r); same_both("12")

print("13) 마지막: 다시 돌리면 할 일 없음")
r = run(); check("할 일 없음", all(r.get(k) == 0 for k in ("up", "down", "delL", "delR", "moved", "conflicts", "errors")), r)

print(f"\n결과: {ok} 통과, {fail} 실패")
srv.terminate()
sys.exit(1 if fail else 0)
