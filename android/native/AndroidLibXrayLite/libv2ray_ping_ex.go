package libv2ray

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"time"

	corenet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/serial"
	core "github.com/xtls/xray-core/core"
	coreserial "github.com/xtls/xray-core/infra/conf/serial"
)

// AntiBS: URL test with selectable HTTP method, session mode and timeout.
//
//	method: "GET" | "HEAD"
//	mode:   0 = Default  (two requests, each through a fresh core instance; best of 2)
//	        1 = Double   (one core instance, keep-alive off; best of 2)
//	        2 = KeepAlive(one core instance + one transport; best of 2, i.e. the reused TLS conn)
//	timeoutMs: per-request timeout (whole test is bounded by 2*timeout + 2s)
func MeasureOutboundDelayEx(configContent string, url string, method string, mode int32, timeoutMs int32) (int64, error) {
	if timeoutMs <= 0 {
		timeoutMs = 5000
	}
	method = strings.ToUpper(strings.TrimSpace(method))
	if method != "HEAD" {
		method = "GET"
	}
	if url == "" {
		url = "https://www.gstatic.com/generate_204"
	}
	timeout := time.Duration(timeoutMs) * time.Millisecond
	ctx, cancel := context.WithTimeout(context.Background(), 2*timeout+2*time.Second)
	defer cancel()

	newInst := func() (*core.Instance, error) {
		config, err := coreserial.LoadJSONConfig(strings.NewReader(configContent))
		if err != nil {
			return nil, fmt.Errorf("config load error: %w", err)
		}
		config.Inbound = nil
		var essentialApp []*serial.TypedMessage
		for _, app := range config.App {
			if app.Type == "xray.app.proxyman.OutboundConfig" ||
				app.Type == "xray.app.dispatcher.Config" ||
				app.Type == "xray.app.log.Config" {
				essentialApp = append(essentialApp, app)
			}
		}
		config.App = essentialApp
		inst, err := core.New(config)
		if err != nil {
			return nil, fmt.Errorf("instance creation failed: %w", err)
		}
		if err := inst.Start(); err != nil {
			return nil, fmt.Errorf("startup failed: %w", err)
		}
		return inst, nil
	}
	newTransport := func(inst *core.Instance, keepAlive bool) *http.Transport {
		return &http.Transport{
			TLSHandshakeTimeout: timeout,
			DisableKeepAlives:   !keepAlive,
			DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
				dest, err := corenet.ParseDestination(fmt.Sprintf("%s:%s", network, addr))
				if err != nil {
					return nil, err
				}
				return core.Dial(ctx, inst, dest)
			},
		}
	}
	once := func(tr *http.Transport) (int64, error) {
		client := &http.Client{Transport: tr, Timeout: timeout}
		req, err := http.NewRequestWithContext(ctx, method, url, nil)
		if err != nil {
			return -1, err
		}
		start := time.Now()
		resp, err := client.Do(req)
		if err != nil {
			return -1, err
		}
		_, _ = io.Copy(io.Discard, resp.Body)
		resp.Body.Close()
		if resp.StatusCode < 200 || resp.StatusCode >= 400 {
			return -1, fmt.Errorf("invalid status: %s", resp.Status)
		}
		return time.Since(start).Milliseconds(), nil
	}

	best := int64(-1)
	var lastErr error
	record := func(d int64, err error) {
		if err != nil {
			lastErr = err
			return
		}
		if best < 0 || d < best {
			best = d
		}
	}

	switch mode {
	case 1, 2:
		inst, err := newInst()
		if err != nil {
			return -1, err
		}
		defer inst.Close()
		tr := newTransport(inst, mode == 2)
		defer tr.CloseIdleConnections()
		for i := 0; i < 2; i++ {
			if ctx.Err() != nil {
				break
			}
			record(once(tr))
		}
	default:
		for i := 0; i < 2; i++ {
			if ctx.Err() != nil {
				break
			}
			inst, err := newInst()
			if err != nil {
				return -1, err
			}
			tr := newTransport(inst, false)
			record(once(tr))
			tr.CloseIdleConnections()
			inst.Close()
		}
	}
	if best < 0 {
		if lastErr == nil {
			lastErr = errors.New("timeout")
		}
		return -1, lastErr
	}
	return best, nil
}
