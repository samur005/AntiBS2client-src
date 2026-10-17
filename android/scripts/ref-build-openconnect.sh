#!/usr/bin/env bash
set -euo pipefail
source /workspace/antibs-client/scripts/env.sh
TC=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64
export PATH=$TC/bin:$PATH
B=/workspace/ref/oc
for abi in arm64-v8a x86_64; do
  case $abi in arm64-v8a) HOST=aarch64-linux-android;; x86_64) HOST=x86_64-linux-android;; esac
  SSL=/workspace/ref/ovpn/out/$abi
  P=$B/out/$abi; mkdir -p $P
  export CC=$TC/bin/${HOST}26-clang AR=$TC/bin/llvm-ar RANLIB=$TC/bin/llvm-ranlib STRIP=$TC/bin/llvm-strip
  if [ ! -f $P/lib/libxml2.a ]; then
    rm -rf $B/xml-$abi; cp -a $B/libxml2-2.13.8 $B/xml-$abi; cd $B/xml-$abi
    ./configure --host=$HOST --prefix=$P --enable-static --disable-shared --without-python --without-lzma --without-zlib --without-iconv --without-icu --without-http --without-ftp --without-modules --without-threads >/dev/null
    make -j8 >$B/xml-$abi.log 2>&1; make install >/dev/null
  fi
  rm -rf $B/oc-$abi; cp -a $B/openconnect-9.12 $B/oc-$abi; cd $B/oc-$abi
  echo '#!/bin/sh' > $P/vpnc-script; chmod +x $P/vpnc-script
  PKG_CONFIG_PATH=$P/lib/pkgconfig:$SSL/lib/pkgconfig PKG_CONFIG_LIBDIR=$P/lib/pkgconfig:$SSL/lib/pkgconfig ./configure --host=$HOST --prefix=$P \
     --without-gnutls --with-openssl --disable-nls --without-stoken --without-libpcsclite --without-gssapi --without-libproxy --without-lz4 \
     --with-builtin-json --without-java --disable-docs --with-vpnc-script=/system/bin/true --enable-static --disable-shared \
     OPENSSL_CFLAGS="-I$SSL/include" OPENSSL_LIBS="-L$SSL/lib -lssl -lcrypto" LIBXML2_CFLAGS="-I$P/include/libxml2" LIBXML2_LIBS="-L$P/lib -lxml2 -lm" \
     ZLIB_CFLAGS=" " ZLIB_LIBS="-lz" CFLAGS="-O2 -fPIE" LDFLAGS="-pie" > $B/occonf-$abi.log 2>&1 || { tail -30 $B/occonf-$abi.log; exit 1; }
  make -j8 openconnect >$B/oc-$abi.log 2>&1 || { tail -40 $B/oc-$abi.log; exit 1; }
  ls -la openconnect .libs/openconnect 2>/dev/null || true
done
echo OC_DONE
