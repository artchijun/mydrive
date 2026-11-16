# Mydrive

Synology Drive Client를 대신하는 NAS ↔ Windows 폴더 동기화 프로그램.

- **서버** `mydrived` (Go): NAS의 Docker 컨테이너에서 돌며, 공유 폴더 색인·변경 알림·파일 주고받기·이전 버전·휴지통을 맡음. 포트 8642.
- **PC 앱** `Mydrive.exe` (C++ Win32): 트레이 앱. 양방향 동기화, 탐색기 연동(cfapi: 필요할 때 받기, 상태 아이콘, 오른쪽 클릭 메뉴), 설정 창.
- **설치 파일** `setup.exe`: 관리자 권한 없이 `%LOCALAPPDATA%\Programs\Mydrive`에 설치.

## 폴더

| 폴더 | 내용 |
|---|---|
| `server/` | mydrived 소스 (`main.go` API, `root.go` 색인, `versions.go` 이전 버전, `dsm.go` DSM 계정 연결, `admin.go` 관리 콘솔, `update.go` NAS 경유 새 판 배포 — 예비용, 지금 앱은 GitHub에서 받음) |
| `server/web/` | 관리 콘솔 화면 (`/admin`) |
| `server/deploy/` | Container Manager 설정 예시 `compose.example.yaml` (실제 `compose.yaml`은 토큰이 있어 올리지 않음) |
| `client/src/` | PC 앱 (`app.cpp` 본체·설정 창, `engine.cpp` 동기화 엔진, `cloud.cpp` 탐색기 연동, `http.cpp` 통신, `popup.inc` 트레이 창, `tools.inc` 오른쪽 클릭 메뉴, `update.inc` 자동 업데이트, `setup.cpp` 설치 파일, `version.h` 판 번호) |
| `client/res/` | 아이콘, 리소스 |
| `client/*.py, *.ps1` | 시험·화면 캡처 스크립트 |

## 빌드

PC 앱 (Visual Studio 2022 Build Tools 필요):

```powershell
cd client
powershell -File package.ps1     # build\ 에 Mydrive.exe, setup.exe → 프로젝트 폴더에 "YYYYMMDD HHMM Mydrive 설치.exe"
```

서버 (Go 1.22+):

```powershell
cd server
$env:GOOS='linux'; $env:GOARCH='amd64'; $env:CGO_ENABLED='0'
go build -trimpath -ldflags '-s -w' -o build\mydrived .
```

## 서버 배포

1. `build\mydrived`를 NAS의 `/docker/mydrive/bin/`에 넣음 (File Station).
2. `POST /v1/admin/restart` (관리 토큰) → 컨테이너가 다시 켜지면서 새 실행 파일을 씀.
3. 확인: `GET /v1/ping` → `mydrived 0.5.0`

## PC 앱 업데이트 (자동)

새 판은 이 저장소의 **GitHub Releases**에서 받음 (로그인 필요 없음).

1. `client/src/version.h`의 판 번호를 올리고 커밋.
2. `powershell -File client\release.ps1 -Notes "바뀐 점 한 줄"` → 빌드 → push → 릴리스 `v<판>`을 만들고 `Mydrive-setup-<판>.exe`를 붙임.
3. 각 PC의 Mydrive가 6시간마다 `api.github.com/repos/artchijun/mydrive/releases/latest`를 확인 → 새 판이면 받아서 GitHub가 알려 준 SHA-256과 맞춰 보고 `/quiet /tray`로 설치 → 다시 켬.
   - 설정 → 업데이트: [지금 확인] [지금 업데이트], "새 판이 나오면 자동으로 설치"(기본 켬)
   - 자동 설치는 동기화가 쉬고 있을 때만 함. 끄면 알림만 띄우고 트레이 메뉴에 "새 판 설치"가 생김.

## 시험 스크립트

토큰은 스크립트에 적지 않음. 실행 전에 환경 변수로:

```powershell
$env:MYDRIVE_TOKEN = '<동기화 토큰>'
python client\test_cloud.py
```

## 휴대폰 사진 앱 Myphoto (`photo/`)

Synology Photos 휴대폰 앱을 대신하는 Android 앱. 서버는 Synology Photos 그대로 씀 (공유 공간 `SYNO.FotoTeam.*`).

- 사진(일·월·년 묶음)·폴더·인물·장소 보기, 뷰어(정보·즐겨찾기·저장·공유·지우기)
- 자동 백업: 고른 폴더를 NAS 폴더로 (원본 그대로, 위치 정보 유지). 이미 올라간 것은 이름·크기로 알아보고 건너뜀
- 공간 확보: 휴대폰 사진을 서버와 대조해 서버에 있는 것만 휴대폰에서 지움. 대조 범위는 백업 폴더(올릴 곳)만 — 기본, 빠름 — 또는 서버 전체. 나란히 비교·이름 찾기·서버에 없음 목록
- 사진 화면: 날짜별 장수를 먼저 받아 전체 자리를 잡아 두고 보이는 곳만 받음 → 오른쪽 막대(연도 눈금·날짜 말풍선)를 끌어 어느 해·달로든 바로. 연도에서 누르면 그 달, 월에서 누르면 그날(두 손가락으로도). 위에 지금 보는 해·달·날 제목. 백업 상태(백업됨·서버에만·백업 안 됨) 표시와 거르기
- 인물·장소·폴더의 사진도 같은 격자(막대·연도/월/일). 장소는 [목록 | 지도] — 지도(OpenStreetMap)에 장소마다 동그라미, 눌러서 사진 보기. 위로 당겼다 놓으면 새로 고침
- 폴더 보기에서 길게 눌러 폴더째 지우기, 파일별 백업 기록에서 골라 휴대폰에서 지우기, 동영상은 어디서나 ▶ 로 구분
- 새 판: 앱이 하루 두 번까지 GitHub 릴리스를 확인 → 앱 안에서 받아(SHA-256 확인) 설치 창을 띄움
- 중복 정리(서버): 크기·찍은 시각이 같고 내용 일부까지 같은 것 + 줄인 사본. 남길 것은 규칙 순서(선호 폴더·해상도·크기·이름·날짜)로 고름
- 접속: 집(192.168.0.6:5000)이 닿으면 바로, 아니면 Mydrive 서버(`/webapi` 중계, Cloudflare 터널). 밖에서 90MB 넘는 파일은 32MB 조각으로 나눠 올림
- 빌드: `cd photo; ./gradlew assembleRelease` (서명 열쇠 `photo/keystore.properties` 는 저장소에 없음). 기기 로그인을 지우지 않고 출시판을 시험하려면 `-PshrinkTest`(디버그 열쇠로 서명)
- 새 판: 이 저장소의 릴리스 중 `photo-v<판>` (앱 설정 → 새 판 확인)
