# 항상 동기화 폴더 / 요청 시 동기화 폴더 / 오래 안 쓴 파일 자동 공간 확보 시험
#  앱은 keepDirs=["항상 시험"], MYDRIVE_AUTOFREE_TEST_SEC=432000(5일) 으로 켜 둔 상태에서 실행
import os, time, json, urllib.request, urllib.parse, ctypes, ctypes.wintypes as wt
ctypes.windll.ntdll.RtlSetProcessPlaceholderCompatibilityMode(2)
TOKEN = os.environ.get("MYDRIVE_TOKEN", "")   # 동기화 토큰은 환경 변수로
ROOT = r"C:\MydriveApp"
K, R = "항상 시험", "요청 시험"
RECALL, PINNED = 0x400000, 0x80000
ok = fail = 0
def api(method, ep, data=None, **q):
    r = urllib.request.Request("http://192.168.0.6:8642" + ep + "?" + urllib.parse.urlencode(q), data=data, method=method, headers={"Authorization": "Bearer " + TOKEN})
    with urllib.request.urlopen(r, timeout=60) as resp: return resp.read()
def check(name, cond, info=""):
    global ok, fail
    if cond: ok += 1; print("  OK  ", name)
    else: fail += 1; print("  FAIL", name, info)
def attrs(p): return ctypes.windll.kernel32.GetFileAttributesW(p) & 0xFFFFFFFF
def wait(cond, sec=40):
    t = time.time()
    while time.time() - t < sec:
        if cond(): return True
        time.sleep(0.5)
    return False
L = lambda rel: os.path.join(ROOT, rel.replace("/", "\\"))

print("1) NAS 에 새 파일: '항상 시험' 폴더 2개, '요청 시험' 폴더 1개")
S = time.strftime("%H%M%S")
files = {f"{K}/a {S}.bin": os.urandom(1_500_000), f"{K}/sub/b {S}.bin": os.urandom(800_000), f"{R}/c {S}.bin": os.urandom(900_000)}
for p, b in files.items(): api("PUT", "/v1/file", b, root="test", path=p, mtime=str(time.time_ns()), **{"if": "none"})
check("PC 에 모두 생김", wait(lambda: all(os.path.exists(L(p)) for p in files)))
check("'항상 시험' 폴더는 항상 유지 표시", wait(lambda: attrs(L(K)) & PINNED), hex(attrs(L(K))))
check("'항상 시험' 안 파일은 내용까지 받아 둠", wait(lambda: all(not (attrs(L(p)) & RECALL) for p in files if p.startswith(K)), 60),
      [hex(attrs(L(p))) for p in files if p.startswith(K)])
check("'항상 시험' 하위 폴더 파일도 항상 유지", attrs(L(f"{K}/sub/b {S}.bin")) & PINNED, hex(attrs(L(f"{K}/sub/b {S}.bin"))))
time.sleep(2)
check("'요청 시험' 파일은 온라인 전용 (내용 없음)", attrs(L(f"{R}/c {S}.bin")) & RECALL, hex(attrs(L(f"{R}/c {S}.bin"))))
check("받아 둔 내용이 NAS 와 같음", open(L(f"{K}/a {S}.bin"), "rb").read() == files[f"{K}/a {S}.bin"])

print("2) 오래 안 쓴 파일 → 자동으로 온라인 전용 (시험: 5일)")
op = L(f"{R}/오래된 문서 {S}.txt")
body = b"old document " * 5000
with open(op, "wb") as f: f.write(body)
def nas_has():
    try: return api("GET", "/v1/file", root="test", path=f"{R}/오래된 문서 {S}.txt") == body
    except Exception: return False
check("먼저 NAS 로 올라감", wait(nas_has))
time.sleep(3)
# 10일 전에 마지막으로 열고 고친 것처럼 시각을 바꿈
old = int((time.time() - 10 * 86400) * 1e7) + 116444736000000000
ft = wt.FILETIME(old & 0xFFFFFFFF, old >> 32)
h = ctypes.windll.kernel32.CreateFileW(op, 0x100, 7, None, 3, 0, None)   # FILE_WRITE_ATTRIBUTES
ctypes.windll.kernel32.SetFileTime(h, None, ctypes.byref(ft), ctypes.byref(ft)); ctypes.windll.kernel32.CloseHandle(h)
new = L(f"{R}/새 문서 {S}.txt")
with open(new, "wb") as f: f.write(b"new document")
check("10일 안 쓴 파일은 온라인 전용으로 바뀜", wait(lambda: attrs(op) & RECALL, 60), hex(attrs(op)))
check("크기는 그대로 보임", os.path.getsize(op) == len(body))
time.sleep(3)
check("방금 만든 파일은 그대로 (내용 있음)", not (attrs(new) & RECALL), hex(attrs(new)))
check("'항상 시험' 파일은 그대로", all(not (attrs(L(p)) & RECALL) for p in files if p.startswith(K)))
check("다시 열면 바로 받음 (내용 같음)", open(op, "rb").read() == body)
time.sleep(8)
check("방금 연 파일은 다시 비우지 않음 (마지막 접근 시각이 꺼진 PC에서도)", not (attrs(op) & RECALL), hex(attrs(op)))
print(f"\n결과: {ok} 통과, {fail} 실패")
