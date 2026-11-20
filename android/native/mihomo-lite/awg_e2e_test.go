package main

import (
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strings"
	"testing"

	awg3 "github.com/metacubex/amneziawg-go/device"
	awg1 "github.com/metacubex/amneziawg-go/device_v1"
	oldtun "github.com/metacubex/tailscale-wireguard-go/tun"
	"github.com/metacubex/tailscale-wireguard-go/tun/netstack"
	"github.com/metacubex/wireguard-go/conn"
	wg "github.com/metacubex/wireguard-go/device"
	"github.com/metacubex/wireguard-go/tun"
)

type awgTestTun struct {
	oldtun.Device
	events chan tun.Event
}

func (t *awgTestTun) Events() <-chan tun.Event { return t.events }
func adaptAWGTun(d oldtun.Device) tun.Device {
	t := &awgTestTun{Device: d, events: make(chan tun.Event, 4)}
	go func() {
		defer close(t.events)
		for e := range d.Events() {
			t.events <- tun.Event(e)
		}
	}()
	return t
}

type awgTestDevice interface {
	IpcSet(string) error
	IpcGet() (string, error)
	Up() error
	Close()
}

// Tests the SAME pinned mihomo adapter/runtime and JSON options as libmhx.so. No host TUN/root/network is required.
// A real encrypted UDP tunnel carries a TCP echo through a gVisor server, including AWG 3.1 header protection/trailers.
func TestWGAndAWGVersions(t *testing.T) {
	for _, version := range []string{"wg", "1.5", "2.0", "3.0", "3.1", "3.1-trailers"} {
		t.Run(version, func(t *testing.T) {
			sk, _ := ecdh.X25519().GenerateKey(rand.Reader)
			ck, _ := ecdh.X25519().GenerateKey(rand.Reader)
			b64 := func(b []byte) string { return base64.StdEncoding.EncodeToString(b) }
			hx := func(b []byte) string { return hex.EncodeToString(b) }
			td, server, err := netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.77.0.1")}, nil, 1280)
			if err != nil {
				t.Fatal("server stack:", err)
			}
			logger := &wg.Logger{Verbosef: func(string, ...any) {}, Errorf: func(string, ...any) {}}
			var dev awgTestDevice
			opts := map[string]any{"jc": 2, "jmin": 10, "jmax": 20, "s1": 20, "s2": 20, "s3": 20, "s4": 20, "h1": "11-20", "h2": "21-30", "h3": "31-40", "h4": "41-50", "i1": "<r 16>", "i2": "<r 17>", "i3": "<r 18>", "i4": "<r 19>", "i5": "<r 20>"}
			ipc := "private_key=" + hx(sk.Bytes()) + "\nlisten_port=0\n"
			switch version {
			case "wg":
				dev = wg.NewDevice(adaptAWGTun(td), conn.NewDefaultBind(), logger, 1)
			case "1.5", "2.0":
				dev = awg1.NewDevice(adaptAWGTun(td), conn.NewDefaultBind(), logger, 1)
				if version == "1.5" {
					opts["j1"] = "<r 16>"
					opts["j2"] = "<r 17>"
					opts["j3"] = "<r 18>"
					opts["itime"] = 10
				}
			default:
				dev = awg3.NewDevice(adaptAWGTun(td), conn.NewDefaultBind(), logger, 1)
				opts["version"] = 3
				opts["header-protection-key"] = b64(sk.Bytes())
				opts["content-padding-addition"] = "1-16"
				opts["rekey-after-time"] = "120-130"
				opts["rekey-timeout"] = "5-6"
				opts["reject-after-time"] = "180-190"
				opts["keepalive-timeout"] = "10-12"
				opts["max-handshake-attempts"] = "18-20"
				if strings.HasPrefix(version, "3.1") {
					opts["random-trailers"] = true
					opts["disable-cookies"] = true
					if version == "3.1-trailers" {
						delete(opts, "content-padding-addition")
					}
				}
			}
			defer dev.Close()
			if version != "wg" {
				for k, v := range opts {
					if k == "version" {
						continue
					}
					if k == "header-protection-key" {
						v = hx(sk.Bytes())
					}
					if b, ok := v.(bool); ok {
						if b {
							v = 1
						} else {
							v = 0
						}
					}
					ipc += strings.ReplaceAll(k, "-", "_") + "=" + fmt.Sprint(v) + "\n"
				}
			}
			ipc += "public_key=" + hx(ck.PublicKey().Bytes()) + "\nallowed_ip=10.77.0.2/32\n"
			if err = dev.IpcSet(ipc); err != nil {
				t.Fatal("server IPC rejected (keys redacted)")
			}
			if err = dev.Up(); err != nil {
				t.Fatal("server up:", err)
			}
			state, err := dev.IpcGet()
			if err != nil {
				t.Fatal("server IPC get")
			}
			var port string
			for _, l := range strings.Split(state, "\n") {
				if strings.HasPrefix(l, "listen_port=") {
					port = strings.TrimPrefix(l, "listen_port=")
				}
			}
			ln, err := server.ListenTCP(&net.TCPAddr{IP: net.ParseIP("10.77.0.1"), Port: 8080})
			if err != nil {
				t.Fatal(err)
			}
			defer ln.Close()
			go func() {
				for {
					c, e := ln.Accept()
					if e != nil {
						return
					}
					go func() { defer c.Close(); io.Copy(c, c) }()
				}
			}()
			proxy := map[string]any{"type": "wireguard", "name": "awg-test", "server": "127.0.0.1", "port": port, "ip": "10.77.0.2", "private-key": b64(ck.Bytes()), "public-key": b64(sk.PublicKey().Bytes()), "allowed-ips": []string{"10.77.0.0/24"}, "mtu": 1280, "workers": 1, "udp": true}
			if version != "wg" {
				proxy["amnezia-wg-option"] = opts
			}
			d, err := newMihomoDialer(proxy)
			if err != nil {
				t.Fatal("client rejected (keys redacted)")
			}
			defer d.p.Close()
			roundtrip(t, d, "10.77.0.1:8080")
		})
	}
}
