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

package io.bosonnetwork.photon.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.bosonnetwork.photon.core.database.entity.ChannelInviteEntity
import io.bosonnetwork.photon.core.model.InviteAction
import io.bosonnetwork.Id
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device validation of the Room-backed [io.bosonnetwork.photon.core.model.ChannelInviteStore]:
 * action round-trips, stored channel id/name columns, and the JOINED-is-terminal precedence.
 */
@RunWith(AndroidJUnit4::class)
class RoomChannelInviteStoreTest {

    private lateinit var db: PhotonDatabase
    private lateinit var store: RoomChannelInviteStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, PhotonDatabase::class.java).build()
        store = RoomChannelInviteStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun joinRecordsActionWithChannelIdAndName() = runBlocking {
        val message = Id.random()
        val channel = Id.random()
        store.setAction(message.toString(), InviteAction.JOINED, channel.toString(), "Team")

        assertEquals(mapOf(message.toString() to InviteAction.JOINED), store.actions().first())

        val row = db.messagingDao().getChannelInvite(message.bytesUnsafe())!!
        assertEquals(ChannelInviteEntity.ACTION_JOINED, row.action)
        assertArrayEquals(channel.bytesUnsafe(), row.channelId)
        assertEquals("Team", row.channelName)
    }

    @Test
    fun ignoreRecordsActionWithNullChannelId() = runBlocking {
        val message = Id.random()
        store.setAction(message.toString(), InviteAction.IGNORED, channelName = "Team")

        assertEquals(mapOf(message.toString() to InviteAction.IGNORED), store.actions().first())

        val row = db.messagingDao().getChannelInvite(message.bytesUnsafe())!!
        assertEquals(ChannelInviteEntity.ACTION_IGNORED, row.action)
        assertNull(row.channelId)
        assertEquals("Team", row.channelName)
    }

    @Test
    fun joinOverridesAPriorIgnore() = runBlocking {
        val message = Id.random()
        val channel = Id.random()
        store.setAction(message.toString(), InviteAction.IGNORED, channelName = "Team")
        store.setAction(message.toString(), InviteAction.JOINED, channel.toString(), "Team")

        assertEquals(InviteAction.JOINED, store.actions().first()[message.toString()])
    }

    @Test
    fun ignoreDoesNotOverrideAJoin() = runBlocking {
        val message = Id.random()
        val channel = Id.random()
        store.setAction(message.toString(), InviteAction.JOINED, channel.toString(), "Team")
        store.setAction(message.toString(), InviteAction.IGNORED, channelName = "Team")

        assertEquals(InviteAction.JOINED, store.actions().first()[message.toString()])
        // The resolved channel id from the join is preserved, not wiped by the later ignore.
        assertArrayEquals(channel.bytesUnsafe(), db.messagingDao().getChannelInvite(message.bytesUnsafe())!!.channelId)
    }

    @Test
    fun tracksMultipleInvitesIndependently() = runBlocking {
        val a = Id.random()
        val b = Id.random()
        store.setAction(a.toString(), InviteAction.JOINED, Id.random().toString(), "A")
        store.setAction(b.toString(), InviteAction.IGNORED, channelName = "B")

        val actions = store.actions().first()
        assertEquals(InviteAction.JOINED, actions[a.toString()])
        assertEquals(InviteAction.IGNORED, actions[b.toString()])
    }
}
