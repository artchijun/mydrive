# 새 기능 시험 — 필터, 선택 동기화, 쓰는 중 파일, 조용해질 때까지 미루기, 속도 제한, 우선순위
import os, re, shutil, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tlib import *

ONLY = sys.argv[1:]
def want(t): return not ONLY or t in ONLY
FILT = ["--exclude", "*.bak;node_modules;임시*"]
reset()

if want("F1"):
    print("F1) 이름 패턴으로 빼기 (*.bak, node_modules, 임시*)")
    w(A, "f1/보관.txt", "keep"); w(A, "f1/옛것.bak", "bak"); w(A, "f1/node_modules/lib/x.js", "js"); w(A, "f1/임시메모.txt", "tmp")
    r = run("A", *FILT); check("A 는 보관.txt 만 올림", r.get("up") == 1 and r.get("errors") == 0, short(r))
    check("NAS 에 .bak·node_modules·임시* 없음", not nas_has("f1/옛것.bak") and not nas_has("f1/node_modules") and not nas_has("f1/임시메모.txt"))
    nas_put("f1/NAS에서.bak", b"remote bak")
    r = run("A", *FILT); check("A 는 NAS 의 .bak 을 받지 않음", r.get("down") == 0 and not ex(A, "f1/NAS에서.bak"), short(r))
    r = run("B"); check("필터 없는 B 는 받음 (보관.txt + NAS에서.bak)", ex(B, "f1/NAS에서.bak") and ex(B, "f1/보관.txt"), short(r))
    check("A 의 빠진 파일은 그대로", ex(A, "f1/옛것.bak") and ex(A, "f1/node_modules/lib/x.js"))

if want("F2"):
    print("F2) 이미 맞춘 파일을 나중에 빼도 지우지 않음")
    w(A, "f2/나중에뺌.bak", "v1"); r = run("A"); run("B")
    check("필터 전에는 올라감", nas_has("f2/나중에뺌.bak"), short(r))
    r = run("A", *FILT); check("필터를 켜도 어느 쪽도 안 지움", r.get("delL") == 0 and r.get("delR") == 0 and nas_has("f2/나중에뺌.bak"), short(r))
    os.remove(lp(A, "f2/나중에뺌.bak"))
    r = run("A", *FILT); check("뺀 파일을 PC 에서 지워도 NAS 는 그대로", r.get("delR") == 0 and nas_has("f2/나중에뺌.bak"), short(r))
    r = run("A"); check("필터를 끄면 NAS 판을 다시 받음 (지우지 않음)", ex(A, "f2/나중에뺌.bak") and r.get("delR") == 0, short(r))

if want("F3"):
    print("F3) 최대 크기 (1 MB)")
    w(A, "f3/작은.bin", os.urandom(100_000), "wb"); w(A, "f3/큰.bin", os.urandom(3_000_000), "wb")
    r = run("A", "--maxsize", "1"); check("작은 것만 올림", r.get("up") == 1 and not nas_has("f3/큰.bin"), short(r))
    w(A, "f3/커짐.bin", os.urandom(500_000), "wb"); run("A", "--maxsize", "1")
    time.sleep(1.1); w(A, "f3/커짐.bin", os.urandom(2_000_000), "wb")
    r = run("A", "--maxsize", "1"); check("한도를 넘게 커진 파일: NAS 옛 판 유지, 지우지 않음", r.get("delR") == 0 and nas_has("f3/커짐.bin"), short(r))
    run("B")
    shutil.rmtree(lp(B, "f3")); r = run("B", "--confirm-delete"); check("B 가 f3 폴더를 지움 → NAS 휴지통", not nas_has("f3"), short(r))
    r = run("A", "--maxsize", "1"); check("A: 크기로 뺀 파일이 든 폴더는 남김", ex(A, "f3/큰.bin") and ex(A, "f3/커짐.bin"), short(r))
    check("A: 작은.bin 은 따라 지움", not ex(A, "f3/작은.bin"))

if want("F4"):
    print("F4) 선택 동기화 (폴더 빼기)")
    nas_put("f4/포함/a.txt", b"a"); nas_put("f4/제외/b.txt", b"b"); nas_put("f4/제외/깊이/c.txt", b"c")
    r = run("B", "--skip", "f4/제외"); check("B 는 포함만 받음", ex(B, "f4/포함/a.txt") and not ex(B, "f4/제외"), short(r))
    api("DELETE", "/v1/file", root="test", path="f4/제외/b.txt")
    r = run("B", "--skip", "f4/제외"); check("뺀 폴더의 NAS 변경은 무시", quiet(r), short(r))
    r = run("B"); check("선택을 풀면 받음", ex(B, "f4/제외/깊이/c.txt") and not ex(B, "f4/제외/b.txt"), short(r))

