# 서버가 꺼져 있던 동안 NAS에서 바뀐 것도, 다시 켜진 뒤 변경 기록으로 이어 받는지
import json, subprocess, time, urllib.request, urllib.parse
B = "http://localhost:8642"; H = {"Authorization": "Bearer test"}
def get(ep, **q):
    r = urllib.request.Request(B + ep + "?" + urllib.parse.urlencode(q), headers=H)
    return json.loads(urllib.request.urlopen(r, timeout=30).read())
def wsl(c): subprocess.run(["wsl.exe", "-e", "bash", "-lc", c], check=True)
s = get("/v1/snapshot", root="test"); ep, seq = s["epoch"], s["seq"]
time.sleep(31)  # 상태 저장 주기(30초) 기다림
wsl("pkill -x mydrived; sleep 1; echo offline > ~/mdt/data/while_off.txt; rm -f ~/mdt/data/a.txt")
p = subprocess.Popen(["wsl.exe", "-e", "bash", "-lc", "cd ~/mdt && MYDRIVE_TOKEN=test MYDRIVE_ROOTS=test=$HOME/mdt/data MYDRIVE_STATE=$HOME/mdt/state exec ./mydrived"])
time.sleep(2)
c = get("/v1/changes", root="test", epoch=ep, since=seq)
ops = [(x["op"], x["e"]["p"]) for x in c["changes"]]
print("reset:", c["reset"], "ops:", ops)
print("OK" if (not c["reset"] and ("put", "while_off.txt") in ops and ("del", "a.txt") in ops) else "FAIL")
wsl("echo hello > ~/mdt/data/a.txt; rm -f ~/mdt/data/while_off.txt")
