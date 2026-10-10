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
