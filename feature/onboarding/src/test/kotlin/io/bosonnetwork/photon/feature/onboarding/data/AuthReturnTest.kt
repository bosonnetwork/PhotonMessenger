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

package io.bosonnetwork.photon.feature.onboarding.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where a node sends the sign-in back, and how the app reads what it brings. */
class AuthReturnTest {
    @Test
    fun `the App Link carries the result in its fragment`() {
        assertEquals(AuthCallback.Token("abc.def"), authCallbackOf("https://bosonnetwork.io/photon/auth#token=abc.def"))
        assertEquals(AuthCallback.Error("access_denied"), authCallbackOf("https://bosonnetwork.io/photon/auth#error=access_denied"))
        // Percent-encoded values are decoded.
        assertEquals(AuthCallback.Token("a+b/c="), authCallbackOf("https://bosonnetwork.io/photon/auth#token=a%2Bb%2Fc%3D"))
    }

    @Test
    fun `the app's own scheme carries it in the query`() {
        assertEquals(AuthCallback.Token("t1"), authCallbackOf("io.bosonnetwork.photon://auth?token=t1"))
        assertEquals(AuthCallback.Error("server_error"), authCallbackOf("io.bosonnetwork.photon://auth?error=server_error"))
    }

    @Test
    fun `anything else is no sign-in result`() {
        // A token in the App Link's query is not where a node puts it: it would have reached the site.
        assertNull(authCallbackOf("https://bosonnetwork.io/photon/auth?token=t"))
        assertNull(authCallbackOf("https://bosonnetwork.io/identity/auth#token=t"))
        assertNull(authCallbackOf("https://evil.example/photon/auth#token=t"))
        assertNull(authCallbackOf("io.bosonnetwork.photon://auth?other=1"))
        assertNull(authCallbackOf("io.bosonnetwork.photon://auth"))
    }

    @Test
    fun `nodes from 3_2 get the App Link, older or unknown ones the app's scheme`() {
        assertTrue(AuthRepository.returnsInFragment("3.2.0"))
        assertTrue(AuthRepository.returnsInFragment("3.2.0-SNAPSHOT"))
        assertTrue(AuthRepository.returnsInFragment("3.10.1"))
        assertTrue(AuthRepository.returnsInFragment("4.0.0"))
        assertFalse(AuthRepository.returnsInFragment("3.1.2"))
        assertFalse(AuthRepository.returnsInFragment("2.0.7"))
        assertFalse(AuthRepository.returnsInFragment("live"))
        assertFalse(AuthRepository.returnsInFragment(""))
        assertFalse(AuthRepository.returnsInFragment(null))
    }
}
