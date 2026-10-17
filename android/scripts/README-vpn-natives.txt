OpenVPN 2.6.15 + OpenSSL 3.5.4 (NDK 27, API 26):
  configure.ac patched: host *-linux-android* → AC_DEFINE TARGET_ANDROID, no libcap-ng; syshead.h: #undef ENABLE_FEATURE_TUN_PERSIST for TARGET_ANDROID.
  ./configure --host=<triple> --disable-lzo --disable-lz4 --disable-plugins --disable-dco --with-crypto-library=openssl
OpenConnect 9.12 + OpenSSL 3.5.4 + libxml2 2.13.8 (static):
  script.c/tun.c: /bin/sh → /system/bin/sh. --without-gnutls --with-openssl --disable-nls --with-builtin-json --without-{stoken,libpcsclite,gssapi,libproxy,lz4}
Both run as child processes (Android exec from nativeLibraryDir) driven by libvpnstack.so.
