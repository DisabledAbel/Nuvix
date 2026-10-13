/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Applies the user's chosen UI language by wrapping a base [Context] with an overridden locale.
 *
 * Done at attachBaseContext time (rather than the newer per-app-language API) so it works the same
 * on every supported version — minSdk 23 through the latest — and on Fire OS, without pulling in
 * AppCompat. A blank tag means "follow the device", so nothing is overridden.
 */
object LocaleUtils {

    fun wrap(base: Context): Context = wrap(base, AppSettings.savedLanguageTag(base))

    fun wrap(base: Context, tag: String): Context {
        if (tag.isBlank()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
