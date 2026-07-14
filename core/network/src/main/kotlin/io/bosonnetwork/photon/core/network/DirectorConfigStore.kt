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

package io.bosonnetwork.photon.core.network

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
        DirectorConfig(
            baseUrl = baseUrl,
            certificatePins = KnownDirectorPins.pinsFor(baseUrl),
            nodeId = prefs[NODE_ID]?.takeIf { it.isNotBlank() },
        )
    }

    suspend fun setBaseUrl(baseUrl: String) {
        dataStore.edit { it[BASE_URL] = baseUrl.trim().trimEnd('/') }
    }

    /**
     * Sets (or clears, when null/blank) the Director's Boson node id used to identity-pin its HTTPS
     * certificate (see [DirectorConfig.nodeId]). A self-signed Director requires this; a Director
     * fronted by a real CA certificate does not.
     */
    suspend fun setNodeId(nodeId: String?) {
        dataStore.edit {
            if (nodeId.isNullOrBlank()) it.remove(NODE_ID) else it[NODE_ID] = nodeId.trim()
        }
    }

    companion object {
        // OAuth providers (Google/GitHub) are discovered via
        // GET /api/v1/auth/providers.
        const val DEFAULT_DIRECTOR_URL = "https://your.super.node/"
        private val BASE_URL = stringPreferencesKey("director_base_url")
        private val NODE_ID = stringPreferencesKey("director_node_id")
    }
}
