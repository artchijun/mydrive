#!/bin/bash
# mydrived build for the NAS (linux/amd64, static)
cd "$(dirname "$0")"
export PATH=$PATH:/usr/local/go/bin:$HOME/go/bin
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o mydrived . && ls -l mydrived
