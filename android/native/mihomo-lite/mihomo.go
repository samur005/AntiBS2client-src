package main

import (
	"context"
	"fmt"
	"net"
	"net/netip"
	"strings"

	"github.com/metacubex/mihomo/adapter/outbound"
	"github.com/metacubex/mihomo/common/structure"
	C "github.com/metacubex/mihomo/constant"
)

type mihomoDialer struct {
	p C.ProxyAdapter
}

func newMihomoDialer(m map[string]any) (*mihomoDialer, error) {
	if m == nil {
		return nil, fmt.Errorf("mihomo: empty proxy")
	}
	dec := structure.NewDecoder(structure.Option{TagName: "proxy", WeaklyTypedInput: true, KeyReplacer: structure.DefaultKeyReplacer})
	typ, _ := m["type"].(string)
	var (
		p   C.ProxyAdapter
		err error
	)
	switch strings.ToLower(typ) {
	case "ssr":
		o := &outbound.ShadowSocksROption{}
		if err = dec.Decode(m, o); err == nil {
			p, err = outbound.NewShadowSocksR(*o)
		}
	case "snell":
		o := &outbound.SnellOption{}
		if err = dec.Decode(m, o); err == nil {
			p, err = outbound.NewSnell(*o)
		}
	case "trusttunnel":
		o := &outbound.TrustTunnelOption{}
		if err = dec.Decode(m, o); err == nil {
			p, err = outbound.NewTrustTunnel(*o)
		}
	case "shadowquic":
		o := &outbound.ShadowQuicOption{}
		if err = dec.Decode(m, o); err == nil {
			p, err = outbound.NewShadowQuic(*o)
		}
	case "vless":
		o := &outbound.VlessOption{}
		if err = dec.Decode(m, o); err == nil {
			p, err = outbound.NewVless(*o)
		}
	case "mieru":
		// 1.0.19: mieru v3 protocol (mierus:// links with handshake-mode etc.); the standalone libmieru is v1.15.1.
		o := &outbound.MieruOption{}
		if err = dec.Decode(m, o); err == nil {
			p, err = outbound.NewMieru(*o)
		}
	case "wireguard":
		// 1.0.19: WireGuard / AmneziaWG (amnezia-wg-option: jc, jmin, jmax, s1–s4, h1–h4 incl. AWG 2.0 ranges, i1–i5).
		o := &outbound.WireGuardOption{}
		if err = dec.Decode(m, o); err == nil {
			p, err = outbound.NewWireGuard(*o)
		}
	default:
		return nil, fmt.Errorf("mihomo: unsupported type %q", typ)
	}
	if err != nil {
		return nil, fmt.Errorf("mihomo %s: %w", typ, err)
	}
	return &mihomoDialer{p: p}, nil
}

func metadata(network C.NetWork, host string, port uint16) *C.Metadata {
	m := &C.Metadata{NetWork: network, Type: C.SOCKS5, DstPort: port}
	if ip, err := netip.ParseAddr(host); err == nil {
		m.DstIP = ip.Unmap()
	} else {
		m.Host = host
	}
	return m
}

func (d *mihomoDialer) DialTCP(ctx context.Context, host string, port uint16) (net.Conn, error) {
	return d.p.DialContext(ctx, metadata(C.TCP, host, port))
}

func (d *mihomoDialer) ListenUDP(ctx context.Context, host string, port uint16) (net.PacketConn, error) {
	if !d.p.SupportUDP() {
		return nil, nil
	}
	m := metadata(C.UDP, host, port)
	if !m.DstIP.IsValid() {
		addr, err := resolveUDP(host, port)
		if err != nil {
			return nil, err
		}
		ip, _ := netip.AddrFromSlice(addr.IP)
		m.DstIP = ip.Unmap()
	}
	return d.p.ListenPacketContext(ctx, m)
}

func (d *mihomoDialer) Close() error {
	return d.p.Close()
}
