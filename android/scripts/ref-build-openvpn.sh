#!/usr/bin/env bash
set -euo pipefail
source /workspace/antibs-client/scripts/env.sh
export ANDROID_NDK_ROOT=$ANDROID_NDK_HOME
TC=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64
export PATH=$TC/bin:$PATH
B=/workspace/ref/ovpn
for abi in arm64-v8a x86_64; do
  case $abi in arm64-v8a) OT=android-arm64; HOST=aarch64-linux-android;; x86_64) OT=android-x86_64; HOST=x86_64-linux-android;; esac
  P=$B/out/$abi; mkdir -p $P
  if [ ! -f $P/lib/libcrypto.a ]; then
    rm -rf $B/ssl-$abi; cp -r $B/openssl-3.5.9 $B/ssl-$abi; cd $B/ssl-$abi
    ./Configure $OT -D__ANDROID_API__=26 no-shared no-tests no-docs no-apps no-engine no-legacy no-quic --prefix=$P --libdir=lib >/dev/null
    make -j${JOBS:-8} build_libs >$B/ssl-$abi.log 2>&1; make install_dev >/dev/null
  fi
  rm -rf $B/ovpn-$abi; cp -r $B/openvpn-2.6.23 $B/ovpn-$abi; cd $B/ovpn-$abi
  CC=$TC/bin/${HOST}26-clang ./configure --host=$HOST --disable-lzo --disable-lz4 --disable-plugins --disable-dco --disable-plugin-auth-pam --disable-plugin-down-root \
     --enable-iproute2=no --with-crypto-library=openssl OPENSSL_CFLAGS="-I$P/include" OPENSSL_LIBS="-L$P/lib -lssl -lcrypto" \
     CFLAGS="-O2 -fPIE" LDFLAGS="-pie" >/dev/null
  grep -n "TARGET_ANDROID\|TARGET_LINUX" config.h || true
  sed -i "s/^#define ENABLE_FEATURE_TUN_PERSIST 1/\/* no tun persist on android *\//" config.h
  make -j${JOBS:-8} >$B/make-$abi.log 2>&1 || { tail -40 $B/make-$abi.log; exit 1; }
  ls -la src/openvpn/openvpn
done
echo OVPN_DONE
