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
import java.util.regex.Pattern

/**
 * A SAF documents provider that faithfully models the *real*
 * `ExternalStorageProvider` (AOSP android-14, `com.android.externalstorage`)
 * on the JVM, backed by a real directory tree on disk:
 *
 * - Document ids are `rootId + ':' + path-under-storage-root`, exactly like
 *   `ExternalStorageProvider.getDocIdForFile` (e.g. `primary:Game/assets`).
 * - `queryDocument` resolves the id to a canonical `File` (like `buildFile`)
 *   and fails with `FileNotFoundException` when it does not exist (like
 *   `includeFile`), echoing the queried document id and reporting the real
 *   file name/length (like `FileSystemProvider.includeFile`).
 * - `queryChildDocuments` lists the parent directory (`File.listFiles`),
 *   hiding `Android/data|obb|sandbox` paths with the same pattern the real
 *   provider uses for scoped-storage privacy.
 * - `isChildDocument` checks canonical path containment, like
 *   `FileSystemProvider.isChildDocument` + `FileUtils.contains`.
 * - `openDocument` opens the real file with `ParcelFileDescriptor.open`.
 *
 * It extends the real [DocumentsProvider], so the genuine AOSP URI matcher
 * and tree-descendant enforcement run. This is the closest possible
 * reproduction of device behavior without a physical device: resolution goes
 * through real files, real canonicalization and real directory listings.
 */
class FakeFileSystemDocumentsProvider(
    private val storageRoot: File,
    private val treeDocId: String
) : DocumentsProvider() {

    private val canonicalRoot: File = storageRoot.canonicalFile
    private val hiddenPathPattern: Pattern = Pattern.compile(
        "(?i)^" + Pattern.quote(canonicalRoot.absolutePath) +
            "/(?:[0-9]+/)?Android/(?:data|obb|sandbox)$"
    )

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<String>?): Cursor =
        MatrixCursor(projection ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val file = fileFor(documentId)
        if (!file.exists()) throw FileNotFoundException("Missing file for $documentId at $file")
        val columns = projection ?: DEFAULT_PROJECTION
        return MatrixCursor(columns).apply { addRow(rowFor(documentId, file, columns)) }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?
    ): Cursor {
        val parent = fileFor(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException("Not a directory: $parentDocumentId")
        val columns = projection ?: DEFAULT_PROJECTION
        return MatrixCursor(columns).apply {
            parent.listFiles()?.sortedBy { it.name }?.forEach { child ->
                if (!hiddenPathPattern.matcher(child.canonicalFile.absolutePath).matches()) {
                    addRow(rowFor(docIdFor(child), child, columns))
                }
            }
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = fileFor(documentId)
        if (!file.exists()) throw FileNotFoundException("Missing file for $documentId")
        if (file.isDirectory) throw FileNotFoundException("Is a directory: $documentId")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getDocumentType(documentId: String): String? {
        val file = fileFor(documentId)
        if (!file.exists()) return null
        return if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else mimeOf(file.name)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = fileFor(parentDocumentId).canonicalFile
        val doc = fileFor(documentId).canonicalFile
        if (doc == parent) return true
        return doc.absolutePath.startsWith(parent.absolutePath + File.separator)
    }

    /** Document id of [file], exactly like `getDocIdForFile`: rootId + ':' + path under the root. */
    private fun docIdFor(file: File): String {
        val canonical = file.canonicalFile
        val relative = canonical.absolutePath.removePrefix(canonicalRoot.absolutePath).removePrefix("/")
        return "$ROOT_TAG:$relative"
    }

    /** Resolves a document id to a canonical file, like `buildFile` (no existence check). */
    private fun fileFor(documentId: String): File {
        val split = documentId.indexOf(':')
        if (split <= 0) throw FileNotFoundException("Invalid document id: $documentId")
        val relative = documentId.substring(split + 1)
        return File(canonicalRoot, relative).canonicalFile
    }

    private fun rowFor(documentId: String, file: File, columns: Array<String>): Array<Any?> =
        columns.map { column ->
            when (column) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> documentId
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> file.name
                DocumentsContract.Document.COLUMN_MIME_TYPE ->
                    if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else mimeOf(file.name)
                DocumentsContract.Document.COLUMN_SIZE -> file.length()
                else -> null
            }
        }.toTypedArray()

    private fun mimeOf(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js" -> "text/javascript"
            "json" -> "application/json"
            "png" -> "image/png"
            "wasm" -> "application/wasm"
            else -> "application/octet-stream"
        }

    companion object {
        const val AUTHORITY = "com.android.externalstorage.documents"
        const val ROOT_TAG = "primary"

        /** Tree URI of the selected folder (e.g. `Game`) under the storage root. */
        fun treeUriFor(selectedFolderName: String): String =
            "content://$AUTHORITY/tree/${ROOT_TAG}%3A$selectedFolderName"

        private val DEFAULT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
    }
}
