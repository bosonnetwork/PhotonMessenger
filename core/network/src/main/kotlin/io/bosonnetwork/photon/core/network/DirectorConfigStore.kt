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

import io.bosonnetwork.photon.core.security.HomeNode
import io.bosonnetwork.photon.core.security.IdentityVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The Director of the profile's home node, as a [DirectorConfig]: kept in the profile's identity record
 * ([IdentityVault]), where onboarding sets it before an identity is chosen. A profile that hasn't chosen
 * one yet has [DEFAULT_DIRECTOR_URL].
 */
class DirectorConfigStore(
    private val vault: IdentityVault,
) {
    val config: Flow<DirectorConfig> = vault.info.map { info ->
        DirectorConfig(
            baseUrl = info.homeNode?.url ?: DEFAULT_DIRECTOR_URL.trimEnd('/'),
            nodeId = info.homeNode?.nodeId,
        )
    }.distinctUntilChanged()

    suspend fun setBaseUrl(baseUrl: String) = withContext(Dispatchers.IO) {
        vault.update { it.copy(homeNode = HomeNode(baseUrl.trim().trimEnd('/'), it.homeNode?.nodeId)) }
    }

    /**
     * Sets (or clears, when null/blank) the Director's Boson node id used to identity-pin its HTTPS
     * certificate (see [DirectorConfig.nodeId]). A self-signed Director requires this; a Director
     * fronted by a real CA certificate does not.
     */
    suspend fun setNodeId(nodeId: String?) = withContext(Dispatchers.IO) {
        vault.update {
            val url = it.homeNode?.url ?: DEFAULT_DIRECTOR_URL.trimEnd('/')
            it.copy(homeNode = HomeNode(url, nodeId?.trim()?.ifBlank { null }))
        }
    }

    companion object {
        const val DEFAULT_DIRECTOR_URL = "https://your.super.node/"
    }
}
