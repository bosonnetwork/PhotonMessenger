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

package io.photonmessenger.core.network

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Persists the Director base URL (default + advanced override, M1-1) in DataStore and exposes it as
 * a [DirectorConfig]. The default endpoint is a deployment decision (spec D-2) and is overridable in
 * advanced settings.
 */
class DirectorConfigStore(
    private val dataStore: DataStore<Preferences>,
) {
    val config: Flow<DirectorConfig> = dataStore.data.map { prefs ->
        val baseUrl = prefs[BASE_URL]?.trimEnd('/') ?: DEFAULT_DIRECTOR_URL
        DirectorConfig(baseUrl = baseUrl, certificatePins = KnownDirectorPins.pinsFor(baseUrl))
    }

    suspend fun setBaseUrl(baseUrl: String) {
        dataStore.edit { it[BASE_URL] = baseUrl.trim().trimEnd('/') }
    }

    companion object {
        // D-2: default to the local dev super node. 10.0.2.2 is the host loopback as seen from the
        // Android emulator (the Director listens on host :9000). On a physical device, override with
        // the host's LAN IP (e.g. http://192.168.8.80:9000) in advanced settings; set the production
        // domain here once one exists. OAuth providers (google/github) are discovered via
        // GET /api/v1/auth/providers.
        const val DEFAULT_DIRECTOR_URL = "http://jmac.dev:9000"
        private val BASE_URL = stringPreferencesKey("director_base_url")
    }
}
