#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/env.sh"
GO_DIR="$ROOT/native/mieru-lite"
TC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
cd "$GO_DIR"
export GOFLAGS="-p=4"
LDF="-s -w -buildid="
mkdir -p "$ROOT/tools"
CGO_ENABLED=0 go build -trimpath -ldflags="$LDF" -o "$ROOT/tools/mieru" .
"$ROOT/tools/mieru" version || true
for abi in ${MIERU_ABIS:-arm64-v8a x86_64}; do
  case $abi in
    arm64-v8a) GOARCH=arm64; CC="$TC/aarch64-linux-android26-clang";;
    x86_64) GOARCH=amd64; CC="$TC/x86_64-linux-android26-clang";;
  esac
  mkdir -p "$ROOT/app/src/main/jniLibs/$abi"
  GOOS=android GOARCH=$GOARCH CGO_ENABLED=1 CC="$CC" \
    go build -trimpath -buildmode=pie -ldflags="$LDF" -o "$ROOT/app/src/main/jniLibs/$abi/libmieru.so" .
  ls -la "$ROOT/app/src/main/jniLibs/$abi/libmieru.so"
done
echo MIERU_DONE
