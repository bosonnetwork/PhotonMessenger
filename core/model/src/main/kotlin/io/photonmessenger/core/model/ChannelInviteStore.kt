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

package io.photonmessenger.core.model

import kotlinx.coroutines.flow.Flow

/**
 * The local action the recipient has taken on a channel-invite message. Absence of an entry means the
 * invite is still pending (untouched). [JOINED] overrides [IGNORED] - an ignored invite can still be
 * joined later (until the ticket expires), so joining wins.
 */
enum class InviteAction { JOINED, IGNORED }

/**
 * Persists the recipient-side action state of channel-invite messages, keyed by the message id, so a
 * joined/ignored invite renders consistently across app restarts. Expiry is NOT stored - it is derived
 * from the ticket's expiration at render time.
 */
interface ChannelInviteStore {
    /** Live map of invite message id -> the action taken on it; absent ids are pending. */
    fun actions(): Flow<Map<String, InviteAction>>

    /** Records [action] for the invite message [messageId] (joining overrides a prior ignore). */
    suspend fun setAction(messageId: String, action: InviteAction)
}
