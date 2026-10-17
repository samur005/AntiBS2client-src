#!/usr/bin/env bash
# Builds the linux/amd64 xray binary from the exact xray-core version pinned by AndroidLibXrayLite (for config tests)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/env.sh"
cd "$ROOT/native/AndroidLibXrayLite"
mkdir -p "$ROOT/tools"
CGO_ENABLED=0 GOFLAGS="-p=4" go build -trimpath -ldflags="-s -w" -o "$ROOT/tools/xray" github.com/xtls/xray-core/main
"$ROOT/tools/xray" version | head -1
echo XRAYBIN_DONE
