package main

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/binary"
	"io"
	"math/big"
	"net"
	"net/http"
	"testing"
	"time"

	"github.com/metacubex/mihomo/transport/snell"
	"github.com/quic-go/quic-go/http3"
)

func echoServer(t *testing.T) net.Listener {
	ln, _ := net.Listen("tcp", "127.0.0.1:0")
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() { io.Copy(c, c); c.Close() }()
		}
	}()
	return ln
}

func roundtrip(t *testing.T, d Dialer, target string) {
	host, ps, _ := net.SplitHostPort(target)
	var port uint16
	for _, ch := range ps {
		port = port*10 + uint16(ch-'0')
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	c, err := d.DialTCP(ctx, host, port)
	if err != nil {
		t.Fatal("dial:", err)
	}
	defer c.Close()
	c.Write([]byte("hello-antibs"))
	buf := make([]byte, 12)
	c.SetReadDeadline(time.Now().Add(5 * time.Second))
	if _, err := io.ReadFull(c, buf); err != nil || string(buf) != "hello-antibs" {
		t.Fatalf("echo mismatch %q %v", buf, err)
	}
}

func TestSnellV1V2V3(t *testing.T) {
	echo := echoServer(t)
	defer echo.Close()
	for _, v := range []int{1, 2, 3} {
		ln, _ := net.Listen("tcp", "127.0.0.1:0")
		go func(v int) {
			for {
				c, err := ln.Accept()
				if err != nil {
					return
				}
				go func() {
					defer c.Close()
					s := snell.ServerStreamConn(c, []byte("psk-test"), v)
					h := make([]byte, 4)
					if _, err := io.ReadFull(s, h); err != nil {
						return
					}
					host := make([]byte, int(h[3]))
					io.ReadFull(s, host)
					p := make([]byte, 2)
					io.ReadFull(s, p)
					up, err := net.Dial("tcp", net.JoinHostPort(string(host), itoa(int(binary.BigEndian.Uint16(p)))))
					if err != nil {
						return
					}
					s.Write([]byte{0})
					go io.Copy(up, s)
					io.Copy(s, up)
				}()
			}
		}(v)
		_, sp, _ := net.SplitHostPort(ln.Addr().String())
		d, err := newMihomoDialer(map[string]any{"type": "snell", "name": "t", "server": "127.0.0.1", "port": sp, "psk": "psk-test", "version": v})
		if err != nil {
			t.Fatal(err)
		}
		roundtrip(t, d, echo.Addr().String())
		ln.Close()
	}
}

func itoa(i int) string { return big.NewInt(int64(i)).String() }

func TestHTTP3Connect(t *testing.T) {
	echo := echoServer(t)
	defer echo.Close()
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	tmpl := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "h3.test"}, DNSNames: []string{"h3.test"},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour)}
	der, _ := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	cert := tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}
	udp, _ := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	srv := &http3.Server{
		TLSConfig: http3.ConfigureTLSConfig(&tls.Config{Certificates: []tls.Certificate{cert}}),
		Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.Method != http.MethodConnect || r.Header.Get("Proxy-Authorization") != "Basic dTpw" {
				w.WriteHeader(407)
				return
			}
			up, err := net.Dial("tcp", r.Host)
			if err != nil {
				w.WriteHeader(502)
				return
			}
			w.WriteHeader(200)
			w.(http.Flusher).Flush()
			str := w.(http3.HTTPStreamer).HTTPStream()
			go func() { io.Copy(up, str); up.Close() }()
			io.Copy(str, up)
			str.Close()
		}),
	}
	go srv.Serve(udp)
	d, err := newH3Dialer(H3Options{Server: "127.0.0.1", Port: udp.LocalAddr().(*net.UDPAddr).Port, Username: "u", Password: "p", SNI: "h3.test", Insecure: true})
	if err != nil {
		t.Fatal(err)
	}
	defer d.Close()
	roundtrip(t, d, echo.Addr().String())
	roundtrip(t, d, echo.Addr().String()) // reused QUIC conn
}
