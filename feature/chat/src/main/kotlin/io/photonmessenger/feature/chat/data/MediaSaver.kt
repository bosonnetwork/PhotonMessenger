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

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.media.MediaScannerConnection
import android.provider.MediaStore
import io.photonmessenger.core.model.AppError
import io.photonmessenger.feature.chat.model.AttachmentKind
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Saves a received attachment into a public collection so it survives outside the app's cache
 * (the "Save" action): images go to `Pictures/Photon`, everything else to
 * `Downloads`.
 */
interface MediaSaver {
    /**
     * Writes the attachment bytes to the public collection chosen from [kind].
     *
     * @param open supplies a fresh [InputStream] over the source bytes (inline bytes or a cache file)
     * @return the human folder label ("Pictures" / "Downloads") for a confirmation message
     */
    suspend fun save(name: String, mime: String, kind: AttachmentKind, open: () -> InputStream): Result<String>
}

/**
 * Android implementation. On API 29+ this uses MediaStore (no permission); on API 26-28 it writes to
 * the public external directory and asks the media scanner to index it - the caller must hold
 * WRITE_EXTERNAL_STORAGE there.
 */
@Singleton
class AndroidMediaSaver @Inject constructor(
    @ApplicationContext private val context: Context,
) : MediaSaver {
    override suspend fun save(
        name: String,
        mime: String,
        kind: AttachmentKind,
        open: () -> InputStream,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveViaMediaStore(name, mime, kind, open)
            else saveLegacy(name, mime, kind, open)
        }
    }

    private fun saveViaMediaStore(name: String, mime: String, kind: AttachmentKind, open: () -> InputStream): String {
        val resolver = context.contentResolver
        val isImage = kind == AttachmentKind.IMAGE
        val collection = if (isImage) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = if (isImage) "${Environment.DIRECTORY_PICTURES}/$IMAGE_SUBDIR"
        else Environment.DIRECTORY_DOWNLOADS

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            if (mime.isNotBlank()) put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw AppError.Network("Couldn't create the destination file")
        try {
            resolver.openOutputStream(uri).use { out ->
                requireNotNull(out) { "Couldn't open the destination file" }
                open().use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
        return if (isImage) "Pictures" else "Downloads"
    }

    private fun saveLegacy(name: String, mime: String, kind: AttachmentKind, open: () -> InputStream): String {
        val isImage = kind == AttachmentKind.IMAGE
        @Suppress("DEPRECATION")
        val base = Environment.getExternalStoragePublicDirectory(
            if (isImage) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS,
        )
        val dir = (if (isImage) File(base, IMAGE_SUBDIR) else base).apply { mkdirs() }
        val dest = uniqueFile(dir, name)
        dest.outputStream().use { out -> open().use { it.copyTo(out) } }
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime), null)
        return if (isImage) "Pictures" else "Downloads"
    }

    private fun uniqueFile(dir: File, name: String): File {
        val safe = name.ifBlank { "attachment" }
        val candidate = File(dir, safe)
        if (!candidate.exists()) return candidate
        val stem = safe.substringBeforeLast('.', safe)
        val ext = safe.substringAfterLast('.', "")
        var i = 1
        while (true) {
            val next = File(dir, if (ext.isEmpty()) "$stem ($i)" else "$stem ($i).$ext")
            if (!next.exists()) return next
            i++
        }
    }

    private companion object {
        const val IMAGE_SUBDIR = "Photon"
    }
}
