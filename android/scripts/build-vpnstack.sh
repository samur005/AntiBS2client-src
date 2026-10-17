#!/usr/bin/env bash
# 1.0.41: libvpnstack.so (native/vpnstack-lite: OpenVPN management client / OpenConnect script-tun → gVisor netstack → SOCKS5)
# plus the prebuilt OpenVPN 2.6.15 (TARGET_ANDROID, OpenSSL 3.5.4) and OpenConnect 9.12 (OpenSSL, libxml2) executables
# from /workspace/ref/ovpn and /workspace/ref/oc (see notes in scripts/README-vpn-natives.txt).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/env.sh"
TC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
cd "$ROOT/native/vpnstack-lite"
export GOFLAGS="-p=4 -mod=mod"
for abi in arm64-v8a x86_64; do
  case $abi in
    arm64-v8a) GOARCH=arm64; CC="$TC/aarch64-linux-android26-clang";;
    x86_64) GOARCH=amd64; CC="$TC/x86_64-linux-android26-clang";;
  esac
  D="$ROOT/app/src/main/jniLibs/$abi"; mkdir -p "$D"
  GOOS=android GOARCH=$GOARCH CGO_ENABLED=1 CC="$CC" go build -trimpath -buildmode=pie -ldflags="-s -w -buildid=" -o "$D/libvpnstack.so" .
  [ -f /workspace/ref/ovpn/ovpn-$abi/src/openvpn/openvpn ] && "$TC/llvm-strip" -o "$D/libopenvpn.so" /workspace/ref/ovpn/ovpn-$abi/src/openvpn/openvpn
  [ -f /workspace/ref/oc/oc-$abi/openconnect ] && "$TC/llvm-strip" -o "$D/libopenconnect.so" /workspace/ref/oc/oc-$abi/openconnect
  ls -la "$D"/libvpnstack.so "$D"/libopenvpn.so "$D"/libopenconnect.so
done
