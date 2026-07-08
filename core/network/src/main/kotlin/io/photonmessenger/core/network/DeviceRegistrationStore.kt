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
import kotlinx.coroutines.flow.first

/**
 * The device registration this app last completed against the Director: which [userId] the device
 * key ([deviceId]) was registered under, and on which super node ([baseUrl] + [nodeId]). The connect
 * path skips re-registration while this record matches the current identity + node + device key, and
 * treats any mismatch as "not registered" (user or node changed).
 */
data class DeviceRegistration(
    val userId: String,
    val baseUrl: String,
    val nodeId: String?,
    val deviceId: String,
) {
    /** True when this record covers exactly the given (user, node, device key) triple. */
    fun matches(userId: String, config: DirectorConfig, deviceId: String): Boolean =
        this.userId == userId &&
            this.baseUrl == config.baseUrl &&
            this.nodeId == config.nodeId &&
            this.deviceId == deviceId
}

/**
 * Persists the completed device registration (device registration management). Written after a
 * successful registration (onboarding, silent connect path, or multi-device pairing) and cleared on
 * sign-out or identity change; a node change invalidates by comparison, not by clearing.
 */
class DeviceRegistrationStore(
    private val dataStore: DataStore<Preferences>,
) {
    /** The last completed registration, or null when none (or an incomplete record) is stored. */
    suspend fun get(): DeviceRegistration? {
        val prefs = dataStore.data.first()
        return DeviceRegistration(
            userId = prefs[USER_ID] ?: return null,
            baseUrl = prefs[BASE_URL] ?: return null,
            nodeId = prefs[NODE_ID],
            deviceId = prefs[DEVICE_ID] ?: return null,
        )
    }

    suspend fun set(registration: DeviceRegistration) {
        dataStore.edit {
            it[USER_ID] = registration.userId
            it[BASE_URL] = registration.baseUrl
            if (registration.nodeId != null) it[NODE_ID] = registration.nodeId else it.remove(NODE_ID)
            it[DEVICE_ID] = registration.deviceId
        }
    }

    suspend fun clear() {
        dataStore.edit {
            it.remove(USER_ID)
            it.remove(BASE_URL)
            it.remove(NODE_ID)
            it.remove(DEVICE_ID)
        }
    }

    private companion object {
        val USER_ID = stringPreferencesKey("device_reg_user_id")
        val BASE_URL = stringPreferencesKey("device_reg_base_url")
        val NODE_ID = stringPreferencesKey("device_reg_node_id")
        val DEVICE_ID = stringPreferencesKey("device_reg_device_id")
    }
}
