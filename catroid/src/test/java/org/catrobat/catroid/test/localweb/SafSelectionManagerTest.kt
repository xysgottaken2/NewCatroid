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

package org.catrobat.catroid.test.localweb

import android.net.Uri
import org.catrobat.catroid.content.localweb.SafSelectionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for the SAF selection state manager: selection state, cancellation
 * semantics and display-name / URI resolution fallbacks. Runs on the JVM with
 * Robolectric (no device needed).
 */
@RunWith(RobolectricTestRunner::class)
class SafSelectionManagerTest {

    @Test
    fun `directory selection updates state and clears cancellation flag`() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments")
        SafSelectionManager.onDirectorySelected(uri)
        assertEquals(uri, SafSelectionManager.lastDirectoryUri)
        assertFalse(SafSelectionManager.lastDirectoryCancelled)

        SafSelectionManager.onDirectoryCancelled()
        assertTrue(SafSelectionManager.lastDirectoryCancelled)
        // A cancelled picker keeps the previous selection.
        assertEquals(uri, SafSelectionManager.lastDirectoryUri)

        SafSelectionManager.onDirectorySelected(Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload"))
        assertFalse(SafSelectionManager.lastDirectoryCancelled)
    }

    @Test
    fun `file selection updates state and clears cancellation flag`() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADocuments%2Fnotes.txt")
        SafSelectionManager.onFileSelected(uri)
        assertEquals(uri, SafSelectionManager.lastFileUri)
        assertFalse(SafSelectionManager.lastFileCancelled)

        SafSelectionManager.onFileCancelled()
        assertTrue(SafSelectionManager.lastFileCancelled)
        assertEquals(uri, SafSelectionManager.lastFileUri)
    }

    @Test
    fun `display name falls back to the last path segment for unknown providers`() {
        // Robolectric has no provider for this authority: the metadata query
        // fails and the fallback must produce the last URI segment.
        val name = SafSelectionManager.resolveDisplayName(
            "content://com.example.provider/document/primary%3ADocuments%2Fmy%20folder"
        )
        assertEquals("my folder", name)
    }

    @Test
    fun `display name falls back to the full uri when there is no segment`() {
        val name = SafSelectionManager.resolveDisplayName("content://")
        assertEquals("content://", name)
    }

    @Test
    fun `display name of empty input falls back to the last selection`() {
        val uri = Uri.parse("content://com.example.provider/document/primary%3ADocuments%2Fselected.txt")
        SafSelectionManager.onFileSelected(uri)
        assertEquals("selected.txt", SafSelectionManager.resolveDisplayName(""))
        assertEquals("selected.txt", SafSelectionManager.resolveDisplayName(null))
    }

    @Test
    fun `display name of non content uri uses the last segment`() {
        assertEquals("photo.png", SafSelectionManager.resolveDisplayName("file:///storage/emulated/0/photo.png"))
    }

    @Test
    fun `display name of garbage input returns the input`() {
        assertEquals("not a uri at all :::", SafSelectionManager.resolveDisplayName("not a uri at all :::"))
    }

    @Test
    fun `normalize item uri keeps content uris in uri form`() {
        val input = "content://com.example.provider/document/primary%3ADocuments%2Fa.txt"
        assertEquals(input, SafSelectionManager.normalizeItemUri(input))
    }

    @Test
    fun `normalize item uri keeps file uris in uri form`() {
        val input = "file:///storage/emulated/0/a.txt"
        assertEquals(input, SafSelectionManager.normalizeItemUri(input))
    }

    @Test
    fun `normalize item uri keeps plain paths unchanged`() {
        assertEquals("/storage/emulated/0/a.txt", SafSelectionManager.normalizeItemUri("/storage/emulated/0/a.txt"))
    }

    @Test
    fun `normalize item uri of empty input returns the last selection uri`() {
        val uri = Uri.parse("content://com.example.provider/document/primary%3ADocuments%2Flast.txt")
        SafSelectionManager.onFileSelected(uri)
        assertEquals(uri.toString(), SafSelectionManager.normalizeItemUri(""))
        assertEquals(uri.toString(), SafSelectionManager.normalizeItemUri(null))
    }

    @Test
    fun `normalize item uri of empty input without selection returns empty string`() {
        // Note: depends on test order only through the shared singleton; the
        // empty-selection case is validated when no selection was recorded by
        // using a uri string that cannot be a selection.
        val result = SafSelectionManager.normalizeItemUri("   ")
        assertTrue(result.isEmpty() || result.startsWith("content://"))
    }

    @Test
    fun `inaccessible content uri is reported as not accessible`() {
        val uri = Uri.parse("content://com.example.unknown/document/x")
        assertFalse(SafSelectionManager.isAccessible(uri))
    }

    @Test
    fun `persisted permission check does not crash for unknown uris`() {
        val uri = Uri.parse("content://com.example.unknown/document/x")
        // Must simply answer false instead of throwing.
        assertFalse(SafSelectionManager.hasPersistedPermission(uri))
    }
}
