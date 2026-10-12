/*
 * Catroid: An on-device visual programming system for Android devices
 * Copyright (C) 2010-2024 The Catrobat Team
 * (<http://developer.catrobat.org/credits>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * An additional term exception under section 7 of the GNU Affero
 * General Public License, version 3, is available at
 * http://developer.catrobat.org/license_additional_term
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.catrobat.catroid.test.pcruntime

import org.catrobat.catroid.pcruntime.PcLogLevel
import org.catrobat.catroid.pcruntime.PcRuntimeLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PcRuntimeLogTest {

    @Test
    fun testEntriesAreKeptInOrder() {
        val log = PcRuntimeLog(10)
        log.info("A", "first")
        log.warn("B", "second")
        log.error("C", "third")

        val entries = log.snapshot()
        assertEquals(3, entries.size)
        assertEquals("first", entries[0].message)
        assertEquals(PcLogLevel.WARN, entries[1].level)
        assertEquals("C", entries[2].tag)
    }

    @Test
    fun testCapacityIsEnforcedAndOldestEntriesAreDropped() {
        val log = PcRuntimeLog(5)
        for (index in 1..20) {
            log.info("T", "message $index")
        }

        assertEquals(5, log.size)
        val messages = log.snapshot().map { it.message }
        assertEquals(listOf("message 16", "message 17", "message 18", "message 19", "message 20"), messages)
    }

    @Test
    fun testDefaultCapacityIsPositive() {
        assertTrue(PcRuntimeLog.DEFAULT_CAPACITY > 0)
        assertEquals(0, PcRuntimeLog().size)
    }

    @Test
    fun testClearRemovesEverything() {
        val log = PcRuntimeLog(3)
        log.info("T", "a")
        log.clear()
        assertEquals(0, log.size)
        assertTrue(log.snapshot().isEmpty())
    }

    @Test
    fun testTailReturnsRequestedNumberOfLines() {
        val log = PcRuntimeLog(50)
        for (index in 1..10) {
            log.info("T", "line $index")
        }

        val tail = log.tail(3)
        val lines = tail.split("\n")
        assertEquals(3, lines.size)
        assertTrue(lines[0].endsWith("line 8"))
        assertTrue(lines[2].endsWith("line 10"))
    }

    @Test
    fun testUserTailHidesDebugEntries() {
        val log = PcRuntimeLog(50)
        log.debug("T", "internal detail")
        log.info("T", "visible detail")

        val tail = log.userTail(10)
        assertFalse(tail.contains("internal detail"))
        assertTrue(tail.contains("visible detail"))
    }

    @Test
    fun testMultiLineMessagesAreCollapsedToOneLine() {
        val log = PcRuntimeLog(5)
        log.info("T", "first\nsecond")
        assertEquals(1, log.snapshot().size)
        assertTrue(log.snapshot()[0].message.contains("first second"))
    }

    @Test
    fun testFilterByLevel() {
        val log = PcRuntimeLog(10)
        log.info("T", "i1")
        log.error("T", "e1")
        log.error("T", "e2")

        assertEquals(2, log.snapshot(PcLogLevel.ERROR).size)
        assertEquals(1, log.snapshot(PcLogLevel.INFO).size)
        assertEquals(0, log.snapshot(PcLogLevel.WARN).size)
    }

    @Test
    fun testConcurrentWritersDoNotExceedCapacity() {
        val log = PcRuntimeLog(200)
        val threads = 4
        val perThread = 250
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)

        repeat(threads) { threadIndex ->
            pool.execute {
                start.await()
                for (index in 0 until perThread) {
                    log.info("T$threadIndex", "entry $index")
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))

        assertEquals(200, log.size)
    }

    @Test
    fun testTailIsEmptyForNonPositiveLength() {
        val log = PcRuntimeLog(5)
        log.info("T", "something")
        assertEquals("", log.tail(0))
        assertEquals("", log.tail(-1))
    }
}
