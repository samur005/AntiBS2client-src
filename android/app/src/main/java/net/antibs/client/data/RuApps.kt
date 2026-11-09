package net.antibs.client.data

/**
 * pt420u: ready-made list of common Russian apps for per-app routing (send them outside the VPN).
 * Every package id was checked to exist on Google Play or RuStore (Nov 2026); none are guessed.
 * The user's own additions/removals are kept apart from this list, so a new bundled list never undoes them.
 * Pure logic only (no Android), so it is unit-tested directly.
 */
object RuApps {
    val BUNDLED: Set<String> = linkedSetOf(
        // banks / finance
        "ru.sberbankmobile", "ru.vtb24.mobilebanking.android", "ru.alfabank.mobile.android", "com.idamob.tinkoff.android",
        "ru.tinkoff.investing", "ru.raiffeisennews", "ru.gazprombank.android.mobilebank.app", "com.openbank", "ru.mkb.mobile",
        "ru.sovcomcard.halva.v1", "ru.ozon.fintech.finance", "com.yandex.bank", "ru.rshb.dbo", "ru.akbars.mobile",
        // marketplaces / shops / food
        "ru.ozon.app.android", "com.wildberries.ru", "ru.beru.android", "com.avito.android", "ru.megamarket.marketplace",
        "ru.dns.shop.android", "ru.perekrestok.app", "ru.vkusvill", "ru.tander.magnit", "ru.foodfox.client", "ru.sbcs.store",
        "ru.instamart", "com.yandex.lavka", "ru.detmir.dmbonus", "ru.sportmaster.app", "ru.leroymerlin.mobile",
        "ru.dodopizza.app", "ru.kfc.kfc_delivery",
        // government services
        "ru.rostel", "ru.gosuslugi.goskey", "ru.fns.lkfl", "ru.altarix.mos.pgu",
        // carriers
        "ru.mts.mymts", "ru.megafon.mlk", "ru.beeline.services", "ru.tele2.mytele2",
        // maps / transport / travel
        "ru.yandex.yandexmaps", "ru.yandex.yandexnavi", "ru.dublgis.dgismobile", "ru.yandex.taxi", "ru.mosmetro.metro",
        "ru.rzd.pass", "ru.tutu.tutu_emp", "ru.aviasales", "ru.aeroflot", "ru.s7.android", "ru.utair.android",
        // social / messengers / mail
        "com.vkontakte.android", "ru.ok.android", "ru.oneme.app", "com.vk.calls", "ru.mail.mailapp", "ru.mail.cloud",
        "ru.yandex.mail", "ru.yandex.disk", "ru.yandex.telemost",
        // streaming / music
        "com.vk.vkvideo", "ru.rutube.app", "ru.kinopoisk", "ru.ivi.client", "ru.more.play", "ru.start.androidmobile",
        "ru.rt.video.app.mobile", "ru.yandex.music", "ru.mts.music.android", "com.zvooq.openplay",
        // other services
        "ru.yandex.searchplugin", "com.yandex.browser", "ru.yandex.translate", "ru.yandex.weatherplugin", "ru.vk.store",
        "ru.hh.android", "ru.cian.main", "ru.auto.ara", "ru.farpost.dromfilter"
    )

    private val PKG = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    fun isValidPackage(p: String) = p.length <= 255 && PKG.matches(p)

    /** Bundled + user-added − user-removed. */
    fun effective(added: Set<String>, removed: Set<String>): Set<String> = (BUNDLED + added) - removed

    /** Only what is installed on this phone (never ABS itself), sorted for display. */
    fun installedFromList(installed: Set<String>, added: Set<String>, removed: Set<String>, self: String): List<String> =
        effective(added, removed).filter { it in installed && it != self }.sorted()

    /** User puts [pkg] on the list: it leaves the removed set; only non-bundled ids are kept as additions. */
    fun add(pkg: String, added: Set<String>, removed: Set<String>): Pair<Set<String>, Set<String>> {
        if (!isValidPackage(pkg)) return added to removed
        return (if (pkg in BUNDLED) added - pkg else added + pkg) to (removed - pkg)
    }

    /** User takes [pkg] off the list: a bundled id is remembered as removed so a list update keeps it off. */
    fun remove(pkg: String, added: Set<String>, removed: Set<String>): Pair<Set<String>, Set<String>> =
        (added - pkg) to (if (pkg in BUNDLED) removed + pkg else removed - pkg)

    /** What the VPN builder gets. Android forbids mixing allowed and disallowed apps in one builder. */
    data class Plan(val allowed: List<String>, val disallowed: List<String>)

    /**
     * [ruOn] is true only after the user confirmed applying the list. OFF / «All except»: the Russian apps join the
     * excluded apps. «Only select»: every unselected app is already outside the VPN, and an app the user picked
     * by hand stays in the VPN (an explicit choice wins), so that mode is unchanged.
     */
    /**
     * [mode] = AdvancedSettings.perAppMode: off / include (ALLOWLIST) / bypass (BLOCKLIST).
     * When [ruOn], installed Russian apps join the disallowed list (except in «include» mode, which is unchanged).
     */
    fun plan(mode: String, include: List<String>, bypass: List<String>, self: String, ruOn: Boolean, ruInstalled: Collection<String>): Plan {
        val ru = if (ruOn) ruInstalled.filter { it != self && it.isNotBlank() } else emptyList()
        return when (mode) {
            "include" -> {
                val list = include.filter { it.isNotBlank() && it != self }.distinct()
                if (list.isEmpty()) Plan(emptyList(), listOf(self)) // empty include = all apps (self still bypasses)
                else Plan(list, emptyList())
            }
            "bypass" -> Plan(emptyList(), (bypass.filter { it.isNotBlank() } + self + ru).distinct())
            else -> Plan(emptyList(), (listOf(self) + ru).distinct())
        }
    }

    fun planNote(mode: String, plan: Plan, ruOn: Boolean, ruCount: Int): String = buildString {
        when (mode) {
            "include" -> if (plan.allowed.isEmpty()) append("per-app: список «ВКЛ» пуст — проксируются все приложения")
            else append("per-app: через VPN только ${plan.allowed.size} прилож.")
            "bypass" -> append("per-app: в обход VPN ${plan.disallowed.size - 1} прилож.")
        }
        if (ruOn && ruCount > 0 && mode != "include") {
            if (isNotEmpty()) append(" · ")
            append("российские в обход: $ruCount")
        }
    }
}

