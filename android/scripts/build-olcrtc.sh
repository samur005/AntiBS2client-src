#!/usr/bin/env bash
# 1.0.41: olcRTC client (openlibrecommunity/olcrtc mobile API, WTFPL) as a PIE executable named libolcrtc.so
# so Android extracts it to nativeLibraryDir (exec allowed there), like libqwdtt.so / libmieru.so.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/env.sh"
GO_DIR="$ROOT/native/olcrtc-lite"
TC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
cd "$GO_DIR"
export GOFLAGS="-p=4 -mod=mod"
LDF="-s -w -buildid= -checklinkname=0"
for abi in ${OLC_ABIS:-arm64-v8a x86_64}; do
  case $abi in
    arm64-v8a) GOARCH=arm64; CC="$TC/aarch64-linux-android26-clang";;
    x86_64) GOARCH=amd64; CC="$TC/x86_64-linux-android26-clang";;
  esac
  mkdir -p "$ROOT/app/src/main/jniLibs/$abi"
  GOOS=android GOARCH=$GOARCH CGO_ENABLED=1 CC="$CC" \
    go build -trimpath -buildmode=pie -ldflags="$LDF" -o "$ROOT/app/src/main/jniLibs/$abi/libolcrtc.so" .
  ls -la "$ROOT/app/src/main/jniLibs/$abi/libolcrtc.so"
done
echo OLCRTC_DONE
