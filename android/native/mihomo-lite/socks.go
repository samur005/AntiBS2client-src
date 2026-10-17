package main

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"strconv"
	"sync"
	"time"
)

type socksServer struct {
	dialer Dialer
	name   string
}

func (s *socksServer) serve(ctx context.Context, ln net.Listener) {
	for {
		c, err := ln.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			continue
		}
		go s.handle(ctx, c)
	}
}

func readAddr(r io.Reader, atyp byte) (string, uint16, error) {
	var host string
	switch atyp {
	case 1:
		b := make([]byte, 4)
		if _, err := io.ReadFull(r, b); err != nil {
			return "", 0, err
		}
		host = net.IP(b).String()
	case 4:
		b := make([]byte, 16)
		if _, err := io.ReadFull(r, b); err != nil {
			return "", 0, err
		}
		host = net.IP(b).String()
	case 3:
		l := make([]byte, 1)
		if _, err := io.ReadFull(r, l); err != nil {
			return "", 0, err
		}
		b := make([]byte, int(l[0]))
		if _, err := io.ReadFull(r, b); err != nil {
			return "", 0, err
		}
		host = string(b)
	default:
		return "", 0, fmt.Errorf("bad atyp %d", atyp)
	}
	p := make([]byte, 2)
	if _, err := io.ReadFull(r, p); err != nil {
		return "", 0, err
	}
	return host, binary.BigEndian.Uint16(p), nil
}

func encodeAddr(host string, port uint16) []byte {
	var b []byte
	if ip := net.ParseIP(host); ip != nil {
		if v4 := ip.To4(); v4 != nil {
			b = append([]byte{1}, v4...)
		} else {
			b = append([]byte{4}, ip.To16()...)
		}
	} else {
		b = append([]byte{3, byte(len(host))}, host...)
	}
	return binary.BigEndian.AppendUint16(b, port)
}

func reply(c net.Conn, code byte, addr net.Addr) {
	host, port := "0.0.0.0", uint16(0)
	if ua, ok := addr.(*net.UDPAddr); ok {
		host, port = ua.IP.String(), uint16(ua.Port)
	}
	c.Write(append([]byte{5, code, 0}, encodeAddr(host, port)...))
}

func (s *socksServer) handle(ctx context.Context, c net.Conn) {
	defer c.Close()
	c.SetDeadline(time.Now().Add(15 * time.Second))
	h := make([]byte, 2)
	if _, err := io.ReadFull(c, h); err != nil || h[0] != 5 {
		return
	}
	methods := make([]byte, int(h[1]))
	if _, err := io.ReadFull(c, methods); err != nil {
		return
	}
	c.Write([]byte{5, 0})
	req := make([]byte, 4)
	if _, err := io.ReadFull(c, req); err != nil || req[0] != 5 {
		return
	}
	host, port, err := readAddr(c, req[3])
	if err != nil {
		return
	}
	switch req[1] {
	case 1: // CONNECT
		dctx, cancel := context.WithTimeout(ctx, 12*time.Second)
		up, err := s.dialer.DialTCP(dctx, host, port)
		cancel()
		if err != nil {
			fmt.Fprintf(os.Stderr, "[%s] dial %s:%d: %v\n", s.name, host, port, err)
			reply(c, 5, nil)
			return
		}
		defer up.Close()
		c.SetDeadline(time.Time{})
		reply(c, 0, nil)
		pipe(c, up)
	case 3: // UDP ASSOCIATE
		s.udpAssociate(ctx, c)
	default:
		reply(c, 7, nil)
	}
}

func pipe(a, b net.Conn) {
	var wg sync.WaitGroup
	wg.Add(2)
	cp := func(dst, src net.Conn) {
		defer wg.Done()
		io.Copy(dst, src)
		if cw, ok := dst.(interface{ CloseWrite() error }); ok {
			cw.CloseWrite()
		} else {
			dst.SetReadDeadline(time.Now().Add(2 * time.Second))
		}
	}
	go cp(a, b)
	go cp(b, a)
	wg.Wait()
}

type udpSession struct {
	pc     net.PacketConn
	target string
}

func (s *socksServer) udpAssociate(ctx context.Context, c net.Conn) {
	relay, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		reply(c, 1, nil)
		return
	}
	defer relay.Close()
	c.SetDeadline(time.Time{})
	reply(c, 0, relay.LocalAddr())
	actx, cancel := context.WithCancel(ctx)
	defer cancel()
	go func() { io.Copy(io.Discard, c); cancel(); relay.Close() }()

	var mu sync.Mutex
	sessions := map[string]net.PacketConn{} // per destination
	defer func() {
		mu.Lock()
		for _, pc := range sessions {
			pc.Close()
		}
		mu.Unlock()
	}()
	buf := make([]byte, 65535)
	for {
		n, client, err := relay.ReadFromUDP(buf)
		if err != nil {
			return
		}
		if n < 4 || buf[2] != 0 { // no fragmentation support
			continue
		}
		r := &byteReader{b: buf[4:n]}
		host, port, err := readAddr(r, buf[3])
		if err != nil {
			continue
		}
		payload := append([]byte(nil), r.b...)
		key := net.JoinHostPort(host, strconv.Itoa(int(port)))
		mu.Lock()
		pc := sessions[key]
		mu.Unlock()
		if pc == nil {
			pc, err = s.dialer.ListenUDP(actx, host, port)
			if err != nil || pc == nil {
				if err == nil {
					err = errors.New("UDP not supported by this protocol")
				}
				fmt.Fprintf(os.Stderr, "[%s] udp %s: %v\n", s.name, key, err)
				continue
			}
			mu.Lock()
			sessions[key] = pc
			mu.Unlock()
			go func(pc net.PacketConn, host string, port uint16, client *net.UDPAddr) {
				rb := make([]byte, 65535)
				hdr := append([]byte{0, 0, 0}, encodeAddr(host, port)...)
				for {
					pc.SetReadDeadline(time.Now().Add(3 * time.Minute))
					m, _, err := pc.ReadFrom(rb)
					if err != nil {
						return
					}
					out := append(append([]byte(nil), hdr...), rb[:m]...)
					relay.WriteToUDP(out, client)
				}
			}(pc, host, port, client)
		}
		dst, err := resolveUDP(host, port)
		if err != nil {
			continue
		}
		pc.WriteTo(payload, dst)
	}
}

type byteReader struct{ b []byte }

func (r *byteReader) Read(p []byte) (int, error) {
	if len(r.b) == 0 {
		return 0, io.EOF
	}
	n := copy(p, r.b)
	r.b = r.b[n:]
	return n, nil
}

func resolveUDP(host string, port uint16) (*net.UDPAddr, error) {
	return net.ResolveUDPAddr("udp", net.JoinHostPort(host, strconv.Itoa(int(port))))
}
