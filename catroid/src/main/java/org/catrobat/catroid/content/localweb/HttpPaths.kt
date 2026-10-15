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

import java.net.URLDecoder

/**
 * Request path handling for the embedded HTTP server.
 *
 * Pure JVM (no Android dependencies) so it can be unit tested directly.
 */
object HttpPaths {

    /**
     * Decodes and normalizes the path component of a request target.
     *
     * - Percent-decoding is applied first, so encoded traversal attempts
     *   (`%2e%2e%2f`, `%2E%2E/`) are treated exactly like plain `../`.
     * - `.` segments are dropped, `..` segments pop the previous segment.
     * - Backslashes are rejected: they are path separators on some platforms
     *   and a classic traversal trick on others.
     * - NUL bytes and absolute Windows drive prefixes are rejected.
     *
     * @return the normalized relative path (no leading or trailing slash,
     *         empty string for the root) or null when the path is invalid or
     *         escapes the document root.
     */
    fun normalize(rawPath: String?): String? {
        if (rawPath == null) return null
        // In URL *paths* a '+' is a literal plus (URLDecoder would turn it into
        // a space, which is only correct for query strings), so protect it
        // before percent-decoding.
        val decoded: String = try {
            URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8")
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (decoded.contains('\u0000')) return null
        if (decoded.contains('\\')) return null
        // A leading "//" followed by a segment is an absolute-form /
        // scheme-relative target ("//host/path"), not a plain path - reject it
        // as an injection attempt. A bare "//" (or "///") still means the root.
        if (decoded.startsWith("//") && decoded.trimStart('/').isNotEmpty()) return null

        val segments = ArrayList<String>()
        for (segment in decoded.split('/')) {
            when {
                segment.isEmpty() || segment == "." -> Unit
                segment == ".." -> {
                    if (segments.isEmpty()) return null // escapes the root
                    segments.removeAt(segments.size - 1)
                }
                segment.length >= 2 && segment[1] == ':' -> return null // windows drive letter
                else -> segments.add(segment)
            }
        }
        return segments.joinToString("/")
    }

    /**
     * Normalizes a configured URL prefix (used by the "Host Directory" block).
     * Always returns a value starting with '/' and not ending with '/'
     * ("/" itself for the root prefix).
     */
    fun normalizePrefix(prefix: String?): String {
        if (prefix.isNullOrBlank()) return "/"
        var p = prefix.trim()
        while (p.contains("//")) p = p.replace("//", "/")
        if (!p.startsWith("/")) p = "/$p"
        while (p.length > 1 && p.endsWith("/")) p = p.dropLast(1)
        return p
    }

    /**
     * Removes the configured [prefix] from a request [path] and returns the
     * remaining relative path, or null when the path does not start with the
     * prefix.
     */
    fun stripPrefix(path: String, prefix: String): String? {
        val normalizedPrefix = normalizePrefix(prefix)
        val withSlash = if (path.startsWith("/")) path else "/$path"
        if (normalizedPrefix == "/") return withSlash.removePrefix("/")
        if (withSlash == normalizedPrefix) return ""
        if (withSlash.startsWith("$normalizedPrefix/")) return withSlash.removePrefix("$normalizedPrefix/")
        return null
    }
}
