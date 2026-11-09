package net.antibs.client.data

import java.util.Locale

/**
 * 2.0.30: app language. Pref value [SYSTEM] follows the device locale; unsupported device
 * languages fall back to English. Existing installs that still have `"ru"` keep Russian.
 */
object AppLang {
    const val SYSTEM = "system"
    /** BCP-47 tags we ship (no fa). */
    data class Lang(val tag: String, val nativeName: String)

    val ALL = listOf(
        Lang(SYSTEM, ""), // label via I18n
        Lang("ru", "Русский"),
        Lang("en", "English"),
        Lang("uk", "Українська"),
        Lang("kk", "Қазақша"),
        Lang("uz", "O'zbekcha"),
        Lang("tr", "Türkçe"),
        Lang("es", "Español"),
        Lang("de", "Deutsch"),
        Lang("fr", "Français"),
        Lang("zh", "中文"),
        Lang("ar", "العربية"),
    )
    val TAGS = ALL.map { it.tag }.filter { it != SYSTEM }.toSet()

    fun normalize(pref: String?): String {
        val p = pref?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (p.isEmpty() || p == SYSTEM || p == "auto" || p == "default") return SYSTEM
        if (p in TAGS) return p
        // legacy / region tags
        val base = p.substringBefore('-').substringBefore('_')
        return if (base in TAGS) base else SYSTEM
    }

    /** Concrete tag used for strings + AppCompatDelegate (never "system"). */
    fun resolve(pref: String?, device: Locale = Locale.getDefault()): String {
        val n = normalize(pref)
        if (n != SYSTEM) return n
        val base = device.language.lowercase(Locale.ROOT)
        return if (base in TAGS) base else "en"
    }

    fun isRtl(tag: String): Boolean = resolve(tag) == "ar"

    fun nativeLabel(tag: String): String = when (normalize(tag)) {
        SYSTEM -> "" // caller uses I18n
        else -> ALL.firstOrNull { it.tag == normalize(tag) }?.nativeName ?: tag
    }
}
