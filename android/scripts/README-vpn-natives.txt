OpenVPN 2.6.23 + OpenSSL 3.5.9 (NDK 27, API 26):
  configure.ac patched: host *-linux-android* → AC_DEFINE TARGET_ANDROID, no libcap-ng; syshead.h: #undef ENABLE_FEATURE_TUN_PERSIST for TARGET_ANDROID.
  ./configure --host=<triple> --disable-lzo --disable-lz4 --disable-plugins --disable-dco --with-crypto-library=openssl
OpenConnect 9.21 + OpenSSL 3.5.9 + libxml2 2.13.8 (static):
  script.c/tun.c: /bin/sh → /system/bin/sh. --without-gnutls --with-openssl --disable-nls --with-builtin-json --without-{stoken,libpcsclite,gssapi,libproxy,lz4}
Both run as child processes (Android exec from nativeLibraryDir) driven by libvpnstack.so.

2.0.44 rebuild notes (OpenVPN 2.6.23 / OpenSSL 3.5.9 / OpenConnect 9.21):
  - No autotools on the build box: the generated openvpn `configure` was patched directly (an `*-*-android*)` case before
    `*-*-linux*)` that defines TARGET_ANDROID + TARGET_PREFIX "O", and an empty android case before the libcap-ng check),
    plus `#undef TARGET_ANDROID` added to config.h.in. Copy the tree with `cp -a` so make doesn't try to rerun aclocal.
  - OpenConnect 9.21: script.c / tun.c execl("/bin/sh") → "/system/bin/sh" (as before).
  - No make/pkg-config packages: GNU make from NDK 27.3 (prebuilt/linux-x86_64/bin/make) and scripts/pkg-config-shim.py
    (installed as `pkg-config` on PATH) were used.
