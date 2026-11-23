package net.antibs.client.data

/** What the add-servers sheet should do with pasted / scanned text. */
sealed class ImportOutcome {
    data class SubscriptionUrl(val url: String) : ImportOutcome()
    data class Servers(val entries: List<ServerEntry>) : ImportOutcome()
    data class Failed(val message: String) : ImportOutcome()
}

object ImportText {
    /** SAF documents may advertise application/octet-stream, so accept any MIME but bound reads before parsing. */
    fun readConfigFile(input: java.io.InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            require(out.size() + n <= 1024 * 1024) { "Конфигурация больше 1 МиБ" }
            out.write(buffer, 0, n)
        }
        return Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString()
    }

    fun classify(raw: String, subscriptionId: String? = null, fileName: String? = null): ImportOutcome {
        val text = raw.trim().removePrefix("\uFEFF")
        if (text.isEmpty()) return ImportOutcome.Failed("Пусто")
        if (WireGuardImport.looksLike(text)) {
            val p = try { WireGuardImport.profile(text, fileName) } catch (e: IllegalArgumentException) {
                return ImportOutcome.Failed(e.message ?: "WG/AWG: неверная конфигурация")
            } catch (_: IllegalStateException) { return ImportOutcome.Failed("WG/AWG: неполная или неподдерживаемая конфигурация") }
            return ImportOutcome.Servers(listOf(ServerEntry(LinkParser.stableId("wg", (subscriptionId ?: "") + text), EngineKind.XRAY, p.remarks, subscriptionId, xray = p)))
        }
        // 1.0.41: a pasted / shared .ovpn file
        if (OpenVpnConfig.looksLike(text)) {
            val s = OpenVpnConfig.summary(text)
            if (s.errors.isNotEmpty()) return ImportOutcome.Failed("OpenVPN: " + s.errors.joinToString("; "))
            val p = OpenVpnConfig.profile(text)
            return ImportOutcome.Servers(listOf(ServerEntry(LinkParser.stableId("o", (subscriptionId ?: "") + text), EngineKind.XRAY, p.remarks, subscriptionId, xray = p)))
        }
        if (text.startsWith("{") || text.startsWith("[")) {
            SubscriptionParser.parse(text, subscriptionId)?.let { q ->
                if (q.servers.isNotEmpty()) return ImportOutcome.Servers(q.servers)
            }
            val one = LinkParser.parse(text, subscriptionId)
            return if (one != null) ImportOutcome.Servers(listOf(one))
            else ImportOutcome.Failed("Не удалось разобрать JSON")
        }
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        if (lines.size == 1 && isSubscriptionUrl(lines[0])) return ImportOutcome.SubscriptionUrl(lines[0])
        val direct = lines.mapNotNull { LinkParser.parse(it, subscriptionId) }
        if (direct.isNotEmpty()) return ImportOutcome.Servers(direct)
        val dec = LinkParser.b64Decode(text)
        if (dec != null) {
            val inner = dec.lines().map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { LinkParser.parse(it, subscriptionId) }
            if (inner.isNotEmpty()) return ImportOutcome.Servers(inner)
        }
        return ImportOutcome.Failed("Не распознано: ссылка, подписка или JSON")
    }

    /**
     * https://host/token → subscription.
     * http(s)://user:pass@host:port or host:port without a path → proxy share-link.
     */
    fun isSubscriptionUrl(s: String): Boolean {
        val l = s.lowercase()
        if (!l.startsWith("http://") && !l.startsWith("https://")) return false
        val p = LinkParser.split(s) ?: return true
        if (!p.userInfo.isNullOrEmpty()) return false
        if (p.port > 0 && p.path.isEmpty()) return false
        return true
    }
}
