// Thin wrapper: libmieru.so run  — uses MIERU_CONFIG_JSON_FILE (stock mieru client behavior).
// rpcPort=0 (mobile mode) so no gRPC lifecycle daemon is required.
package main

import (
	"os"

	"github.com/enfein/mieru/pkg/appctl"
	"github.com/enfein/mieru/pkg/cli"
	"github.com/enfein/mieru/pkg/log"
)

func main() {
	if len(os.Args) > 1 && os.Args[1] == "version" {
		println("mieru", "1.15.1")
		return
	}
	appctl.SetAppType(appctl.CLIENT_APP)
	cli.RegisterClientCommands()
	// Force "run" so the binary is a foreground socks5 process when invoked without args.
	if len(os.Args) == 1 {
		os.Args = append(os.Args, "run")
	}
	if err := cli.ParseAndExecute(); err != nil {
		log.Fatalf("%v", err)
	}
}
