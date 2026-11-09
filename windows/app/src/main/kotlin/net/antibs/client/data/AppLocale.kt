package net.antibs.client.data

/** 2.0.30 Windows: no AppCompat — only [I18n] tag (ru/en/…; system → unsupported falls back to en). */
object AppLocale {
    fun apply(pref: String?) { I18n.use(AppLang.resolve(pref)) }
    fun bootstrap(pref: String?) = apply(pref)
}
