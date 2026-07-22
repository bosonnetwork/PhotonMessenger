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

package io.bosonnetwork.photon.app.session

import io.bosonnetwork.photon.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStatusTextTest {

    @Test
    fun `banner hidden when idle or ready`() {
        assertFalse(SessionStatus(SessionPhase.IDLE).isBannerVisible())
        assertFalse(SessionStatus(SessionPhase.READY).isBannerVisible())
    }

    @Test
    fun `banner shown for every transient and failed phase`() {
        val transient = listOf(
            SessionPhase.DISCOVERING,
            SessionPhase.CONNECTING,
            SessionPhase.CONNECTED,
            SessionPhase.DISCONNECTED,
            SessionPhase.FAILED,
        )
        transient.forEach { phase ->
            assertTrue("expected $phase visible", SessionStatus(phase).isBannerVisible())
        }
    }

    @Test
    fun `hidden states resolve to no resource`() {
        assertNull(SessionStatus(SessionPhase.IDLE).bannerLabelRes())
        assertNull(SessionStatus(SessionPhase.READY).bannerLabelRes())
    }

    @Test
    fun `discovering and connecting read as connecting`() {
        assertEquals(
            R.string.app_session_status_connecting,
            SessionStatus(SessionPhase.DISCOVERING).bannerLabelRes(),
        )
        assertEquals(
            R.string.app_session_status_connecting,
            SessionStatus(SessionPhase.CONNECTING).bannerLabelRes(),
        )
    }

    @Test
    fun `disconnected reads as reconnecting`() {
        assertEquals(
            R.string.app_session_status_reconnecting,
            SessionStatus(SessionPhase.DISCONNECTED).bannerLabelRes(),
        )
    }

    @Test
    fun `failed resolves to the could-not-connect fallback resource`() {
        // The composable prefers SessionStatus.message (a runtime error detail) when present; the
        // pure phase-to-resource mapping tested here always yields the fallback resource id.
        assertEquals(
            R.string.app_session_status_could_not_connect,
            SessionStatus(SessionPhase.FAILED, "boom").bannerLabelRes(),
        )
        assertEquals(
            R.string.app_session_status_could_not_connect,
            SessionStatus(SessionPhase.FAILED).bannerLabelRes(),
        )
    }
}
