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

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import org.catrobat.catroid.CatroidApplication

/**
 * Tracks the results of the Storage Access Framework pickers launched by the
 * "Select Directory" and "Select File" blocks.
 *
 * The textual value stored in a NewCatroid variable is only a *reference* to
 * the selection. Persisted URI permissions are kept by Android independently
 * (see [takePersistablePermission]); a string alone is never treated as proof
 * that access is still granted.
 *
 * Cancellation semantics: when the user dismisses a picker, the corresponding
 * `*Cancelled` flag is set and any previously stored selection is kept - a
 * cancelled picker never overwrites a valid earlier selection.
 */
object SafSelectionManager {

    private const val TAG = "SafSelectionManager"

    private val lock = Any()

    @Volatile
    var lastDirectoryUri: Uri? = null
        private set

    @Volatile
    var lastFileUri: Uri? = null
        private set

    @Volatile
    var lastDirectoryCancelled: Boolean = false
        private set

    @Volatile
    var lastFileCancelled: Boolean = false
        private set

    /** Records a successful directory selection and persists its URI permission. */
    fun onDirectorySelected(uri: Uri) {
        takePersistablePermission(uri)
        synchronized(lock) {
            lastDirectoryUri = uri
            lastDirectoryCancelled = false
        }
        Log.i(TAG, "Directory selected: $uri")
    }

    /** Records that the directory picker was dismissed without a selection. */
    fun onDirectoryCancelled() {
        synchronized(lock) {
            lastDirectoryCancelled = true
        }
        Log.i(TAG, "Directory selection cancelled")
    }

    /** Records a successful file selection and persists its URI permission. */
    fun onFileSelected(uri: Uri) {
        takePersistablePermission(uri)
        synchronized(lock) {
            lastFileUri = uri
            lastFileCancelled = false
        }
        Log.i(TAG, "File selected: $uri")
    }

    /** Records that the file picker was dismissed without a selection. */
    fun onFileCancelled() {
        synchronized(lock) {
            lastFileCancelled = true
        }
        Log.i(TAG, "File selection cancelled")
    }

    /**
     * Takes a persistable URI permission for [uri] so the selection stays
     * usable across reboots and after the picking activity is gone. Failures
     * (provider does not support persistence, SecurityException, ...) are
     * logged and swallowed - the selection remains valid for this session.
     */
    fun takePersistablePermission(uri: Uri) {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            CatroidApplication.getAppContext().contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to persist URI permission for $uri: ${e.message}")
        }
    }

    /** True when Android still holds a persisted read permission for [uri]. */
    fun hasPersistedPermission(uri: Uri): Boolean {
        return try {
            CatroidApplication.getAppContext().contentResolver
                .persistedUriPermissions
                .any { it.uri == uri && it.isReadPermission }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Resolves the best available human readable display name for a selected
     * file or directory URI. Uses the content provider's metadata
     * ([OpenableColumns.DISPLAY_NAME]) when available and falls back to the
     * last URI path segment, then to the full URI string.
     */
    fun resolveDisplayName(uri: Uri): String {
        if (uri.scheme == "content") {
            try {
                CatroidApplication.getAppContext().contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst() && !cursor.isNull(0)) {
                            val name = cursor.getString(0)
                            if (!name.isNullOrBlank()) return name
                        }
                    }
            } catch (e: Exception) {
                Log.w(TAG, "Unable to query display name for $uri: ${e.message}")
            }
        }
        return fallbackDisplayName(uri)
    }

    /**
     * String overload used by the formula reporter. An empty [uri] falls back
     * to the most recent file or directory selection.
     */
    fun resolveDisplayName(uri: String?): String {
        val trimmed = uri?.trim().orEmpty()
        if (trimmed.isNotEmpty()) {
            return try {
                resolveDisplayName(Uri.parse(trimmed))
            } catch (e: Exception) {
                trimmed
            }
        }
        val last = lastFileUri ?: lastDirectoryUri
        return if (last != null) resolveDisplayName(last) else ""
    }

    /**
     * Normalizes a selected item reference to a stable URI string that can be
     * stored in variables and passed between blocks. Content URIs and file
     * URIs are returned in URI form; plain textual paths are returned
     * unchanged. The result is never misrepresented as an absolute filesystem
     * path: only `file://` URIs and plain paths denote filesystem locations.
     */
    fun normalizeItemUri(uri: String?): String {
        val trimmed = uri?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return (lastFileUri ?: lastDirectoryUri)?.toString() ?: ""
        }
        return try {
            Uri.parse(trimmed).toString()
        } catch (e: Exception) {
            trimmed
        }
    }

    /**
     * Checks that [uri] is currently readable through the content resolver.
     * Used to detect revoked permissions or inaccessible providers before a
     * selection is committed to a variable.
     */
    fun isAccessible(uri: Uri): Boolean {
        if (uri.scheme != "content") return true
        return try {
            CatroidApplication.getAppContext().contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { true } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "URI not accessible: $uri (${e.message})")
            false
        }
    }

    /** Fallback display name: last path segment, or the full URI string. */
    private fun fallbackDisplayName(uri: Uri): String {
        val lastSegment = uri.lastPathSegment
        if (!lastSegment.isNullOrBlank()) return lastSegment
        return uri.toString()
    }
}
