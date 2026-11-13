// mhx: third helper engine for AntiBS client (libmhx.so in nativeLibraryDir).
//
//	libmhx.so run <config.json> [--watch-stdin]
//	libmhx.so check <config.json>
//	libmhx.so version
//
// Each slot is a loopback SOCKS5 server (TCP CONNECT + UDP ASSOCIATE) that dials one profile:
//   - "mihomo": a mihomo (Clash.Meta) outbound — ssr, snell (v1–v5), trusttunnel, shadowquic
//   - "h3":     HTTP/3 CONNECT proxy client (quic-go http3, same approach as Exclave's http3 outbound)
//
// Xray dials these slots as plain socks outbounds, so routing/DNS/stats stay in Xray.
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"os"
	"os/signal"
	"runtime/debug"
	"syscall"

	mlog "github.com/metacubex/mihomo/log"
)

const version = "mhx 1.0.8 (mihomo v1.19.34, quic-go http3, vless, wireguard/amneziawg)"

type Slot struct {
	Port   int            `json:"port"`
	Type   string         `json:"type"` // mihomo | h3
	Proxy  map[string]any `json:"proxy,omitempty"`
	H3     *H3Options     `json:"h3,omitempty"`
	Listen string         `json:"listen,omitempty"`
}

type Config struct {
	Slots []Slot `json:"slots"`
}

// Dialer is what one SOCKS slot uses upstream.
type Dialer interface {
	DialTCP(ctx context.Context, host string, port uint16) (net.Conn, error)
	// ListenUDP returns nil, nil when UDP is unsupported.
	ListenUDP(ctx context.Context, host string, port uint16) (net.PacketConn, error)
	Close() error
}

func buildDialer(s Slot) (Dialer, error) {
	switch s.Type {
	case "h3":
		if s.H3 == nil {
			return nil, fmt.Errorf("slot %d: missing h3 options", s.Port)
		}
		return newH3Dialer(*s.H3)
	case "mihomo", "":
		return newMihomoDialer(s.Proxy)
	default:
		return nil, fmt.Errorf("slot %d: unknown type %q", s.Port, s.Type)
	}
}

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "usage: mhx run|check <config.json> | version")
		os.Exit(2)
	}
	switch os.Args[1] {
	case "version":
		fmt.Println(version)
		return
	case "run", "check":
		if os.Getenv("MHX_DEBUG") != "" {
			mlog.SetLevel(mlog.DEBUG)
		}
	default:
		fmt.Fprintln(os.Stderr, "unknown command")
		os.Exit(2)
	}
	if len(os.Args) < 3 {
		fmt.Fprintln(os.Stderr, "missing config path")
		os.Exit(2)
	}
	debug.SetGCPercent(50)
	debug.SetMemoryLimit(64 << 20)
	raw, err := os.ReadFile(os.Args[2])
	if err != nil {
		fmt.Fprintln(os.Stderr, "FATAL read config:", err)
		os.Exit(1)
	}
	var cfg Config
	if err := json.Unmarshal(raw, &cfg); err != nil {
		fmt.Fprintln(os.Stderr, "FATAL decode config:", err)
		os.Exit(1)
	}
	if len(cfg.Slots) == 0 {
		fmt.Fprintln(os.Stderr, "FATAL no slots")
		os.Exit(1)
	}
	dialers := make([]Dialer, len(cfg.Slots))
	for i, s := range cfg.Slots {
		d, err := buildDialer(s)
		if err != nil {
			fmt.Fprintln(os.Stderr, "FATAL create:", err)
			os.Exit(1)
		}
		dialers[i] = d
	}
	if os.Args[1] == "check" {
		for _, d := range dialers {
			d.Close()
		}
		fmt.Println("OK")
		return
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	var listeners []net.Listener
	for i, s := range cfg.Slots {
		listen := s.Listen
		if listen == "" {
			listen = "127.0.0.1"
		}
		ln, err := net.Listen("tcp", net.JoinHostPort(listen, fmt.Sprint(s.Port)))
		if err != nil {
			fmt.Fprintln(os.Stderr, "FATAL listen:", err)
			os.Exit(1)
		}
		listeners = append(listeners, ln)
		srv := &socksServer{dialer: dialers[i], name: fmt.Sprintf("slot-%d", s.Port)}
		go srv.serve(ctx, ln)
	}
	fmt.Println("MHX_STARTED")
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM, syscall.SIGHUP)
	stdinClosed := make(chan struct{})
	if len(os.Args) > 3 && os.Args[3] == "--watch-stdin" {
		go func() { io.Copy(io.Discard, os.Stdin); close(stdinClosed) }()
	}
	select {
	case <-sig:
	case <-stdinClosed:
	}
	cancel()
	for _, ln := range listeners {
		ln.Close()
	}
	for _, d := range dialers {
		d.Close()
	}
}
