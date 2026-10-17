#!/usr/bin/env bash
# Build hev-socks5-tunnel JNI lib for Kotlin class net.antibs.client.service.TProxyService
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/env.sh"
SRC="$ROOT/native/hev-socks5-tunnel"
ABIS="arm64-v8a x86_64"
TMPDIR=$(mktemp -d)
mkdir -p "$TMPDIR/jni"
ln -s "$SRC" "$TMPDIR/jni/hev-socks5-tunnel"
echo 'include $(call all-subdir-makefiles)' > "$TMPDIR/jni/Android.mk"
cd "$TMPDIR"
"$NDK_HOME/ndk-build" \
    NDK_PROJECT_PATH=. \
    APP_BUILD_SCRIPT=jni/Android.mk \
    "APP_ABI=$ABIS" \
    APP_PLATFORM=android-24 \
    NDK_LIBS_OUT="$TMPDIR/libs" \
    NDK_OUT="$TMPDIR/obj" \
    "APP_CFLAGS=-O3 -DPKGNAME=net/antibs/client/service" \
    "APP_LDFLAGS=-Wl,--build-id=none -Wl,--hash-style=gnu" -j4
for abi in $ABIS; do
  mkdir -p "$ROOT/app/src/main/jniLibs/$abi"
  cp "$TMPDIR/libs/$abi/libhev-socks5-tunnel.so" "$ROOT/app/src/main/jniLibs/$abi/"
done
rm -rf "$TMPDIR"
echo HEVTUN_DONE
