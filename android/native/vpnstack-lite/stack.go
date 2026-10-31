package main

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"strconv"
	"sync"
	"time"

	"golang.zx2c4.com/wireguard/tun"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

// Stack = gVisor netstack fed from a packet fd (OpenVPN socketpair / OpenConnect VPNFD) + a loopback SOCKS5 server.
type Stack struct {
	dev  tun.Device
	tnet *netstack.Net
	fd   *os.File
	ln   net.Listener
	once sync.Once
	local4 netip.Addr
	done chan struct{}
	routes []netip.Prefix
}

func newStack(fd *os.File, addrs []netip.Addr, dns []netip.Addr, mtu int, port int) (*Stack, error) {
	if mtu <= 0 {
		mtu = 1400
	}
	if len(dns) == 0 {
		dns = []netip.Addr{netip.MustParseAddr("1.1.1.1")}
	}
	dev, tnet, err := netstack.CreateNetTUN(addrs, dns, mtu)
	if err != nil {
		return nil, fmt.Errorf("netstack: %w", err)
	}
	ln, err := net.Listen("tcp", "127.0.0.1:"+strconv.Itoa(port))
	if err != nil {
		dev.Close()
		return nil, fmt.Errorf("socks listen: %w", err)
	}
	s := &Stack{dev: dev, tnet: tnet, fd: fd, ln: ln, done: make(chan struct{}), local4: netip.IPv4Unspecified()}
	for _, a := range addrs {
		if a.Is4() {
			s.local4 = a
			break
		}
	}
	go s.fdToStack(mtu)
	go s.stackToFd(mtu)
	go s.serveSocks()
	return s, nil
}

func (s *Stack) Close() {
	s.once.Do(func() {
		close(s.done)
		s.ln.Close()
		s.dev.Close()
		s.fd.Close()
	})
}

func (s *Stack) fdToStack(mtu int) {
	buf := make([]byte, mtu+64)
	for {
		n, err := s.fd.Read(buf)
		if err != nil {
			s.Close()
			return
		}
		if n == 0 {
			continue
		}
		pkt := make([]byte, n)
		copy(pkt, buf[:n])
		if _, err := s.dev.Write([][]byte{pkt}, 0); err != nil {
			select {
			case <-s.done:
				return
			default:
			}
		}
	}
}

func (s *Stack) stackToFd(mtu int) {
	bufs := [][]byte{make([]byte, mtu+64)}
	sizes := []int{0}
	for {
		n, err := s.dev.Read(bufs, sizes, 0)
		if err != nil {
			s.Close()
			return
		}
		for i := 0; i < n; i++ {
			if _, err := s.fd.Write(bufs[i][:sizes[i]]); err != nil {
				s.Close()
				return
			}
		}
	}
}

// ── SOCKS5 (no auth, CONNECT + UDP ASSOCIATE) ──

func (s *Stack) serveSocks() {
	for {
		c, err := s.ln.Accept()
		if err != nil {
			return
		}
		go s.handle(c)
	}
}

func readAddr(r io.Reader) (string, error) {
	var t [1]byte
	if _, err := io.ReadFull(r, t[:]); err != nil {
		return "", err
	}
	var host string
	switch t[0] {
	case 1:
		b := make([]byte, 4)
		if _, err := io.ReadFull(r, b); err != nil {
			return "", err
		}
		host = net.IP(b).String()
	case 4:
		b := make([]byte, 16)
		if _, err := io.ReadFull(r, b); err != nil {
			return "", err
		}
		host = net.IP(b).String()
	case 3:
		var l [1]byte
		if _, err := io.ReadFull(r, l[:]); err != nil {
			return "", err
		}
		b := make([]byte, l[0])
		if _, err := io.ReadFull(r, b); err != nil {
			return "", err
		}
		host = string(b)
	default:
		return "", errors.New("bad atyp")
	}
	var p [2]byte
	if _, err := io.ReadFull(r, p[:]); err != nil {
		return "", err
	}
	return net.JoinHostPort(host, strconv.Itoa(int(binary.BigEndian.Uint16(p[:])))), nil
}

func reply(c net.Conn, code byte, a *net.TCPAddr) {
	b := []byte{5, code, 0, 1, 0, 0, 0, 0, 0, 0}
	if a != nil {
		if ip4 := a.IP.To4(); ip4 != nil {
			copy(b[4:8], ip4)
		}
		binary.BigEndian.PutUint16(b[8:], uint16(a.Port))
	}
	c.Write(b)
}

