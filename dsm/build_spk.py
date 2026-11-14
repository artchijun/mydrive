# build_spk.py — Mydrive DSM 메뉴 패키지(.spk) 만들기
#   DSM 기본 메뉴에 'Mydrive' 아이콘을 넣고, 누르면 DSM 안의 창으로 관리 콘솔을 엶 (서버는 Container Manager 의 mydrive 컨테이너).
#   결과: 프로젝트 폴더에 "YYYYMMDD HHMM Mydrive DSM 메뉴.spk" (이전 것은 _이전 설치파일 로)
#   설치: DSM → 패키지 센터 → 수동 설치 → 이 파일
import hashlib, io, json, os, shutil, tarfile, time

VERSION = "0.4.0-0001"
HERE = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(HERE)
NOW = int(time.time())

def add_bytes(tar, name, data, mode=0o644):
    ti = tarfile.TarInfo(name); ti.size = len(data); ti.mode = mode; ti.mtime = NOW
    tar.addfile(ti, io.BytesIO(data))

def add_dir(tar, src, arc, exec_files=False):
    for root, dirs, files in os.walk(src):
        dirs.sort()
        rel = os.path.relpath(root, src)
        base = arc if rel == "." else arc + "/" + rel.replace("\\", "/")
        ti = tarfile.TarInfo(base); ti.type = tarfile.DIRTYPE; ti.mode = 0o755; ti.mtime = NOW
        tar.addfile(ti)
        for f in sorted(files):
            data = open(os.path.join(root, f), "rb").read()
            if exec_files: data = data.replace(b"\r\n", b"\n")   # 셸 스크립트는 LF
            add_bytes(tar, base + "/" + f, data, 0o755 if exec_files else 0o644)

UI_CONFIG = {
    "Mydrive.js": {
        "SYNO.SDS.Mydrive.Application": {
            "type": "app", "title": "Mydrive", "desc": "Mydrive 관리 콘솔 — 동기화 기록, 동기화 폴더, 버전 보관, 접속한 PC",
            "icon": "images/mydrive_{0}.png", "texts": "texts",
            "allUsers": False, "allowMultiInstance": False,
            "appWindow": "SYNO.SDS.Mydrive.AppWindow", "depend": ["SYNO.SDS.Mydrive.AppWindow"],
        },
        "SYNO.SDS.Mydrive.AppWindow": {"type": "lib", "title": "Mydrive", "icon": "images/mydrive_{0}.png", "texts": "texts", "depend": []},
    }
}
INDEX_CONF = {"app": "SYNO.SDS.Mydrive.Application", "title": "Mydrive", "desc": "Mydrive 관리 콘솔",
              "stringset": "texts", "keywords": ["Mydrive", "mydrive", "동기화", "sync"]}

def main():
    # package.tgz : ui/ (Mydrive.js, images, config, index.conf, texts)
    pk = io.BytesIO()
    with tarfile.open(fileobj=pk, mode="w:gz", format=tarfile.USTAR_FORMAT) as t:
        add_dir(t, os.path.join(HERE, "ui"), "ui")
        add_bytes(t, "ui/config", json.dumps(UI_CONFIG, ensure_ascii=False, indent=4).encode("utf-8"))
        add_bytes(t, "ui/index.conf", json.dumps(INDEX_CONF, ensure_ascii=False, indent=4).encode("utf-8"))
        for lang in ("enu", "krn"):
            add_bytes(t, f"ui/texts/{lang}/strings", b'[app]\ntitle = "Mydrive"\n')
    pkg = pk.getvalue()
    info = "\n".join([
        'package="Mydrive"', f'version="{VERSION}"', 'os_min_ver="7.0-40000"', 'displayname="Mydrive"',
        'description="Mydrive 관리 콘솔을 DSM 기본 메뉴에서 DSM 창으로 엽니다. 동기화 기록, 동기화 폴더, 버전 보관, 접속한 PC를 관리합니다. (서버 자체는 Container Manager의 mydrive 컨테이너로 돌아갑니다)"',
        'maintainer="Junhee"', 'arch="noarch"', 'dsmuidir="ui"', 'dsmappname="SYNO.SDS.Mydrive.Application"',
        'ctl_stop="no"', 'thirdparty="yes"', f'checksum="{hashlib.md5(pkg).hexdigest()}"', ""])
    out = io.BytesIO()
    with tarfile.open(fileobj=out, mode="w", format=tarfile.USTAR_FORMAT) as t:
        add_bytes(t, "INFO", info.encode("utf-8"))
        add_bytes(t, "package.tgz", pkg)
        for icon in ("PACKAGE_ICON.PNG", "PACKAGE_ICON_256.PNG"):
            add_bytes(t, icon, open(os.path.join(HERE, icon), "rb").read())
        add_dir(t, os.path.join(HERE, "scripts"), "scripts", exec_files=True)
        add_dir(t, os.path.join(HERE, "conf"), "conf")
    old = os.path.join(PROJ, "_이전 설치파일")
    for f in os.listdir(PROJ):
        if f.endswith("Mydrive DSM 메뉴.spk"):
            os.makedirs(old, exist_ok=True); shutil.move(os.path.join(PROJ, f), os.path.join(old, f))
    name = time.strftime("%Y%m%d %H%M") + " Mydrive DSM 메뉴.spk"
    open(os.path.join(PROJ, name), "wb").write(out.getvalue())
    print("OK", name, len(out.getvalue()))

if __name__ == "__main__":
    main()
