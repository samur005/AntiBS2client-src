#!/usr/bin/env bash
# Builds the qWDTT Go client (copied from SpaceNeuroX/proxy-turn-vk-android go_client, GPL-3.0)
# as a PIE executable named libqwdtt.so so Android extracts it to nativeLibraryDir (exec allowed there).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/env.sh"
GO_DIR="$ROOT/native/qwdtt-client"
TC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
cd "$GO_DIR"
[ -f go.sum ] || go mod tidy -e
export GOFLAGS="-p=4"
for abi in arm64-v8a x86_64; do
  case $abi in
    arm64-v8a) GOARCH=arm64; CC="$TC/aarch64-linux-android24-clang";;
    x86_64) GOARCH=amd64; CC="$TC/x86_64-linux-android24-clang";;
  esac
  mkdir -p "$ROOT/app/src/main/jniLibs/$abi"
  GOOS=android GOARCH=$GOARCH CGO_ENABLED=1 CC="$CC" \
    go build -trimpath -ldflags="-s -w -checklinkname=0" -o "$ROOT/app/src/main/jniLibs/$abi/libqwdtt.so" .
  ls -la "$ROOT/app/src/main/jniLibs/$abi/libqwdtt.so"
done
echo QWDTT_DONE
