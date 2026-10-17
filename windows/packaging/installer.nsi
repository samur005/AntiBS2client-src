; AntiBS2client for Windows — per-user installer (no admin rights needed to install; TUN mode asks for them at run time)
Target amd64-unicode   ; x64-only app → 64-bit installer stub
ManifestDPIAware true
SetCompressor /SOLID lzma
SetCompressorDictSize 64
RequestExecutionLevel user

!ifndef VERSION
  !define VERSION "1.0.50"
!endif
!ifndef SRC
  !define SRC "..\build-win\AntiBS2client"
!endif
!ifndef OUTFILE
  !define OUTFILE "AntiBS2client-${VERSION}-windows-x64-setup.exe"
!endif
!define APP "AntiBS2client"
!define UNINST "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP}"

!include "MUI2.nsh"
!include "FileFunc.nsh"

Name "${APP} ${VERSION}"
OutFile "${OUTFILE}"
InstallDir "$LOCALAPPDATA\Programs\${APP}"
InstallDirRegKey HKCU "Software\${APP}" "InstallDir"
BrandingText "${APP} ${VERSION}"
VIProductVersion "${VERSION}.0"
VIAddVersionKey "ProductName" "${APP}"
VIAddVersionKey "FileDescription" "${APP} setup"
VIAddVersionKey "FileVersion" "${VERSION}"
VIAddVersionKey "ProductVersion" "${VERSION}"
VIAddVersionKey "LegalCopyright" "AntiBS"

!define MUI_ICON "..\launcher\icon.ico"
!define MUI_UNICON "..\launcher\icon.ico"
!define MUI_ABORTWARNING
!define MUI_FINISHPAGE_RUN "$INSTDIR\${APP}.exe"
!define MUI_FINISHPAGE_RUN_TEXT "Запустить ${APP}"
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_LICENSE "license-ru.txt"
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "Russian"
!insertmacro MUI_LANGUAGE "English"

; Stop a running copy from this folder (only our processes — other xray/sing-box on the PC are left alone).
!macro StopApp DIR
  nsExec::Exec `powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command "Get-Process | Where-Object { $$_.Path -and $$_.Path.StartsWith('${DIR}\', [StringComparison]::OrdinalIgnoreCase) } | Stop-Process -Force -ErrorAction SilentlyContinue"`
  ; fallback without PowerShell: the renamed JVM name is unique to this app
  nsExec::Exec `taskkill /F /T /IM AntiBS2client-jvm.exe`
  Sleep 800
!macroend

Section "Install"
  !insertmacro StopApp "$INSTDIR"
  SetOutPath "$INSTDIR"
  ; a clean lib\ so jars of the previous version do not stay on the classpath
  RMDir /r "$INSTDIR\lib"
  RMDir /r "$INSTDIR\runtime"
  File /r /x "portable.txt" /x "jvm-options.txt" /x "data" "${SRC}\*.*"
  WriteUninstaller "$INSTDIR\Uninstall.exe"
  CreateDirectory "$SMPROGRAMS\${APP}"
  CreateShortcut "$SMPROGRAMS\${APP}\${APP}.lnk" "$INSTDIR\${APP}.exe"
  CreateShortcut "$SMPROGRAMS\${APP}\Удалить ${APP}.lnk" "$INSTDIR\Uninstall.exe"
  IfSilent +2
  CreateShortcut "$DESKTOP\${APP}.lnk" "$INSTDIR\${APP}.exe"
  WriteRegStr HKCU "Software\${APP}" "InstallDir" "$INSTDIR"
  WriteRegStr HKCU "${UNINST}" "DisplayName" "${APP}"
  WriteRegStr HKCU "${UNINST}" "DisplayVersion" "${VERSION}"
  WriteRegStr HKCU "${UNINST}" "Publisher" "AntiBS"
  WriteRegStr HKCU "${UNINST}" "DisplayIcon" "$INSTDIR\${APP}.exe"
  WriteRegStr HKCU "${UNINST}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "${UNINST}" "UninstallString" '"$INSTDIR\Uninstall.exe"'
  WriteRegStr HKCU "${UNINST}" "QuietUninstallString" '"$INSTDIR\Uninstall.exe" /S'
  WriteRegDWORD HKCU "${UNINST}" "NoModify" 1
  WriteRegDWORD HKCU "${UNINST}" "NoRepair" 1
  ${GetSize} "$INSTDIR" "/S=0K" $0 $1 $2
  WriteRegDWORD HKCU "${UNINST}" "EstimatedSize" "$0"
SectionEnd

; silent update from the app: «setup.exe /S /RUN» starts the new version when done
Function .onInstSuccess
  ${GetParameters} $0
  ClearErrors
  ${GetOptions} $0 "/RUN" $1
  IfErrors +2
  Exec '"$INSTDIR\${APP}.exe"'
FunctionEnd

Section "Uninstall"
  !insertmacro StopApp "$INSTDIR"
  ; the app restores the system proxy itself; this is the safety net if it was killed while connected
  nsExec::Exec `powershell -NoProfile -NonInteractive -Command "$$p='HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'; if ((Get-ItemProperty $$p).ProxyServer -like '127.0.0.1:*') { Set-ItemProperty $$p ProxyEnable 0 }"`
  DeleteRegValue HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "${APP}"
  Delete "$DESKTOP\${APP}.lnk"
  RMDir /r "$SMPROGRAMS\${APP}"
  RMDir /r "$INSTDIR\lib"
  RMDir /r "$INSTDIR\runtime"
  RMDir /r "$INSTDIR\engines"
  RMDir /r "$INSTDIR\licenses"
  Delete "$INSTDIR\*.*"
  RMDir "$INSTDIR"
  DeleteRegKey HKCU "${UNINST}"
  DeleteRegKey HKCU "Software\${APP}"
  ; settings in %APPDATA%\AntiBS2client are kept (subscriptions, keys) — delete that folder by hand to wipe them
SectionEnd
