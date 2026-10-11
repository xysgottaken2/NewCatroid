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
 * In-memory SAF documents provider that mimics the primary external storage
 * provider (`com.android.externalstorage.documents`) for a small browser-game
 * folder tree (root "game/Game", like a user picks with the directory picker).
 *
 * It extends the *real* [DocumentsProvider] base class, so the framework's
 * URI matching (document URIs, tree-scoped document URIs and their children
 * listings - and crucially NO pattern for a bare tree URI) and the
 * tree-descendant enforcement are the genuine AOSP code: querying a bare
 * tree URI throws `UnsupportedOperationException`, exactly like on a real
 * device. Tests that
 * exercise the SAF document resolution path therefore fail if production code
 * queries the bare tree URI instead of the tree root's document URI.
 */
class FakeGameDocumentsProvider : DocumentsProvider() {

    class Node(
        val id: String,
        val name: String,
        val isDirectory: Boolean,
        val content: ByteArray? = null,
        val children: List<Node> = emptyList()
    )

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<String>?): Cursor =
        MatrixCursor(projection ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val node = find(documentId) ?: throw FileNotFoundException("No document: $documentId")
        val columns = projection ?: DEFAULT_DOCUMENT_PROJECTION
        return MatrixCursor(columns).apply { addRow(rowFor(node, columns)) }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?
    ): Cursor {
        val node = find(parentDocumentId) ?: throw FileNotFoundException("No document: $parentDocumentId")
        if (!node.isDirectory) throw FileNotFoundException("Not a directory: $parentDocumentId")
        val columns = projection ?: DEFAULT_DOCUMENT_PROJECTION
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
        val file = File.createTempFile("fake-saf-doc", ".bin")
        file.deleteOnExit()
        file.writeBytes(node.content ?: ByteArray(0))
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getDocumentType(documentId: String): String? = find(documentId)?.let { mimeOf(it) }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId == parentDocumentId || documentId.startsWith("$parentDocumentId/")

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

    private fun mimeOf(node: Node): String {
        if (node.isDirectory) return DocumentsContract.Document.MIME_TYPE_DIR
        return when (node.name.substringAfterLast('.', "")) {
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js" -> "text/javascript"
            "png" -> "image/png"
            "wasm" -> "application/wasm"
            else -> "application/octet-stream"
        }
    }

    private fun rowFor(node: Node, columns: Array<String>): Array<Any?> =
        columns.map { column ->
            when (column) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> node.id
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> node.name
                DocumentsContract.Document.COLUMN_MIME_TYPE -> mimeOf(node)
                DocumentsContract.Document.COLUMN_SIZE ->
                    if (node.isDirectory) 0L else (node.content?.size?.toLong() ?: 0L)
                else -> null
            }
        }.toTypedArray()

    companion object {
        const val AUTHORITY = "com.android.externalstorage.documents"
        const val ROOT_ID = "primary:Documents/game/Game"
        const val TREE_URI = "content://$AUTHORITY/tree/primary%3ADocuments%2Fgame%2FGame"

        val INDEX_HTML = "<html><body>index</body></html>".toByteArray(StandardCharsets.UTF_8)
        val GAME_HTML = "<html><body>game entry</body></html>".toByteArray(StandardCharsets.UTF_8)
        val CSS = "body { color: red; }".toByteArray(StandardCharsets.UTF_8)
        val JS = "console.log('asset');".toByteArray(StandardCharsets.UTF_8)
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4)
        val BIN = ByteArray(300_000) { (it % 251).toByte() }
        val WASM = ByteArray(4_096) { (it % 253).toByte() }

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )

        private val root = Node(
            id = ROOT_ID,
            name = "Game",
            isDirectory = true,
            children = listOf(
                Node("$ROOT_ID/index.html", "index.html", false, INDEX_HTML),
                Node("$ROOT_ID/game.html", "game.html", false, GAME_HTML),
                Node("$ROOT_ID/style.css", "style.css", false, CSS),
                Node("$ROOT_ID/data.bin", "data.bin", false, BIN),
                Node(
                    "$ROOT_ID/assets", "assets", true,
                    children = listOf(Node("$ROOT_ID/assets/game.wasm", "game.wasm", false, WASM))
                ),
                Node(
                    "$ROOT_ID/nested", "nested", true,
                    children = listOf(
                        Node("$ROOT_ID/nested/asset.js", "asset.js", false, JS),
                        Node("$ROOT_ID/nested/texture.png", "texture.png", false, PNG)
                    )
                )
            )
        )

        /** All file documents (document id to content), used to register stream suppliers in tests. */
        val allFiles: List<Pair<String, ByteArray>> = listOf(
            "$ROOT_ID/index.html" to INDEX_HTML,
            "$ROOT_ID/game.html" to GAME_HTML,
            "$ROOT_ID/style.css" to CSS,
            "$ROOT_ID/data.bin" to BIN,
            "$ROOT_ID/assets/game.wasm" to WASM,
            "$ROOT_ID/nested/asset.js" to JS,
            "$ROOT_ID/nested/texture.png" to PNG
        )
    }
}
