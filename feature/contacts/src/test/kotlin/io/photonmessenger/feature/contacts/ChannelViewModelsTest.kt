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

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import io.photonmessenger.feature.contacts.data.ChannelRepository
import io.photonmessenger.feature.contacts.model.UiChannel
import io.photonmessenger.feature.contacts.model.UiChannelDetail
import io.photonmessenger.feature.contacts.model.UiChannelPermission
import io.photonmessenger.feature.contacts.model.UiChannelRole
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChannelViewModelsTest {

    private class FakeChannelRepository(
        private val detailFlow: Flow<UiChannelDetail> = MutableStateFlow(sampleDetail()),
        var createResult: Result<String> = Result.success("CHAN1"),
        var actionResult: Result<Unit> = Result.success(Unit),
        var joinResult: Result<String> = Result.success("CHAN1"),
    ) : ChannelRepository {
        override fun channels(): Flow<List<UiChannel>> = MutableStateFlow(emptyList())
        override fun channelDetail(channelId: String): Flow<UiChannelDetail> = detailFlow
        override suspend fun createChannel(
            name: String,
            notice: String?,
            permission: UiChannelPermission,
            announce: Boolean,
        ): Result<String> = createResult
        override suspend fun invite(channelId: String, inviteeId: String?): Result<String> = Result.success("ticket")
        override suspend fun joinChannel(ticket: String): Result<String> = joinResult
        override suspend fun leave(channelId: String): Result<Unit> = actionResult
        override suspend fun remove(channelId: String): Result<Unit> = actionResult
        override suspend fun setRole(channelId: String, memberId: String, role: UiChannelRole): Result<Unit> = actionResult
        override suspend fun ban(channelId: String, memberId: String): Result<Unit> = actionResult
        override suspend fun unban(channelId: String, memberId: String): Result<Unit> = actionResult
        override suspend fun kick(channelId: String, memberId: String): Result<Unit> = actionResult
        override suspend fun transferOwnership(channelId: String, newOwnerId: String): Result<Unit> = actionResult
        override suspend fun updateInfo(channelId: String, name: String?, notice: String?): Result<Unit> = actionResult
        override suspend fun rotateSessionKey(channelId: String): Result<Unit> = actionResult
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
    fun `create requires a name`() {
        val vm = CreateChannelViewModel(FakeChannelRepository())
        assertFalse(vm.uiState.value.canSubmit)
        vm.setName("Friends")
        assertTrue(vm.uiState.value.canSubmit)
    }

    @Test
    fun `create success emits Created with channel id`() = runTest {
        val vm = CreateChannelViewModel(FakeChannelRepository(createResult = Result.success("CHAN9")))
        vm.setName("Team")
        vm.events.test {
            vm.create()
            assertEquals(CreateChannelEvent.Created("CHAN9"), awaitItem())
        }
    }

    @Test
    fun `create failure emits Error`() = runTest {
        val vm = CreateChannelViewModel(FakeChannelRepository(createResult = Result.failure(RuntimeException("nope"))))
        vm.setName("Team")
        vm.events.test {
            vm.create()
            assertEquals(CreateChannelEvent.Error("nope"), awaitItem())
        }
    }

    @Test
    fun `detail exposes channel and members`() = runTest {
        val vm = ChannelDetailViewModel(FakeChannelRepository(), FakeProfileResolver(), SavedStateHandle(mapOf("channelId" to "CHAN1")))
        vm.uiState.test {
            val state = awaitItem()
            assertEquals("Team", state.detail?.channel?.name)
            assertEquals(1, state.detail?.members?.size)
            assertFalse(state.loading)
        }
    }

    @Test
    fun `failed moderation action emits a message`() = runTest {
        val vm = ChannelDetailViewModel(
            FakeChannelRepository(actionResult = Result.failure(RuntimeException("denied"))),
            FakeProfileResolver(),
            SavedStateHandle(mapOf("channelId" to "CHAN1")),
        )
        vm.messages.test {
            vm.kick("MEMBER2")
            assertTrue(awaitItem().contains("denied"))
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
            members = listOf(
                io.photonmessenger.feature.contacts.model.UiChannelMember("ME", "Me", UiChannelRole.OWNER),
            ),
        )
    }
}
