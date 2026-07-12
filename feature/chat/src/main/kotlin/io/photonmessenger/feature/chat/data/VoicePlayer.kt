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

package io.photonmessenger.feature.chat.data

import android.media.MediaPlayer
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * App-wide voice-note playback. Only one note plays at a time - starting a new one stops the
 * previous - so the [state] flow always describes the single active note (or [Playback] idle). The
 * bubble drives this via play/pause; the ViewModel resolves each note to a local file first (inline
 * bytes materialized in the cache, or a downloaded remote file).
 */
@Singleton
class VoicePlayer @Inject constructor() {
    /** Active playback snapshot. [messageId] is null when nothing is playing. */
    data class Playback(
        val messageId: String? = null,
        val positionMs: Int = 0,
        val durationMs: Int = 0,
        val isPlaying: Boolean = false,
        val preparing: Boolean = false,
    )

    private val _state = MutableStateFlow(Playback())
    val state: StateFlow<Playback> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: MediaPlayer? = null
    private var currentId: String? = null
    private var ticker: Job? = null

    /** Play/pause [messageId]: toggles when it is the active note, otherwise starts it fresh. */
    fun toggle(messageId: String, file: File) {
        val p = player
        if (currentId == messageId && p != null) {
            if (runCatching { p.isPlaying }.getOrDefault(false)) pause() else resume()
        } else {
            start(messageId, file)
        }
    }

    private fun start(messageId: String, file: File) {
        stopInternal()
        currentId = messageId
        _state.value = Playback(messageId = messageId, preparing = true)
        val p = MediaPlayer()
        player = p
        p.setOnPreparedListener { mp ->
            if (currentId != messageId) return@setOnPreparedListener
            _state.value = Playback(messageId, 0, mp.duration.coerceAtLeast(0), isPlaying = true)
            runCatching { mp.start() }
            startTicker()
        }
        p.setOnCompletionListener { onEnded() }
        p.setOnErrorListener { _, _, _ -> onEnded(); true }
        runCatching {
            p.setDataSource(file.absolutePath)
            p.prepareAsync()
        }.onFailure { onEnded() }
    }

    private fun pause() {
        runCatching { player?.pause() }
        ticker?.cancel()
        updateFromPlayer(isPlaying = false)
    }

    private fun resume() {
        runCatching { player?.start() }
        updateFromPlayer(isPlaying = true)
        startTicker()
    }

    fun seekTo(ms: Int) {
        runCatching { player?.seekTo(ms) }
        updateFromPlayer()
    }

    /** Stops any active playback and resets to idle (e.g. when leaving the chat). */
    fun stop() {
        stopInternal()
        _state.value = Playback()
    }

    private fun onEnded() {
        stopInternal()
        _state.value = Playback()
    }

    private fun stopInternal() {
        ticker?.cancel()
        ticker = null
        player?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        player = null
        currentId = null
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                updateFromPlayer()
                delay(POSITION_TICK_MS)
            }
        }
    }

    private fun updateFromPlayer(isPlaying: Boolean? = null) {
        val p = player ?: return
        val id = currentId ?: return
        val playing = isPlaying ?: runCatching { p.isPlaying }.getOrDefault(false)
        _state.value = Playback(
            messageId = id,
            positionMs = runCatching { p.currentPosition }.getOrDefault(0),
            durationMs = runCatching { p.duration }.getOrDefault(0).coerceAtLeast(0),
            isPlaying = playing,
        )
    }

    private companion object {
        const val POSITION_TICK_MS = 100L
    }
}
