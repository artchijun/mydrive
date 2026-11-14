#!/bin/bash
# DSM 계정 연결·콘솔 로그인 기본 동작 (WSL 시험 서버: DSM 없음 → 실패 경로 확인)
S=localhost:8642
echo "pair(DSM 없음): $(curl -s -X POST -d '{"account":"x","passwd":"y"}' $S/v1/pair)"
echo "pair(빈 값): $(curl -s -X POST -d '{}' $S/v1/pair)"
echo "공용 토큰: $(curl -s -o /dev/null -w '%{http_code}' -H 'Authorization: Bearer test' $S/v1/roots)"
echo "틀린 토큰: $(curl -s -o /dev/null -w '%{http_code}' -H 'Authorization: Bearer nope' $S/v1/roots)"
echo "빈 토큰: $(curl -s -o /dev/null -w '%{http_code}' $S/v1/roots)"
echo "devices: $(curl -s -H 'Authorization: Bearer admintest' $S/v1/admin/devices)"
echo "whoami(관리 토큰): $(curl -s -H 'Authorization: Bearer admintest' $S/v1/admin/whoami)"
echo "whoami(가짜 쿠키): $(curl -s -H 'X-Mydrive-Console: 1' --cookie 'id=bogus' $S/v1/admin/whoami)"
echo "whoami(쿠키, 머리글 없음): $(curl -s --cookie 'id=bogus' $S/v1/admin/whoami)"
for i in 1 2 3 4 5 6; do curl -s -o /dev/null -X POST -d '{"account":"x","passwd":"y"}' $S/v1/pair; done
echo "pair 반복 뒤: $(curl -s -X POST -d '{"account":"x","passwd":"y"}' $S/v1/pair)"
