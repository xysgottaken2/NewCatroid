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

import android.net.Uri
import android.provider.DocumentsContract
import org.catrobat.catroid.content.localweb.SafDocumentStore
import org.catrobat.catroid.content.localweb.SafSelectionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayInputStream
import java.util.function.Supplier

/**
 * Regression tests for SAF document resolution: hosting a folder selected
 * with the directory picker must resolve the tree root, "index.html",
 * non-"index.html" entry files, nested assets and directory listings through
 * the provider - never by querying the bare tree URI (providers reject it).
 *
 * Runs on the JVM with Robolectric against [FakeGameDocumentsProvider], which
 * extends the real `DocumentsProvider`, so the framework URI matching is the
 * genuine AOSP code.
 */
@RunWith(RobolectricTestRunner::class)
class SafDocumentStoreTest {

    private lateinit var treeUri: Uri
    private lateinit var store: SafDocumentStore

    @Before
    fun setUp() {
        ShadowContentResolver.registerProviderInternal(
            FakeGameDocumentsProvider.AUTHORITY,
            FakeGameDocumentsProvider()
        )
        registerDocumentStreams()
        treeUri = Uri.parse(FakeGameDocumentsProvider.TREE_URI)
        store = SafDocumentStore(RuntimeEnvironment.getApplication(), treeUri)
    }

    private fun registerDocumentStreams() {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        for ((documentId, content) in FakeGameDocumentsProvider.allFiles) {
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            shadowOf(resolver).registerInputStreamSupplier(uri, Supplier { ByteArrayInputStream(content) })
        }
    }

    @Test
    fun `root resolves to the selected tree directory`() {
        val root = store.resolve("")
        assertNotNull("the tree root must resolve", root)
        assertTrue(root!!.isDirectory)
        assertEquals(FakeGameDocumentsProvider.ROOT_ID, root.id)
        assertEquals("Game", root.name)
    }

    @Test
    fun `index html resolves through the saf tree`() {
        val entry = store.resolve("index.html")
        assertNotNull(entry)
        assertFalse(entry!!.isDirectory)
        assertEquals("index.html", entry.name)
        assertEquals(FakeGameDocumentsProvider.INDEX_HTML.size.toLong(), entry.size)
    }

    @Test
    fun `non index entry file resolves through the saf tree`() {
        val entry = store.resolve("game.html")
        assertNotNull("entry files other than index.html must resolve", entry)
        assertEquals("game.html", entry!!.name)
        assertEquals(FakeGameDocumentsProvider.GAME_HTML.size.toLong(), entry.size)
    }

    @Test
    fun `nested asset resolves through the saf tree`() {
        val entry = store.resolve("nested/asset.js")
        assertNotNull(entry)
        assertEquals("asset.js", entry!!.name)
        assertEquals(FakeGameDocumentsProvider.JS.size.toLong(), entry.size)
    }

    @Test
    fun `wasm asset resolves through the saf tree`() {
        val entry = store.resolve("assets/game.wasm")
        assertNotNull(entry)
        assertEquals("game.wasm", entry!!.name)
        assertEquals(FakeGameDocumentsProvider.WASM.size.toLong(), entry.size)
    }

    @Test
    fun `missing document resolves to null`() {
        assertNull(store.resolve("missing.txt"))
        assertNull(store.resolve("nested/missing.js"))
    }

    @Test
    fun `open input streams the document content`() {
        val entry = store.resolve("index.html") ?: fail("index.html must resolve")
        val streamed = store.openInput(entry)?.readBytes()
        assertNotNull(streamed)
        assertEquals(
            String(FakeGameDocumentsProvider.INDEX_HTML, Charsets.UTF_8),
            String(streamed!!, Charsets.UTF_8)
        )
    }

    @Test
    fun `list children exposes the folder hierarchy`() {
        val root = store.resolve("") ?: fail("root must resolve")
        val children = store.listChildren(root)
        val names = children.map { it.name }
        assertTrue(names.contains("index.html"))
        assertTrue(names.contains("game.html"))
        assertTrue(names.contains("style.css"))
        assertTrue(names.contains("data.bin"))
        assertTrue(names.contains("nested"))
        assertTrue(names.contains("assets"))
        assertTrue(children.first { it.name == "nested" }.isDirectory)

        val nested = store.resolve("nested") ?: fail("nested must resolve")
        val nestedNames = store.listChildren(nested).map { it.name }
        assertEquals(listOf("asset.js", "texture.png"), nestedNames)
    }

    @Test
    fun `root display name comes from the provider`() {
        assertEquals("Game", store.rootDisplayName)
    }

    @Test
    fun `documents outside the selected tree are not resolved`() {
        // Defense in depth: even a crafted relative path must not escape the tree.
        assertNull(store.resolve("nested/../../secret.txt"))
        assertNull(store.resolve("../other-folder/index.html"))
    }

    @Test
    fun `a selected tree uri is reported accessible`() {
        // Regression for the "Select Game Folder" block: the accessibility
        // check must query the tree root's document URI, not the bare tree URI.
        assertTrue(SafSelectionManager.isAccessible(treeUri))
    }

    @Test
    fun `display name of a tree selection comes from the provider`() {
        assertEquals("Game", SafSelectionManager.resolveDisplayName(FakeGameDocumentsProvider.TREE_URI))
    }

    @Test
    fun `a plain file document uri is still reported accessible`() {
        val fileUri = Uri.parse(
            "content://${FakeGameDocumentsProvider.AUTHORITY}/document/primary%3ADocuments%2Fgame%2FGame%2Findex.html"
        )
        assertTrue(SafSelectionManager.isAccessible(fileUri))
    }

    @Test
    fun `constructing a store from a non tree uri fails with a clear error`() {
        val fileUri = Uri.parse(
            "content://${FakeGameDocumentsProvider.AUTHORITY}/document/primary%3ADocuments%2Fgame%2FGame%2Findex.html"
        )
        try {
            SafDocumentStore(RuntimeEnvironment.getApplication(), fileUri)
            fail("a file document URI is not a servable directory tree")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("tree"))
        }
    }
}
