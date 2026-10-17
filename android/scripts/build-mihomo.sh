#!/usr/bin/env bash
# Builds native/mihomo-lite (libmhx.so): mihomo v1.19.32 outbounds (ssr, snell v1–v5, trusttunnel, shadowquic)
# + an HTTP/3 CONNECT client (quic-go http3, Exclave-style), each behind a loopback SOCKS5 slot.
# PIE executable named lib*.so so Android extracts it to nativeLibraryDir. Also builds tools/mhx (linux) for tests.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/env.sh"
GO_DIR="$ROOT/native/mihomo-lite"
TC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
cd "$GO_DIR"
export GOFLAGS="-p=4"
LDF="-s -w -buildid= -checklinkname=0"
mkdir -p "$ROOT/tools"
CGO_ENABLED=0 go build -trimpath -ldflags="$LDF" -o "$ROOT/tools/mhx" .
"$ROOT/tools/mhx" version
for abi in ${MHX_ABIS:-arm64-v8a x86_64}; do
  case $abi in
    arm64-v8a) GOARCH=arm64; CC="$TC/aarch64-linux-android26-clang";;
    x86_64) GOARCH=amd64; CC="$TC/x86_64-linux-android26-clang";;
  esac
  mkdir -p "$ROOT/app/src/main/jniLibs/$abi"
  GOOS=android GOARCH=$GOARCH CGO_ENABLED=1 CC="$CC" \
    go build -trimpath -buildmode=pie -ldflags="$LDF" -o "$ROOT/app/src/main/jniLibs/$abi/libmhx.so" .
  ls -la "$ROOT/app/src/main/jniLibs/$abi/libmhx.so"
done
echo MHX_DONE
