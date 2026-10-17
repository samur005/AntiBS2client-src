// AntiBS2client.exe — tiny Windows launcher: starts the bundled JVM (runtime\bin\AntiBS2client-jvm.exe, a renamed
// javaw so the TUN can exclude the app by process name) with the app classpath, and passes our own path so the app
// can register autostart / relaunch itself elevated. A «portable.txt» next to the exe keeps all data in .\data.
package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"unsafe"
)

func msg(text string) {
	u := syscall.NewLazyDLL("user32.dll").NewProc("MessageBoxW")
	t, _ := syscall.UTF16PtrFromString(text)
	c, _ := syscall.UTF16PtrFromString("AntiBS2client")
	u.Call(0, uintptr(unsafe.Pointer(t)), uintptr(unsafe.Pointer(c)), 0x10)
}

func main() {
	self, err := os.Executable()
	if err != nil {
		msg("Не удалось определить путь программы: " + err.Error())
		os.Exit(1)
	}
	dir := filepath.Dir(self)
	jvm := filepath.Join(dir, "runtime", "bin", "AntiBS2client-jvm.exe")
	if _, err := os.Stat(jvm); err != nil {
		msg("Не найдена среда выполнения:\n" + jvm + "\n\nПереустановите программу или распакуйте архив целиком.")
		os.Exit(1)
	}
	args := []string{
		"-Xms32m", "-Xmx512m", "-XX:+UseSerialGC", "-Xss2m",
		"-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8",
		"-Dantibs.launcher=" + self,
		"-Dantibs.appdir=" + dir,
		"-Dantibs.engines=" + filepath.Join(dir, "engines"),
	}
	if _, err := os.Stat(filepath.Join(dir, "portable.txt")); err == nil {
		args = append(args, "-Dantibs.home="+filepath.Join(dir, "data"))
	}
	if extra, err := os.ReadFile(filepath.Join(dir, "jvm-options.txt")); err == nil {
		for _, l := range strings.Split(string(extra), "\n") {
			if l = strings.TrimSpace(l); l != "" && !strings.HasPrefix(l, "#") {
				args = append(args, l)
			}
		}
	}
	args = append(args, "-cp", filepath.Join(dir, "lib", "*"), "net.antibs.client.desktop.MainKt")
	args = append(args, os.Args[1:]...)
	cmd := exec.Command(jvm, args...)
	cmd.Dir = dir
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	if err := cmd.Start(); err != nil {
		msg("Не удалось запустить: " + err.Error())
		os.Exit(1)
	}
	// Do not wait: the launcher exits and the JVM keeps running (single-instance is handled by the app).
}
