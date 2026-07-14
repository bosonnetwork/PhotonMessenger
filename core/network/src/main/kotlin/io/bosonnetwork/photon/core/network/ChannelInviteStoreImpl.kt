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
import androidx.datastore.preferences.core.stringSetPreferencesKey
import io.bosonnetwork.photon.core.model.ChannelInviteStore
import io.bosonnetwork.photon.core.model.InviteAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore-backed [ChannelInviteStore]. The joined and ignored invite message ids are kept as two
 * string sets; joining an id also drops it from the ignored set so [InviteAction.JOINED] always wins.
 */
class ChannelInviteStoreImpl(
    private val dataStore: DataStore<Preferences>,
) : ChannelInviteStore {

    override fun actions(): Flow<Map<String, InviteAction>> = dataStore.data.map { prefs ->
        val joined = prefs[JOINED] ?: emptySet()
        val ignored = prefs[IGNORED] ?: emptySet()
        buildMap {
            // Ignored first, then joined, so a message present in both resolves to JOINED.
            ignored.forEach { put(it, InviteAction.IGNORED) }
            joined.forEach { put(it, InviteAction.JOINED) }
        }
    }

    override suspend fun setAction(messageId: String, action: InviteAction) {
        dataStore.edit { prefs ->
            val joined = prefs[JOINED] ?: emptySet()
            val ignored = prefs[IGNORED] ?: emptySet()
            when (action) {
                InviteAction.JOINED -> {
                    prefs[JOINED] = joined + messageId
                    if (messageId in ignored) prefs[IGNORED] = ignored - messageId
                }
                InviteAction.IGNORED -> {
                    // A joined invite is terminal; do not let a later ignore hide it.
                    if (messageId !in joined) prefs[IGNORED] = ignored + messageId
                }
            }
        }
    }

    private companion object {
        val JOINED = stringSetPreferencesKey("channel_invites_joined")
        val IGNORED = stringSetPreferencesKey("channel_invites_ignored")
    }
}
