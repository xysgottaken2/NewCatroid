/*
 * Catroid: An on-device visual programming system for Android devices
 * Copyright (C) 2010-2025 The Catrobat Team
 * (<http://developer.catrobat.org/credits>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * An additional term exception under section 7 of the GNU Affero
 * General Public License, version 3, is available at
 * http://www.gnu.org/licenses/.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.catrobat.catroid.test.localweb

import org.catrobat.catroid.content.localweb.HttpRanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpRangesTest {

    private val size = 1000L

    @Test
    fun `missing or irrelevant headers are ignored`() {
        assertTrue(HttpRanges.parse(null, size) is HttpRanges.Result.Ignore)
        assertTrue(HttpRanges.parse("", size) is HttpRanges.Result.Ignore)
        assertTrue(HttpRanges.parse("items=0-10", size) is HttpRanges.Result.Ignore)
        assertTrue(HttpRanges.parse("bytes=", size) is HttpRanges.Result.Ignore)
        assertTrue(HttpRanges.parse("bytes=abc", size) is HttpRanges.Result.Ignore)
        assertTrue(HttpRanges.parse("bytes=5", size) is HttpRanges.Result.Ignore)
    }

    @Test
    fun `closed range`() {
        val result = HttpRanges.parse("bytes=0-99", size)
        assertTrue(result is HttpRanges.Result.Single)
        result as HttpRanges.Result.Single
        assertEquals(0L, result.start)
        assertEquals(99L, result.end)
        assertEquals(100L, result.length)
    }

    @Test
    fun `open ended range`() {
        val result = HttpRanges.parse("bytes=500-", size) as HttpRanges.Result.Single
        assertEquals(500L, result.start)
        assertEquals(999L, result.end)
        assertEquals(500L, result.length)
    }

    @Test
    fun `suffix range`() {
        val result = HttpRanges.parse("bytes=-100", size) as HttpRanges.Result.Single
        assertEquals(900L, result.start)
        assertEquals(999L, result.end)
        assertEquals(100L, result.length)
    }

    @Test
    fun `suffix range larger than the file returns the whole file`() {
        val result = HttpRanges.parse("bytes=-5000", size) as HttpRanges.Result.Single
        assertEquals(0L, result.start)
        assertEquals(999L, result.end)
    }

    @Test
    fun `end beyond the file size is clamped`() {
        val result = HttpRanges.parse("bytes=900-2000", size) as HttpRanges.Result.Single
        assertEquals(900L, result.start)
        assertEquals(999L, result.end)
    }

    @Test
    fun `unsatisfiable ranges`() {
        assertTrue(HttpRanges.parse("bytes=1000-", size) is HttpRanges.Result.NotSatisfiable)
        assertTrue(HttpRanges.parse("bytes=1000-2000", size) is HttpRanges.Result.NotSatisfiable)
        assertTrue(HttpRanges.parse("bytes=200-100", size) is HttpRanges.Result.NotSatisfiable)
        assertTrue(HttpRanges.parse("bytes=-0", size) is HttpRanges.Result.NotSatisfiable)
        assertTrue(HttpRanges.parse("bytes=0-10", 0L) is HttpRanges.Result.NotSatisfiable)
        assertTrue(HttpRanges.parse("bytes=-5", 0L) is HttpRanges.Result.NotSatisfiable)
    }

    @Test
    fun `first range of a multi range request is used`() {
        val result = HttpRanges.parse("bytes=0-9,20-29", size) as HttpRanges.Result.Single
        assertEquals(0L, result.start)
        assertEquals(9L, result.end)
    }

    @Test
    fun `range header is case insensitive and tolerant to spaces`() {
        val result = HttpRanges.parse("  Bytes = 0 - 4 ", size) as HttpRanges.Result.Single
        assertEquals(0L, result.start)
        assertEquals(4L, result.end)
    }
}
