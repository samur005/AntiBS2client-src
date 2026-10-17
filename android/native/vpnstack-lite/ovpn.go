package main

import (
	"bufio"
	"encoding/base64"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

func q(s string) string {
	s = strings.ReplaceAll(s, `\`, `\\`)
	return `"` + strings.ReplaceAll(s, `"`, `\"`) + `"`
}

type ovpnState struct {
	mu    sync.Mutex
	ip4   string
	mask  string
	ip6   string
	mtu   int
	dns   []string
	stack *Stack
}

func runOpenVPN(c cfg) {
	os.Remove(c.Mgmt)
	ln, err := net.ListenUnix("unix", &net.UnixAddr{Name: c.Mgmt, Net: "unix"})
	if err != nil {
		fail("management socket: %v", err)
	}
	defer os.Remove(c.Mgmt)
	args := []string{"--config", c.Config,
		"--management", c.Mgmt, "unix", "--management-client", "--management-query-passwords", "--management-hold",
		"--machine-readable-output", "--ifconfig-nowarn", "--script-security", "0", "--verb", "3", "--suppress-timestamps",
		"--setenv", "IV_GUI_VER", "AntiBS2client 1.0.41", "--setenv", "IV_PLAT_VER", "Android"}
	cmd := exec.Command(c.Bin, args...)
	cmd.Dir = strings.TrimSuffix(c.Mgmt, "/"+lastPart(c.Mgmt))
	out, _ := cmd.StdoutPipe()
	cmd.Stderr = cmd.Stdout
	if err := cmd.Start(); err != nil {
		fail("openvpn: %v", err)
	}
	exited := make(chan error, 1)
	go func() { exited <- cmd.Wait() }()
	secrets := []string{c.Pass, c.KeyPass, c.OTP}
	go func() {
		s := bufio.NewScanner(out)
		for s.Scan() {
			l := s.Text()
			for _, sec := range secrets {
				if len(sec) >= 3 {
					l = strings.ReplaceAll(l, sec, "•••")
				}
			}
			say("LOG %s", l)
		}
	}()
	stop := func() {
		if cmd.Process != nil {
			cmd.Process.Signal(syscall.SIGTERM)
			time.AfterFunc(2500*time.Millisecond, func() { cmd.Process.Kill() })
		}
	}
	connCh := make(chan *net.UnixConn, 1)
	go func() {
		ln.SetDeadline(time.Now().Add(20 * time.Second))
		uc, err := ln.AcceptUnix()
		if err != nil {
			fail("openvpn не подключился к management: %v", err)
		}
		connCh <- uc
	}()
	var uc *net.UnixConn
	select {
	case uc = <-connCh:
	case err := <-exited:
		fail("openvpn завершился: %v", err)
	}
	st := &ovpnState{}
	ready := make(chan struct{})
	var readyOnce sync.Once
	fatal := make(chan string, 1)
	send := func(line string, fds ...int) {
		var oob []byte
		if len(fds) > 0 {
			oob = syscall.UnixRights(fds...)
		}
		uc.WriteMsgUnix([]byte(line+"\n"), oob, nil)
	}
	send("version 3")
	send("bytecount 0")
	send("state on")
	go func() {
		rd := &fdReader{c: uc}
		br := bufio.NewReader(rd)
		for {
			line, err := br.ReadString('\n')
			if err != nil {
				fatal <- "management: соединение закрыто"
				return
			}
			line = strings.TrimRight(line, "\r\n")
			fds := rd.take()
			handleMgmt(c, st, line, fds, send, func() { readyOnce.Do(func() { close(ready) }) }, fatal)
		}
	}()
	timeout := time.Duration(c.TimeoutMs) * time.Millisecond
	deadline := timeout + 30*time.Second
	select {
	case <-ready:
	case msg := <-fatal:
		stop()
		fail("%s", msg)
	case err := <-exited:
		fail("openvpn завершился: %v", err)
	case <-time.After(deadline):
		stop()
		fail("OpenVPN: нет подключения за %d с", int(deadline/time.Second))
	}
	if c.Mode == "ping" {
		ms, err := httpPing(c.Port, c.URL, timeout/2)
		stop()
		if err != nil {
			fail("ping: %v", err)
		}
		say("PING %d", ms)
		return
	}
	say("READY 127.0.0.1:%d", c.Port)
	go func() {
		select {
		case err := <-exited:
			say("ERR openvpn завершился: %v", err)
		case msg := <-fatal:
			say("ERR %s", msg)
			stop()
		}
		os.Exit(2)
	}()
	waitStop(func() { send("signal SIGTERM"); stop() })
}

func lastPart(p string) string {
	i := strings.LastIndex(p, "/")
	if i < 0 {
		return p
	}
	return p[i+1:]
}

// fdReader reads the management stream and collects SCM_RIGHTS fds (PROTECTFD sends the socket fd with the request).
type fdReader struct {
	c   *net.UnixConn
	mu  sync.Mutex
	fds []int
}

func (r *fdReader) Read(p []byte) (int, error) {
	oob := make([]byte, 128)
	n, oobn, _, _, err := r.c.ReadMsgUnix(p, oob)
	if oobn > 0 {
		if msgs, e := syscall.ParseSocketControlMessage(oob[:oobn]); e == nil {
			for _, m := range msgs {
				if fds, e := syscall.ParseUnixRights(&m); e == nil {
					r.mu.Lock()
					r.fds = append(r.fds, fds...)
					r.mu.Unlock()
				}
			}
		}
	}
	if n == 0 && err == nil {
		return 0, io.ErrNoProgress
	}
	return n, err
}

func (r *fdReader) take() []int {
	r.mu.Lock()
	defer r.mu.Unlock()
	f := r.fds
	r.fds = nil
	return f
}

func maskBits(m string) int {
	a, err := netip.ParseAddr(m)
	if err != nil || !a.Is4() {
		return 24
	}
	b := a.As4()
	n := 0
	for _, x := range b {
		for i := 7; i >= 0; i-- {
			if x&(1<<i) != 0 {
				n++
			}
		}
	}
	return n
}

func handleMgmt(c cfg, st *ovpnState, line string, fds []int, send func(string, ...int), onReady func(), fatal chan<- string) {
	for _, fd := range fds {
		if !strings.Contains(line, "'OPENTUN'") {
			syscall.Close(fd) // PROTECTFD: our uid is already outside the VPN
		}
	}
	switch {
	case strings.HasPrefix(line, ">HOLD:"):
		send("hold release")
	case strings.HasPrefix(line, ">PASSWORD:Verification Failed"):
		select {
		case fatal <- "OpenVPN: неверный логин/пароль (AUTH_FAILED)":
		default:
		}
	case strings.HasPrefix(line, ">PASSWORD:Need 'Auth'"):
		pass := c.Pass
		if strings.Contains(line, "SC:") { // static-challenge → SCRV1 with the OTP
			pass = "SCRV1:" + base64.StdEncoding.EncodeToString([]byte(c.Pass)) + ":" + base64.StdEncoding.EncodeToString([]byte(c.OTP))
		}
		if c.User == "" && c.Pass == "" {
			select {
			case fatal <- "OpenVPN: сервер требует логин и пароль — укажите их в карточке":
			default:
			}
			return
		}
		send("username \"Auth\" " + q(c.User))
		send("password \"Auth\" " + q(pass))
	case strings.HasPrefix(line, ">PASSWORD:Need 'Private Key'"):
		send("password \"Private Key\" " + q(c.KeyPass))
	case strings.HasPrefix(line, ">PASSWORD:Need 'HTTP Proxy'"):
		send("username \"HTTP Proxy\" \"\"")
		send("password \"HTTP Proxy\" \"\"")
	case strings.HasPrefix(line, ">INFOMSG:CRV1") || strings.HasPrefix(line, ">PASSWORD:Auth-Token") :
		// dynamic challenge / token: nothing to do here
	case strings.HasPrefix(line, ">NEED-OK:Need '"):
		rest := line[len(">NEED-OK:Need '"):]
		what := rest[:strings.Index(rest, "'")]
		msg := ""
		if i := strings.Index(line, "MSG:"); i >= 0 {
			msg = line[i+4:]
		}
		switch what {
		case "IFCONFIG": // local mask mtu topology
			f := strings.Fields(msg)
			st.mu.Lock()
			if len(f) > 0 {
				st.ip4 = f[0]
			}
			if len(f) > 1 {
				st.mask = f[1]
			}
			if len(f) > 2 {
				st.mtu, _ = strconv.Atoi(f[2])
			}
			st.mu.Unlock()
			send("needok 'IFCONFIG' ok")
		case "IFCONFIG6":
			f := strings.Fields(msg)
			st.mu.Lock()
			if len(f) > 0 {
				st.ip6 = f[0]
			}
			st.mu.Unlock()
			send("needok 'IFCONFIG6' ok")
		case "DNSSERVER", "DNS6SERVER":
			st.mu.Lock()
			st.dns = append(st.dns, strings.TrimSpace(msg))
			st.mu.Unlock()
			send("needok '" + what + "' ok")
		case "PERSIST_TUN_ACTION":
			send("needok 'PERSIST_TUN_ACTION' OPEN_BEFORE_CLOSE")
		case "OPENTUN":
			st.mu.Lock()
			if st.stack != nil {
				st.stack.Close()
				st.stack = nil
			}
			pair, err := syscall.Socketpair(syscall.AF_UNIX, syscall.SOCK_DGRAM, 0)
			if err != nil {
				st.mu.Unlock()
				send("needok 'OPENTUN' cancel")
				return
			}
			addrs := parseAddrs(st.ip4, st.ip6)
			dns := parseAddrs(strings.Join(st.dns, " "))
			mtu := st.mtu
			stack, err := newStack(os.NewFile(uintptr(pair[1]), "ovpn-tun"), addrs, dns, mtu, c.Port)
			if err != nil {
				st.mu.Unlock()
				syscall.Close(pair[0])
				say("LOG %v", err)
				send("needok 'OPENTUN' cancel")
				return
			}
			st.stack = stack
			st.dns = nil
			st.mu.Unlock()
			say("LOG туннель: %v/%d, DNS %v, MTU %d", addrs, maskBits(st.mask), dns, mtu)
			send("needok 'OPENTUN' ok", pair[0])
			syscall.Close(pair[0])
		default: // ROUTE, ROUTE6, DNSDOMAIN, HTTPPROXY, PROTECTFD — all routing is ours (Xray)
			send("needok '" + what + "' ok")
		}
	case strings.HasPrefix(line, ">STATE:"):
		f := strings.Split(line[len(">STATE:"):], ",")
		if len(f) > 1 {
			say("STATE %s", f[1])
			if f[1] == "CONNECTED" {
				if len(f) > 2 && f[2] != "SUCCESS" {
					say("LOG подключено с ошибками: %s", f[2])
				}
				onReady()
			}
		}
	case strings.HasPrefix(line, ">FATAL:"):
		select {
		case fatal <- "OpenVPN: " + line[len(">FATAL:"):]:
		default:
		}
	case strings.HasPrefix(line, ">INFO:"), strings.HasPrefix(line, ">BYTECOUNT:"), strings.HasPrefix(line, "SUCCESS:"):
	default:
		if strings.HasPrefix(line, "ERROR:") {
			say("LOG management %s", line)
		}
	}
	_ = fmt.Sprintf
}
