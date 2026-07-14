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

package io.bosonnetwork.photon.core.network

import io.bosonnetwork.photon.core.model.AppError
import io.bosonnetwork.photon.core.model.ProfileResolver
import io.bosonnetwork.photon.core.model.ResolvedProfile
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * [ProfileResolver] backed by the Director's public-profile endpoint with an in-memory TTL cache.
 *
 * Cache design: profiles carry no updatedAt (any persistence would be TTL-guessing anyway), the
 * Director itself caches remote profiles for 24h, and avatar BYTES are persistently cached for free
 * by the image loader's HTTP disk cache (the endpoint serves full cache headers) - so an in-memory
 * cache per process is the sweet spot. 404s are negative-cached briefly; transient failures retry
 * after a short window. The whole cache is dropped when the Director config changes (profiles are
 * per-Director).
 */
class DirectorProfileResolver(
    private val apiFactory: DirectorApiFactory,
    configStore: DirectorConfigStore,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : ProfileResolver {

    private sealed interface Entry {
        val at: Long

        data class Found(val profile: ResolvedProfile, override val at: Long) : Entry
        data class NotFound(override val at: Long) : Entry
        data class Failed(override val at: Long) : Entry
    }

    private val entries = ConcurrentHashMap<String, MutableStateFlow<Entry?>>()
    private val inFlight = ConcurrentHashMap<String, Job>()

    /** The API + base URL for the current Director config; null until the config first loads. */
    private val current = MutableStateFlow<Pair<DirectorApi, String>?>(null)

    init {
        scope.launch {
            configStore.config.collect { cfg ->
                current.value = apiFactory.create(cfg) to cfg.baseUrl
                // Profiles are per-Director: reset entries IN PLACE (never replace the StateFlow
                // instances - active collectors stay attached and observe the reset).
                entries.values.forEach { it.value = null }
            }
        }
    }

    override fun profile(userId: String): Flow<ResolvedProfile?> =
        entryFlow(userId)
            // Re-resolve whenever the entry is missing (first collection, or reset by a Director
            // config change) or has outlived its TTL; prefetch dedups concurrent fetches.
            .onEach { entry -> if (entry == null || entry.isStale()) prefetch(userId) }
            .map { (it as? Entry.Found)?.profile }

    override fun cached(userId: String): ResolvedProfile? =
        (entries[userId]?.value as? Entry.Found)?.profile

    override fun prefetch(userId: String) {
        if (userId.isBlank()) return
        val entry = entryFlow(userId).value
        if (entry != null && !entry.isStale()) return
        inFlight.computeIfAbsent(userId) {
            scope.launch { fetch(userId) }.also { job ->
                job.invokeOnCompletion { inFlight.remove(userId) }
            }
        }
    }

    private fun entryFlow(userId: String): MutableStateFlow<Entry?> =
        entries.getOrPut(userId) { MutableStateFlow(null) }

    private fun Entry.isStale(): Boolean {
        val ttl = when (this) {
            is Entry.Found -> FOUND_TTL_MS
            is Entry.NotFound -> NOT_FOUND_TTL_MS
            is Entry.Failed -> FAILED_RETRY_MS
        }
        return nowMs() - at > ttl
    }

    private suspend fun fetch(userId: String) {
        val (api, baseUrl) = current.filterNotNull().first() // suspend until the config first loads
        val entry = try {
            val dto = api.getUserProfile(userId)
            Entry.Found(
                ResolvedProfile(
                    userId = dto.id,
                    name = dto.name?.takeIf { it.isNotBlank() },
                    bio = dto.bio?.takeIf { it.isNotBlank() },
                    avatarUrl = if (dto.avatar.isNullOrBlank()) null
                    else "$baseUrl/api/v1/client/avatar/${dto.id}",
                ),
                nowMs(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            when (e.toDirectorError()) {
                is AppError.NotFound -> Entry.NotFound(nowMs())
                else -> Entry.Failed(nowMs())
            }
        }
        entryFlow(userId).value = entry
    }

    private companion object {
        const val FOUND_TTL_MS = 60L * 60 * 1000 // 1h; the Director caches remote profiles 24h
        const val NOT_FOUND_TTL_MS = 10L * 60 * 1000 // unknown users re-checked after 10min
        const val FAILED_RETRY_MS = 30L * 1000 // transient failures retry quickly
    }
}
