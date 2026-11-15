#!/usr/bin/env bash
# 휴대폰 앱 새 판 내기: 서명한 APK 를 만들어 GitHub 릴리스(android-v<판>)로 올림
#   - make_latest=false : PC 앱의 자동 업데이트는 '최신 릴리스(latest)'를 보므로 휴대폰 판이 그 자리를 차지하지 않게
#   - 필요한 것: ANDROID_HOME, android/keystore.properties (서명 열쇠 — 저장소에 넣지 않음), gh (로그인됨)
#   사용: android/release.sh "고친 점 한 줄"
set -euo pipefail
cd "$(dirname "$0")"
notes="${1:-}"
[ -f keystore.properties ] || { echo "keystore.properties 가 없습니다 (서명 열쇠)"; exit 1; }
[ -z "$(git status --porcelain .)" ] || { echo "커밋하지 않은 변경이 있습니다"; exit 1; }
ver=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)
./gradlew -q --no-daemon testDebugUnitTest assembleRelease
apk="app/build/outputs/apk/release/app-release.apk"
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify "$apk"
out="build/Mydrive-android-$ver.apk"; mkdir -p build; cp "$apk" "$out"
tag="android-v$ver"
if gh release view "$tag" >/dev/null 2>&1; then
  gh release upload "$tag" "$out" --clobber
else
  gh api repos/{owner}/{repo}/releases -f tag_name="$tag" -f target_commitish="$(git rev-parse HEAD)" \
     -f name="Mydrive 휴대폰 앱 $ver" -f body="$notes" -f make_latest=false >/dev/null
  gh release upload "$tag" "$out"
fi
echo "OK $tag  $(stat -c %s "$out") bytes  https://github.com/$(gh repo view --json nameWithOwner -q .nameWithOwner)/releases/tag/$tag"
