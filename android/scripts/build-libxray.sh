#!/usr/bin/env bash
# Builds libv2ray.aar (Xray-core v26.10.31 via AndroidLibXrayLite) for arm64 + x86_64
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "${ENVFILE:-$ROOT/scripts/env.sh}"
cd "$ROOT/native/AndroidLibXrayLite"
export GOFLAGS="-p=4"
gomobile init
go mod download
mkdir -p "$ROOT/app/libs"
gomobile bind -v -androidapi 24 -target android/arm64,android/amd64 -trimpath \
  -ldflags='-s -w -buildid= -checklinkname=0' -o "$ROOT/app/libs/libv2ray.aar" ./
ls -la "$ROOT/app/libs/libv2ray.aar"
echo LIBXRAY_DONE
