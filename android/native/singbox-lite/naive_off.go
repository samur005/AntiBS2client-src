//go:build !with_naive_outbound

package main

import "github.com/sagernet/sing-box/adapter/outbound"

// linux test build (tools/sbx): cronet needs the Chromium toolchain there, so naive is Android-only.
func registerNaive(r *outbound.Registry) {}
