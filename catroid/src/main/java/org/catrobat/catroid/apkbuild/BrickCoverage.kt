/*
 * Catroid: An on-device visual programming system for Android devices
 * Copyright (C) 2010-2026 The Catrobat Team
 * (<http://developer.catrobat.org/credits>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * An additional term exception under section 7 of the GNU Affero General
 * Public License, version 3, is available at
 * http://www.gnu.org/licenses/additional_term
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.catrobat.catroid.apkbuild

import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Checks whether an APK really contains the classes a project needs.
 *
 * An exported APK is not compiled: [ApkToolboxManager] only patches a finished
 * template APK (package name, icon, permissions, `assets/project.zip`). The
 * dex files inside the template are therefore the only code the exported app
 * will ever have. When the project uses a brick the template was not built
 * with, XStream cannot resolve the class while loading `code.xml`, the project
 * fails to load and the exported app shows a splash screen and then closes.
 *
 * Class names are looked up in the string pool of `classes*.dex`, which is how
 * class references are stored in a dex file. That needs no dex parser and it is
 * exactly the name XStream would try to resolve. Everything here is plain
 * `java.io` / `java.util.zip` so it can be unit tested on the JVM.
 */
object BrickCoverage {

    /** Result of a coverage check: what was looked for and what is absent. */
    data class Result(
        val checked: List<String>,
        val missing: List<String>
    ) {
        val isComplete: Boolean get() = missing.isEmpty()
        val hasCheckedAnything: Boolean get() = checked.isNotEmpty()

        /** Short, user facing summary of the missing classes. */
        fun missingSummary(limit: Int = 6): String {
            val shown = missing.take(limit).joinToString(", ")
            return if (missing.size > limit) "$shown, +${missing.size - limit}" else shown
        }
    }

    /** Windows overlap so a name split between two reads is still found. */
    private const val READ_CHUNK = 256 * 1024

    /**
     * True when every name in [classNames] occurs somewhere in `classes*.dex`.
     *
     * The whole APK is scanned once: all needles are searched for in the same
     * streaming pass, which keeps the check cheap even for a 300 MB template.
     */
    fun check(apk: File, classNames: Collection<String>): Result {
        val candidates = classNames
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        if (candidates.isEmpty()) return Result(emptyList(), emptyList())
        if (!apk.isFile) return Result(candidates, candidates)

        val found = HashSet<String>()
        try {
            ZipFile(apk).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!DEX_NAME.matches(entry.name)) continue
                    val stillMissing = candidates.filterNot { found.contains(it) }
                    if (stillMissing.isEmpty()) break
                    zip.getInputStream(entry).use { stream ->
                        found.addAll(search(stream, stillMissing))
                    }
                }
            }
        } catch (e: Exception) {
            // An unreadable APK cannot be verified; report everything as missing
            // so the caller refuses the build instead of shipping a broken app.
            return Result(candidates, candidates)
        }
        val missing = candidates.filterNot { found.contains(it) }
        return Result(candidates, missing)
    }

    /**
     * Streams [input] once and returns the needles that occur in it.
     *
     * Each read keeps the trailing `longest - 1` bytes of the previous window so
     * a name that straddles a chunk boundary is still matched.
     */
    internal fun search(input: InputStream, needles: List<String>): Set<String> {
        if (needles.isEmpty()) return emptySet()
        val encoded = needles.associateWith { it.toByteArray(Charsets.US_ASCII) }
        val longest = encoded.values.maxOf { it.size }
        val overlap = (longest - 1).coerceAtLeast(0)

        val found = HashSet<String>()
        var carry = ByteArray(0)
        val buffer = ByteArray(READ_CHUNK)

        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            val window = ByteArray(carry.size + read)
            System.arraycopy(carry, 0, window, 0, carry.size)
            System.arraycopy(buffer, 0, window, carry.size, read)

            for ((name, bytes) in encoded) {
                if (found.contains(name)) continue
                if (indexOf(window, bytes) >= 0) found.add(name)
            }
            if (found.size == encoded.size) break

            carry = if (window.size > overlap) {
                window.copyOfRange(window.size - overlap, window.size)
            } else {
                window
            }
        }
        return found
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        val last = haystack.size - needle.size
        outer@ for (i in 0..last) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private val DEX_NAME = Regex("classes[0-9]*\\.dex")
}
