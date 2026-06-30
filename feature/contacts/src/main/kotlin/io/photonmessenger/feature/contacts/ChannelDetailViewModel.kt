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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.photonmessenger.feature.contacts.data.ChannelRepository
import io.photonmessenger.feature.contacts.model.UiChannelDetail
import io.photonmessenger.feature.contacts.model.UiChannelRole
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
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
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val channelId: String = checkNotNull(savedStateHandle["channelId"]) { "channelId arg missing" }

    val uiState: StateFlow<ChannelDetailUiState> =
        repository.channelDetail(channelId)
            .map { ChannelDetailUiState(loading = false, detail = it) }
            .catch { e -> emit(ChannelDetailUiState(loading = false, error = e.message)) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ChannelDetailUiState(loading = true),
            )

    /** Transient one-shot messages (action failures, generated invite ticket) for a snackbar. */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages = _messages.asSharedFlow()

    fun setRole(memberId: String, role: UiChannelRole) =
        run("Couldn't change role") { repository.setRole(channelId, memberId, role) }

    fun ban(memberId: String) = run("Couldn't ban member") { repository.ban(channelId, memberId) }
    fun unban(memberId: String) = run("Couldn't unban member") { repository.unban(channelId, memberId) }
    fun kick(memberId: String) = run("Couldn't remove member") { repository.kick(channelId, memberId) }
    fun leave() = run("Couldn't leave channel") { repository.leave(channelId) }
    fun remove() = run("Couldn't delete channel") { repository.remove(channelId) }

    fun transferOwnership(memberId: String) =
        run("Couldn't transfer ownership") { repository.transferOwnership(channelId, memberId) }

    fun updateInfo(name: String?, notice: String?) =
        run("Couldn't update channel") { repository.updateInfo(channelId, name, notice) }

    /** Invites a specific user; surfaces the resulting ticket text via [messages]. */
    fun invite(inviteeId: String?) {
        viewModelScope.launch {
            repository.invite(channelId, inviteeId)
                .onSuccess { _messages.tryEmit("Invite ticket: $it") }
                .onFailure { _messages.tryEmit("Couldn't create invite: ${it.message ?: "unknown error"}") }
        }
    }

    private fun run(failurePrefix: String, action: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            action().onFailure { e ->
                _messages.tryEmit("$failurePrefix: ${e.message ?: "unknown error"}")
            }
        }
    }
}
