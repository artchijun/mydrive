#!/bin/bash
# 서버 밖에서(NAS의 다른 프로그램처럼) 빈 폴더를 먼저 만들고 잠시 뒤 파일을 넣었을 때 서버 목록에 바로 잡히는지
D="$HOME/mdt/data/newdir_$(date +%s)"
mkdir "$D"
sleep 1.5
for i in 1 2 3; do echo hi > "$D/f$i.txt"; done
mkdir "$D/sub"; sleep 1; echo x > "$D/sub/g.txt"
sleep 1.5
n=$(curl -s -H 'Authorization: Bearer test' 'localhost:8642/v1/snapshot?root=test' | grep -o "$(basename "$D")/[^\"]*" | sort -u)
echo "$n"
c=$(echo "$n" | grep -c '\.txt')
if [ "$c" = "4" ]; then echo "PASS (4 files seen)"; else echo "FAIL ($c files seen)"; fi
