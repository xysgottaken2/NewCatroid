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

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import java.io.InputStream

/**
 * Serves the contents of a Storage Access Framework document tree
 * (a directory the user picked with `ACTION_OPEN_DOCUMENT_TREE`) over HTTP.
 *
 * All access goes through [ContentResolver] and [DocumentsContract]; no
 * assumption is made that the document URI maps to a normal filesystem path.
 * Every resolved document is verified to stay inside the selected tree, so
 * requests can never escape the user-approved root.
 */
class SafDocumentStore(
    private val context: Context,
    private val treeUri: Uri
) : DocumentStore {

    private val resolver: ContentResolver = context.contentResolver
    private val rootDocumentId: String = DocumentsContract.getTreeDocumentId(treeUri)

    override val rootDisplayName: String by lazy {
        querySingle(treeUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) { cursor ->
            cursor.getString(0)
        }?.takeIf { it.isNotBlank() } ?: FALLBACK_ROOT_NAME
    }

    /**
     * Resolves a normalized relative path inside the tree.
     *
     * Two strategies are used:
     *  1. Fast path: most providers (including the primary external storage
     *     provider) build child document ids by appending the relative path to
     *     the tree document id. This is tried first.
     *  2. Fallback: walk the tree level by level matching display names, which
     *     works for providers with opaque document ids.
     *
     * The result is verified to be the tree root or a descendant of it.
     */
    override fun resolve(relativePath: String): DocumentEntry? {
        if (relativePath.isEmpty()) {
            return queryEntry(treeUri, rootDocumentId, FALLBACK_ROOT_NAME)?.copy(isDirectory = true)
        }

        // Fast path: treeDocumentId + "/" + relativePath
        val directId = rootDocumentId + "/" + relativePath
        val directUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, directId)
        queryEntry(directUri, directId, relativePath.substringAfterLast('/'))?.let { entry ->
            if (isInsideTree(entry.id)) return entry
        }

        // Fallback: walk segment by segment using display names.
        var currentUri = treeUri
        var currentId = rootDocumentId
        for (segment in relativePath.split('/')) {
            if (segment.isEmpty()) continue
            val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, currentId)
            val match = queryChildren(childUri) { cursor ->
                val name = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                val id = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
                name to id
            }.firstOrNull { (name, _) -> name == segment } ?: return null
            currentId = match.second
            if (!isInsideTree(currentId)) return null
            currentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, currentId)
        }
        return queryEntry(currentUri, currentId, relativePath.substringAfterLast('/'))
    }

    override fun openInput(entry: DocumentEntry): InputStream? {
        if (entry.isDirectory) return null
        if (!isInsideTree(entry.id)) return null
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.id)
        return try {
            resolver.openInputStream(uri)
        } catch (e: Exception) {
            null
        }
    }

    override fun listChildren(directory: DocumentEntry): List<DocumentEntry> {
        if (!directory.isDirectory || !isInsideTree(directory.id)) return emptyList()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, directory.id)
        return queryChildren(childrenUri) { cursor ->
            val id = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
            val name = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
            val mime = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE))
            val size = cursor.getLong(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE))
            DocumentEntry(
                id = id,
                name = name ?: id.substringAfterLast('/'),
                isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                size = if (size >= 0) size else -1L,
                mimeType = mime
            )
        }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    /** True when [documentId] is the tree root itself or a descendant of it. */
    private fun isInsideTree(documentId: String): Boolean {
        return documentId == rootDocumentId || documentId.startsWith("$rootDocumentId/")
    }

    private fun queryEntry(uri: Uri, expectedId: String, fallbackName: String): DocumentEntry? {
        return querySingle(
            uri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            )
        ) { cursor ->
            val id = cursor.getString(0)
            val name = cursor.getString(1)
            val mime = cursor.getString(2)
            val size = cursor.getLong(3)
            DocumentEntry(
                id = id ?: expectedId,
                name = name?.takeIf { it.isNotBlank() } ?: fallbackName,
                isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                size = if (size >= 0) size else -1L,
                mimeType = mime
            )
        }
    }

    private fun <T> querySingle(uri: Uri, projection: Array<String>, map: (Cursor) -> T): T? {
        return try {
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) map(cursor) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun <T> queryChildren(childrenUri: Uri, map: (Cursor) -> T): List<T> {
        val result = ArrayList<T>()
        try {
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE
                ),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    result.add(map(cursor))
                }
            }
        } catch (e: Exception) {
            // Provider does not support listing or access was revoked: treat as empty.
        }
        return result
    }

    companion object {
        const val FALLBACK_ROOT_NAME = "root"
    }
}
