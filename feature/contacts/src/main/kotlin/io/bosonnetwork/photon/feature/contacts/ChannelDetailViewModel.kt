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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.bosonnetwork.photon.core.model.ProfileResolver
import io.bosonnetwork.photon.core.model.toDisplay
import io.bosonnetwork.photon.feature.contacts.data.ChannelRepository
import io.bosonnetwork.photon.feature.contacts.model.UiChannelDetail
import io.bosonnetwork.photon.feature.contacts.model.UiChannelMember
import io.bosonnetwork.photon.feature.contacts.model.UiChannelRole
import io.bosonnetwork.photonmessaging.exceptions.ChannelNotExistsException
import io.bosonnetwork.photonmessaging.exceptions.InsufficientPermissionException
import io.bosonnetwork.photonmessaging.exceptions.NotChannelMemberException
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChannelDetailUiState(
    val loading: Boolean = true,
    val detail: UiChannelDetail? = null,
    val error: String? = null,
)

@HiltViewModel
class ChannelDetailViewModel @Inject constructor(
    private val repository: ChannelRepository,
    private val profileResolver: ProfileResolver,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val channelId: String = checkNotNull(savedStateHandle["channelId"]) { "channelId arg missing" }

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<ChannelDetailUiState> =
        repository.channelDetail(channelId)
            .flatMapLatest { detail -> resolvedMembers(detail) }
            .map { ChannelDetailUiState(loading = false, detail = it) }
            .catch { e -> emit(ChannelDetailUiState(loading = false, error = e.message)) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ChannelDetailUiState(loading = true),
            )

    /**
     * Enriches the roster with Director-resolved profiles: every member gets an avatar, and members
     * whose local name is just a shortened id are upgraded to their resolved public name. Capped to
     * the first [MAX_RESOLVED_MEMBERS] to bound the per-channel fan-out on large rosters.
     */
    private fun resolvedMembers(detail: UiChannelDetail): Flow<UiChannelDetail> {
        val members = detail.members
        if (members.isEmpty()) return flowOf(detail)
        val enriched = members.mapIndexed { index, member ->
            if (index >= MAX_RESOLVED_MEMBERS) return@mapIndexed flowOf(member)
            profileResolver.profile(member.id).map { profile ->
                // One policy for name + avatar: a member's local name wins; only a member still on
                // the short-id fallback is upgraded to the Director-resolved name.
                val display = profile.toDisplay(
                    member.id,
                    localName = member.displayName.takeUnless { member.nameIsFallback },
                )
                member.copy(
                    avatarUrl = display.avatarUrl,
                    displayName = if (display.nameIsFallback) member.displayName else display.displayName,
                )
            }
        }
        return combine(enriched) { detail.copy(members = it.toList()) }
    }

    /** Transient one-shot messages (action failures) for a snackbar. */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages = _messages.asSharedFlow()

    /** Emitted after a leave/delete succeeds, so the screen navigates away only on success. */
    private val _closed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val closed = _closed.asSharedFlow()

    /** Emits a freshly minted, shareable invite-ticket string (M4-2). */
    private val _inviteTicket = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val inviteTicket = _inviteTicket.asSharedFlow()

    fun setRole(memberId: String, role: UiChannelRole) =
        run("Couldn't change role") { repository.setRole(channelId, memberId, role) }

    fun ban(memberId: String) = run("Couldn't ban member") { repository.ban(channelId, memberId) }
    fun unban(memberId: String) = run("Couldn't unban member") { repository.unban(channelId, memberId) }
    fun kick(memberId: String) = run("Couldn't remove member") { repository.kick(channelId, memberId) }

    fun leave() {
        viewModelScope.launch {
            repository.leave(channelId)
                .onSuccess { _closed.tryEmit(Unit) }
                .onFailure { e -> _messages.tryEmit(e.toUserMessage("Couldn't leave channel")) }
        }
    }

    fun remove() {
        viewModelScope.launch {
            repository.remove(channelId)
                .onSuccess { _closed.tryEmit(Unit) }
                .onFailure { e -> _messages.tryEmit(e.toUserMessage("Couldn't delete channel")) }
        }
    }

    fun transferOwnership(memberId: String) =
        run("Couldn't transfer ownership") { repository.transferOwnership(channelId, memberId) }

    fun updateInfo(name: String?, notice: String?) =
        run("Couldn't update channel") { repository.updateInfo(channelId, name, notice) }

    fun rotateSessionKey() =
        run("Couldn't rotate session key") { repository.rotateSessionKey(channelId) }

    /** Mints an invite (open, or bound to [inviteeId]); the ticket surfaces via [inviteTicket]. */
    fun invite(inviteeId: String?) {
        viewModelScope.launch {
            repository.invite(channelId, inviteeId)
                .onSuccess { _inviteTicket.tryEmit(it) }
                .onFailure { _messages.tryEmit(it.toUserMessage("Couldn't create invite")) }
        }
    }

    private fun run(failurePrefix: String, action: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            action().onFailure { e -> _messages.tryEmit(e.toUserMessage(failurePrefix)) }
        }
    }

    /** Maps known channel moderation failures to specific, user-facing messages (M4-7). */
    private fun Throwable.toUserMessage(failurePrefix: String): String = when (this) {
        is InsufficientPermissionException -> "You don't have permission to do that"
        is NotChannelMemberException -> "You're not a member of this channel"
        is ChannelNotExistsException -> "This channel no longer exists"
        else -> "$failurePrefix: ${message ?: "unknown error"}"
    }

    private companion object {
        const val MAX_RESOLVED_MEMBERS = 100
    }
}