if want("F5"):
    print("F5) 다른 프로그램이 잡고 있는 파일 → 프로그램 이름 표시")
    w(A, "f5/잠김.docx", "v1"); run("A")
    time.sleep(1.1); w(A, "f5/잠김.docx", "v2")
    h = lock(lp(A, "f5/잠김.docx"))
    r = run("A"); lines = [l for l in r["_out"].splitlines() if "기다림" in l]
    check("올리지 않고 '기다림'으로 남김 (오류 아님)", r.get("up") == 0 and r.get("errors") == 0 and r.get("waiting") == 1, short(r))
    check("잡고 있는 프로그램 이름을 알림", any(re.search(r"python|Python", l) for l in lines), lines)
    unlock(h)
    r = run("A"); check("놓은 뒤 올림", r.get("up") == 1 and r.get("waiting") == 0, short(r))

if want("F6"):
    print("F6) 방금 바뀐 파일은 조용해질 때까지 미룸 (--quiet 4)")
    w(A, "f6/방금.txt", "now")
    r = run("A", "--quiet", "4"); check("바로는 안 올림", r.get("up") == 0, short(r))
    time.sleep(4.5)
    r = run("A", "--quiet", "4"); check("4초 뒤 올림", r.get("up") == 1, short(r))

if want("F7"):
    print("F7) 속도 제한")
    w(A, "f7/20MB.bin", os.urandom(20 * 1024 * 1024), "wb")
    r = run("A", "--up-kbps", "5120"); check(f"올리기 5 MB/s 제한 → 20MB 에 약 4초 ({r['_t']:.1f}초)", r.get("up") == 1 and r["_t"] >= 3.3, short(r))
    r = run("B", "--down-kbps", "10240"); check(f"받기 10 MB/s 제한 → 약 2초 ({r['_t']:.1f}초)", ex(B, "f7/20MB.bin") and r["_t"] >= 1.6, short(r))

if want("F8"):
    print("F8) 순서: 먼저 맞출 폴더 → 작은 파일 먼저")
    w(A, "f8/일반/큰영상.bin", os.urandom(30 * 1024 * 1024), "wb"); w(A, "f8/일반/작은.txt", "s"); w(A, "f8/급함/보고서.txt", "r")
    r = run("A", "--prio", "f8/급함")
    ups = [m.group(1) for m in re.finditer(r"올림\s+(\S+)", r["_out"])]
    check("급함·작은 것이 큰 것보다 먼저 끝남", len(ups) == 3 and ups[-1] == "f8/일반/큰영상.bin" and ups.index("f8/급함/보고서.txt") <= 1, ups)

if want("F9"):
    print("F9) 앞의 파일이 잡혀 있어도 뒤의 파일은 계속")
    for i in range(5): w(A, f"f9/{i}.txt", f"v1 {i}")
    run("A"); time.sleep(1.1)
    for i in range(5): w(A, f"f9/{i}.txt", f"v2 {i}")
    h = lock(lp(A, "f9/0.txt"))
    r = run("A"); check("잡힌 1개만 기다리고 나머지 4개는 올림", r.get("up") == 4 and r.get("waiting") == 1, short(r))
    unlock(h); r = run("A"); check("놓은 뒤 나머지 1개", r.get("up") == 1, short(r))

if want("F10"):
    print("F10) 양쪽에 같은 파일이 이미 있음 (수정 시각만 다름) → 충돌 사본 없이 맞춤")
    w(A, "f10/같음.txt", "same content"); w(A, "f10/다름.txt", "AAAA content")
    nas_put("f10/같음.txt", b"same content"); nas_put("f10/다름.txt", b"BBBB content")
    r = run("A"); check("같음: 그대로 / 다름: 충돌 1", r.get("conflicts") == 1 and r.get("errors") == 0, short(r))
    names = sorted(os.listdir(lp(A, "f10")))
    check("같음.txt 는 하나, 다름.txt 는 둘", names.count("같음.txt") == 1 and len(names) == 3, names)

print("마지막) 다시 돌려도 할 일 없음")
run("A"); run("B")
for pc in "AB":
    r = run(pc); check(pc + " 할 일 없음", quiet(r), short(r))
print(f"\n결과: {cnt['ok']} 통과, {cnt['fail']} 실패")
