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

package io.bosonnetwork.photon.app

import io.bosonnetwork.photon.core.network.DirectorConfig

/**
 * The dev super node the LIVE integration tests run against. Single source of truth for the node's
 * coordinates so no test hardcodes the Director URL.
 *
 * The node is reachable at its public DNS name and serves a real (Let's Encrypt) certificate, so
 * tests use default system-CA trust: [directorNodeId] is null (no identity pin). To run against a
 * self-signed node instead, set [directorNodeId] to the node's Boson id - the Director client then
 * pins the certificate to that identity exactly as the app does when the operator enters a Server ID.
 */
object TestSuperNode {
    const val directorUrl = "https://whisper.freeddns.org:9000"
    val directorNodeId: String? = null

    /** A [DirectorConfig] for the dev super node (CA-trusted; identity-pinned when an id is set). */
    val directorConfig: DirectorConfig get() = DirectorConfig(directorUrl, nodeId = directorNodeId)
}
