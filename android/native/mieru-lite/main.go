// Thin wrapper: libmieru.so run  — uses MIERU_CONFIG_JSON_FILE (stock mieru client behavior).
// rpcPort=0 (mobile mode) so no gRPC lifecycle daemon is required.
// 2.0.44: mieru v3.39.0 (was v1.15.1, whose protocol current mita v3 servers no longer speak).
package main

import (
	"os"

	"github.com/enfein/mieru/v3/pkg/appctl"
	"github.com/enfein/mieru/v3/pkg/cli"
	"github.com/enfein/mieru/v3/pkg/log"
)

func main() {
	if len(os.Args) > 1 && os.Args[1] == "version" {
		println("mieru", "3.39.0")
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
