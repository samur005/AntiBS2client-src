#!/usr/bin/env bash
# Builds the Windows x64 package on Linux: app jars (Gradle) + jlink runtime from the Temurin Windows JDK +
# Go launcher + official engines → build-win/AntiBS2client, then a portable zip and an NSIS setup.exe in dist/.
# Inputs (download + verify once): /workspace/win-jdk/jdk (Temurin 17.0.20.1+1 windows x64),
# /workspace/win-engines/win (Xray-windows-64.zip + sing-box-1.14.2-windows-amd64.zip, checksums verified).
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
VERSION=$(sed -n 's/^val appVersion = "\(.*\)"/\1/p' app/build.gradle.kts)
JDK=${JDK_WIN:-/workspace/win-jdk/jdk}
ENG=${ENGINES_WIN:-/workspace/win-engines/win}
OUT=$ROOT/build-win/AntiBS2client
DIST=$ROOT/dist
source /workspace/antibs-client/scripts/env.sh

./gradlew -q :app:test :app:stageWindowsLibs

# launcher
( cd launcher && /tmp/gobin/rsrc -arch amd64 -ico icon.ico -manifest app.manifest -o rsrc_windows_amd64.syso \
  && GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -ldflags "-s -w -H=windowsgui" -o AntiBS2client.exe . )

rm -rf "$ROOT/build-win" && mkdir -p "$OUT/engines" "$OUT/licenses"
jlink --module-path "$JDK/jmods" \
  --add-modules java.base,java.desktop,java.logging,java.management,java.naming,java.net.http,java.prefs,java.scripting,java.sql,java.xml,java.datatransfer,jdk.unsupported,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.crypto.mscapi,jdk.charsets,jdk.localedata,jdk.zipfs,jdk.accessibility,jdk.net,java.security.jgss,jdk.management \
  --include-locales=en,ru --strip-debug --no-header-files --no-man-pages --compress=2 --output "$OUT/runtime"
# renamed javaw: lets the TUN exclude the app's own traffic by process name
cp "$OUT/runtime/bin/javaw.exe" "$OUT/runtime/bin/AntiBS2client-jvm.exe"
cp launcher/AntiBS2client.exe "$OUT/"
cp -r app/build/win/lib "$OUT/lib"
cp "$ENG"/{xray.exe,wintun.dll,geoip.dat,geosite.dat,sing-box.exe,libcronet.dll} "$OUT/engines/"
TMP=$(mktemp -d); unzip -q -o /workspace/win-engines/Xray-windows-64.zip LICENSE LICENSE-Wintun -d "$TMP"
cp "$TMP/LICENSE" "$OUT/licenses/Xray-core-MPL-2.0.txt"; cp "$TMP/LICENSE-Wintun" "$OUT/licenses/Wintun-LICENSE.txt"; rm -rf "$TMP"
cp "$ENG/LICENSE" "$OUT/licenses/sing-box-GPL-3.0-NOTICE.txt"
cp app/src/main/resources/licenses/* "$OUT/licenses/"
cp "$OUT/runtime/legal/java.base/LICENSE" "$OUT/licenses/OpenJDK-GPLv2-CPE.txt"
cp packaging/license-ru.txt "$OUT/ПРОЧТИ.txt"
sed -i 's/$/\r/' "$OUT/ПРОЧТИ.txt"

mkdir -p "$DIST"
ZIP="$DIST/AntiBS2client-$VERSION-windows-x64-portable.zip"
SETUP="$DIST/AntiBS2client-$VERSION-windows-x64-setup.exe"
rm -f "$ZIP" "$SETUP"
# portable: data stays in .\data next to the exe
STAGE=$(mktemp -d); cp -r "$OUT" "$STAGE/AntiBS2client"
printf 'Портативный режим: настройки хранятся в папке data рядом с программой.\r\n' > "$STAGE/AntiBS2client/portable.txt"
( cd "$STAGE" && zip -q -9 -r "$ZIP" AntiBS2client ); rm -rf "$STAGE"
( cd packaging && makensis -V2 -DVERSION="$VERSION" -DSRC="$OUT" -DOUTFILE="$SETUP" installer.nsi )
( cd "$DIST" && sha256sum "$(basename "$ZIP")" "$(basename "$SETUP")" > "SHA256SUMS-$VERSION.txt" && cat "SHA256SUMS-$VERSION.txt" && ls -la )
