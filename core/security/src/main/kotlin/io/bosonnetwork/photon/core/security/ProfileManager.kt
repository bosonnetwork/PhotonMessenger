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
import java.util.Properties
import java.util.UUID

/**
 * One on-device profile: an independent home for a single Boson identity's local data (its own Room
 * database, settings, secrets, caches, and messaging-client working dir). Created with a local [id]
 * (UUID) before the identity is known; [userId] (base58 Boson id) is bound once onboarding resolves
 * the identity. [displayName]/[homeNodeId] are denormalized snapshots for the profile picker.
 */
data class Profile(
    val id: String,
    val userId: String? = null,
    val displayName: String? = null,
    val homeNodeId: String? = null,
    val lastActiveAt: Long = 0L,
)

/**
 * The only application-level (cross-profile) state: the list of profiles and which one is active. The
 * running process only ever touches the ACTIVE profile - switching to another profile is done by
 * setting it active and relaunching the app, so the whole singleton graph rebinds to its stores.
 *
 * Persistence is a plain [Properties] file under [filesDir]; the contents (UUIDs, public Boson ids,
 * display names) are non-sensitive, and a synchronous file read keeps startup simple. Takes bare
 * directories rather than a Context so it stays a pure-JVM, unit-testable class.
 */
class ProfileManager(
    private val filesDir: File,
    private val cacheDir: File,
) {
    private val registryFile = File(filesDir, REGISTRY_FILE)

    private var activeId: String? = null
    private val profiles = LinkedHashMap<String, Profile>()
    private var loaded = false

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        if (registryFile.exists()) {
            val props = Properties()
            registryFile.inputStream().use { props.load(it) }
            activeId = props.getProperty(KEY_ACTIVE)?.takeIf { it.isNotBlank() }
            props.getProperty(KEY_IDS).orEmpty().split(',').filter { it.isNotBlank() }.forEach { id ->
                profiles[id] = Profile(
                    id = id,
                    userId = props.getProperty("$id.$FIELD_USER_ID")?.takeIf { it.isNotBlank() },
                    displayName = props.getProperty("$id.$FIELD_NAME")?.takeIf { it.isNotBlank() },
                    homeNodeId = props.getProperty("$id.$FIELD_NODE")?.takeIf { it.isNotBlank() },
                    lastActiveAt = props.getProperty("$id.$FIELD_LAST_ACTIVE")?.toLongOrNull() ?: 0L,
                )
            }
        }
        loaded = true
    }

    @Synchronized
    private fun persist() {
        val props = Properties()
        activeId?.let { props.setProperty(KEY_ACTIVE, it) }
        props.setProperty(KEY_IDS, profiles.keys.joinToString(","))
        profiles.values.forEach { p ->
            p.userId?.let { props.setProperty("${p.id}.$FIELD_USER_ID", it) }
            p.displayName?.let { props.setProperty("${p.id}.$FIELD_NAME", it) }
            p.homeNodeId?.let { props.setProperty("${p.id}.$FIELD_NODE", it) }
            props.setProperty("${p.id}.$FIELD_LAST_ACTIVE", p.lastActiveAt.toString())
        }
        filesDir.mkdirs()
        registryFile.outputStream().use { props.store(it, "Photon profiles") }
    }

    /** The active profile id, creating and selecting a fresh one on first run so the graph always binds. */
    @Synchronized
    fun activeProfileId(): String {
        ensureLoaded()
        activeId?.let { if (profiles.containsKey(it)) return it }
        val id = newProfile()
        activeId = id
        persist()
        return id
    }

    /** `filesDir/profiles/<id>` - permanent per-profile store root (Room db, settings, boson dir). */
    fun activeFilesRoot(): File = filesRootFor(activeProfileId())

    /**
     * `filesDir/profiles/<id>` for an ARBITRARY profile - used to seed a not-yet-active profile's
     * stores (token, Director config) before relaunching into it.
     */
    fun filesRootFor(id: String): File = File(File(filesDir, PROFILES_DIR), id).apply { mkdirs() }

    /** `cacheDir/profiles/<id>` - per-profile cache root (attachments, image cache, voice). */
    fun activeCacheRoot(): File = File(File(cacheDir, PROFILES_DIR), activeProfileId()).apply { mkdirs() }

    /** EncryptedSharedPreferences file name for the active profile (prefs cannot be relocated by dir). */
    fun secretsFileName(): String = secretsFileNameFor(activeProfileId())

    /** EncryptedSharedPreferences file name for a given profile - used to delete it on removal. */
    fun secretsFileNameFor(id: String): String = "$SECRETS_PREFIX$id"

    @Synchronized
    fun listProfiles(): List<Profile> {
        ensureLoaded()
        return profiles.values.toList()
    }

    @Synchronized
    fun findByUserId(userId: String): Profile? {
        ensureLoaded()
        return profiles.values.firstOrNull { it.userId == userId }
    }

    /** Creates a fresh (unbound) profile and returns its id; does NOT change the active profile. */
    @Synchronized
    fun createProfile(): String {
        ensureLoaded()
        val id = newProfile()
        persist()
        return id
    }

    @Synchronized
    fun setActive(id: String) {
        ensureLoaded()
        require(profiles.containsKey(id)) { "Unknown profile: $id" }
        activeId = id
        profiles[id] = profiles.getValue(id).copy(lastActiveAt = System.currentTimeMillis())
        persist()
    }

    /** Records the resolved identity + snapshot on a profile once onboarding knows it. */
    @Synchronized
    fun bindUserId(id: String, userId: String, displayName: String?, homeNodeId: String?) {
        ensureLoaded()
        val existing = profiles[id] ?: return
        profiles[id] = existing.copy(userId = userId, displayName = displayName, homeNodeId = homeNodeId)
        persist()
    }

    /** Removes a profile from the registry and deletes its on-disk data (files, caches). */
    @Synchronized
    fun deleteProfile(id: String) {
        ensureLoaded()
        profiles.remove(id)
        if (activeId == id) activeId = null
        File(File(filesDir, PROFILES_DIR), id).deleteRecursively()
        File(File(cacheDir, PROFILES_DIR), id).deleteRecursively()
        persist()
    }

    /**
     * Drops transient onboarding leftovers: profiles that never bound an identity and are not active.
     * Their directories are removed too. Returns the removed ids so the caller (which owns the Android
     * Context) can also delete their encrypted-secrets prefs. Call at startup.
     */
    @Synchronized
    fun gcUnboundInactiveProfiles(): List<String> {
        ensureLoaded()
        val removed = profiles.values.filter { it.userId == null && it.id != activeId }.map { it.id }
        removed.forEach { id ->
            profiles.remove(id)
            File(File(filesDir, PROFILES_DIR), id).deleteRecursively()
            File(File(cacheDir, PROFILES_DIR), id).deleteRecursively()
        }
        persist()
        return removed
    }

    private fun newProfile(): String {
        val id = UUID.randomUUID().toString()
        profiles[id] = Profile(id)
        return id
    }

    private companion object {
        const val REGISTRY_FILE = "app_profiles.properties"
        const val PROFILES_DIR = "profiles"
        const val SECRETS_PREFIX = "secrets_"
        const val KEY_ACTIVE = "active"
        const val KEY_IDS = "ids"
        const val FIELD_USER_ID = "userId"
        const val FIELD_NAME = "name"
        const val FIELD_NODE = "node"
        const val FIELD_LAST_ACTIVE = "lastActive"
    }
}
