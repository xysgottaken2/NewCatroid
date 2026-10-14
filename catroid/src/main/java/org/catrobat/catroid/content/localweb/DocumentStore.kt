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

import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * A single file or directory that can be served over HTTP.
 *
 * @param id provider specific document id (for SAF stores) or the absolute
 *           file path (for [FileDocumentStore]). Never a display name.
 * @param name human readable display name of the document.
 * @param isDirectory true when the document is a directory.
 * @param size size in bytes, or -1 when unknown.
 * @param mimeType provider reported MIME type, or null when unknown.
 */
data class DocumentEntry(
    val id: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val mimeType: String?
)

/**
 * Abstraction over the place files are served from. Implementations exist for
 * plain [File] directories ([FileDocumentStore]) and for Storage Access
 * Framework document trees ([SafDocumentStore]). Keeping this an interface
 * lets the HTTP engine stay free of Android dependencies and be unit tested
 * on the JVM.
 */
interface DocumentStore {
    /** Human readable name of the served root (used for directory listings). */
    val rootDisplayName: String

    /**
     * Resolves a normalized, relative, '/'-separated path (no leading slash,
     * no '..' segments - see [HttpPaths.normalize]) against the store root.
     * Returns null when the document does not exist or is not readable.
     */
    fun resolve(relativePath: String): DocumentEntry?

    /** Opens a bounded stream for reading the entry's content. Null on failure. */
    fun openInput(entry: DocumentEntry): InputStream?

    /** Lists the children of a directory entry. Empty list for non-directories. */
    fun listChildren(directory: DocumentEntry): List<DocumentEntry>
}

/**
 * Deterministic fallback search for pages whose SITE root is nested inside
 * the selected folder.
 *
 * Web pages reference absolute URLs relative to their site root. When a
 * downloaded site is placed inside the selected folder (e.g. a mirror or
 * wrapper directory, so the site root is one or more levels below the
 * selected root), a requested path such as `b/<build>/title/logo.png` does
 * not exist directly under the selected root even though the file exists
 * under the nested site root. This searcher locates a document whose path
 * relative to the store root ENDS WITH the full requested path.
 *
 * The search is deliberately conservative:
 * - the match must be the FULL requested path (all segments), not a prefix;
 * - the match must be unique - two or more candidates mean ambiguity and
 *   the result is null (the server never guesses);
 * - only directories inside the store root are traversed, so path traversal
 *   is impossible;
 * - the search is bounded (depth and directory count) and results are cached
 *   per requested path, and the first successful match also records a site
 *   root hint so later requests resolve with a single lookup.
 */
internal class PathSuffixSearcher(private val store: DocumentStore) {

    private val cache = HashMap<String, DocumentEntry?>()
    private val cacheLock = Any()

    @Volatile
    private var siteRootHint: String? = null

    fun find(relativePath: String, maxDepth: Int = 8, maxDirectories: Int = 256): DocumentEntry? {
        // Fast path: a site root discovered by an earlier successful search.
        siteRootHint?.let { hint ->
            val candidate = store.resolve(if (hint.isEmpty()) relativePath else "$hint/$relativePath")
            if (candidate != null) return candidate
        }
        synchronized(cacheLock) {
            if (cache.containsKey(relativePath)) return cache[relativePath]
        }
        // Anchor fast path: for multi-segment paths the first segment is a
        // directory. Finding that directory near the top and resolving the
        // rest directly inside it costs a handful of lookups instead of
        // scanning a potentially huge tree (the first request on a big
        // selected folder otherwise took ~16 s of SAF directory listing).
        // When anchors exist but the rest of the path does not resolve inside
        // any of them, the miss is authoritative (skip the full search) so
        // that 404s and the game io worker's per-batch cache probes stay cheap.
        val segments = relativePath.split('/').filter { it.isNotEmpty() }
        val anchorResult = if (segments.size > 1) findByAnchor(segments, maxDepth, maxDirectories) else null
        val found = when {
            anchorResult == null -> search(relativePath, maxDepth, maxDirectories)
            anchorResult.first != null -> anchorResult.first
            anchorResult.second -> null // anchors exist, the path does not: authoritative miss
            else -> search(relativePath, maxDepth, maxDirectories) // no anchors anywhere: full search
        }
        synchronized(cacheLock) {
            if (cache.size >= MAX_CACHE_ENTRIES) cache.clear()
            cache[relativePath] = found
        }
        return found
    }

