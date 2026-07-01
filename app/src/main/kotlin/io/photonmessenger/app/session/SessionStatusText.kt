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

package io.photonmessenger.app.session

/**
 * Pure presentation logic for the connection banner (F1 / M1-16), separated from the composable so
 * it can be unit-tested. The banner is hidden when the session is idle (signed out) or fully READY.
 */

/** True when the banner should be shown (any transient/failed state). */
fun SessionStatus.isBannerVisible(): Boolean =
    phase != SessionPhase.IDLE && phase != SessionPhase.READY

/** User-facing banner text for the current phase; empty for the hidden states. */
fun SessionStatus.bannerLabel(): String = when (phase) {
    SessionPhase.DISCOVERING, SessionPhase.CONNECTING -> "Connecting..."
    SessionPhase.CONNECTED -> "Securing connection..."
    SessionPhase.DISCONNECTED -> "Reconnecting..."
    SessionPhase.FAILED -> message ?: "Couldn't connect"
    SessionPhase.IDLE, SessionPhase.READY -> ""
}
