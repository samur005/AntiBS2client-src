package net.antibs.client.engine

import android.content.Context
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Desktop: «напрямую» = no proxy (the app's own JVM process is excluded from the TUN). */
object SpeedNet {
    class Direct(val client: OkHttpClient, val label: String)

    fun direct(@Suppress("UNUSED_PARAMETER") ctx: Context): Direct = Direct(
        OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build(),
        "сеть",
    )
}

/** Persisted speed-test history (newest first, at most [SpeedHistory.MAX]). */
class SpeedHistoryStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("speedtest", Context.MODE_PRIVATE)
    fun load(): List<SpeedRecord> = SpeedHistory.decode(prefs.getString("history", null))
    fun save(list: List<SpeedRecord>) { prefs.edit().putString("history", SpeedHistory.encode(list)).apply() }
}
