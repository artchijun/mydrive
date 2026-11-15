# Mydrive 휴대폰 앱 (안드로이드)

NAS 의 동기화 폴더를 둘러보고, 파일을 열거나 휴대폰에 저장하고, 휴대폰의 파일을 올리는 앱.

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
