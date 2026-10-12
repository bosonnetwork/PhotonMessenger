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

package io.bosonnetwork.photon.core.security

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Properties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * An avatar image as kept, with the validators it was downloaded with, so that the home node can say it
 * is still current without sending it again.
 */
class StoredAvatar(
    val contentType: String,
    val data: ByteArray,
    val eTag: String? = null,
    val lastModified: String? = null,
)

/**
 * The identity's own avatar, in the profile's files: part of its profile, which this profile keeps, so it
 * is data, not a cache. Every screen that shows the identity reads [avatar], so they show the same
 * picture, offline too. The image and its description are each replaced atomically; the description
 * names the image by its digest, so a crash between the two leaves no avatar rather than the wrong one
 * (it is downloaded again).
 */
class AvatarStore(private val dir: File) {
    private val _avatar = MutableStateFlow(read(dir))

    /** The avatar, or null when the identity has none (or it isn't downloaded yet). */
    val avatar: StateFlow<StoredAvatar?> = _avatar.asStateFlow()

    /** Keeps [avatar], or forgets the one kept when null. */
    @Synchronized
    fun put(avatar: StoredAvatar?) {
        val image = File(dir, IMAGE)
        val meta = File(dir, META)
        if (avatar == null) {
            meta.delete()
            image.delete()
        } else {
            dir.mkdirs()
            replace(image) { it.write(avatar.data) }
            val props = Properties().apply {
                setProperty(KEY_TYPE, avatar.contentType)
                setProperty(KEY_DIGEST, digest(avatar.data))
                avatar.eTag?.let { setProperty(KEY_ETAG, it) }
                avatar.lastModified?.let { setProperty(KEY_MODIFIED, it) }
            }
            replace(meta) { props.store(it, null) }
            IdentityVault.syncDirectory(dir)
        }
        _avatar.value = avatar
    }

    private fun replace(file: File, write: (FileOutputStream) -> Unit) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            write(out)
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) throw IOException("Could not replace ${file.name}")
    }

    companion object {
        private const val IMAGE = "avatar.img"
        private const val META = "avatar.properties"
        private const val KEY_TYPE = "type"
        private const val KEY_DIGEST = "sha256"
        private const val KEY_ETAG = "etag"
        private const val KEY_MODIFIED = "modified"

        /** The avatar kept in [dir] (another profile's), or null when there is none. */
        fun read(dir: File): StoredAvatar? = runCatching {
            val meta = File(dir, META)
            if (!meta.exists()) return null
            val props = Properties().apply { meta.inputStream().use { load(it) } }
            val data = File(dir, IMAGE).readBytes()
            if (digest(data) != props.getProperty(KEY_DIGEST)) return null
            StoredAvatar(
                props.getProperty(KEY_TYPE) ?: "application/octet-stream",
                data,
                props.getProperty(KEY_ETAG),
                props.getProperty(KEY_MODIFIED),
            )
        }.getOrNull()

        private fun digest(data: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    }
}
