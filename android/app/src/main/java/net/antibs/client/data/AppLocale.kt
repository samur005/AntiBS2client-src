package net.antibs.client.data

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/** 2.0.30: apply [AdvancedSettings.language] via AppCompat per-app locales + [I18n]. */
object AppLocale {
    fun apply(pref: String?) {
        val resolved = AppLang.resolve(pref)
        I18n.use(resolved)
        val list = if (AppLang.normalize(pref) == AppLang.SYSTEM) {
            // empty LocaleList = follow system (Android 13+); we still set I18n to resolved tag
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(resolved)
        }
        AppCompatDelegate.setApplicationLocales(list)
    }

    fun bootstrap(app: Context, pref: String?) {
        // Ensure I18n matches before first frame; AppCompat may recreate later.
        I18n.use(AppLang.resolve(pref))
        apply(pref)
    }
}
