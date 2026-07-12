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

package io.photonmessenger.feature.chat.data

import io.photonmessenger.feature.chat.model.UiAttachment
import javax.inject.Inject
import javax.inject.Singleton

/** What the user chose to forward: plain text or an existing attachment. */
sealed interface ForwardPayload {
    data class Text(val text: String) : ForwardPayload
    data class Attachment(val attachment: UiAttachment) : ForwardPayload
}

/**
 * Hands a forward payload from the chat screen to the forward picker. An attachment (especially inline
 * bytes) cannot ride a navigation string route, so the source chat stashes the chosen payload here and
 * the picker reads it back - a small process-scoped handoff, cleared once consumed.
 */
@Singleton
class ForwardPayloadStore @Inject constructor() {
    @Volatile
    private var pending: ForwardPayload? = null

    fun set(payload: ForwardPayload) { pending = payload }

    /** Returns the pending payload without clearing it (the picker may re-read across recompositions). */
    fun peek(): ForwardPayload? = pending

    fun clear() { pending = null }
}
