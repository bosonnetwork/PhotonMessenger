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

package io.bosonnetwork.photon.feature.chat.data

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records a short voice note as Opus in an Ogg container (16 kHz mono, ~20 kbps, one minute max),
 * matching the inline voice wire format. Recording requires API 29+ (OGG/OPUS output); callers gate
 * the mic button on [isSupported]. Playback of received notes works on all API levels.
 *
 * An interface so the Context-bound Android implementation can be swapped for a fake in unit tests.
 */
interface VoiceRecorder {
    /** A finished recording: the encoded file and its measured playback length. */
    data class Recording(val file: File, val durationMs: Long)

    /** Whether this device has the OGG/OPUS encoder (API 29+); the mic button is hidden otherwise. */
    val isSupported: Boolean

    val isRecording: Boolean

    /** Invoked (on the recorder's thread) when the max duration is reached and recording auto-stops. */
    var onMaxDuration: (() -> Unit)?

    /** Starts a new recording, returning the file bytes are written to. Throws if it cannot start. */
    fun start(): File

    /** Stops and returns the finished recording, or null if nothing usable was captured. */
    fun stop(): Recording?

    /** Stops and discards the current recording. */
    fun cancel()

    /** Current peak amplitude (0..32767) for the live recording level; 0 when idle. */
    fun maxAmplitude(): Int
}

/**
 * The platform-encoder ([MediaRecorder]) implementation. The libopus-level knobs (application mode,
 * frame size, complexity) are not exposed by the platform encoder, so only container, codec, sample
 * rate, channels, bit rate and max duration are configured; the rest use encoder defaults.
 */
@Singleton
class AndroidVoiceRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
) : VoiceRecorder {

    private val dir: File = File(context.cacheDir, "voice").apply { mkdirs() }

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAtMs: Long = 0L

    override var onMaxDuration: (() -> Unit)? = null

    override val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    override val isRecording: Boolean get() = recorder != null

    /**
     * Starts a new recording, returning the output file bytes will be written to. Any in-flight
     * recording is discarded first. Throws if the encoder cannot be prepared (caller marks the
     * attempt failed).
     */
    override fun start(): File {
        cancel()
        val file = File(dir, "voice-${System.currentTimeMillis()}.ogg")
        val rec = newRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.OGG)
            setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            setAudioSamplingRate(SAMPLE_RATE_HZ)
            setAudioChannels(1)
            setAudioEncodingBitRate(BIT_RATE)
            setMaxDuration(MAX_DURATION_MS)
            setOutputFile(file.absolutePath)
            setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onMaxDuration?.invoke()
            }
            prepare()
            start()
        }
        recorder = rec
        outputFile = file
        startedAtMs = SystemClock.elapsedRealtime()
        return file
    }

    /**
     * Stops recording and returns the finished [Recording], or null if nothing usable was captured
     * (e.g. released before any audio was written). Duration is clamped to [MAX_DURATION_MS].
     */
    override fun stop(): VoiceRecorder.Recording? {
        val rec = recorder ?: return null
        val file = outputFile
        val elapsed = (SystemClock.elapsedRealtime() - startedAtMs).coerceIn(0L, MAX_DURATION_MS.toLong())
        val stopped = runCatching { rec.stop() }.isSuccess
        release()
        return if (stopped && file != null && file.length() > 0) VoiceRecorder.Recording(file, elapsed) else {
            file?.delete()
            null
        }
    }

    override fun cancel() {
        val rec = recorder ?: return
        runCatching { rec.stop() }
        release()
        outputFile?.delete()
        outputFile = null
    }

    override fun maxAmplitude(): Int = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)

    private fun release() {
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
    }

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
        else @Suppress("DEPRECATION") MediaRecorder()

    private companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val BIT_RATE = 20_000
        const val MAX_DURATION_MS = 60_000
    }
}
