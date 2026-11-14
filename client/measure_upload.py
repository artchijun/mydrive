# 올리기 시간 재기: 큰 파일 1개, 작은 파일 30개
import os, time, urllib.request
HERE = os.path.dirname(os.path.abspath(__file__))
TOKEN = open(os.path.join(HERE, "..", "server", "deploy", "token.txt")).read().strip()
B = "http://192.168.0.6:8642/v1/file?root=test&if=&mtime=1700000000000000000&path="
def put(path, data):
    r = urllib.request.Request(B + urllib.parse.quote(path), data=data, method="PUT", headers={"Authorization": "Bearer " + TOKEN})
    return urllib.request.urlopen(r, timeout=300).status
import urllib.parse
big = os.urandom(200 * 1024 * 1024)
t0 = time.time(); put("_measure/big.bin", big); t = time.time() - t0
print(f"200MB 한 번: {t:.1f}초 ({200 / t:.0f} MB/s)")
t0 = time.time()
for i in range(30): put(f"_measure/s{i}.txt", b"x" * 1000)
t = time.time() - t0
print(f"작은 파일 30개: {t:.1f}초 (개당 {t / 30 * 1000:.0f} ms)")
