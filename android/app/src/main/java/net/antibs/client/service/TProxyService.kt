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

        /** 2.0.43: pure builder in [net.antibs.client.data.HevConfig] (unit-tested without the native library). */
        fun buildConfig(socksPort: Int, mtu: Int, ipv4: String, ipv6: String?, user: String = "", pass: String = ""): String =
            net.antibs.client.data.HevConfig.build(socksPort, mtu, ipv4, ipv6, user, pass)
    }
}