    /**
     * Breadth-first search for directories NAMED like the first segment of
     * the requested path, level by level, stopping at the shallowest depth
     * that has any. For each anchor at that depth the remaining path is
     * resolved directly (one lookup). The match must still be unique across
     * all anchors at that depth.
     */
    private fun findByAnchor(
        segments: List<String>,
        maxDepth: Int,
        maxDirectories: Int
    ): Pair<DocumentEntry?, Boolean> {
        val firstSegment = segments[0]
        val rest = segments.drop(1).joinToString("/")
        val requested = segments.joinToString("/")
        var visited = 0
        var depth = 0
        val level = ArrayDeque<String>()
        level.add("")
        while (level.isNotEmpty() && visited < maxDirectories) {
            val anchors = mutableListOf<String>()
            val nextLevel = ArrayDeque<String>()
            while (level.isNotEmpty()) {
                val dir = level.removeFirst()
                if (depth > maxDepth) continue
                val dirEntry = store.resolve(dir) ?: continue
                if (!dirEntry.isDirectory) continue
                visited++
                for (child in store.listChildren(dirEntry)) {
                    if (!child.isDirectory) continue
                    val childPath = if (dir.isEmpty()) child.name else "$dir/${child.name}"
                    if (child.name == firstSegment) anchors.add(childPath)
                    if (nextLevel.size < maxDirectories * 4) nextLevel.add(childPath)
                }
            }
            if (anchors.isNotEmpty()) {
                // Shallowest depth with an anchor: resolve the rest inside each.
                var foundPath: String? = null
                var foundEntry: DocumentEntry? = null
                for (anchor in anchors) {
                    val candidate = store.resolve("$anchor/$rest")
                    if (candidate != null) {
                        if (foundEntry != null) return null to true // ambiguous: never guess
                        foundPath = "$anchor/$rest"
                        foundEntry = candidate
                    }
                }
                if (foundEntry != null) {
                    // foundPath ends with the full requested path; the site root
                    // is what precedes it.
                    siteRootHint = foundPath!!.removeSuffix(requested).removeSuffix("/")
                    return foundEntry to true
                }
                return null to true // anchors exist but the rest does not resolve inside
            }
            level.addAll(nextLevel)
            depth++
        }
        return null to false // no anchors anywhere within the bounds
    }

    private fun search(relativePath: String, maxDepth: Int, maxDirectories: Int): DocumentEntry? {
        var foundPath: String? = null
        var foundEntry: DocumentEntry? = null
        var visited = 0
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add("" to 0)
        while (queue.isNotEmpty() && visited < maxDirectories) {
            val (dir, depth) = queue.removeFirst()
            if (depth > maxDepth) continue
            val dirEntry = store.resolve(dir) ?: continue
            if (!dirEntry.isDirectory) continue
            visited++
            for (child in store.listChildren(dirEntry)) {
                val childPath = if (dir.isEmpty()) child.name else "$dir/${child.name}"
                if (childPath == relativePath || childPath.endsWith("/$relativePath")) {
                    if (foundEntry != null) return null // ambiguous: never guess
                    foundPath = childPath
                    foundEntry = child
                }
                if (child.isDirectory && queue.size < maxDirectories * 4) {
                    queue.add(childPath to depth + 1)
                }
            }
        }
        if (foundEntry != null && foundPath != null) {
            // Remember the nested site root (matched path minus the suffix).
            val hint = foundPath.removeSuffix(relativePath).removeSuffix("/")
            if (hint.isNotEmpty()) siteRootHint = hint
        }
        return foundEntry
    }

    companion object {
        private const val MAX_CACHE_ENTRIES = 64
    }
}

/**
 * Serves a plain directory from the local filesystem. Used for `file://`
 * roots and by the unit tests. Contains its own canonical-path confinement
 * check as defense in depth on top of [HttpPaths.normalize].
 */
class FileDocumentStore(private val rootDir: File) : DocumentStore {

    private val canonicalRoot: String = try {
        rootDir.canonicalFile.absolutePath
    } catch (e: Exception) {
        rootDir.absolutePath
    }

    override val rootDisplayName: String = rootDir.name.ifEmpty { rootDir.absolutePath }

    override fun resolve(relativePath: String): DocumentEntry? {
        if (!rootDir.isDirectory) return null
        val candidate = File(rootDir, relativePath.ifEmpty { "." })
        val canonical = try {
            candidate.canonicalFile
        } catch (e: Exception) {
            return null
        }
        // Confinement: the resolved file must stay inside the root directory.
        if (canonical.absolutePath != canonicalRoot &&
            !canonical.absolutePath.startsWith(canonicalRoot + File.separator)
        ) {
            return null
        }
        if (!canonical.exists()) return null
        return DocumentEntry(
            id = canonical.absolutePath,
            name = if (relativePath.isEmpty()) rootDisplayName else canonical.name,
            isDirectory = canonical.isDirectory,
            size = if (canonical.isFile) canonical.length() else -1L,
            mimeType = null
        )
    }

    override fun openInput(entry: DocumentEntry): InputStream? {
        return try {
            val file = File(entry.id)
            if (file.isFile && file.canonicalFile.absolutePath.startsWith(canonicalRoot)) {
                FileInputStream(file)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun listChildren(directory: DocumentEntry): List<DocumentEntry> {
        if (!directory.isDirectory) return emptyList()
        val dir = File(directory.id)
        if (!dir.isDirectory) return emptyList()
        if (dir.canonicalFile.absolutePath != canonicalRoot &&
            !dir.canonicalFile.absolutePath.startsWith(canonicalRoot + File.separator)
        ) {
            return emptyList()
        }
        return dir.listFiles()?.map { child ->
            DocumentEntry(
                id = child.canonicalFile.absolutePath,
                name = child.name,
                isDirectory = child.isDirectory,
                size = if (child.isFile) child.length() else -1L,
                mimeType = null
            )
        }?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
    }
}
