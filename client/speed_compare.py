# 시놀로지 드라이브 vs Mydrive 동기화 속도 비교
#   python speed_compare.py up <synology|mydrive>
#       PC 폴더에 파일을 넣은 순간부터 NAS에 다 생길 때까지 (NAS 쪽은 Mydrive 서버의 inotify 목록으로 확인)
#   python speed_compare.py wait <synology|mydrive> <폴더> <개수> <크기>
#       NAS에 파일을 넣는 동안(DSM File Station 업로드) PC에 다 받아질 때까지 기다렸다가 끝난 시각(epoch 초)을 찍음
# 시놀로지 쪽은 C:\DESKTOP\TEMP\mydrive-speedtest (기존 DESKTOP 연결 안의 시험 폴더) 만 사용.
import os, sys, time, json, shutil, subprocess, urllib.request, urllib.parse

SERVER = "http://192.168.0.6:8642"
TOK = os.environ.get("MYDRIVE_TOKEN", "")   # 동기화 토큰은 환경 변수로
N_SMALL = 300
SMALL = 20 * 1024
BIG = 296 * 1024 * 1024   # 8MB x 37
TMP = os.path.join(os.environ["TEMP"], "mydrive-speed-src")

TOOLS = {
    "synology": {"local": r"C:\DESKTOP\TEMP\mydrive-speedtest", "root": "speed", "pre": ""},
    "mydrive": {"local": r"C:\MydriveApp\speedtest", "root": "test", "pre": "speedtest/"},
}

def req(method, path, q=None, data=None, timeout=600):
    url = SERVER + path + ("?" + urllib.parse.urlencode(q) if q else "")
    r = urllib.request.Request(url, data=data, method=method, headers={"Authorization": "Bearer " + TOK})
    with urllib.request.urlopen(r, timeout=timeout) as resp:
        return resp.read()

def nas_sizes(root, pre):
    j = json.loads(req("GET", "/v1/snapshot", {"root": root}))
    return {e["p"][len(pre):]: e["s"] for e in j["entries"] if not e.get("d") and e["p"].startswith(pre)}

def local_sizes(base):
    out = {}
    for dp, dn, fn in os.walk(base):
        for f in fn:
            p = os.path.join(dp, f)
            try:
                st = os.stat(p)
            except OSError:
                continue
            if st.st_file_attributes & 0x400000:  # 아직 온라인 전용(안 받음)
                continue
            out[os.path.relpath(p, base).replace("\\", "/")] = st.st_size
    return out

def wait_until(fn, want, limit=900):
    t = time.time()
    while time.time() - t < limit:
        have = fn()
        if all(have.get(k) == v for k, v in want.items()):
            return True
        time.sleep(0.25)
    return False

def make_src():
    if os.path.isdir(TMP):
        shutil.rmtree(TMP)
    os.makedirs(os.path.join(TMP, "small"))
    for i in range(N_SMALL):
        with open(os.path.join(TMP, "small", f"s{i:04d}.bin"), "wb") as f:
            f.write(os.urandom(SMALL))
    with open(os.path.join(TMP, "big.bin"), "wb") as f:
        for _ in range(BIG // (8 << 20)):
            f.write(os.urandom(8 << 20))

def up_test(tool, name):
    c = TOOLS[tool]
    sub = f"up_{name}" + os.environ.get("RUN", "")
    dst = os.path.join(c["local"], sub)
    if name == "small":
        want = {f"{sub}/s{i:04d}.bin": SMALL for i in range(N_SMALL)}
        t0 = time.time()
        shutil.copytree(os.path.join(TMP, "small"), dst)
    else:
        want = {f"{sub}/big.bin": BIG}
        os.makedirs(dst)
        t0 = time.time()
        shutil.copyfile(os.path.join(TMP, "big.bin"), os.path.join(dst, "big.bin"))
    t_copy = time.time() - t0
    ok = wait_until(lambda: nas_sizes(c["root"], c["pre"]), want)
    return ok, time.time() - t0, t_copy

def main():
    mode, tool = sys.argv[1], sys.argv[2]
    c = TOOLS[tool]
    if mode == "up":
        if not os.path.isdir(TMP):
            make_src()
        os.makedirs(c["local"], exist_ok=True)
        subprocess.run(["attrib", "+P", c["local"]], capture_output=True)  # 둘 다 '항상 이 기기에 유지'로 받게
        for kind in (sys.argv[3:] or ["small", "big"]):
            ok, tot, part = up_test(tool, kind)
            print(json.dumps({"tool": tool, "dir": "up", "kind": kind, "ok": ok, "sec": round(tot, 2), "copy": round(part, 2)}), flush=True)
            time.sleep(5)
    elif mode == "wait":
        sub, n, size = sys.argv[3], int(sys.argv[4]), int(sys.argv[5])
        if n == 1:
            want = {f"{sub}/big.bin": size}
        else:
            want = {f"{sub}/s{i:04d}.bin": size for i in range(n)}
        ok = wait_until(lambda: local_sizes(c["local"]), want)
        print(json.dumps({"tool": tool, "dir": "down", "sub": sub, "ok": ok, "done": time.time()}), flush=True)

if __name__ == "__main__":
    main()
