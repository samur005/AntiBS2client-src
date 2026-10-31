// libvpnstack.so — AntiBS2client helper for OpenVPN / OpenConnect (1.0.41).
//
// Classic VPN clients normally own the TUN. Here they get a socketpair (OpenVPN, via the Android management
// protocol OPENTUN) or run in --script-tun mode (OpenConnect, VPNFD) and the packets end in a gVisor netstack
// that exposes a loopback SOCKS5, so Xray stays the front engine (routing, ad block, per-app, stats).
//
//	libvpnstack.so ovpn <cfg.json>   run (READY 127.0.0.1:<port>) or ping (PING <ms>) depending on cfg.mode
//	libvpnstack.so oc <cfg.json>
//	libvpnstack.so oc-tun            (spawned by openconnect -S; env VPNFD, INTERNAL_IP4_*, ANTIBS_SOCKS_PORT)
package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"os"
	"os/exec"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"golang.org/x/net/proxy"
)

type cfg struct {
	Mode      string   `json:"mode"` // run | ping
	Port      int      `json:"port"`
	URL       string   `json:"url"`
	TimeoutMs int      `json:"timeout_ms"`
	Bin       string   `json:"bin"`    // openvpn / openconnect executable
	Config    string   `json:"config"` // .ovpn path
	Mgmt      string   `json:"mgmt"`   // management unix socket path
	User      string   `json:"user"`
	Pass      string   `json:"pass"`
	KeyPass   string   `json:"key_pass"`
	OTP       string   `json:"otp"`
	Server    string   `json:"server"` // openconnect
	Protocol  string   `json:"protocol"`
	Group     string   `json:"group"`
	CertPin   string   `json:"cert_pin"`
	UserAgent string   `json:"user_agent"`
	Answers   []string `json:"answers"`
	Self      string   `json:"self"`
	Sock      string   `json:"sock"`   // pkt: unix socket the app writes framed IP packets to
	Addrs     []string `json:"addrs"`  // pkt: tunnel addresses
	DNS       []string `json:"dns"`    // pkt: DNS servers inside the tunnel
	MTU       int      `json:"mtu"`
	Routes    []string `json:"routes"` // pkt: if set, only these prefixes go into the tunnel, the rest is dialled directly
}

var outMu sync.Mutex

func say(format string, a ...any) {
	outMu.Lock()
	fmt.Printf(format+"\n", a...)
	outMu.Unlock()
}

func fail(format string, a ...any) {
	say("ERR "+format, a...)
	os.Exit(1)
}

func loadCfg(p string) cfg {
	b, err := os.ReadFile(p)
	if err != nil {
		fail("config: %v", err)
	}
	var c cfg
	if err := json.Unmarshal(b, &c); err != nil {
		fail("config: %v", err)
	}
	if c.Port <= 0 {
		c.Port = 11300
	}
	if c.TimeoutMs <= 0 {
		c.TimeoutMs = 20000
	}
	return c
}

func waitStop(stop func()) {
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGTERM, syscall.SIGINT)
	in := make(chan struct{})
	go func() {
		s := bufio.NewScanner(os.Stdin)
		for s.Scan() {
			if strings.TrimSpace(s.Text()) == "STOP" {
				break
			}
		}
		close(in)
	}()
	select {
	case <-sig:
	case <-in:
	}
	stop()
}

