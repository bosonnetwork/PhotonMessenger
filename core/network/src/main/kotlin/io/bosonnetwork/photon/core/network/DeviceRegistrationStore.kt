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
import kotlinx.coroutines.flow.first

/**
 * The super node this app last completed a device registration against ([baseUrl] + [nodeId]). The
 * user<->device binding is held authoritatively by the device key's owner record (KeyManager's
 * `device_key_owner`), so this snapshot only needs the node: the connect path skips re-registration
 * while the device key is still owned by the current user AND this node still matches the configured
 * Director. A node change invalidates by comparison, not by clearing.
 */
data class RegisteredNode(
    val baseUrl: String,
    val nodeId: String?,
) {
    /** True when this record covers the node in [config]. */
    fun matches(config: DirectorConfig): Boolean =
        baseUrl == config.baseUrl && nodeId == config.nodeId
}

/**
 * Persists the node of the completed device registration. Written after a successful registration
 * (onboarding, silent connect path, or multi-device pairing) and cleared on sign-out.
 */
class DeviceRegistrationStore(
    private val dataStore: DataStore<Preferences>,
) {
    /** The node last registered against, or null when none is stored. */
    suspend fun get(): RegisteredNode? {
        val prefs = dataStore.data.first()
        return RegisteredNode(
            baseUrl = prefs[BASE_URL] ?: return null,
            nodeId = prefs[NODE_ID],
        )
    }

    suspend fun set(node: RegisteredNode) {
        dataStore.edit {
            it[BASE_URL] = node.baseUrl
            if (node.nodeId != null) it[NODE_ID] = node.nodeId else it.remove(NODE_ID)
        }
    }

    suspend fun clear() {
        dataStore.edit {
            it.remove(BASE_URL)
            it.remove(NODE_ID)
        }
    }

    private companion object {
        val BASE_URL = stringPreferencesKey("device_reg_base_url")
        val NODE_ID = stringPreferencesKey("device_reg_node_id")
    }
}
