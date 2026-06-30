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

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Content-addressed download cache for IonStore attachments, under `cacheDir/attachments` (spec M5-7).
 * Files are named by content id (the SHA-256), so identical content is fetched once. A best-effort
 * LRU-by-last-modified eviction keeps the directory under [MAX_CACHE_BYTES].
 */
@Singleton
class AttachmentCache @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val dir: File = File(context.cacheDir, "attachments").apply { mkdirs() }

    /** The cache file for the given content id, keeping the original name's extension for the viewer. */
    fun fileFor(contentId: String, name: String): File {
        val ext = name.substringAfterLast('.', "").take(MAX_EXT_LEN)
        val safe = contentId.filter { it.isLetterOrDigit() }
        val fileName = if (ext.isEmpty()) safe else "$safe.$ext"
        return File(dir, fileName)
    }

    /** Trims the cache to [MAX_CACHE_BYTES], deleting least-recently-modified files first. */
    fun trim() {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        files.sortedBy { it.lastModified() }.forEach { f ->
            if (total <= MAX_CACHE_BYTES) return
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    private companion object {
        const val MAX_CACHE_BYTES = 256L * 1024 * 1024
        const val MAX_EXT_LEN = 8
    }
}
