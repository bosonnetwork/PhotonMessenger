/*
 * Copyright (c) 2023 -      bosonnetwork.io
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.bosonnetwork.photon.app

import android.content.Context
import android.content.res.Configuration
import io.bosonnetwork.photon.core.model.AppLanguage
import java.util.Locale

/**
 * App-managed UI language.
 *
 * The selected language is persisted in a small SharedPreferences file so it can be read synchronously
 * from [android.app.Application.attachBaseContext] and [android.app.Activity.attachBaseContext] - long
 * before any coroutine or DataStore is available - and used to wrap every context's resources with the
 * chosen locale. Changing the language re-runs [wrap] for the whole process via a relaunch
 * ([AppRelauncher]), so activities, the application, notifications, and the foreground service all pick
 * up the new locale together.
 *
 * [AppLanguage.SYSTEM] applies no override, leaving Android's own locale resolution in place (which
 * falls back to the default English resources when the device language is unsupported).
 */
object LocaleManager {
    private const val PREFS = "app_locale"
    private const val KEY_TAG = "language_tag"

    /** The currently selected language (defaults to [AppLanguage.SYSTEM]). */
    fun current(context: Context): AppLanguage =
        AppLanguage.fromTag(prefs(context).getString(KEY_TAG, null))

    /**
     * Persists [language] as the app language. Apply it process-wide by relaunching afterwards.
     *
     * Uses a synchronous commit (not apply): the caller relaunches immediately via
     * [AppRelauncher.relaunch], which calls Runtime.exit(0) - an async apply() write can be lost when
     * the process dies before it flushes, silently dropping the selection. commit() blocks until the
     * value is on disk, which is fine for this one-off, user-initiated action.
     */
    fun setLanguage(context: Context, language: AppLanguage) {
        prefs(context).edit().putString(KEY_TAG, language.tag).commit()
    }

    /**
     * Returns a context whose resources use the selected language, or [base] unchanged for
     * [AppLanguage.SYSTEM]. Call from `attachBaseContext` in the Application and every Activity.
     */
    fun wrap(base: Context): Context {
        val language = current(base)
        if (language == AppLanguage.SYSTEM) return base
        val locale = Locale.forLanguageTag(language.tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
