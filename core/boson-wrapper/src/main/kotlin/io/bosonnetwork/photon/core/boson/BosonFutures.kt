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

import io.vertx.core.Future
import io.vertx.kotlin.coroutines.coAwait
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.future.await as cfAwait

/**
 * The two Boson libraries expose different async types, so the data layer needs two bridges
 * (design spec section 4.5):
 *
 *  - MessagingClient returns java.util.concurrent.CompletableFuture -> bridge with
 *    kotlinx-coroutines-jdk8 await().
 *  - IonStore returns Vert.x io.vertx.core.Future -> bridge with the Vert.x coroutine coAwait().
 *
 * These thin aliases give call sites a single, consistent suspend point and a place to centralize
 * error mapping later (see [io.bosonnetwork.photon.core.model.AppError]).
 */

/** Suspends until this MessagingClient future completes, rethrowing its cause on failure. */
suspend fun <T> CompletableFuture<T>.awaitResult(): T = cfAwait()

/** Suspends until this IonStore Vert.x future completes, rethrowing its cause on failure. */
suspend fun <T> Future<T>.awaitResult(): T = coAwait()
