#!/bin/bash
# run the freshly built mydrived in WSL for local tests (localhost:8642)
cd "$(dirname "$0")"
pkill -x mydrived 2>/dev/null; for i in 1 2 3 4 5; do pgrep -x mydrived >/dev/null || break; sleep 0.5; done
sleep 0.5
mkdir -p "$HOME/mdt/data" "$HOME/mdt/state" "$HOME/mdt/other"
cp mydrived "$HOME/mdt/mydrived" && chmod +x "$HOME/mdt/mydrived"
cd "$HOME/mdt"
MYDRIVE_ROOTS="test=$HOME/mdt/data" MYDRIVE_TOKEN=test MYDRIVE_ADMIN_TOKEN=admintest MYDRIVE_STATE="$HOME/mdt/state" MYDRIVE_NAS_BASE="$HOME/mdt" \
  nohup "$HOME/mdt/mydrived" > "$HOME/mdt/server.log" 2>&1 &
sleep 1
curl -s localhost:8642/v1/ping