// httpPing: best of 2 GETs through the loopback SOCKS.
func httpPing(port int, target string, timeout time.Duration) (int64, error) {
	if target == "" {
		target = "https://www.gstatic.com/generate_204"
	}
	d, err := proxy.SOCKS5("tcp", "127.0.0.1:"+strconv.Itoa(port), nil, &net.Dialer{Timeout: timeout})
	if err != nil {
		return 0, err
	}
	cd := d.(proxy.ContextDialer)
	tr := &http.Transport{DialContext: cd.DialContext, TLSHandshakeTimeout: timeout, ResponseHeaderTimeout: timeout}
	cl := &http.Client{Transport: tr, Timeout: timeout, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	defer tr.CloseIdleConnections()
	var best int64
	var last error
	for i := 0; i < 3; i++ {
		t0 := time.Now()
		resp, err := cl.Get(target)
		if err != nil {
			last = err
			continue
		}
		io.Copy(io.Discard, resp.Body)
		resp.Body.Close()
		if i == 0 {
			continue // warm-up: TLS + TCP through the tunnel
		}
		ms := time.Since(t0).Milliseconds()
		if ms < 1 {
			ms = 1
		}
		if best == 0 || ms < best {
			best = ms
		}
	}
	if best == 0 {
		if last == nil {
			last = fmt.Errorf("no response")
		}
		return 0, last
	}
	return best, nil
}

func parseAddrs(list ...string) []netip.Addr {
	var out []netip.Addr
	for _, s := range list {
		for _, f := range strings.FieldsFunc(s, func(r rune) bool { return r == ' ' || r == ',' }) {
			f = strings.TrimSpace(strings.SplitN(f, "/", 2)[0])
			if a, err := netip.ParseAddr(f); err == nil {
				out = append(out, a)
			}
		}
	}
	return out
}

func main() {
	if len(os.Args) < 2 {
		fail("usage: ovpn|oc <cfg.json> | oc-tun")
	}
	switch os.Args[1] {
	case "version":
		fmt.Println("vpnstack 1.0.41")
	case "ovpn":
		if len(os.Args) < 3 {
			fail("no config")
		}
		runOpenVPN(loadCfg(os.Args[2]))
	case "oc":
		if len(os.Args) < 3 {
			fail("no config")
		}
		runOpenConnect(loadCfg(os.Args[2]))
	case "oc-tun":
		ocTun()
	case "pkt":
		if len(os.Args) < 3 {
			fail("no config")
		}
		runPkt(loadCfg(os.Args[2]))
	default:
		fail("unknown mode %q", os.Args[1])
	}
}

// ───────────── OpenConnect ─────────────

func runOpenConnect(c cfg) {
	args := []string{"--script-tun", "--script", c.Self + " oc-tun"}
	if c.Protocol != "" {
		args = append(args, "--protocol="+c.Protocol)
	}
	if c.User != "" {
		args = append(args, "--user="+c.User)
	}
	if c.Group != "" {
		args = append(args, "--authgroup="+c.Group)
	}
	if c.CertPin != "" {
		args = append(args, "--servercert="+c.CertPin)
	}
	if c.UserAgent != "" {
		args = append(args, "--useragent="+c.UserAgent)
	}
	args = append(args, c.Server)
	cmd := exec.Command(c.Bin, args...)
	cmd.Env = append(os.Environ(), "ANTIBS_SOCKS_PORT="+strconv.Itoa(c.Port))
	stdin, _ := cmd.StdinPipe()
	pr, pw := io.Pipe()
	cmd.Stdout = pw
	cmd.Stderr = pw
	if err := cmd.Start(); err != nil {
		fail("openconnect: %v", err)
	}
	// password, then extra answers (OTP / second factor) line by line, as openconnect prompts for them
	go func() {
		lines := []string{}
		if c.Pass != "" {
			lines = append(lines, c.Pass)
		}
		lines = append(lines, c.Answers...)
		for _, l := range lines {
			io.WriteString(stdin, l+"\n")
		}
	}()
	ready := make(chan struct{})
	var once sync.Once
	exited := make(chan error, 1)
	go func() { exited <- cmd.Wait(); pw.Close() }()
	go func() {
		s := bufio.NewScanner(pr)
		s.Buffer(make([]byte, 64*1024), 1<<20)
		for s.Scan() {
			l := s.Text()
			if strings.HasPrefix(l, "READY ") {
				once.Do(func() { close(ready) })
				if c.Mode != "ping" {
					say("%s", l)
				}
				continue
			}
			if c.Pass != "" {
				l = strings.ReplaceAll(l, c.Pass, "•••")
			}
			say("LOG %s", l)
		}
	}()
	stop := func() {
		if cmd.Process != nil {
			cmd.Process.Signal(syscall.SIGINT)
			time.AfterFunc(2*time.Second, func() { cmd.Process.Kill() })
		}
	}
	timeout := time.Duration(c.TimeoutMs) * time.Millisecond
	select {
	case <-ready:
	case err := <-exited:
		fail("openconnect завершился: %v", err)
	case <-time.After(timeout + 30*time.Second):
		stop()
		fail("openconnect: нет подключения за %d с", int((timeout+30*time.Second)/time.Second))
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
	go func() {
		err := <-exited
		say("ERR openconnect завершился: %v", err)
		os.Exit(2)
	}()
	waitStop(stop)
}

func ocTun() {
	fdn, err := strconv.Atoi(os.Getenv("VPNFD"))
	if err != nil {
		fail("VPNFD not set")
	}
	f := os.NewFile(uintptr(fdn), "vpnfd")
	addrs := parseAddrs(os.Getenv("INTERNAL_IP4_ADDRESS"), os.Getenv("INTERNAL_IP6_ADDRESS"))
	if len(addrs) == 0 {
		fail("no INTERNAL_IP4_ADDRESS")
	}
	dns := parseAddrs(os.Getenv("INTERNAL_IP4_DNS"), os.Getenv("INTERNAL_IP6_DNS"))
	mtu, _ := strconv.Atoi(os.Getenv("INTERNAL_IP4_MTU"))
	port, _ := strconv.Atoi(os.Getenv("ANTIBS_SOCKS_PORT"))
	if port == 0 {
		port = 11300
	}
	st, err := newStack(f, addrs, dns, mtu, port)
	if err != nil {
		fail("%v", err)
	}
	say("LOG туннель: %v, DNS %v, MTU %d", addrs, dns, mtu)
	say("READY 127.0.0.1:%d", port)
	<-st.done
}
