# 앱을 켜 둔 상태에서: PC 에 파일을 만들면 NAS 에, NAS 에 만들면 PC 에 몇 초 안에 생기는지
import os, subprocess, time, urllib.request
HERE = os.path.dirname(os.path.abspath(__file__))
CLI = os.path.join(HERE, "build", "mydrive-cli.exe")
LOCAL = r"C:\MydriveTest"
def wsl(c): return subprocess.run(["wsl.exe", "-e", "bash", "-lc", c], capture_output=True, text=True, encoding="utf-8").stdout
srv = subprocess.Popen(["wsl.exe", "-e", "bash", "-lc", "cd ~/mdt && MYDRIVE_TOKEN=test MYDRIVE_ROOTS=test=$HOME/mdt/data MYDRIVE_STATE=$HOME/mdt/state exec ./mydrived"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
time.sleep(2)
app = subprocess.Popen([CLI, "--server", "http://localhost:8642", "--token", "test", "--root", "test", "--local", LOCAL,
                        "--state", r"C:\MydriveTest-state", "--pc", "TESTPC"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
time.sleep(3)
res = []
# PC → NAS
t0 = time.time(); open(os.path.join(LOCAL, "live_pc.txt"), "w").write("from pc live")
while time.time() - t0 < 20 and wsl("cat ~/mdt/data/live_pc.txt 2>/dev/null") != "from pc live": time.sleep(0.2)
res.append(("PC에 만든 파일이 NAS에", time.time() - t0))
# NAS → PC
t0 = time.time(); wsl("printf 'from nas live' > ~/mdt/data/live_nas.txt")
p = os.path.join(LOCAL, "live_nas.txt")
while time.time() - t0 < 20 and not (os.path.exists(p) and open(p).read() == "from nas live"): time.sleep(0.2)
res.append(("NAS에 만든 파일이 PC에", time.time() - t0))
app.terminate(); srv.terminate()
for n, t in res: print(("OK  " if t < 20 else "FAIL"), n, f"{t:.1f}초")
