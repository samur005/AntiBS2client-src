//go:build with_naive_outbound

package main

import (
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/protocol/naive"
)

func registerNaive(r *outbound.Registry) { naive.RegisterOutbound(r) }
