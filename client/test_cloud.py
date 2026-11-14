# 탐색기 연동 시험 (실제 앱 + C:\MydriveApp, 연결 test 가 onDemand 켜짐)
#  1) NAS 에 새 파일 → PC 에는 온라인 전용(내용 없음)으로 생김
#  2) 열면(읽으면) 그 자리에서 받아짐 — 내용이 NAS 와 같음
#  3) 공간 확보(attrib +U) → 내용 비움, 크기는 그대로
#  4) 항상 유지(attrib +P) → 다시 받아 둠
#  5) PC 에서 고침 → NAS 로 올라가고 다시 '맞춤' 상태
import os, time, json, subprocess, urllib.request, urllib.parse, hashlib
TOKEN = os.environ.get("MYDRIVE_TOKEN", "")   # 동기화 토큰은 환경 변수로
ROOT = r"C:\MydriveApp"
D = "탐색기 시험 " + time.strftime("%H%M%S")
ok = fail = 0

def api(method, ep, data=None, **q):
    r = urllib.request.Request("http://192.168.0.6:8642" + ep + "?" + urllib.parse.urlencode(q), data=data, method=method,
                               headers={"Authorization": "Bearer " + TOKEN})
    with urllib.request.urlopen(r, timeout=60) as resp: return resp.read()

def check(name, cond, info=""):
    global ok, fail
    if cond: ok += 1; print("  OK  ", name)
    else: fail += 1; print("  FAIL", name, info)

RECALL, PINNED, UNPINNED, REPARSE = 0x400000, 0x80000, 0x100000, 0x400
import ctypes
ctypes.windll.ntdll.RtlSetProcessPlaceholderCompatibilityMode(2)   # 자리표시를 숨기지 말고 보이게 (기본은 숨김)
def attrs(p):
    import ctypes
    return ctypes.windll.kernel32.GetFileAttributesW(p)

def wait(cond, sec=30):
    t = time.time()
    while time.time() - t < sec:
        if cond(): return True
        time.sleep(0.3)
    return False

print("1) NAS 에 새 파일 3개 → PC 에 온라인 전용으로")
data = {f"{D}/문서 {i}.bin": os.urandom(300_000 * (i + 1)) for i in range(3)}
data[f"{D}/큰 영상.bin"] = os.urandom(40 * 1024 * 1024)
for p, b in data.items():
    api("PUT", "/v1/file", b, root="test", path=p, mtime=str(time.time_ns()), **{"if": "none"})
paths = {p: os.path.join(ROOT, p.replace("/", "\\")) for p in data}
check("PC 에 모두 생김", wait(lambda: all(os.path.exists(x) for x in paths.values()), 30))
time.sleep(1)
for p, x in paths.items():
    a = attrs(x)
    check(f"온라인 전용 (내용 없음): {os.path.basename(x)}", a & RECALL and os.path.getsize(x) == len(data[p]), hex(a))

print("2) 열면 그 자리에서 받음")
x = paths[f"{D}/문서 1.bin"]
t0 = time.time(); got = open(x, "rb").read(); dt = time.time() - t0
check(f"내용이 NAS 와 같음 ({dt:.2f}초)", got == data[f"{D}/문서 1.bin"])
check("받은 뒤에는 내용이 PC 에 있음", not (attrs(x) & RECALL), hex(attrs(x)))
x = paths[f"{D}/큰 영상.bin"]
t0 = time.time(); got = open(x, "rb").read(); dt = time.time() - t0
check(f"40MB 파일 열기 ({dt:.1f}초, {40/dt:.0f} MB/s)", hashlib.sha256(got).digest() == hashlib.sha256(data[f'{D}/큰 영상.bin']).digest())

print("3) 공간 확보 (탐색기 오른쪽 클릭과 같은 표시: attrib +U)")
subprocess.run(["attrib", "-P", "+U", x], capture_output=True)
check("내용을 비움", wait(lambda: attrs(x) & RECALL, 30), hex(attrs(x)))
check("크기는 그대로 보임", os.path.getsize(x) == len(data[f"{D}/큰 영상.bin"]))
check("공간 확보 표시는 지워짐 (다시 열면 그대로 둠)", wait(lambda: not (attrs(x) & UNPINNED), 10), hex(attrs(x)))

print("4) 항상 유지 (attrib +P)")
x2 = paths[f"{D}/문서 2.bin"]
subprocess.run(["attrib", "+P", x2], capture_output=True)
check("내용을 받아 둠", wait(lambda: not (attrs(x2) & RECALL), 30), hex(attrs(x2)))
check("항상 유지 표시 남음", attrs(x2) & PINNED, hex(attrs(x2)))

print("5) PC 에서 고치면 NAS 로 올라감")
x0 = paths[f"{D}/문서 0.bin"]
new = b"edited on PC " + os.urandom(1000)
with open(x0, "wb") as f: f.write(new)
def nas_same():
    try: return api("GET", "/v1/file", root="test", path=f"{D}/문서 0.bin") == new
    except Exception: return False
check("NAS 내용이 바뀜", wait(nas_same, 30))

print("6) 기존 파일도 상태 아이콘 대상 (자리표시로 바뀜)")
cnt = tot = 0
for dp, dn, fn in os.walk(ROOT):
    for n in fn[:5]:
        tot += 1; cnt += 1 if attrs(os.path.join(dp, n)) & REPARSE else 0
check(f"기존 파일 {cnt}/{tot} 자리표시", cnt == tot)

print(f"\n결과: {ok} 통과, {fail} 실패")
