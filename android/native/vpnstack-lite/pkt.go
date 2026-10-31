package main

// 2.0.1: «pkt» mode — SSTP / ZeroTier run inside the app (Kotlin) and exchange raw IP packets with this
// netstack over a unix socket (each packet prefixed with a 2-byte big-endian length). The stack exposes the
// usual loopback SOCKS5, so Xray stays the front engine (routing, per-app rules, traffic stats / speed graph).

import (
	"encoding/binary"
	"io"
	"net"
	"net/netip"
	"os"
	"syscall"
)

func runPkt(c cfg) {
	addrs := parseAddrs(c.Addrs...)
	if len(addrs) == 0 {
		fail("pkt: нет адреса туннеля")
	}
	dns := parseAddrs(c.DNS...)
	var routes []netip.Prefix
	for _, r := range c.Routes {
		if p, err := netip.ParsePrefix(r); err == nil {
			routes = append(routes, p.Masked())
		}
	}
	os.Remove(c.Sock)
	ln, err := net.Listen("unix", c.Sock)
	if err != nil {
		fail("pkt listen: %v", err)
	}
	os.Chmod(c.Sock, 0600)
	fds, err := syscall.Socketpair(syscall.AF_UNIX, syscall.SOCK_SEQPACKET, 0)
	if err != nil {
		fail("socketpair: %v", err)
	}
	inner := os.NewFile(uintptr(fds[0]), "pkt-stack")
	outer := os.NewFile(uintptr(fds[1]), "pkt-app")
	mtu := c.MTU
	if mtu <= 0 {
		mtu = 1400
	}
	st, err := newStack(inner, addrs, dns, mtu, c.Port)
	if err != nil {
		fail("%v", err)
	}
	st.routes = routes
	say("LOG туннель: %v, DNS %v, MTU %d, маршруты %v", addrs, dns, mtu, routes)
	say("LISTEN %s", c.Sock)
	conn, err := ln.Accept()
	ln.Close()
	os.Remove(c.Sock)
	if err != nil {
		fail("pkt accept: %v", err)
	}
	go func() { // app → stack
		var h [2]byte
		buf := make([]byte, 65535)
		for {
			if _, err := io.ReadFull(conn, h[:]); err != nil {
				break
			}
			n := int(binary.BigEndian.Uint16(h[:]))
			if _, err := io.ReadFull(conn, buf[:n]); err != nil {
				break
			}
			if n > 0 {
				outer.Write(buf[:n])
			}
		}
		say("ERR туннель закрыт")
		os.Exit(2)
	}()
	go func() { // stack → app
		buf := make([]byte, 65537)
		for {
			n, err := outer.Read(buf[2:])
			if err != nil {
				return
			}
			binary.BigEndian.PutUint16(buf[:2], uint16(n))
			if _, err := conn.Write(buf[:n+2]); err != nil {
				return
			}
		}
	}()
	say("READY 127.0.0.1:%d", c.Port)
	waitStop(func() { conn.Close(); st.Close() })
}

// inTunnel: with split routes, only destinations inside them use the netstack.
func (s *Stack) inTunnel(host string) bool {
	if len(s.routes) == 0 {
		return true
	}
	a, err := netip.ParseAddr(host)
	if err != nil {
		return false
	}
	for _, p := range s.routes {
		if p.Contains(a.Unmap()) {
			return true
		}
	}
	return false
}
