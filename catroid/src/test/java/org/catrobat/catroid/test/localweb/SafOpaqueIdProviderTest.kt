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
import org.catrobat.catroid.content.localweb.HttpServerEngine
import org.catrobat.catroid.content.localweb.SafDocumentStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.util.function.Supplier

/**
 * Contract tests for providers whose document ids are NOT path
 * concatenations ([FakeOpaqueIdDocumentsProvider]: child ids are opaque
 * tokens, `isChildDocument` knows the real parent chain). This is the
 * scenario from the external research (a real project serving SAF trees over
 * HTTP, verified on a Samsung device, states a path must be resolved with a
 * per-segment `DocumentsContract` walk, never by concatenating
 * `treeDocId + "/" + relativePath`).
 *
 * These tests prove the hosting resolution works for such providers at
 * arbitrary depth: the concatenation fast path cleanly fails (the
 * concatenated pseudo-id is not a descendant of the tree) and the
 * segment-by-segment walk - using each listing row's own document id and
 * taking the final entry from the listing row - resolves everything.
 */
@RunWith(RobolectricTestRunner::class)
class SafOpaqueIdProviderTest {

    private lateinit var provider: FakeOpaqueIdDocumentsProvider
    private lateinit var store: SafDocumentStore
    private var engine: HttpServerEngine? = null
    private val treeUri: Uri
        get() = Uri.parse(FakeOpaqueIdDocumentsProvider.TREE_URI)

    @Before
    fun setUp() {
        provider = FakeOpaqueIdDocumentsProvider()
        SafTestEnvironment.registerProvider(FakeOpaqueIdDocumentsProvider.AUTHORITY, provider)
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        for ((documentId, content) in FakeOpaqueIdDocumentsProvider.allFiles) {
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            shadowOf(resolver).registerInputStreamSupplier(uri, Supplier { ByteArrayInputStream(content) })
        }
        store = SafDocumentStore(RuntimeEnvironment.getApplication(), treeUri)
    }

    @After
    fun tearDown() {
        engine?.stop()
        engine = null
    }

    @Test
    fun `concatenated pseudo document ids are not descendants of the tree`() {
        // Documents the research finding: for this provider,
        // treeDocId + "/" + relativePath is NOT a valid document id.
        assertFalse(provider.isChildDocument(FakeOpaqueIdDocumentsProvider.TREE_DOC_ID, "cloud-tree-root/assets/app.js"))
        assertFalse(provider.isChildDocument(FakeOpaqueIdDocumentsProvider.TREE_DOC_ID, "cloud-tree-root/assets/nested/deep/config.json"))
        // ...while the provider's own opaque ids are genuine descendants.
        assertTrue(provider.isChildDocument(FakeOpaqueIdDocumentsProvider.TREE_DOC_ID, "doc-7"))
    }

    @Test
    fun `deeply nested file resolves through the segment walk despite opaque ids`() {
        val config = store.resolve("assets/nested/deep/config.json")
        assertNotNull(config)
        assertEquals("doc-7", config!!.id)
        assertEquals("config.json", config.name)
        assertFalse(config.isDirectory)
        assertEquals(FakeOpaqueIdDocumentsProvider.allFiles.first { it.first == "doc-7" }.second.size.toLong(), config.size)
    }

    @Test
    fun `nested branches resolve independently with opaque ids`() {
        val deep = store.resolve("assets/nested/deep/config.json")
        val data = store.resolve("data/config.json")
        assertNotNull(deep)
        assertNotNull(data)
        assertEquals("doc-7", deep!!.id)
        assertEquals("doc-9", data!!.id)
        assertEquals(
            "{\"name\":\"opaque-deep\"}",
            String(store.openInput(deep)!!.readBytes(), Charsets.UTF_8)
        )
    }

    @Test
    fun `directories at depth resolve with opaque ids`() {
        for (path in listOf("assets", "assets/nested", "assets/nested/deep", "data")) {
            val entry = store.resolve(path)
            assertNotNull("directory must resolve: $path", entry)
            assertTrue("must be a directory: $path", entry!!.isDirectory)
        }
    }

    @Test
    fun `listings include nested subdirectories at every level with opaque ids`() {
        val assets = store.listChildren(store.resolve("assets")!!)
        assertEquals(setOf("app.js", "nested"), assets.map { it.name }.toSet())

        val nested = store.listChildren(store.resolve("assets/nested")!!)
        assertEquals(setOf("module.js", "deep"), nested.map { it.name }.toSet())

        val deep = store.listChildren(store.resolve("assets/nested/deep")!!)
        assertEquals(listOf("config.json"), deep.map { it.name })
    }

    @Test
    fun `missing files resolve to null with opaque ids`() {
        assertNull(store.resolve("missing.js"))
        assertNull(store.resolve("assets/nested/deep/missing.json"))
    }

    @Test
    fun `end to end http serves a deeply nested file from an opaque id tree`() {
        val newEngine = HttpServerEngine(storeProvider = { store }, urlPrefixProvider = { "/" })
        val result = newEngine.start(0)
        engine = newEngine
        assertTrue("server should start", result is HttpServerEngine.Result.Success)
        val port = (result as HttpServerEngine.Result.Success).port

        val response = rawHttpRequest(port, "GET", "/assets/nested/deep/config.json")
        assertEquals(200, response.statusCode)
        assertEquals("{\"name\":\"opaque-deep\"}", String(response.body, Charsets.UTF_8))

        val listing = rawHttpRequest(port, "GET", "/assets/nested/")
        assertEquals(200, listing.statusCode)
        assertTrue(String(listing.body, Charsets.UTF_8).contains("module.js"))
        assertTrue(String(listing.body, Charsets.UTF_8).contains("deep/"))
    }
}
