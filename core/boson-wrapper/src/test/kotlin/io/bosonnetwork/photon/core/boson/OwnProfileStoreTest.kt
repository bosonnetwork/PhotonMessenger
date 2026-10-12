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

package io.bosonnetwork.photon.core.boson

import io.bosonnetwork.Id
import io.bosonnetwork.director.client.Avatar
import io.bosonnetwork.director.client.AvatarRefresh
import io.bosonnetwork.director.client.DirectorClient
import io.bosonnetwork.director.client.Profile
import io.bosonnetwork.photon.core.security.AvatarStore
import io.bosonnetwork.photon.core.security.IdentityVault
import io.bosonnetwork.photon.core.security.KeySealer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.nio.file.Files
import java.util.Optional
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OwnProfileStoreTest {
    private class PlainSealer : KeySealer {
        override fun seal(alias: String, plain: ByteArray): String = java.util.Base64.getEncoder().encodeToString(plain)
        override fun open(alias: String, sealed: String): ByteArray = java.util.Base64.getDecoder().decode(sealed)
        override fun delete(alias: String) {}
    }

    private val dir = Files.createTempDirectory("profile").toFile()
    private val vault = IdentityVault(dir, "test", PlainSealer())
    private val avatars = AvatarStore(dir)
    private val store = OwnProfileStore(vault, avatars)
    private val client = mockk<DirectorClient>()
    private val image = Avatar.of("image/jpeg", byteArrayOf(1, 2, 3), "\"v1\"", null)

    init {
        vault.setUserKey(ByteArray(64), Id.random().toString())
    }

    private fun profile(name: String, revision: Long, avatar: Boolean): Profile = mockk {
        every { this@mockk.name } returns Optional.of(name)
        every { bio } returns Optional.empty()
        every { email } returns Optional.empty()
        every { this@mockk.avatar } returns Optional.ofNullable("bnr://node/api/v1/client/avatar/me".takeIf { avatar })
        every { profileRevision } returns revision
    }

    private fun refresh(status: String, avatar: Avatar? = null): AvatarRefresh {
        val method = AvatarRefresh::class.java.getDeclaredMethod(status, Avatar::class.java)
        method.isAccessible = true
        return method.invoke(null, avatar) as AvatarRefresh
    }

    @Test
    fun `the profile and its avatar are kept, and read back as kept`() = runBlocking {
        every { client.avatar } returns CompletableFuture.completedFuture(Optional.of(image))
        store.keep(profile("Alice", 3, avatar = true), client)

        val kept = store.kept.first()!!
        assertEquals("Alice", kept.name)
        assertEquals(3, kept.revision)
        assertArrayEquals(byteArrayOf(1, 2, 3), kept.avatar)
        // Another profile's (the accounts list) reads the same, from the files.
        assertArrayEquals(byteArrayOf(1, 2, 3), AvatarStore.read(dir)?.data)
        assertEquals("Alice", IdentityVault.readInfo(dir)?.profile?.name)
    }

    @Test
    fun `the same revision asks nothing, a new one asks conditionally`() = runBlocking {
        every { client.avatar } returns CompletableFuture.completedFuture(Optional.of(image))
        store.keep(profile("Alice", 3, avatar = true), client)
        store.keep(profile("Alice", 3, avatar = true), client)
        verify(exactly = 1) { client.avatar }
        verify(exactly = 0) { client.refreshAvatar(any()) }

        val newer = Avatar.of("image/png", byteArrayOf(9), "\"v2\"", null)
        every { client.refreshAvatar(any()) } returns CompletableFuture.completedFuture(refresh("changed", newer))
        store.keep(profile("Alice", 4, avatar = true), client)
        assertArrayEquals(byteArrayOf(9), store.kept.first()!!.avatar)
    }

    @Test
    fun `an avatar removed on the node is removed here`() = runBlocking {
        every { client.avatar } returns CompletableFuture.completedFuture(Optional.of(image))
        store.keep(profile("Alice", 3, avatar = true), client)
        store.keep(profile("Alice", 4, avatar = false), client)
        assertNull(store.kept.first()!!.avatar)
        assertNull(AvatarStore.read(dir))
    }

    @Test
    fun `an avatar the node can't give now stays as kept`() = runBlocking {
        every { client.avatar } returns CompletableFuture.completedFuture(Optional.of(image))
        store.keep(profile("Alice", 3, avatar = true), client)
        every { client.refreshAvatar(any()) } returns CompletableFuture.failedFuture(java.io.IOException("offline"))
        store.keep(profile("Alicia", 4, avatar = true), client)
        val kept = store.kept.first()!!
        assertEquals("Alicia", kept.name)
        assertArrayEquals(byteArrayOf(1, 2, 3), kept.avatar)
    }
}
