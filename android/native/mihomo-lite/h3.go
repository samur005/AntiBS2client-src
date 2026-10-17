package main

import (
	"context"
	"crypto/tls"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"sync"
	"time"

	"github.com/quic-go/quic-go"
	"github.com/quic-go/quic-go/http3"
)

// H3Options: plain HTTP/3 CONNECT proxy (RFC 9114 §4.4). Same flow as Exclave's http3 outbound.
type H3Options struct {
	Server   string            `json:"server"`
	Port     int               `json:"port"`
	Username string            `json:"username,omitempty"`
	Password string            `json:"password,omitempty"`
	SNI      string            `json:"sni,omitempty"`
	Insecure bool              `json:"insecure,omitempty"`
	Headers  map[string]string `json:"headers,omitempty"`
	Path     string            `json:"path,omitempty"`
	Host     string            `json:"host,omitempty"`
}

type h3Dialer struct {
	opt   H3Options
	tr    *http3.Transport
	qcfg  *quic.Config
	mu    sync.Mutex
	conn  *http3.ClientConn
	qconn *quic.Conn
}

func newH3Dialer(o H3Options) (*h3Dialer, error) {
	if o.Server == "" || o.Port <= 0 || o.Port > 65535 {
		return nil, errors.New("h3: server/port required")
	}
	q := &quic.Config{KeepAlivePeriod: 15 * time.Second, HandshakeIdleTimeout: 8 * time.Second, MaxIdleTimeout: 60 * time.Second}
	return &h3Dialer{opt: o, qcfg: q, tr: &http3.Transport{QUICConfig: q}}, nil
}

func (d *h3Dialer) clientConn(ctx context.Context) (*http3.ClientConn, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.conn != nil {
		select {
		case <-d.conn.Context().Done():
			d.conn = nil
		default:
			return d.conn, nil
		}
	}
	sni := d.opt.SNI
	if sni == "" {
		sni = d.opt.Host
	}
	if sni == "" && net.ParseIP(d.opt.Server) == nil {
		sni = d.opt.Server
	}
	tlsCfg := &tls.Config{ServerName: sni, NextProtos: []string{"h3"}, InsecureSkipVerify: d.opt.Insecure, MinVersion: tls.VersionTLS13}
	addr, err := net.ResolveUDPAddr("udp", net.JoinHostPort(d.opt.Server, strconv.Itoa(d.opt.Port)))
	if err != nil {
		return nil, err
	}
	udp, err := net.ListenUDP("udp", nil)
	if err != nil {
		return nil, err
	}
	qc, err := quic.Dial(ctx, udp, addr, tlsCfg, d.qcfg)
	if err != nil {
		udp.Close()
		return nil, fmt.Errorf("h3 quic: %w", err)
	}
	cc := d.tr.NewClientConn(qc)
	select {
	case <-cc.ReceivedSettings():
	case <-cc.Context().Done():
		return nil, cc.Context().Err()
	case <-ctx.Done():
		qc.CloseWithError(0, "")
		return nil, ctx.Err()
	}
	d.conn, d.qconn = cc, qc
	return cc, nil
}

func (d *h3Dialer) DialTCP(ctx context.Context, host string, port uint16) (net.Conn, error) {
	cc, err := d.clientConn(ctx)
	if err != nil {
		return nil, err
	}
	target := net.JoinHostPort(host, strconv.Itoa(int(port)))
	str, err := cc.OpenRequestStream(context.Background())
	if err != nil {
		d.mu.Lock()
		if d.conn == cc {
			d.conn = nil
		}
		d.mu.Unlock()
		cc.CloseWithError(0, "")
		return nil, err
	}
	req := &http.Request{
		Method: http.MethodConnect,
		URL:    &url.URL{Scheme: "https", Host: target},
		Header: make(http.Header),
		Host:   target,
	}
	if d.opt.Username != "" || d.opt.Password != "" {
		req.Header.Set("Proxy-Authorization", "Basic "+base64.StdEncoding.EncodeToString([]byte(d.opt.Username+":"+d.opt.Password)))
	}
	for k, v := range d.opt.Headers {
		req.Header.Set(k, v)
	}
	if err := str.SendRequestHeader(req); err != nil {
		str.CancelRead(0)
		str.Close()
		return nil, err
	}
	type res struct {
		r   *http.Response
		err error
	}
	ch := make(chan res, 1)
	go func() { r, e := str.ReadResponse(); ch <- res{r, e} }()
	select {
	case <-ctx.Done():
		str.CancelRead(0)
		str.Close()
		return nil, ctx.Err()
	case r := <-ch:
		if r.err != nil {
			str.CancelRead(0)
			str.Close()
			return nil, r.err
		}
		if r.r.StatusCode != http.StatusOK {
			r.r.Body.Close()
			str.CancelRead(0)
			str.Close()
			return nil, fmt.Errorf("h3 proxy: %s", r.r.Status)
		}
		return &h3Conn{str: str}, nil
	}
}

func (d *h3Dialer) ListenUDP(ctx context.Context, host string, port uint16) (net.PacketConn, error) {
	return nil, nil // CONNECT-UDP (RFC 9298) not implemented
}

func (d *h3Dialer) Close() error {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.qconn != nil {
		d.qconn.CloseWithError(0, "")
	}
	d.conn, d.qconn = nil, nil
	return nil
}

type h3Conn struct {
	str *http3.RequestStream
}

func (c *h3Conn) Read(p []byte) (int, error)  { return c.str.Read(p) }
func (c *h3Conn) Write(p []byte) (int, error) { return c.str.Write(p) }
func (c *h3Conn) Close() error {
	c.str.CancelRead(0)
	return c.str.Close()
}
func (c *h3Conn) CloseWrite() error                  { return c.str.Close() }
func (c *h3Conn) LocalAddr() net.Addr                { return &net.TCPAddr{} }
func (c *h3Conn) RemoteAddr() net.Addr               { return &net.TCPAddr{} }
func (c *h3Conn) SetDeadline(t time.Time) error      { return c.str.SetDeadline(t) }
func (c *h3Conn) SetReadDeadline(t time.Time) error  { return c.str.SetReadDeadline(t) }
func (c *h3Conn) SetWriteDeadline(t time.Time) error { return c.str.SetWriteDeadline(t) }

var _ io.ReadWriteCloser = (*h3Conn)(nil)
