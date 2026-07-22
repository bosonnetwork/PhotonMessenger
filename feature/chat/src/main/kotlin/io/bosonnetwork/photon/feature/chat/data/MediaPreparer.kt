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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import io.bosonnetwork.photon.core.model.AppError
import io.bosonnetwork.photon.feature.chat.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A picked-and-prepared attachment ready to send (compressed for images). */
data class PreparedMedia(
    val bytes: ByteArray,
    val mime: String,
    val name: String,
    val width: Int?,
    val height: Int?,
)

/**
 * Reads a picked content [Uri], probing its MIME/name and - for images - downscaling and
 * recompressing to a transport-friendly JPEG (spec M5-1). Other kinds are read verbatim, capped at
 * [MAX_RAW_BYTES] so a huge pick cannot OOM the process.
 */
@Singleton
class MediaPreparer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Cheaply probes a picked content uri for its display name and MIME type without reading or
     * compressing its bytes - used to build the immediate optimistic outgoing bubble.
     *
     * @return (name, mime)
     */
    fun probe(uriString: String): Pair<String, String> {
        val uri = Uri.parse(uriString)
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val name = queryDisplayName(uri) ?: defaultName(mime)
        return name to mime
    }

    suspend fun prepare(uriString: String): PreparedMedia = withContext(Dispatchers.IO) {
        val uri = Uri.parse(uriString)
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val displayName = queryDisplayName(uri) ?: defaultName(mime)

        if (mime.startsWith("image/")) compressImage(uri, displayName)
        else readRaw(uri, mime, displayName)
    }

    private fun compressImage(uri: Uri, displayName: String): PreparedMedia {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { context.getString(R.string.chat_error_cannot_open_image) }
            BitmapFactory.decodeStream(input, null, bounds)
        }
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0)
            throw AppError.InvalidInput(context.getString(R.string.chat_error_unsupported_or_corrupt_image))

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(srcW, srcH, MAX_DIMENSION)
        }
        val decoded = resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { context.getString(R.string.chat_error_cannot_open_image) }
            BitmapFactory.decodeStream(input, null, decodeOpts)
        } ?: throw AppError.InvalidInput(context.getString(R.string.chat_error_unsupported_or_corrupt_image))

        val scaled = scaleToMax(decoded, MAX_DIMENSION)
        if (scaled !== decoded) decoded.recycle()

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        val width = scaled.width
        val height = scaled.height
        scaled.recycle()

        return PreparedMedia(
            bytes = out.toByteArray(),
            mime = "image/jpeg",
            name = replaceExtension(displayName, "jpg"),
            width = width,
            height = height,
        )
    }

    private fun readRaw(uri: Uri, mime: String, displayName: String): PreparedMedia {
        val bytes = context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { context.getString(R.string.chat_error_cannot_open_file) }
            input.readBytes()
        }
        if (bytes.size > MAX_RAW_BYTES)
            throw AppError.InvalidInput(
                context.getString(R.string.chat_error_file_too_large, (MAX_RAW_BYTES / (1024 * 1024)).toInt()),
            )
        return PreparedMedia(bytes, mime, displayName, null, null)
    }

    private fun queryDisplayName(uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
                }
        }.getOrNull()

    private companion object {
        const val MAX_DIMENSION = 1600
        const val JPEG_QUALITY = 80
        const val MAX_RAW_BYTES = 25L * 1024 * 1024

        fun defaultName(mime: String): String {
            val ext = mime.substringAfterLast('/', "bin")
            return "attachment.$ext"
        }

        fun replaceExtension(name: String, ext: String): String {
            val base = name.substringBeforeLast('.', name)
            return "$base.$ext"
        }

        fun sampleSizeFor(width: Int, height: Int, maxDim: Int): Int {
            var sample = 1
            var w = width
            var h = height
            while (w / 2 >= maxDim || h / 2 >= maxDim) {
                w /= 2
                h /= 2
                sample *= 2
            }
            return sample
        }

        fun scaleToMax(bitmap: Bitmap, maxDim: Int): Bitmap {
            val w = bitmap.width
            val h = bitmap.height
            if (w <= maxDim && h <= maxDim) return bitmap
            val ratio = minOf(maxDim.toFloat() / w, maxDim.toFloat() / h)
            val tw = (w * ratio).toInt().coerceAtLeast(1)
            val th = (h * ratio).toInt().coerceAtLeast(1)
            return Bitmap.createScaledBitmap(bitmap, tw, th, true)
        }
    }
}
