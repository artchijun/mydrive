# 대량 동기화 시험: 작은 파일 3000개 + 큰 파일 3개를 C:\MydriveApp\대량 시험 에 만들고
# NAS 에 다 올라갈 때까지 시간을 재며 창을 찍음
import os, sys, time, json, random, subprocess, urllib.request, urllib.parse, ctypes
HERE = os.path.dirname(os.path.abspath(__file__))
TOKEN = os.environ.get("MYDRIVE_TOKEN", "")   # 동기화 토큰은 환경 변수로
TOP = sys.argv[1] if len(sys.argv) > 1 else "대량 시험"
DST = os.path.join(r"C:\MydriveApp", TOP)

def api(method, ep, **q):
    r = urllib.request.Request("http://192.168.0.6:8642" + ep + "?" + urllib.parse.urlencode(q), method=method,
                               headers={"Authorization": "Bearer " + TOKEN})
    return json.loads(urllib.request.urlopen(r, timeout=60).read())

def show():
    u = ctypes.windll.user32
    h = u.FindWindowW("MydriveMain", None)
    u.PostMessageW(h, 0x8003, 0, 0)

shot_n = 0
def shot(tag):
    global shot_n; shot_n += 1
    out = os.path.join(HERE, "build", f"mass_{shot_n:02}_{tag}.png")
    subprocess.run(["powershell", "-ExecutionPolicy", "Bypass", "-File", os.path.join(HERE, "cap.ps1"), "-Class", "MydrivePopup", "-Out", out],
                   capture_output=True)

random.seed(7)
want = {}
show(); time.sleep(0.5)
t0 = time.time()
os.makedirs(DST, exist_ok=True)
# 큰 파일
for name, mb in (("영상/현장 기록 1.mp4", 600), ("영상/현장 기록 2.mp4", 400), ("자료/스캔 원본.zip", 250)):
    p = os.path.join(DST, name); os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "wb") as f:
        chunk = os.urandom(1 << 20)
        for _ in range(mb): f.write(chunk)
    want[TOP + "/" + name.replace("\\", "/")] = mb << 20
# 작은 파일 3000개 (폴더 20개 × 150개)
exts = [".jpg", ".pdf", ".docx", ".xlsx", ".dwg", ".hwp", ".txt", ".png"]
for d in range(20):
    for i in range(150):
        rel = f"프로젝트 {d+1:02}/파일 {i+1:03}{random.choice(exts)}"
        n = random.randint(2_000, 300_000)
        p = os.path.join(DST, rel); os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "wb") as f: f.write(os.urandom(n))
        want[TOP + "/" + rel] = n
made = time.time() - t0
total = sum(want.values())
print(f"만든 파일 {len(want)}개, {total/1e6:.0f} MB, 만드는 데 {made:.1f}초", flush=True)

shots_at = [3, 8, 15, 25, 40, 60, 90]
done_at = None
while time.time() - t0 < 900:
    el = time.time() - t0
    if shots_at and el >= shots_at[0]:
        shot(f"{int(el)}s"); shots_at.pop(0)
    try:
        ents = api("GET", "/v1/snapshot", root="test")["entries"]
        have = {e["p"]: e.get("s", e.get("size", -1)) for e in ents if e["p"].startswith(TOP + "/")}
        ok = sum(1 for k, v in want.items() if have.get(k) == v)
    except Exception as ex:
        ok = -1
    print(f"{el:6.1f}s  NAS에 도착 {ok}/{len(want)}", flush=True)
    if ok == len(want):
        done_at = time.time() - t0; break
    time.sleep(2)
time.sleep(2); shot("done")
if done_at:
    print(f"RESULT 전부 도착: {done_at:.1f}초 (파일 만들기 시작부터) · 평균 {total/1e6/done_at:.1f} MB/s · 초당 {len(want)/done_at:.0f}개")
else:
    print("RESULT 15분 안에 끝나지 않음")
