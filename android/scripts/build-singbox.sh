#!/usr/bin/env bash
# Builds the sing-box runner (native/singbox-lite, sing-box v1.14.4) as a PIE executable named libsbx.so
# so Android extracts it to nativeLibraryDir (exec allowed there), like libqwdtt.so.
# Protocols: hysteria(1), hysteria2, tuic (with_quic), anytls, ssh, snell (core), naive (with_naive_outbound, cronet).
# Also builds tools/sbx (linux/amd64, no naive) for config-check tests.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "${ENVFILE:-$ROOT/scripts/env.sh}"
GO_DIR="$ROOT/native/singbox-lite"
# cronet (Chromium clang) objects need lld >= 19 (NDK 27 lld rejects R_AARCH64_GOTPCREL32) -> NDK r30
SBX_NDK="${SBX_NDK:-$ANDROID_HOME/ndk/30.0.16248370}"
TC="$SBX_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
cd "$GO_DIR"
export GOFLAGS="-p=4"
BASE_TAGS="with_quic,with_utls,badlinkname,tfogo_checklinkname0"
LDF="-s -w -buildid= -checklinkname=0 -X github.com/sagernet/sing-box/constant.Version=1.14.4"
mkdir -p "$ROOT/tools"
[ -x "$ROOT/tools/sbx" ] || CGO_ENABLED=0 go build -trimpath -tags "$BASE_TAGS" -ldflags="$LDF" -o "$ROOT/tools/sbx" .
"$ROOT/tools/sbx" version
for abi in ${SBX_ABIS:-arm64-v8a x86_64}; do
  case $abi in
    arm64-v8a) GOARCH=arm64; CC="$TC/aarch64-linux-android26-clang";;
    x86_64) GOARCH=amd64; CC="$TC/x86_64-linux-android26-clang";;
  esac
  mkdir -p "$ROOT/app/src/main/jniLibs/$abi"
  GOOS=android GOARCH=$GOARCH CGO_ENABLED=1 CC="$CC" \
    go build -trimpath -buildmode=pie -tags "$BASE_TAGS,with_naive_outbound" -ldflags="$LDF" \
    -o "$ROOT/app/src/main/jniLibs/$abi/libsbx.so" .
  ls -la "$ROOT/app/src/main/jniLibs/$abi/libsbx.so"
done
echo SBX_DONE