func (s *Stack) handle(c net.Conn) {
	defer c.Close()
	defer func() { recover() }()
	c.SetDeadline(time.Now().Add(20 * time.Second))
	var h [2]byte
	if _, err := io.ReadFull(c, h[:]); err != nil || h[0] != 5 {
		return
	}
	m := make([]byte, h[1])
	if _, err := io.ReadFull(c, m); err != nil {
		return
	}
	c.Write([]byte{5, 0})
	var req [3]byte
	if _, err := io.ReadFull(c, req[:]); err != nil {
		return
	}
	addr, err := readAddr(c)
	if err != nil {
		return
	}
	switch req[1] {
	case 1: // CONNECT
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		var up net.Conn
		if h, _, _ := net.SplitHostPort(addr); s.inTunnel(h) {
			up, err = s.tnet.DialContext(ctx, "tcp", addr)
		} else {
			up, err = (&net.Dialer{}).DialContext(ctx, "tcp", addr)
		}
		cancel()
		if err != nil {
			reply(c, 5, nil)
			return
		}
		defer up.Close()
		reply(c, 0, nil)
		c.SetDeadline(time.Time{})
		go func() { io.Copy(up, c); up.Close() }()
		io.Copy(c, up)
	case 3: // UDP ASSOCIATE
		s.udpAssociate(c)
	default:
		reply(c, 7, nil)
	}
}

func (s *Stack) udpAssociate(c net.Conn) {
	lc, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		reply(c, 1, nil)
		return
	}
	defer lc.Close()
	rc, err := s.tnet.ListenUDPAddrPort(netip.AddrPortFrom(s.local4, 0))
	if err != nil {
		reply(c, 1, nil)
		return
	}
	defer rc.Close()
	la := lc.LocalAddr().(*net.UDPAddr)
	reply(c, 0, &net.TCPAddr{IP: la.IP, Port: la.Port})
	c.SetDeadline(time.Time{})
	var client *net.UDPAddr
	var mu sync.Mutex
	var dc net.PacketConn // direct socket for destinations outside split routes
	if len(s.routes) > 0 {
		if d, err := net.ListenUDP("udp", nil); err == nil {
			dc = d
			defer d.Close()
		}
	}
	recv := func(rc net.PacketConn) {
		buf := make([]byte, 65535)
		for {
			n, from, err := rc.ReadFrom(buf)
			if err != nil {
				return
			}
			ua, ok := from.(*net.UDPAddr)
			mu.Lock()
			cl := client
			mu.Unlock()
			if !ok || cl == nil {
				continue
			}
			hdr := []byte{0, 0, 0, 1, 0, 0, 0, 0, 0, 0}
			if ip4 := ua.IP.To4(); ip4 != nil {
				copy(hdr[4:8], ip4)
			} else {
				hdr = append([]byte{0, 0, 0, 4}, ua.IP.To16()...)
				hdr = append(hdr, 0, 0)
			}
			binary.BigEndian.PutUint16(hdr[len(hdr)-2:], uint16(ua.Port))
			lc.WriteToUDP(append(hdr, buf[:n]...), cl)
		}
	}
	go recv(rc)
	if dc != nil {
		go recv(dc)
	}
	go func() {
		buf := make([]byte, 65535)
		for {
			n, from, err := lc.ReadFromUDP(buf)
			if err != nil {
				return
			}
			if n < 4 || buf[2] != 0 {
				continue
			}
			mu.Lock()
			client = from
			mu.Unlock()
			r := &sliceReader{b: buf[3:n]}
			dst, err := readAddr(r)
			if err != nil {
				continue
			}
			ua, err := s.resolveUDP(dst)
			if err != nil {
				continue
			}
			var out net.PacketConn = rc
			if dc != nil && !s.inTunnel(ua.IP.String()) {
				out = dc
			}
			if _, err := out.WriteTo(r.b[r.i:], ua); err != nil { say("LOG udp write %v: %v", ua, err) }
		}
	}()
	io.Copy(io.Discard, c) // association lives while the TCP control connection is open
}

func (s *Stack) resolveUDP(hp string) (*net.UDPAddr, error) {
	host, port, err := net.SplitHostPort(hp)
	if err != nil {
		return nil, err
	}
	p, _ := strconv.Atoi(port)
	if ip := net.ParseIP(host); ip != nil {
		return &net.UDPAddr{IP: ip, Port: p}, nil
	}
	var ips []string
	if len(s.routes) > 0 {
		ips, err = net.LookupHost(host)
	} else {
		ips, err = s.tnet.LookupHost(host)
	}
	if err != nil || len(ips) == 0 {
		return nil, fmt.Errorf("resolve %s: %v", host, err)
	}
	return &net.UDPAddr{IP: net.ParseIP(ips[0]), Port: p}, nil
}

type sliceReader struct {
	b []byte
	i int
}

func (r *sliceReader) Read(p []byte) (int, error) {
	if r.i >= len(r.b) {
		return 0, io.EOF
	}
	n := copy(p, r.b[r.i:])
	r.i += n
	return n, nil
}
