package net.antibs.client.service

/** JNI binding for hev-socks5-tunnel (built with -DPKGNAME=net/antibs/client/service, class TProxyService). */
class TProxyService {
    companion object {
        @JvmStatic @Suppress("FunctionName") external fun TProxyStartService(configPath: String, fd: Int): Boolean
        @JvmStatic @Suppress("FunctionName") external fun TProxyStopService(): Boolean
        @JvmStatic @Suppress("FunctionName") external fun TProxyIsRunning(): Boolean
        /** [txPackets, txBytes, rxPackets, rxBytes] */
        @JvmStatic @Suppress("FunctionName") external fun TProxyGetStats(): LongArray?

        init { System.loadLibrary("hev-socks5-tunnel") }

        fun buildConfig(socksPort: Int, mtu: Int, ipv4: String, ipv6: String?): String = buildString {
            appendLine("tunnel:")
            appendLine("  mtu: $mtu")
            appendLine("  ipv4: $ipv4")
            if (ipv6 != null) appendLine("  ipv6: '$ipv6'")
            appendLine("socks5:")
            appendLine("  port: $socksPort")
            appendLine("  address: 127.0.0.1")
            appendLine("  udp: 'udp'")
            appendLine("misc:")
            appendLine("  tcp-read-write-timeout: 300000")
            appendLine("  udp-read-write-timeout: 60000")
            appendLine("  log-level: warn")
        }
    }
}
