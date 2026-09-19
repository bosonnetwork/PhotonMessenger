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

import android.content.Context
import coil.decode.DataSource
import coil.disk.DiskCache
import coil.fetch.SourceResult
import coil.request.CachePolicy
import coil.request.Options
import io.bosonnetwork.Id
import io.bosonnetwork.director.client.Avatar
import io.bosonnetwork.director.client.AvatarRefresh
import io.bosonnetwork.director.client.DirectorClient
import io.bosonnetwork.director.client.exceptions.DirectorException
import io.bosonnetwork.photon.core.boson.DirectorAvatars
import io.bosonnetwork.photon.core.boson.DirectorClients
import io.bosonnetwork.photon.core.model.AppError
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.util.Optional
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DirectorAvatarFetcherTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val userId = Id.random()
    private val key = DirectorAvatars.uri(userId.toString())
    private val context: Context = mockk(relaxed = true)
    private lateinit var diskCache: DiskCache

    /**
     * The Director: answers with [userId]'s current avatar given the caller's copy - that very copy when it
     * is unchanged, null when there is none.
     */
    private var director: (Avatar?) -> CompletableFuture<Avatar?> = { CompletableFuture.completedFuture(null) }
    private val asked = mutableListOf<Avatar?>()

    // The outcome of a refresh, as the Director client reports it: the held copy itself when unchanged.
    private fun refreshOf(held: Avatar, current: Avatar?): AvatarRefresh = mockk {
        every { status } returns when {
            current == null -> AvatarRefresh.Status.REMOVED
            current === held -> AvatarRefresh.Status.UNCHANGED
            else -> AvatarRefresh.Status.CHANGED
        }
        every { avatar } returns Optional.ofNullable(current)
    }

    private val clients: DirectorClients = mockk {
        val client = mockk<DirectorClient> {
            every { getUserAvatar(any()) } answers {
                asked += null
                director(null).thenApply { Optional.ofNullable(it) }
            }
            every { refreshUserAvatar(any(), any()) } answers {
                val held = secondArg<Avatar>()
                asked += held
                director(held).thenApply { refreshOf(held, it) }
            }
        }
        coEvery { client(any()) } returns client
    }

    @Before
    fun setUp() {
        diskCache = DiskCache.Builder().directory(tmp.newFolder("image_cache")).maxSizeBytes(1024 * 1024).build()
    }

    private fun avatar(text: String, lastModified: String?) =
        Avatar.of("image/png", text.toByteArray(), null, lastModified)

    private fun fetcher(
        diskPolicy: CachePolicy = CachePolicy.ENABLED,
        networkPolicy: CachePolicy = CachePolicy.ENABLED,
    ) = DirectorAvatarFetcher(
        userId, clients,
        Options(context, diskCachePolicy = diskPolicy, networkCachePolicy = networkPolicy),
        diskCache,
    )

    private suspend fun fetchBytes(fetcher: DirectorAvatarFetcher = fetcher()): Pair<ByteArray, DataSource> {
        val result = fetcher.fetch() as SourceResult
        return result.source.use { it.source().readByteArray() } to result.dataSource
    }

    private fun kept(): Avatar? = diskCache.openSnapshot(key)?.use {
        AvatarCacheEntry.read(diskCache.fileSystem, it.metadata, it.data)
    }

    @Test
    fun `a first load downloads and keeps the avatar`() = runTest {
        director = { CompletableFuture.completedFuture(avatar("one", "Mon, 01 Jan 2024 00:00:00 GMT")) }

        val (bytes, source) = fetchBytes()

        assertArrayEquals("one".toByteArray(), bytes)
        assertEquals(DataSource.NETWORK, source)
        assertNull("nothing kept yet, so an unconditional download", asked.single())
        assertEquals("Mon, 01 Jan 2024 00:00:00 GMT", kept()!!.lastModified.orElse(null))
    }

    @Test
    fun `an unchanged avatar is served from disk`() = runTest {
        director = { CompletableFuture.completedFuture(avatar("one", "Mon, 01 Jan 2024 00:00:00 GMT")) }
        fetchBytes()

        director = { copy -> CompletableFuture.completedFuture(copy) } // 304: the caller's copy is current
        val (bytes, source) = fetchBytes()

        assertArrayEquals("one".toByteArray(), bytes)
        assertEquals(DataSource.DISK, source)
        assertEquals("the kept copy was offered for revalidation",
            "Mon, 01 Jan 2024 00:00:00 GMT", asked.last()!!.lastModified.orElse(null))
    }

    @Test
    fun `a changed avatar replaces the kept one`() = runTest {
        director = { CompletableFuture.completedFuture(avatar("one", "Mon, 01 Jan 2024 00:00:00 GMT")) }
        fetchBytes()

        director = { CompletableFuture.completedFuture(avatar("two", "Tue, 02 Jan 2024 00:00:00 GMT")) }
        val (bytes, source) = fetchBytes()

        assertArrayEquals("two".toByteArray(), bytes)
        assertEquals(DataSource.NETWORK, source)
        assertArrayEquals("two".toByteArray(), kept()!!.data)
    }

    @Test
    fun `a removed avatar fails the load and is dropped`() = runTest {
        director = { CompletableFuture.completedFuture(avatar("one", "Mon, 01 Jan 2024 00:00:00 GMT")) }
        fetchBytes()

        director = { CompletableFuture.completedFuture(null) }
        assertThrows(AppError.NotFound::class.java) { kotlinx.coroutines.runBlocking { fetcher().fetch() } }
        assertNull(kept())
    }

    @Test
    fun `an unreachable Director shows the kept copy`() = runTest {
        director = { CompletableFuture.completedFuture(avatar("one", "Mon, 01 Jan 2024 00:00:00 GMT")) }
        fetchBytes()

        director = { CompletableFuture.failedFuture(DirectorException("Director request failed", IOException("down"))) }
        val (bytes, source) = fetchBytes()

        assertArrayEquals("one".toByteArray(), bytes)
        assertEquals(DataSource.DISK, source)
    }

    @Test
    fun `an unreachable Director with nothing kept fails the load`() = runTest {
        director = { CompletableFuture.failedFuture(DirectorException("Director request failed", IOException("down"))) }
        assertThrows(DirectorException::class.java) { kotlinx.coroutines.runBlocking { fetcher().fetch() } }
    }

    @Test
    fun `a disk-only load never asks the Director`() = runTest {
        director = { CompletableFuture.completedFuture(avatar("one", "Mon, 01 Jan 2024 00:00:00 GMT")) }
        fetchBytes()
        val asks = asked.size

        val (bytes, source) = fetchBytes(fetcher(networkPolicy = CachePolicy.DISABLED))

        assertArrayEquals("one".toByteArray(), bytes)
        assertEquals(DataSource.DISK, source)
        assertEquals(asks, asked.size)
    }

    @Test
    fun `nothing is kept when the disk cache is off`() = runTest {
        director = { CompletableFuture.completedFuture(avatar("one", "Mon, 01 Jan 2024 00:00:00 GMT")) }
        fetchBytes(fetcher(diskPolicy = CachePolicy.DISABLED))
        assertNull(kept())
    }

    @Test
    fun `the metadata round-trips, absent validators included`() {
        val full = Avatar.of("image/jpeg", byteArrayOf(1, 2), "\"v1\"", "Mon, 01 Jan 2024 00:00:00 GMT")
        val back = AvatarCacheEntry.decodeMetadata(AvatarCacheEntry.encodeMetadata(full), full.data)!!
        assertEquals("image/jpeg", back.contentType)
        assertEquals("\"v1\"", back.eTag.orElse(null))
        assertEquals("Mon, 01 Jan 2024 00:00:00 GMT", back.lastModified.orElse(null))

        val bare = AvatarCacheEntry.decodeMetadata(
            AvatarCacheEntry.encodeMetadata(Avatar.of("image/png", byteArrayOf(), null, null)), byteArrayOf(),
        )!!
        assertEquals(false, bare.hasValidators())
        assertNull(AvatarCacheEntry.decodeMetadata("something else", byteArrayOf()))
    }

    @Test
    fun `an unreadable entry is downloaded again`() = runTest {
        diskCache.openEditor(key)!!.apply {
            FileSystem.SYSTEM.write(metadata) { writeUtf8("garbage") }
            FileSystem.SYSTEM.write(data) { writeUtf8("x") }
        }.commit()
        director = { CompletableFuture.completedFuture(avatar("one", "Mon, 01 Jan 2024 00:00:00 GMT")) }

        val (bytes, source) = fetchBytes()

        assertArrayEquals("one".toByteArray(), bytes)
        assertEquals(DataSource.NETWORK, source)
        assertNull("an unreadable entry is no copy to offer", asked.single())
    }
}
