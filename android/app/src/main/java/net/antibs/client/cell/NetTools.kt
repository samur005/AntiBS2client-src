package net.antibs.client.cell

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.URL

/** 1.0.56: ping / traceroute / DNS / public IP — plain system tools, nothing touches the VPN setup. */
object NetTools {
    private fun run(vararg cmd: String, timeoutMs: Long = 15_000): String = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = StringBuilder(); val t = Thread { p.inputStream.bufferedReader().forEachLine { out.append(it).append('\n') } }; t.start()
        if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) p.destroy()
        t.join(1000); out.toString()
    }.getOrElse { "Ошибка: ${it.message}" }

    private fun safeHost(h: String) = h.trim().takeIf { it.matches(Regex("[A-Za-z0-9.:\\-]{1,253}")) }

    suspend fun ping(host: String): String = withContext(Dispatchers.IO) {
        val h = safeHost(host) ?: return@withContext "Неверный адрес"
        run("/system/bin/ping", "-c", "4", "-W", "2", h)
    }

    suspend fun traceroute(host: String, onHop: (String) -> Unit) = withContext(Dispatchers.IO) {
        val h = safeHost(host) ?: run { onHop("Неверный адрес"); return@withContext }
        val target = runCatching { InetAddress.getByName(h).hostAddress }.getOrNull() ?: run { onHop("Не удалось разрешить $h"); return@withContext }
        for (ttl in 1..30) {
            val o = run("/system/bin/ping", "-c", "1", "-W", "1", "-t", ttl.toString(), target, timeoutMs = 3000)
            val from = Regex("[Ff]rom ([0-9.]+)").find(o)?.groupValues?.get(1) ?: Regex("bytes from ([0-9.]+)").find(o)?.groupValues?.get(1)
            val ms = Regex("time=([0-9.]+)").find(o)?.groupValues?.get(1)
            onHop("%2d  %s%s".format(ttl, from ?: "*", ms?.let { "  $it мс" } ?: ""))
            if (from == target) break
        }
    }

    suspend fun dns(host: String): String = withContext(Dispatchers.IO) {
        val h = safeHost(host) ?: return@withContext "Неверный адрес"
        runCatching { InetAddress.getAllByName(h).joinToString("\n") { it.hostAddress ?: "" } }.getOrElse { "Ошибка: ${it.message}" }
    }

    /** Через текущий маршрут: при включённом VPN покажет адрес выхода VPN. */
    suspend fun publicIp(): String = withContext(Dispatchers.IO) {
        runCatching { (URL("https://api.ipify.org").openConnection() as java.net.HttpURLConnection).run { connectTimeout = 6000; readTimeout = 6000; inputStream.bufferedReader().readText().trim() } }
            .getOrElse { "Ошибка: ${it.message}" }
    }
}
