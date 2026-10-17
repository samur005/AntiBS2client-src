# AntiBS2client — исходный код

Приватный репозиторий с исходниками клиента AntiBS2client:

| Папка | Что это |
|---|---|
| `android/` | Android-приложение (Kotlin + Jetpack Compose), пакет `fun.antibs.client` |
| `windows/` | Windows-версия (Kotlin + Compose Desktop, JVM 17) + Go-лаунчер + NSIS-инсталлятор |

Готовые сборки публикуются отдельно, здесь только исходный код.

## Android

### Требования
- JDK 17
- Android SDK (compileSdk 36, build-tools 35+), NDK 27.2.12479018 — только для пересборки нативных ядер
- Go 1.23+ и gomobile — только для пересборки нативных ядер

Пути к инструментам на сборочной машине задаются в `android/scripts/env.sh` (по умолчанию `/workspace/...`), поправьте под себя.
Создайте `android/local.properties`:
```
sdk.dir=/путь/к/android-sdk
```

### Подпись
Пароли и ключ в репозитории **не хранятся**. Скопируйте `android/keystore.properties.example` в `android/keystore.properties`
и укажите путь к `.jks` и пароли (или задайте переменные окружения `ANTIBS_STORE_FILE`, `ANTIBS_STORE_PASSWORD`,
`ANTIBS_KEY_ALIAS`, `ANTIBS_KEY_PASSWORD`). Чтобы обновления ставились поверх уже установленного приложения, нужен тот же ключ,
что и у опубликованных APK. Без настроенной подписи debug подписывается стандартным debug-ключом, а release собирается неподписанным.

### Сборка
```bash
cd android
./gradlew :app:assembleRelease        # APK: app/build/outputs/apk/release/ (arm64-v8a, x86_64, universal)
./gradlew :app:testDebugUnitTest      # unit-тесты
```

### Нативные ядра
Готовые бинарники уже лежат в репозитории, чтобы собрать APK без Go/NDK:
- `app/libs/libv2ray.aar` — Xray-core (gomobile, исходники в `native/AndroidLibXrayLite`)
- `app/src/main/jniLibs/<abi>/lib*.so` — sing-box, mihomo, mieru, olcrtc, qwdtt, vpnstack, OpenVPN, OpenConnect

Пересборка: скрипты `android/scripts/build-*.sh` (исходники обёрток лежат в `android/native/*`).
OpenVPN/OpenConnect собираются через `scripts/ref-build-openvpn.sh` / `ref-build-openconnect.sh` (заметки в `scripts/README-vpn-natives.txt`).
Файлы geoip.dat/geosite.dat для `native/AndroidLibXrayLite/assets` не хранятся, их скачивает `gen_assets.sh`.

## Windows

### Требования
- JDK 17 (Linux-хост), Go 1.22+, `rsrc` (`go install github.com/akavel/rsrc@latest`), NSIS (`makensis`), `zip`
- Temurin JDK 17 для Windows x64 (jmods для jlink): `JDK_WIN=/путь/к/jdk`
- Движки для Windows (xray.exe, wintun.dll, geoip.dat, geosite.dat, sing-box.exe, libcronet.dll) из официальных релизов
  Xray-core и sing-box 1.14.2: `ENGINES_WIN=/путь/к/engines`

### Сборка
```bash
cd windows
./gradlew :app:test :app:run          # тесты / запуск на десктопе
bash packaging/package-win.sh         # portable .zip и setup.exe в windows/dist/
```
`package-win.sh` подключает `android/scripts/env.sh` по пути `/workspace/antibs-client/scripts/env.sh`, поправьте при необходимости.

## Чего нет в репозитории
Результатов сборки (`build/`, `out/`, `dist/`, APK/EXE), локальных SDK/JDK, keystore и паролей, реальных подписок и ключей:
в тестовых фикстурах они заменены на фиктивные значения.

## Лицензии сторонних компонентов
См. `android/third_party/` и `windows/app/src/main/resources/licenses/`.
