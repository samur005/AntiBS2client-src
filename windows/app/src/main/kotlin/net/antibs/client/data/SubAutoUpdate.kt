package net.antibs.client.data

/**
 * 1.0.25: «Автообновление подписок» — pure scheduling / interval logic (unit-tested).
 * The user's global setting always wins over the panel's profile-update-interval header,
 * which is only shown as a hint.
 */
object SubAutoUpdate {
    const val WORK_NAME = "antibs-sub-auto-update"
    const val DEFAULT_HOURS = 6
    const val MIN_HOURS = 1
    const val MAX_HOURS = 168
    val PRESETS = listOf(1, 3, 6, 12, 24)

    /** A periodic run may fire a bit early (WorkManager flex) — don't skip a subscription for a few minutes. */
    const val SLACK_MS = 10 * 60_000L

    fun normalizeHours(h: Int): Int = if (h <= 0) DEFAULT_HOURS else h.coerceIn(MIN_HOURS, MAX_HOURS)

    fun intervalMs(hours: Int): Long = normalizeHours(hours) * 3_600_000L

    fun parseCustomHours(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in MIN_HOURS..MAX_HOURS }

    fun label(hours: Int): String = when (val h = normalizeHours(hours)) {
        1 -> "Каждый час"
        else -> "Каждые $h ч"
    }

    fun isPreset(hours: Int): Boolean = normalizeHours(hours) in PRESETS

    fun isRemote(s: Subscription): Boolean = s.kind != "manual" && s.url.isNotBlank()

    /** Never fetched, clock moved back, or the interval (minus [SLACK_MS]) elapsed. */
    fun isDue(lastRefresh: Long, nowMs: Long, hours: Int): Boolean {
        if (lastRefresh <= 0 || lastRefresh > nowMs) return true
        return nowMs - lastRefresh >= intervalMs(hours) - SLACK_MS
    }

    fun dueSubscriptions(subs: List<Subscription>, nowMs: Long, enabled: Boolean, hours: Int): List<Subscription> =
        if (!enabled) emptyList() else subs.filter { isRemote(it) && isDue(it.lastRefresh, nowMs, hours) }

    /** Most recent fetch among remote subscriptions (0 = never). */
    fun lastUpdate(subs: List<Subscription>): Long = subs.filter(::isRemote).maxOfOrNull { it.lastRefresh } ?: 0L

    /** «Панель рекомендует: каждые 12 ч» from profile-update-interval headers, or null. */
    fun headerHint(subs: List<Subscription>): String? {
        val hs = subs.filter(::isRemote).map { it.updateIntervalHours }.filter { it > 0 }.distinct().sorted()
        if (hs.isEmpty()) return null
        return "Подписка рекомендует: " + hs.joinToString(" / ") { if (it == 1) "каждый час" else "каждые $it ч" } + " (используется ваша настройка)"
    }

    /** What WorkManager should run; null = cancel the periodic work. */
    data class Plan(val hours: Int, val unmeteredOnly: Boolean)

    fun plan(enabled: Boolean, hours: Int, wifiOnly: Boolean): Plan? =
        if (!enabled) null else Plan(normalizeHours(hours), wifiOnly)

    /** App open: refresh only if allowed on the current network. */
    fun mayRunNow(wifiOnly: Boolean, unmetered: Boolean): Boolean = !wifiOnly || unmetered

    /** Same host, connection-relevant fields only (display names / remarks don't matter). */
    private fun configOf(e: ServerEntry): ServerEntry =
        e.copy(name = "", xray = e.xray?.copy(remarks = ""), qwdtt = e.qwdtt?.copy(name = ""))

    /** The selected host disappeared or its connection parameters changed. */
    fun hostConfigChanged(before: ServerEntry?, after: ServerEntry?): Boolean {
        if (before == null) return false
        if (after == null) return true
        return configOf(before) != configOf(after)
    }

    /** Reconnect only when the tunnel is up and something it uses actually changed. */
    fun shouldReconnect(connected: Boolean, hostChanged: Boolean, routingAffectsTunnel: Boolean): Boolean =
        connected && (hostChanged || routingAffectsTunnel)
}
