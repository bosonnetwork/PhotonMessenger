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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun manager(): ProfileManager = ProfileManager(tmp.newFolder("files"), tmp.newFolder("cache"))

    @Test
    fun `first run bootstraps a default active profile`() {
        val pm = manager()
        val id = pm.activeProfileId()
        assertTrue(id.isNotBlank())
        assertEquals(listOf(id), pm.listProfiles().map { it.id })
    }

    @Test
    fun `active id and roots are stable across calls`() {
        val pm = manager()
        val id = pm.activeProfileId()
        assertEquals(id, pm.activeProfileId())
        assertTrue(pm.activeFilesRoot().absolutePath.endsWith("profiles/$id"))
        assertTrue(pm.activeCacheRoot().absolutePath.endsWith("profiles/$id"))
        assertEquals("secrets_$id", pm.secretsFileName())
    }

    @Test
    fun `state persists across manager instances (same registry file)`() {
        val files = tmp.newFolder("files")
        val cache = tmp.newFolder("cache")
        val first = ProfileManager(files, cache)
        val id = first.activeProfileId()
        first.bindUserId(id, "USER-A", "Alice", "NODE-1")

        val second = ProfileManager(files, cache)
        assertEquals(id, second.activeProfileId())
        assertEquals("USER-A", second.findByUserId("USER-A")?.userId)
        assertEquals("Alice", second.findByUserId("USER-A")?.displayName)
    }

    @Test
    fun `createProfile adds an unbound profile without changing the active one`() {
        val pm = manager()
        val active = pm.activeProfileId()
        val other = pm.createProfile()
        assertNotEquals(active, other)
        assertEquals(active, pm.activeProfileId())
        assertEquals(setOf(active, other), pm.listProfiles().map { it.id }.toSet())
    }

    @Test
    fun `setActive switches the active profile and stamps last-active`() {
        val pm = manager()
        val active = pm.activeProfileId()
        val other = pm.createProfile()
        pm.setActive(other)
        assertEquals(other, pm.activeProfileId())
        assertTrue(pm.listProfiles().first { it.id == other }.lastActiveAt > 0)
        assertEquals("prior profile is kept", true, pm.listProfiles().any { it.id == active })
    }

    @Test
    fun `findByUserId locates a bound profile`() {
        val pm = manager()
        val id = pm.activeProfileId()
        assertNull(pm.findByUserId("USER-A"))
        pm.bindUserId(id, "USER-A", "Alice", "NODE-1")
        assertEquals(id, pm.findByUserId("USER-A")?.id)
    }

    @Test
    fun `deleteProfile removes it and its directories`() {
        val files = tmp.newFolder("files")
        val cache = tmp.newFolder("cache")
        val pm = ProfileManager(files, cache)
        val id = pm.activeProfileId()
        pm.activeFilesRoot() // materializes profiles/<id>
        pm.activeCacheRoot()
        assertTrue(File(File(files, "profiles"), id).exists())

        pm.deleteProfile(id)

        assertFalse(pm.listProfiles().any { it.id == id })
        assertFalse(File(File(files, "profiles"), id).exists())
        assertFalse(File(File(cache, "profiles"), id).exists())
    }

    @Test
    fun `gcUnboundInactiveProfiles drops only unbound non-active profiles`() {
        val pm = manager()
        val active = pm.activeProfileId()   // unbound but active -> kept
        val boundInactive = pm.createProfile().also { pm.bindUserId(it, "USER-B", "Bob", null) }
        val unboundInactive = pm.createProfile() // dropped

        pm.gcUnboundInactiveProfiles()

        val ids = pm.listProfiles().map { it.id }.toSet()
        assertTrue(ids.contains(active))
        assertTrue(ids.contains(boundInactive))
        assertFalse(ids.contains(unboundInactive))
    }
}
