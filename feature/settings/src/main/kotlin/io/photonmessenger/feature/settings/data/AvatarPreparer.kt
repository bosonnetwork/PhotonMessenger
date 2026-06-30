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

package io.photonmessenger.feature.settings.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import io.photonmessenger.core.model.AppError
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A picked avatar image, downscaled and recompressed to a small JPEG (spec 2.6, M6-1). */
class PreparedAvatar(val bytes: ByteArray, val mime: String)

/** Reads a picked image [Uri] and produces a square-capped JPEG suitable for an avatar upload. */
@Singleton
class AvatarPreparer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun prepare(uriString: String): PreparedAvatar = withContext(Dispatchers.IO) {
        val uri = Uri.parse(uriString)
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: ""
        if (!mime.startsWith("image/"))
            throw AppError.InvalidInput("Please choose an image")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open image" }
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
            throw AppError.InvalidInput("Unsupported or corrupt image")

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
        }
        val decoded = resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open image" }
            BitmapFactory.decodeStream(input, null, decodeOpts)
        } ?: throw AppError.InvalidInput("Unsupported or corrupt image")

        val scaled = scaleToMax(decoded, MAX_DIMENSION)
        if (scaled !== decoded) decoded.recycle()

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        scaled.recycle()

        PreparedAvatar(out.toByteArray(), "image/jpeg")
    }

    private companion object {
        const val MAX_DIMENSION = 512
        const val JPEG_QUALITY = 85

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
