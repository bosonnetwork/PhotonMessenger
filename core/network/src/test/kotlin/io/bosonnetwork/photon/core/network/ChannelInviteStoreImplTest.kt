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
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.bosonnetwork.photon.core.model.InviteAction
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChannelInviteStoreImplTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())

    private fun dataStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) {
        File(tmp.root, "test_${System.nanoTime()}.preferences_pb")
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `records joined and ignored actions independently`() = runTest {
        val store = ChannelInviteStoreImpl(dataStore())
        store.setAction("m1", InviteAction.JOINED)
        store.setAction("m2", InviteAction.IGNORED)

        val actions = store.actions().first()
        assertEquals(InviteAction.JOINED, actions["m1"])
        assertEquals(InviteAction.IGNORED, actions["m2"])
        assertNull(actions["m3"])
    }

    @Test
    fun `joining an ignored invite promotes it to joined`() = runTest {
        val store = ChannelInviteStoreImpl(dataStore())
        store.setAction("m1", InviteAction.IGNORED)
        store.setAction("m1", InviteAction.JOINED)

        assertEquals(InviteAction.JOINED, store.actions().first()["m1"])
    }

    @Test
    fun `a joined invite cannot later be downgraded to ignored`() = runTest {
        val store = ChannelInviteStoreImpl(dataStore())
        store.setAction("m1", InviteAction.JOINED)
        store.setAction("m1", InviteAction.IGNORED)

        assertEquals(InviteAction.JOINED, store.actions().first()["m1"])
    }
}
