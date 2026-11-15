# Mydrive 휴대폰 앱 (안드로이드)

휴대폰 파일과 NAS 동기화 폴더를 **탭**으로 나란히 여는 파일 탐색기. 화면 모양과 색은 PC 의 Myplorer 와 같게(어두운 화면 기본).

- 탭: 폴더마다 탭 하나 (추가·닫기·고정·복제), 껐다 켜도 그대로. 뒤로·앞으로·위로, 경로 조각, 즐겨찾기(☆), 최근 위치
- 처음 화면: 휴대폰 저장소(사용량 막대) · 다운로드 · NAS 연결 폴더 · 즐겨찾기 · 휴지통
- 길게 눌러 고르기 → 복사·이동·이름·공유·삭제, 더보기(압축, 새 탭, 즐겨찾기, 속성)
- 복사 → 다른 탭에서 붙여넣기: 휴대폰 ↔ NAS 는 올리기·받기로 (폴더째, 같은 이름은 '이름 (2)', 덮어쓰지 않음)
- 지우기: 휴대폰 → 앱 휴지통(`/.Mydrive-trash`, 되살리기), NAS → NAS 휴지통. 지우기 전에 항상 확인
- 검색: 이름 일부·확장자, 지금 위치 아래 전체 (NAS 는 서버 색인으로 `/v1/search`, 서버 0.6.11 이상)
- ZIP: 폴더처럼 열기·풀기·만들기 (한국 윈도 ZIP 이름 CP949 도 읽음)
- 다른 앱의 '공유 → Mydrive' 로 받은 파일을 지금 폴더(휴대폰·NAS)에 저장
- 내장 뷰어: 사진(옆으로 넘기기·두 손가락/두 번 눌러 확대, HEIC·GIF), 동영상(NAS 는 받지 않고 바로 재생), PDF, 텍스트 — ⋮ › 내장 뷰어로 열기 에서 끌 수 있음
- 목록에 휴대폰 사진·동영상 미리보기

- 안드로이드 10 이상, 외부 부품 없음 (Kotlin + 안드로이드 기본 화면 부품). 서명한 판은 100 KB 남짓.
- 연결: 서버 주소(집 안 · 인터넷)와 DSM 계정으로 이 휴대폰 전용 열쇠(토큰)를 받음 (`/v1/pair`). 비밀번호는 저장하지 않음.
- 서버 기능: `/v1/roots`, `/v1/list`(폴더 하나), `/v1/file`(받기·작은 파일 올리기), `/v1/part`(큰 파일 나눠 올리기 — Cloudflare 터널은 요청 하나에 100MB 까지).
- 올리기는 같은 이름이 있으면 덮지 않고(`if=none`) 이름 뒤에 번호를 붙임.

## 빌드·시험

```
export ANDROID_HOME=…/android-sdk          # platforms;android-35, build-tools;35.0.0
MYDRIVED=…/mydrived ./gradlew testDebugUnitTest   # 실제 서버에 대고 주고받기 시험 (리눅스용 서버 실행 파일)
./gradlew assembleRelease                  # keystore.properties 가 있으면 서명
```

## 서명 열쇠

`android/keystore.properties` (저장소에 넣지 않음):

```
storeFile=../_서명열쇠/mydrive-android.jks   (저장소 밖 _서명열쇠 폴더 — .gitignore 됨)
storePassword=…
keyAlias=mydrive
keyPassword=…
```

같은 열쇠로 서명해야 휴대폰에서 새 판으로 업데이트됩니다. 열쇠를 잃으면 앱을 지우고 다시 설치해야 하니 안전한 곳에 두 벌 보관하세요.

## 새 판 내기

`android/release.sh "고친 점"` — versionCode·versionName 을 올리고 커밋한 뒤 실행. 릴리스 이름은 `android-v<판>` 이고
`make_latest=false` 로 올려 PC 앱의 자동 업데이트(최신 릴리스)와 섞이지 않습니다. 앱은 `android-v` 릴리스 중 가장 새 것을 찾아 알려 줍니다.
