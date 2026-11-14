# 실제 앱(설치본, C:\MydriveApp ↔ NAS test)으로: 큰 파일이 올라가는 중에도 작은 파일이 바로 지나가는지
import os, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tlib import api, check, cnt

L = r"C:\MydriveApp\통로시험"
os.makedirs(L, exist_ok=True)
def nas_has(p): return any(e["p"] == p for e in api("GET", "/v1/snapshot", root="test")["entries"])
t0 = time.time()
with open(os.path.join(L, "큰파일_600MB.bin"), "wb") as f:
    for _ in range(60): f.write(os.urandom(10 * 1024 * 1024))
t_big_written = time.time() - t0
time.sleep(4)   # 큰 파일이 올라가기 시작하도록
t1 = time.time()
for i in range(5):
    with open(os.path.join(L, f"작은_{i}.txt"), "w", encoding="utf-8") as f: f.write(f"small {i}")
small_at = big_at = None
while time.time() - t1 < 180 and (small_at is None or big_at is None):
    if small_at is None and all(nas_has(f"통로시험/작은_{i}.txt") for i in range(5)): small_at = time.time() - t1
    if big_at is None and nas_has("통로시험/큰파일_600MB.bin"):
        e = [x for x in api("GET", "/v1/snapshot", root="test")["entries"] if x["p"] == "통로시험/큰파일_600MB.bin"][0]
        if e["s"] == 600 * 1024 * 1024: big_at = time.time() - t1
    time.sleep(0.3)
print(f"  작은 파일 5개 NAS 도착: {small_at}초 / 큰 파일 끝: {big_at}초 (작은 파일 만든 때 기준)")
check("작은 파일이 큰 파일보다 먼저 도착", small_at is not None and (big_at is None or small_at < big_at), (small_at, big_at))
check("작은 파일은 10초 안에", small_at is not None and small_at < 10, small_at)
print(f"\n결과: {cnt['ok']} 통과, {cnt['fail']} 실패")
