package net.antibs.client.engine

/** StatsService.QueryStats over [XrayApi] (desktop replacement for libv2ray's queryAllOutboundTrafficStats). */
object XrayStats {
    class Delta(val proxyUp: Long, val proxyDown: Long, val directUp: Long, val directDown: Long)

    fun delta(apiPort: Int): Delta? {
        val req = XrayApi.Proto.string(1, "outbound>>>") + XrayApi.Proto.varint(2, 1)
        val resp = XrayApi.call(apiPort, "xray.app.stats.command.StatsService/QueryStats", req) ?: return null
        var pu = 0L; var pd = 0L; var du = 0L; var dd = 0L
        for (f in XrayApi.Proto.fields(resp)) {
            if (f.no != 1 || f.bytes == null) continue
            var name = ""; var v = 0L
            for (g in XrayApi.Proto.fields(f.bytes)) { if (g.no == 1 && g.bytes != null) name = String(g.bytes); if (g.no == 2) v = g.varint }
            val parts = name.split(">>>")
            if (parts.size < 4) continue
            val tag = parts[1]; val up = parts[3] == "uplink"
            when {
                tag == "direct" -> if (up) du += v else dd += v
                XrayCoreTags.isProxyTag(tag) -> if (up) pu += v else pd += v
            }
        }
        return Delta(pu, pd, du, dd)
    }
}

object XrayCoreTags {
    fun isProxyTag(tag: String): Boolean = tag.isNotEmpty() && tag !in setOf("direct", "block", "dns-out", "api", "blackhole", "freedom", "dns")
}
