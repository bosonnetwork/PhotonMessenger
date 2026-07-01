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

package io.photonmessenger.feature.contacts

import app.cash.turbine.test
import io.photonmessenger.feature.contacts.data.ChannelRepository
import io.photonmessenger.feature.contacts.data.ContactRepository
import io.photonmessenger.feature.contacts.model.UiChannel
import io.photonmessenger.feature.contacts.model.UiChannelDetail
import io.photonmessenger.feature.contacts.model.UiChannelPermission
import io.photonmessenger.feature.contacts.model.UiChannelRole
import io.photonmessenger.feature.contacts.model.UiContact
import io.photonmessenger.feature.contacts.model.UiFriendRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class ContactsViewModelTest {

    private val contactsFlow = MutableStateFlow<List<UiContact>>(emptyList())
    private val requestsFlow = MutableStateFlow<List<UiFriendRequest>>(emptyList())

    private class FakeRepo(
        private val contacts: Flow<List<UiContact>>,
        private val requests: Flow<List<UiFriendRequest>>,
        var sendResult: Result<Unit> = Result.success(Unit),
    ) : ContactRepository {
        override fun contacts() = contacts
        override fun friendRequests() = requests
        override suspend fun sendFriendRequest(idText: String, hello: String) = sendResult
        override suspend fun acceptFriendRequest(userIdText: String) = Result.success(Unit)
        override suspend fun declineFriendRequest(userIdText: String) = Result.success(Unit)
        override suspend fun setMuted(contactId: String, muted: Boolean) = Result.success(Unit)
        override suspend fun setBlocked(contactId: String, blocked: Boolean) = Result.success(Unit)
        override suspend fun removeContact(contactId: String) = Result.success(Unit)
    }

    private class FakeChannelRepo(
        var joinResult: Result<String> = Result.success("CHAN1"),
    ) : ChannelRepository {
        override fun channels(): Flow<List<UiChannel>> = MutableStateFlow(emptyList())
        override fun channelDetail(channelId: String): Flow<UiChannelDetail> = MutableStateFlow(sampleDetail())
        override suspend fun createChannel(
            name: String,
            notice: String?,
            permission: UiChannelPermission,
            announce: Boolean,
        ): Result<String> = Result.success("CHAN1")
        override suspend fun invite(channelId: String, inviteeId: String?): Result<String> = Result.success("ticket")
        override suspend fun joinChannel(ticket: String): Result<String> = joinResult
        override suspend fun leave(channelId: String): Result<Unit> = Result.success(Unit)
        override suspend fun remove(channelId: String): Result<Unit> = Result.success(Unit)
        override suspend fun setRole(channelId: String, memberId: String, role: UiChannelRole): Result<Unit> = Result.success(Unit)
        override suspend fun ban(channelId: String, memberId: String): Result<Unit> = Result.success(Unit)
        override suspend fun unban(channelId: String, memberId: String): Result<Unit> = Result.success(Unit)
        override suspend fun kick(channelId: String, memberId: String): Result<Unit> = Result.success(Unit)
        override suspend fun transferOwnership(channelId: String, newOwnerId: String): Result<Unit> = Result.success(Unit)
        override suspend fun updateInfo(channelId: String, name: String?, notice: String?): Result<Unit> = Result.success(Unit)
        override suspend fun rotateSessionKey(channelId: String): Result<Unit> = Result.success(Unit)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `splits contacts into friends and channels`() = runTest {
        contactsFlow.value = listOf(
            UiContact("a", "Alice", isChannel = false, muted = false, blocked = false),
            UiContact("c", "Team", isChannel = true, muted = false, blocked = false),
        )
        requestsFlow.value = listOf(UiFriendRequest("r", "hi"))
        val vm = ContactsViewModel(FakeRepo(contactsFlow, requestsFlow), FakeChannelRepo())

        vm.uiState.test {
            // skip initial loading state, take the first loaded state
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertFalse(state.loading)
            assertEquals(listOf("Alice"), state.friends.map { it.displayName })
            assertEquals(listOf("Team"), state.channels.map { it.displayName })
            assertEquals(1, state.requests.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `failed add emits a message`() = runTest {
        val repo = FakeRepo(contactsFlow, requestsFlow, sendResult = Result.failure(IllegalStateException("boom")))
        val vm = ContactsViewModel(repo, FakeChannelRepo())

        vm.messages.test {
            vm.addFriend("bad-id", "hi")
            val msg = awaitItem()
            assert(msg.contains("boom"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `join success emits the joined channel id`() = runTest {
        val vm = ContactsViewModel(
            FakeRepo(contactsFlow, requestsFlow),
            FakeChannelRepo(joinResult = Result.success("CHAN9")),
        )
        vm.joinedChannel.test {
            vm.joinChannel(" ticket-json ")
            assertEquals("CHAN9", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `join failure emits a message`() = runTest {
        val vm = ContactsViewModel(
            FakeRepo(contactsFlow, requestsFlow),
            FakeChannelRepo(joinResult = Result.failure(IllegalStateException("bad ticket"))),
        )
        vm.messages.test {
            vm.joinChannel("nope")
            assert(awaitItem().contains("bad ticket"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    private companion object {
        fun sampleDetail() = UiChannelDetail(
            channel = UiChannel(
                id = "CHAN1",
                name = "Team",
                notice = null,
                permission = UiChannelPermission.OWNER_INVITE,
                myRole = UiChannelRole.OWNER,
                memberCount = 1,
                muted = false,
            ),
            members = emptyList(),
        )
    }
}
