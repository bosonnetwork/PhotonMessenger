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

package io.bosonnetwork.photon.feature.contacts

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import io.bosonnetwork.photon.core.model.ResolvedProfile
import io.bosonnetwork.photon.feature.contacts.data.ChannelRepository
import io.bosonnetwork.photon.feature.contacts.data.ContactRepository
import io.bosonnetwork.photon.feature.contacts.model.UiChannel
import io.bosonnetwork.photon.feature.contacts.model.UiChannelDetail
import io.bosonnetwork.photon.feature.contacts.model.UiChannelPermission
import io.bosonnetwork.photon.feature.contacts.model.UiChannelRole
import io.bosonnetwork.photon.feature.contacts.model.UiContact
import io.bosonnetwork.photon.feature.contacts.model.UiFriendRequest
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
        override fun contact(contactId: String): Flow<UiContact?> = MutableStateFlow(null)
        override suspend fun sendFriendRequest(idText: String, hello: String) = sendResult
        override suspend fun acceptFriendRequest(userIdText: String) = Result.success(Unit)
        override suspend fun declineFriendRequest(userIdText: String) = Result.success(Unit)
        override suspend fun setMuted(contactId: String, muted: Boolean) = Result.success(Unit)
        override suspend fun setBlocked(contactId: String, blocked: Boolean) = Result.success(Unit)
        var remarkArgs: Pair<String, String?>? = null
        override suspend fun setRemark(contactId: String, remark: String?) =
            Result.success(Unit).also { remarkArgs = contactId to remark }
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
        val invitedContacts = mutableListOf<Pair<String, String>>()
        var inviteContactResult: Result<Unit> = Result.success(Unit)
        override suspend fun inviteContact(channelId: String, inviteeId: String): Result<Unit> {
            invitedContacts += channelId to inviteeId
            return inviteContactResult
        }
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
        val vm = ContactsViewModel(FakeRepo(contactsFlow, requestsFlow), FakeChannelRepo(), FakeProfileResolver())

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
    fun `friend requests are enriched with resolved profiles`() = runTest {
        requestsFlow.value = listOf(UiFriendRequest("USER-R", "hello there"))
        val resolver = FakeProfileResolver()
        val vm = ContactsViewModel(FakeRepo(contactsFlow, requestsFlow), FakeChannelRepo(), resolver)

        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertEquals(null, state.requests.single().name)

            resolver.profiles.value = mapOf(
                "USER-R" to ResolvedProfile(
                    userId = "USER-R",
                    name = "Robert",
                    bio = null,
                    avatarUrl = "https://node/api/v1/client/avatar/USER-R",
                ),
            )

            state = awaitItem()
            assertEquals("Robert", state.requests.single().name)
            assertEquals("https://node/api/v1/client/avatar/USER-R", state.requests.single().avatarUrl)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `failed add emits a message`() = runTest {
        val repo = FakeRepo(contactsFlow, requestsFlow, sendResult = Result.failure(IllegalStateException("boom")))
        val vm = ContactsViewModel(repo, FakeChannelRepo(), FakeProfileResolver())

        vm.messages.test {
            vm.addFriend("bad-id", "hi")
            val msg = awaitItem()
            assert(msg.contains("boom"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setRemark forwards the alias to the repository`() = runTest {
        val repo = FakeRepo(contactsFlow, requestsFlow)
        val vm = ContactsViewModel(repo, FakeChannelRepo(), FakeProfileResolver())

        vm.setRemark("a", "Ali")
        assertEquals("a" to "Ali", repo.remarkArgs)
    }

    @Test
    fun `join success emits the joined channel id`() = runTest {
        val vm = ContactsViewModel(
            FakeRepo(contactsFlow, requestsFlow),
            FakeChannelRepo(joinResult = Result.success("CHAN9")),
            FakeProfileResolver(),
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
            FakeProfileResolver(),
        )
        vm.messages.test {
            vm.joinChannel("nope")
            assert(awaitItem().contains("bad ticket"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `invite picker lists only contacts and sends a named invite`() = runTest {
        contactsFlow.value = listOf(
            UiContact("a", "Alice", isChannel = false, muted = false, blocked = false),
            UiContact("b", "Blocked", isChannel = false, muted = false, blocked = true),
            UiContact("c", "Team", isChannel = true, muted = false, blocked = false),
        )
        val channelRepo = FakeChannelRepo()
        val vm = InviteContactPickerViewModel(
            FakeRepo(contactsFlow, requestsFlow),
            channelRepo,
            FakeProfileResolver(),
            SavedStateHandle(mapOf("channelId" to "CHAN1")),
        )

        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            // Only the non-blocked friend is offered - no channels, no blocked contacts.
            assertEquals(listOf("a"), state.contacts.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }

        vm.events.test {
            vm.invite(UiContact("a", "Alice", isChannel = false, muted = false, blocked = false))
            assertEquals(InvitePickerEvent.Sent("Alice"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("CHAN1" to "a"), channelRepo.invitedContacts)
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
