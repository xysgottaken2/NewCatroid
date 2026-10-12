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

package org.catrobat.catroid.pcruntime

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class PcLogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR
}

data class PcLogEntry(
    val timestampMs: Long,
    val level: PcLogLevel,
    val tag: String,
    val message: String
) {
    fun format(formatter: SimpleDateFormat): String =
        "${formatter.format(Date(timestampMs))} [${level.name}] $tag: $message"
}

/**
 * Fixed-size, thread-safe ring buffer used as the single source of truth for
 * user visible PC runtime diagnostics. The buffer is intentionally bounded so
 * that a runaway native process cannot exhaust the Java heap.
 */
class PcRuntimeLog(private val capacity: Int = DEFAULT_CAPACITY) {

    companion object {
        const val DEFAULT_CAPACITY = 400
        private const val MAX_TAG_LENGTH = 24
    }

    private val entries = ArrayDeque<PcLogEntry>(capacity)
    private val lock = Any()

    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun debug(tag: String, message: String) = append(PcLogLevel.DEBUG, tag, message)

    fun info(tag: String, message: String) = append(PcLogLevel.INFO, tag, message)

    fun warn(tag: String, message: String) = append(PcLogLevel.WARN, tag, message)

    fun error(tag: String, message: String) = append(PcLogLevel.ERROR, tag, message)

    fun append(level: PcLogLevel, tag: String, message: String) {
        val entry = PcLogEntry(
            System.currentTimeMillis(),
            level,
            tag.take(MAX_TAG_LENGTH),
            message.replace('\n', ' ')
        )
        synchronized(lock) {
            if (entries.size >= capacity) {
                entries.removeFirst()
            }
            entries.addLast(entry)
        }
    }

    fun snapshot(): List<PcLogEntry> = synchronized(lock) { entries.toList() }

    fun snapshot(level: PcLogLevel): List<PcLogEntry> = snapshot().filter { it.level == level }

    fun clear() = synchronized(lock) { entries.clear() }

    val size: Int
        get() = synchronized(lock) { entries.size }

    /**
     * @return the most recent [maxLines] entries, oldest first, as a single
     * string that is safe to store inside a Catrobat user variable.
     */
    fun tail(maxLines: Int): String {
        val all = snapshot()
        val selected = if (maxLines <= 0) emptyList() else all.takeLast(maxLines)
        return synchronized(formatter) {
            selected.joinToString(separator = "\n") { it.format(formatter) }
        }
    }

    /**
     * Non-verbose tail used for the user facing "runtime log" block. DEBUG
     * entries are filtered out because they are only useful in logcat.
     */
    fun userTail(maxLines: Int): String {
        val filtered = snapshot().filter { it.level != PcLogLevel.DEBUG }
        val selected = if (maxLines <= 0) emptyList() else filtered.takeLast(maxLines)
        return synchronized(formatter) {
            selected.joinToString(separator = "\n") { it.format(formatter) }
        }
    }
}
