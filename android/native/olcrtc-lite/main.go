// libolcrtc.so — thin CLI around olcRTC's gomobile API (github.com/openlibrecommunity/olcrtc/mobile, WTFPL).
//
//	libolcrtc.so run  <cfg.json>                    → prints "READY 127.0.0.1:<port>", runs until stdin closes / SIGTERM
//	libolcrtc.so ping <cfg.json> <url> <timeoutMs>  → prints "PING <ms>" or "ERR <message>"
//	libolcrtc.so check <cfg.json> <timeoutMs>       → RTC startup latency, "PING <ms>" or "ERR …"
//	libolcrtc.so version
package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"os/signal"
	"strconv"
	"syscall"

	"github.com/openlibrecommunity/olcrtc/mobile"
)

type cfg struct {
	Provider  string `json:"provider"`
	Transport string `json:"transport"`
	Room      string `json:"room"`
	Channel   string `json:"channel"`
	Key       string `json:"key"`
	DNS       string `json:"dns"`
	Port      int    `json:"port"`
	Token     string `json:"token"`
	DeviceID  string `json:"device_id"`
	Debug     bool   `json:"debug"`
	VP8FPS    int    `json:"vp8_fps"`
	VP8Batch  int    `json:"vp8_batch"`
	SEIFPS    int    `json:"sei_fps"`
	SEIBatch  int    `json:"sei_batch"`
	SEIFrag   int    `json:"sei_frag"`
	SEIAck    int    `json:"sei_ack_ms"`
	VideoW    int    `json:"video_w"`
	VideoH    int    `json:"video_h"`
	VideoFPS  int    `json:"video_fps"`
	VideoCod  string `json:"video_codec"`
	QRSize    int    `json:"video_qr_size"`
	QRRec     string `json:"video_qr_recovery"`
	TileMod   int    `json:"video_tile_module"`
	TileRS    int    `json:"video_tile_rs"`
}

func or(v, d int) int {
	if v > 0 {
		return v
	}
	return d
}

func fail(format string, a ...any) {
	fmt.Printf("ERR "+format+"\n", a...)
	os.Exit(1)
}

func load(path string) (*mobile.Runtime, cfg) {
	b, err := os.ReadFile(path)
	if err != nil {
		fail("read config: %v", err)
	}
	var c cfg
	if err := json.Unmarshal(b, &c); err != nil {
		fail("parse config: %v", err)
	}
	r := mobile.New()
	must := func(err error) {
		if err != nil {
			fail("%v", err)
		}
	}
	must(r.SetProvider(c.Provider))
	if c.Transport != "" {
		must(r.SetTransport(c.Transport))
	}
	must(r.SetRoom(c.Room))
	if c.Channel != "" {
		r.SetChannel(c.Channel)
	}
	must(r.SetKey(c.Key))
	if c.DNS != "" {
		must(r.SetDNS(c.DNS))
	}
	if c.Port > 0 {
		must(r.SetSocksPort(c.Port))
	}
	if c.Token != "" {
		r.SetProviderToken(c.Token)
	}
	if c.DeviceID != "" {
		r.SetDeviceID(c.DeviceID)
	}
	r.SetDebug(c.Debug)
	if c.VP8FPS > 0 || c.VP8Batch > 0 {
		must(r.SetVP8Options(or(c.VP8FPS, 30), or(c.VP8Batch, 64)))
	}
	if c.SEIFPS > 0 || c.SEIBatch > 0 || c.SEIFrag > 0 || c.SEIAck > 0 {
		must(r.SetSEIOptions(or(c.SEIFPS, 30), or(c.SEIBatch, 64), or(c.SEIFrag, 900), or(c.SEIAck, 2000)))
	}
	if c.VideoW > 0 || c.VideoH > 0 || c.VideoFPS > 0 || c.VideoCod != "" || c.QRSize > 0 || c.QRRec != "" || c.TileMod > 0 || c.TileRS > 0 {
		codec := c.VideoCod
		if codec == "" {
			codec = "qrcode"
		}
		rec := c.QRRec
		if rec == "" {
			rec = "low"
		}
		must(r.SetVideoOptions(or(c.VideoW, 1920), or(c.VideoH, 1080), or(c.VideoFPS, 30), c.QRSize, rec, codec, or(c.TileMod, 4), c.TileRS))
	}
	return r, c
}

func main() {
	if len(os.Args) < 2 {
		fail("usage: run|ping|check <cfg.json>")
	}
	switch os.Args[1] {
	case "version":
		fmt.Println("olcrtc mobile f3ad8fb (2026-09-30)")
	case "run":
		if len(os.Args) < 3 {
			fail("no config")
		}
		r, c := load(os.Args[2])
		if err := r.Start(); err != nil {
			fail("start: %v", err)
		}
		if err := r.WaitReady(60000); err != nil {
			fail("ready: %v", err)
		}
		fmt.Printf("READY 127.0.0.1:%d\n", c.Port)
		done := make(chan struct{}, 2)
		go func() {
			s := bufio.NewScanner(os.Stdin)
			for s.Scan() {
				if s.Text() == "STOP" {
					break
				}
			}
			done <- struct{}{}
		}()
		sig := make(chan os.Signal, 1)
		signal.Notify(sig, syscall.SIGTERM, syscall.SIGINT)
		go func() { <-sig; done <- struct{}{} }()
		go func() {
			for r.IsRunning() {
				select {
				case <-done:
					_ = r.Stop(3000)
					os.Exit(0)
				default:
				}
				waitMs(500)
			}
			fmt.Println("ERR tunnel stopped:", r.State())
			os.Exit(2)
		}()
		<-done
		_ = r.Stop(3000)
	case "ping", "check":
		if len(os.Args) < 3 {
			fail("no config")
		}
		r, c := load(os.Args[2])
		var ms int64
		var err error
		if os.Args[1] == "ping" {
			url := ""
			if len(os.Args) > 3 {
				url = os.Args[3]
			}
			t := 15000
			if len(os.Args) > 4 {
				t, _ = strconv.Atoi(os.Args[4])
			}
			ms, err = r.Ping(c.Provider, c.Transport, c.Room, c.DeviceID, c.Key, c.Port, t, url, or(c.VP8FPS, 30), or(c.VP8Batch, 64))
		} else {
			t := 15000
			if len(os.Args) > 3 {
				t, _ = strconv.Atoi(os.Args[3])
			}
			ms, err = r.Check(c.Provider, c.Transport, c.Room, c.DeviceID, c.Key, c.Port, t, or(c.VP8FPS, 30), or(c.VP8Batch, 64))
		}
		if err != nil {
			fail("%v", err)
		}
		fmt.Printf("PING %d\n", ms)
	default:
		fail("unknown command %q", os.Args[1])
	}
}
