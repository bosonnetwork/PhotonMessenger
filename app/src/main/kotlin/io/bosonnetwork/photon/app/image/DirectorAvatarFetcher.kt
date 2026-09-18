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

package io.bosonnetwork.photon.app.image

import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.disk.DiskCache
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import io.bosonnetwork.Id
import io.bosonnetwork.director.client.Avatar
import io.bosonnetwork.photon.core.boson.DirectorAvatars
import io.bosonnetwork.photon.core.boson.DirectorClients
import io.bosonnetwork.photon.core.model.AppError
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.future.await
import okio.Buffer
import okio.FileSystem

/**
 * Loads a [DirectorAvatars] model through the Director client, which authenticates the request with the
 * profile's own key (the Director fetches a remote user's avatar only for a signed-in user of its own) and
 * pins a self-signed Director to its node id. A user without an avatar fails the request, so the image
 * falls back to initials.
 *
 * Avatars are kept in Coil's disk cache and revalidated on every load, as an HTTP cache would with the
 * Director's `max-age=0`: the Director is asked whether the kept copy is still current and sends the image
 * only when it changed. So an avatar is downloaded once per change, a changed one shows on its next load,
 * and a removed one is dropped. Without an answer (offline, Director down) the kept copy is shown.
 */
class DirectorAvatarFetcher(
    private val userId: Id,
    private val clients: DirectorClients,
    private val options: Options,
    private val diskCache: DiskCache?,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        // One entry per user, whatever the model's version parameter: revalidation keeps it current.
        val key = options.diskCacheKey ?: DirectorAvatars.uri(userId.toString())
        val cache = diskCache
        var snapshot = if (cache != null && options.diskCachePolicy.readEnabled) cache.openSnapshot(key) else null
        try {
            val cached = snapshot?.let { AvatarCacheEntry.read(cache!!.fileSystem, it.metadata, it.data) }
            if (cached == null) {
                // Unreadable entry: start over.
                snapshot?.close()
                snapshot = null
            }
            if (snapshot != null && !options.networkCachePolicy.readEnabled) return fromDisk(cache!!, snapshot, key, cached!!)

            val current = try {
                clients.client().getUserAvatar(userId, cached).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (snapshot == null) throw e
                return fromDisk(cache!!, snapshot, key, cached!!) // unreachable: show the copy we have
            }

            when {
                current == null -> {
                    snapshot?.close()
                    snapshot = null
                    cache?.remove(key)
                    throw AppError.NotFound("No avatar for $userId")
                }
                current === cached -> return fromDisk(cache!!, snapshot!!, key, cached)
                else -> {
                    snapshot?.close()
                    snapshot = null
                    if (cache != null && options.diskCachePolicy.writeEnabled) store(cache, key, current)
                    return SourceResult(
                        source = ImageSource(Buffer().write(current.data), options.context),
                        mimeType = current.contentType,
                        dataSource = DataSource.NETWORK,
                    )
                }
            }
        } catch (e: Throwable) {
            snapshot?.close()
            throw e
        }
    }

    // The snapshot now belongs to the image source, which closes it.
    private fun fromDisk(cache: DiskCache, snapshot: DiskCache.Snapshot, key: String, avatar: Avatar): FetchResult =
        SourceResult(
            source = ImageSource(snapshot.data, cache.fileSystem, key, snapshot),
            mimeType = avatar.contentType,
            dataSource = DataSource.DISK,
        )

    // Best effort: a copy that cannot be kept is just downloaded again next time.
    private fun store(cache: DiskCache, key: String, avatar: Avatar) {
        val editor = cache.openEditor(key) ?: return
        try {
            AvatarCacheEntry.write(cache.fileSystem, editor.metadata, editor.data, avatar)
            editor.commit()
        } catch (e: Exception) {
            editor.abort()
        }
    }

    class Factory(private val clients: DirectorClients) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            DirectorAvatars.userIdOf(data)?.let { DirectorAvatarFetcher(it, clients, options, imageLoader.diskCache) }
    }
}

/**
 * How a kept avatar is laid out in a disk cache entry: the image as the data, and as the metadata the
 * content type and the Director's validators, one per line (an absent validator is an empty line).
 */
internal object AvatarCacheEntry {
    fun write(fs: FileSystem, metadata: okio.Path, data: okio.Path, avatar: Avatar) {
        fs.write(data) { write(avatar.data) }
        fs.write(metadata) {
            writeUtf8(encodeMetadata(avatar))
        }
    }

    /** The kept avatar, or null when the entry is not one this writes. */
    fun read(fs: FileSystem, metadata: okio.Path, data: okio.Path): Avatar? = runCatching {
        decodeMetadata(fs.read(metadata) { readUtf8() }, fs.read(data) { readByteArray() })
    }.getOrNull()

    fun encodeMetadata(avatar: Avatar): String =
        listOf(
            VERSION,
            avatar.contentType,
            avatar.eTag.orElse(""),
            avatar.lastModified.orElse(""),
        ).joinToString("\n")

    fun decodeMetadata(metadata: String, data: ByteArray): Avatar? {
        val lines = metadata.split('\n')
        if (lines.size != FIELDS || lines[0] != VERSION || lines[1].isBlank()) return null
        return Avatar.of(lines[1], data, lines[2].ifEmpty { null }, lines[3].ifEmpty { null })
    }

    private const val VERSION = "director-avatar/1"
    private const val FIELDS = 4
}
