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

package io.photonmessenger.app

import io.photonmessenger.core.network.DirectorConfig

/**
 * The dev super node the LIVE integration tests run against. Single source of truth for the node's
 * coordinates so no test hardcodes the Director URL or node id.
 *
 * [directorUrl] is the host loopback as seen from the emulator (the Director listens on host :9000);
 * [directorNodeId] is the node's stable Boson id (== its peer id), used to identity-pin the self-
 * signed ECDSA certificate exactly as the app does once the operator enters it. Unlike the app, tests
 * may hardcode the id because it is a fixed property of the known dev node.
 */
object TestSuperNode {
    const val directorUrl = "https://10.0.2.2:9000"
    const val directorNodeId = "4yqoHy8628CYGSLTYAWC4VhDPtaaqEBwwgc22RXSczVA"

    /** A [DirectorConfig] for the dev super node, identity-pinned to [directorNodeId]. */
    val directorConfig: DirectorConfig get() = DirectorConfig(directorUrl, nodeId = directorNodeId)
}
