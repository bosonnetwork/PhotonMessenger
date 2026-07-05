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

import android.os.Debug
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import io.vertx.core.Vertx
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M6-8 performance: brings one MessagingClient to READY against the live node, holds the idle mqtts
 * connection, and samples memory over time to catch runaway growth (a leak smoke test) and to record
 * the idle footprint. Requires the dev super node reachable (see [LiveTestHarness]).
 *
 * Caveat: this samples the *instrumentation* process (test + app code in one process), so the absolute
 * numbers include test overhead; the leak signal is the growth trend across the idle window, not the
 * absolute PSS. Logged under the tag [TAG] for capture via logcat.
 */
@RunWith(AndroidJUnit4::class)
class LiveIdleMemoryTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private data class Sample(val label: String, val javaHeapKb: Long, val nativeHeapKb: Long, val pssKb: Long)

    private fun sample(label: String): Sample {
        System.gc()
        Thread.sleep(300)
        val rt = Runtime.getRuntime()
        val javaHeapKb = (rt.totalMemory() - rt.freeMemory()) / 1024
        val nativeHeapKb = Debug.getNativeHeapAllocatedSize() / 1024
        val mi = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val s = Sample(label, javaHeapKb, nativeHeapKb, mi.totalPss.toLong())
        Log.i(TAG, "sample[$label] javaHeap=${s.javaHeapKb}KB nativeHeap=${s.nativeHeapKb}KB pss=${s.pssKb}KB")
        return s
    }

    @Test
    fun idleMqttsMemoryStaysBounded() {
        val vertx = Vertx.vertx()
        val harness = LiveTestHarness(context, vertx)
        try {
            val alice = harness.register("AliceIdle")
            harness.connect(alice)

            // Let the connection settle, then take a baseline.
            Thread.sleep(TimeUnit.SECONDS.toMillis(5))
            val baseline = sample("baseline")

            // Idle for ~40s, sampling periodically.
            val samples = mutableListOf(baseline)
            repeat(IDLE_SAMPLES) { i ->
                Thread.sleep(TimeUnit.SECONDS.toMillis(SAMPLE_INTERVAL_SEC))
                samples += sample("idle-${(i + 1) * SAMPLE_INTERVAL_SEC}s")
            }
            val last = samples.last()

            Log.i(
                TAG,
                "idle footprint: baseline pss=${baseline.pssKb}KB -> final pss=${last.pssKb}KB " +
                    "(java ${baseline.javaHeapKb}->${last.javaHeapKb}KB, native ${baseline.nativeHeapKb}->${last.nativeHeapKb}KB)",
            )

            // Leak smoke: after GC, the Java heap must not balloon past a generous bound over idle.
            val growthKb = last.javaHeapKb - baseline.javaHeapKb
            assertTrue(
                "Java heap grew ${growthKb}KB over idle (baseline=${baseline.javaHeapKb}KB); possible leak",
                growthKb <= MAX_HEAP_GROWTH_KB,
            )
        } finally {
            harness.close()
            vertx.close()
        }
    }

    private companion object {
        const val TAG = "PhotonPerf"
        const val IDLE_SAMPLES = 4
        const val SAMPLE_INTERVAL_SEC = 10L
        // Generous ceiling: idle mqtts keep-alive churns a little; a real leak grows far past this.
        const val MAX_HEAP_GROWTH_KB = 8 * 1024L
    }
}
