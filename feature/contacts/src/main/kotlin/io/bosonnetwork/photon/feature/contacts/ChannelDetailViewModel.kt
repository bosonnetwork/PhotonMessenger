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

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import io.bosonnetwork.photon.core.model.ProfileResolver
import io.bosonnetwork.photon.core.model.toDisplay
import io.bosonnetwork.photon.feature.contacts.data.ChannelRepository
import io.bosonnetwork.photon.feature.contacts.model.UiChannelDetail
import io.bosonnetwork.photon.feature.contacts.model.UiChannelMember
import io.bosonnetwork.photon.feature.contacts.model.UiChannelRole
import io.bosonnetwork.photonmessaging.exceptions.ChannelNotExistsException
import io.bosonnetwork.photonmessaging.exceptions.InsufficientPermissionException
import io.bosonnetwork.photonmessaging.exceptions.NotChannelMemberException
import io.bosonnetwork.photonmessaging.exceptions.rpc.ChannelLimitExceededException
import io.bosonnetwork.photonmessaging.exceptions.rpc.ForbiddenRpcRequestException
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
    @ApplicationContext private val context: Context,
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
        run(R.string.contacts_error_prefix_change_role) { repository.setRole(channelId, memberId, role) }

    fun ban(memberId: String) =
        run(R.string.contacts_error_prefix_ban_member) { repository.ban(channelId, memberId) }
    fun unban(memberId: String) =
        run(R.string.contacts_error_prefix_unban_member) { repository.unban(channelId, memberId) }
    fun kick(memberId: String) =
        run(R.string.contacts_error_prefix_remove_member) { repository.kick(channelId, memberId) }

    fun leave() {
        viewModelScope.launch {
            repository.leave(channelId)
                .onSuccess { _closed.tryEmit(Unit) }
                .onFailure { e ->
                    _messages.tryEmit(e.toUserMessage(R.string.contacts_error_prefix_leave_channel))
                }
        }
    }

    fun remove() {
        viewModelScope.launch {
            repository.remove(channelId)
                .onSuccess { _closed.tryEmit(Unit) }
                .onFailure { e ->
                    _messages.tryEmit(e.toUserMessage(R.string.contacts_error_prefix_delete_channel))
                }
        }
    }

    /**
     * Hands the channel to another member. The channel moves into the RECIPIENT's channel allowance,
     * so the server decides on their plan, not this user's - a refusal here is about them, and the
     * message says so.
     *
     * A [ForbiddenRpcRequestException] means "channels are not on that plan" only for this action;
     * leaving a channel uses it for unrelated refusals, which is why [toUserMessage] does not map it.
     */
    fun transferOwnership(memberId: String) {
        viewModelScope.launch {
            repository.transferOwnership(channelId, memberId).onFailure { e ->
                _messages.tryEmit(when (e) {
                    is ChannelLimitExceededException ->
                        context.getString(R.string.contacts_error_transfer_limit_reached)
                    is ForbiddenRpcRequestException ->
                        context.getString(R.string.contacts_error_transfer_not_available)
                    else -> e.toUserMessage(R.string.contacts_error_prefix_transfer_ownership)
                })
            }
        }
    }

    fun updateInfo(name: String?, notice: String?) =
        run(R.string.contacts_error_prefix_update_channel) { repository.updateInfo(channelId, name, notice) }

    fun rotateSessionKey() =
        run(R.string.contacts_error_prefix_rotate_session_key) { repository.rotateSessionKey(channelId) }

    /** Mints an invite (open, or bound to [inviteeId]); the ticket surfaces via [inviteTicket]. */
    fun invite(inviteeId: String?) {
        viewModelScope.launch {
            repository.invite(channelId, inviteeId)
                .onSuccess { _inviteTicket.tryEmit(it) }
                .onFailure { _messages.tryEmit(it.toUserMessage(R.string.contacts_error_prefix_create_invite)) }
        }
    }

    private fun run(@StringRes failurePrefixRes: Int, action: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            action().onFailure { e -> _messages.tryEmit(e.toUserMessage(failurePrefixRes)) }
        }
    }

    /** Maps known channel moderation failures to specific, user-facing messages (M4-7). */
    private fun Throwable.toUserMessage(@StringRes failurePrefixRes: Int): String = when (this) {
        is InsufficientPermissionException -> context.getString(R.string.contacts_error_no_permission)
        is NotChannelMemberException -> context.getString(R.string.contacts_error_not_channel_member)
        is ChannelNotExistsException -> context.getString(R.string.contacts_error_channel_no_longer_exists)
        else -> context.getString(
            R.string.contacts_error_format,
            context.getString(failurePrefixRes),
            message ?: context.getString(R.string.contacts_error_unknown),
        )
    }

    private companion object {
        const val MAX_RESOLVED_MEMBERS = 100
    }
}
