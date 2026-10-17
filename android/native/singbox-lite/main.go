// sbx: minimal sing-box runner for AntiBS client.
// Started by the app as a child process (libsbx.so in nativeLibraryDir):
//   libsbx.so run <config.json>     run until SIGTERM/SIGINT or stdin EOF
//   libsbx.so check <config.json>   validate config (create, don't start)
//   libsbx.so version
// The app feeds it a config with local SOCKS inbounds only (no TUN); Xray dials those
// inbounds as a regular socks outbound, so routing/stats/DNS stay in Xray.
package main

import (
	"context"
	"fmt"
	"io"
	"os"
	"os/signal"
	"runtime/debug"
	"syscall"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter/certificate"
	"github.com/sagernet/sing-box/adapter/endpoint"
	"github.com/sagernet/sing-box/adapter/inbound"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/adapter/service"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/dns/transport"
	"github.com/sagernet/sing-box/dns/transport/hosts"
	"github.com/sagernet/sing-box/dns/transport/local"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-box/protocol/anytls"
	"github.com/sagernet/sing-box/protocol/block"
	"github.com/sagernet/sing-box/protocol/direct"
	"github.com/sagernet/sing-box/protocol/hysteria"
	"github.com/sagernet/sing-box/protocol/hysteria2"
	"github.com/sagernet/sing-box/protocol/mixed"
	"github.com/sagernet/sing-box/protocol/snell"
	"github.com/sagernet/sing-box/protocol/socks"
	"github.com/sagernet/sing-box/protocol/ssh"
	"github.com/sagernet/sing-box/protocol/tuic"
	"github.com/sagernet/sing/common/json"
)

// Only what AntiBS needs: loopback socks/mixed inbounds, the protocols Xray lacks, plain DNS transports.
// (include.Context would pull every protocol/service and roughly double the binary.)
func boxContext(ctx context.Context) context.Context {
	in := inbound.NewRegistry()
	socks.RegisterInbound(in)
	mixed.RegisterInbound(in)

	out := outbound.NewRegistry()
	direct.RegisterOutbound(out)
	block.RegisterOutbound(out)
	socks.RegisterOutbound(out)
	hysteria.RegisterOutbound(out)
	hysteria2.RegisterOutbound(out)
	tuic.RegisterOutbound(out)
	anytls.RegisterOutbound(out)
	ssh.RegisterOutbound(out)
	snell.RegisterOutbound(out)
	registerNaive(out)

	dnsReg := dns.NewTransportRegistry()
	transport.RegisterTCP(dnsReg)
	transport.RegisterUDP(dnsReg)
	transport.RegisterTLS(dnsReg)
	transport.RegisterHTTPS(dnsReg)
	hosts.RegisterTransport(dnsReg)
	local.RegisterTransport(dnsReg)

	return box.Context(ctx, in, out, endpoint.NewRegistry(), dnsReg, service.NewRegistry(), certificate.NewRegistry())
}

func load(ctx context.Context, path string) (option.Options, error) {
	content, err := os.ReadFile(path)
	if err != nil {
		return option.Options{}, err
	}
	return json.UnmarshalExtendedContext[option.Options](ctx, content)
}

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "usage: sbx run|check <config.json> | version")
		os.Exit(2)
	}
	switch os.Args[1] {
	case "version":
		fmt.Println("sing-box", C.Version)
		return
	case "run", "check":
	default:
		fmt.Fprintln(os.Stderr, "unknown command")
		os.Exit(2)
	}
	if len(os.Args) < 3 {
		fmt.Fprintln(os.Stderr, "missing config path")
		os.Exit(2)
	}
	debug.SetGCPercent(50)
	debug.SetMemoryLimit(96 << 20)
	ctx, cancel := context.WithCancel(boxContext(context.Background()))
	defer cancel()
	opts, err := load(ctx, os.Args[2])
	if err != nil {
		fmt.Fprintln(os.Stderr, "FATAL decode config:", err)
		os.Exit(1)
	}
	instance, err := box.New(box.Options{Context: ctx, Options: opts})
	if err != nil {
		fmt.Fprintln(os.Stderr, "FATAL create:", err)
		os.Exit(1)
	}
	if os.Args[1] == "check" {
		instance.Close()
		fmt.Println("OK")
		return
	}
	if err = instance.Start(); err != nil {
		fmt.Fprintln(os.Stderr, "FATAL start:", err)
		os.Exit(1)
	}
	fmt.Println("SBX_STARTED")
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM, syscall.SIGHUP)
	stdinClosed := make(chan struct{})
	if len(os.Args) > 3 && os.Args[3] == "--watch-stdin" {
		// parent death detection: our stdin is a pipe owned by the app process
		go func() { io.Copy(io.Discard, os.Stdin); close(stdinClosed) }()
	}
	select {
	case <-sig:
	case <-stdinClosed:
	}
	cancel()
	instance.Close()
}
