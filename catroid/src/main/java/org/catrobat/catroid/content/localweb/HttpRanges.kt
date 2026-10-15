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

package org.catrobat.catroid.content.localweb

/**
 * RFC 7233 byte-range parsing for the embedded HTTP server.
 * Pure JVM so it can be unit tested directly.
 *
 * Only single ranges are supported. When a client asks for multiple ranges
 * the first one is served (documented limitation); clients that need
 * multi-range responses are rare in practice.
 */
object HttpRanges {

    sealed class Result {
        /** No Range header present, or the header should be ignored. */
        object Ignore : Result()

        /** The range cannot be satisfied for the given size (-> 416). */
        object NotSatisfiable : Result()

        /** A single inclusive byte range [start]..[end]. */
        data class Single(val start: Long, val end: Long) : Result() {
            val length: Long get() = end - start + 1
        }
    }

    /**
     * Parses a `Range` header value for a representation of [size] bytes.
     * [size] must be >= 0 (known length); pass [Result.Ignore] semantics for
     * unknown lengths by not calling this at all.
     */
    fun parse(rangeHeader: String?, size: Long): Result {
        if (rangeHeader.isNullOrBlank() || size < 0) return Result.Ignore
        val value = rangeHeader.trim()
        val eqIndex = value.indexOf('=')
        if (eqIndex <= 0) return Result.Ignore
        val unit = value.substring(0, eqIndex).trim()
        if (!unit.equals("bytes", ignoreCase = true)) return Result.Ignore

        // Only the first range of a multi-range request is served.
        val rangeSpec = value.substring(eqIndex + 1).split(',')[0].trim()
        if (rangeSpec.isEmpty()) return Result.Ignore

        val dashIndex = rangeSpec.indexOf('-')
        if (dashIndex < 0) return Result.Ignore
        val startText = rangeSpec.substring(0, dashIndex).trim()
        val endText = rangeSpec.substring(dashIndex + 1).trim()

        return try {
            when {
                // suffix range: bytes=-N (last N bytes)
                startText.isEmpty() -> {
                    if (endText.isEmpty()) return Result.Ignore
                    val suffixLength = endText.toLong()
                    if (suffixLength <= 0) return Result.NotSatisfiable
                    if (size == 0L) return Result.NotSatisfiable
                    val start = maxOf(0L, size - suffixLength)
                    Result.Single(start, size - 1)
                }
                // open ended range: bytes=N-
                endText.isEmpty() -> {
                    val start = startText.toLong()
                    if (start < 0 || start >= size) return Result.NotSatisfiable
                    Result.Single(start, size - 1)
                }
                // closed range: bytes=N-M
                else -> {
                    val start = startText.toLong()
                    val end = endText.toLong()
                    if (start < 0 || end < start || start >= size) return Result.NotSatisfiable
                    Result.Single(start, minOf(end, size - 1))
                }
            }
        } catch (e: NumberFormatException) {
            Result.Ignore
        }
    }
}
