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
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.catrobat.catroid.test.localweb

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.nio.charset.StandardCharsets

/**
 * A SAF documents provider whose child document ids are OPAQUE tokens
 * (`doc-1`, `doc-2`, ...), NOT path concatenations - modeling providers where
 * "document ids are the provider's own business" (cloud providers, and the
 * scenario described in the external research: a path must be resolved with a
 * per-segment `DocumentsContract` walk, never by concatenating
 * `treeDocId + "/" + relativePath`).
 *
 * - `isChildDocument` knows the real parent chain of its own ids, so
 *   `enforceTree` passes for genuine descendants (including deeply nested
 *   ones) and fails for concatenated pseudo-ids.
 * - `queryDocument` resolves only the provider's own opaque ids.
 * - `queryChildDocuments` lists a node's children with their opaque ids.
 *
 * It extends the real [DocumentsProvider], so the genuine AOSP URI matcher
 * and tree-descendant enforcement run.
 */
class FakeOpaqueIdDocumentsProvider : DocumentsProvider() {

    class Node(
        val id: String,
        val name: String,
        val isDirectory: Boolean,
        val content: ByteArray? = null,
        val children: List<Node> = emptyList(),
        val parentId: String? = null
    )

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<String>?): Cursor =
        MatrixCursor(projection ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val node = find(documentId) ?: throw FileNotFoundException("No document: $documentId")
        val columns = projection ?: DEFAULT_PROJECTION
        return MatrixCursor(columns).apply { addRow(rowFor(node, columns)) }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?
    ): Cursor {
        val node = find(parentDocumentId) ?: throw FileNotFoundException("No document: $parentDocumentId")
        if (!node.isDirectory) throw FileNotFoundException("Not a directory: $parentDocumentId")
        val columns = projection ?: DEFAULT_PROJECTION
        return MatrixCursor(columns).apply {
            node.children.forEach { child -> addRow(rowFor(child, columns)) }
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val node = find(documentId) ?: throw FileNotFoundException("No document: $documentId")
        if (node.isDirectory) throw FileNotFoundException("Is a directory: $documentId")
        val file = File.createTempFile("fake-opaque-doc", ".bin")
        file.deleteOnExit()
        file.writeBytes(node.content ?: ByteArray(0))
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getDocumentType(documentId: String): String? =
        find(documentId)?.let { if (it.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream" }

    /**
     * Real parent-chain semantics (like a provider that knows its own tree):
     * true only when [documentId] is [parentDocumentId] or one of its
     * descendants. Concatenated pseudo-ids are NOT descendants.
     */
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        var current: Node? = find(documentId) ?: return false
        while (current != null) {
            if (current.id == parentDocumentId) return true
            current = current.parentId?.let { find(it) }
        }
        return false
    }

    private fun find(documentId: String): Node? {
        fun walk(node: Node): Node? {
            if (node.id == documentId) return node
            for (child in node.children) {
                walk(child)?.let { return it }
            }
            return null
        }
        return walk(root)
    }

    private fun rowFor(node: Node, columns: Array<String>): Array<Any?> =
        columns.map { column ->
            when (column) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> node.id
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> node.name
                DocumentsContract.Document.COLUMN_MIME_TYPE ->
                    if (node.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
                DocumentsContract.Document.COLUMN_SIZE ->
                    if (node.isDirectory) 0L else (node.content?.size?.toLong() ?: 0L)
                else -> null
            }
        }.toTypedArray()

    companion object {
        const val AUTHORITY = "org.catrobat.catroid.test.opaque.documents"
        const val TREE_DOC_ID = "cloud-tree-root"
        const val TREE_URI = "content://$AUTHORITY/tree/$TREE_DOC_ID"

        private val DEFAULT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )

        private fun node(id: String, name: String, isDirectory: Boolean, content: String? = null, children: List<Node> = emptyList(), parentId: String? = null): Node =
            Node(id, name, isDirectory, content?.toByteArray(StandardCharsets.UTF_8), children, parentId)

        /**
         * The same deep game tree as the in-memory fixture, but with opaque
         * document ids:
         *
         * ```
         * Game/ (cloud-tree-root)
         * |- index.html (doc-1)
         * |- assets/ (doc-2)
         * |  |- app.js (doc-3)
         * |  `- nested/ (doc-4)
         * |     |- module.js (doc-5)
         * |     `- deep/ (doc-6)
         * |        `- config.json (doc-7)
         * `- data/ (doc-8)
         *    `- config.json (doc-9)
         * ```
         */
        val root: Node by lazy {
            val deep = node("doc-6", "deep", true, children = listOf(
                node("doc-7", "config.json", false, "{\"name\":\"opaque-deep\"}", parentId = "doc-6")
            ), parentId = "doc-4")
            val nested = node("doc-4", "nested", true, children = listOf(
                node("doc-5", "module.js", false, "console.log('opaque-module');", parentId = "doc-4"),
                deep
            ), parentId = "doc-2")
            val assets = node("doc-2", "assets", true, children = listOf(
                node("doc-3", "app.js", false, "console.log('opaque-app');", parentId = "doc-2"),
                nested
            ), parentId = TREE_DOC_ID)
            val data = node("doc-8", "data", true, children = listOf(
                node("doc-9", "config.json", false, "{\"name\":\"opaque-data\"}", parentId = "doc-8")
            ), parentId = TREE_DOC_ID)
            node(TREE_DOC_ID, "Game", true, children = listOf(
                node("doc-1", "index.html", false, "<html><body>opaque index</body></html>", parentId = TREE_DOC_ID),
                assets,
                data
            ))
        }

        /** All file documents of the opaque tree (document id to content). */
        val allFiles: List<Pair<String, ByteArray>> by lazy {
            fun collect(node: Node, acc: MutableList<Pair<String, ByteArray>>) {
                if (!node.isDirectory && node.content != null) acc.add(node.id to node.content)
                node.children.forEach { collect(it, acc) }
            }
            val acc = mutableListOf<Pair<String, ByteArray>>()
            collect(root, acc)
            acc
        }
    }
}
