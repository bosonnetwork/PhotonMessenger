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
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeviceRegistrationStoreTest {

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

    private val node = RegisteredNode(
        baseUrl = "https://whisper.freeddns.org:9000",
        nodeId = "NODE-1",
    )

    @Test
    fun `get returns null when nothing was stored`() = runTest {
        assertNull(DeviceRegistrationStore(dataStore()).get())
    }

    @Test
    fun `set then get round-trips the record`() = runTest {
        val store = DeviceRegistrationStore(dataStore())
        store.set(node)
        assertEquals(node, store.get())
    }

    @Test
    fun `a null nodeId round-trips and replaces a previous one`() = runTest {
        val store = DeviceRegistrationStore(dataStore())
        store.set(node)
        store.set(node.copy(nodeId = null))
        assertEquals(node.copy(nodeId = null), store.get())
    }

    @Test
    fun `clear removes the record`() = runTest {
        val store = DeviceRegistrationStore(dataStore())
        store.set(node)
        store.clear()
        assertNull(store.get())
    }

    @Test
    fun `a record without a baseUrl reads as null`() = runTest {
        val ds = dataStore()
        ds.edit { it[stringPreferencesKey("device_reg_node_id")] = "NODE-1" }
        assertNull(DeviceRegistrationStore(ds).get())
    }

    @Test
    fun `matches requires the node to line up`() {
        val cfg = DirectorConfig(
            baseUrl = node.baseUrl,
            certificatePins = emptyList(),
            nodeId = node.nodeId,
        )
        assertTrue(node.matches(cfg))
        assertFalse(node.matches(cfg.copy(baseUrl = "https://other.node")))
        assertFalse(node.matches(cfg.copy(nodeId = null)))
        assertFalse(node.matches(cfg.copy(nodeId = "NODE-2")))
    }
}
