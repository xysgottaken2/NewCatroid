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

import org.catrobat.catroid.content.localweb.SafDocumentStore
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

/**
 * Regression tests for arbitrarily deep nested directories inside a
 * SAF-selected tree (the structure reported from a physical device, where the
 * exposed directory appeared to stop at the first level of subdirectories).
 *
 * The fixture (`FakeGameDocumentsProvider.deepRoot`, registered via
 * [SafTestEnvironment.registerDeep]) models:
 *
 * ```
 * Game/
 * |- index.html
 * |- root.js
 * |- assets/
 * |  |- app.js
 * |  `- nested/
 * |     |- module.js
 * |     `- deep/
 * |        `- config.json
 * |- data/
 * |  `- config.json
 * |- my folder/          (space in the directory name)
 * |  `- spaced file.js    (spaces in the file name)
 * `- ícones/              (Unicode directory name)
 *    `- häupt.js          (Unicode file name)
 * ```
 *
 * The fake extends the real `android.provider.DocumentsProvider`, so the
 * genuine AOSP URI matcher and tree-descendant enforcement run. Note: this is
 * a mocked document provider, not a physical Storage Access Framework — an
 * on-device instrumented check remains necessary for full confidence (see
 * docs/LOCAL_HTTP_SERVER_BLOCKS.md).
 */
@RunWith(RobolectricTestRunner::class)
class SafDeepTreeTest {

    private lateinit var store: SafDocumentStore

    @Before
    fun setUp() {
        SafTestEnvironment.registerDeep()
        store = SafDocumentStore(RuntimeEnvironment.getApplication(), SafTestEnvironment.treeUri)
    }

    // ---------- resolution at every depth ----------

    @Test
    fun `root level files resolve`() {
        val index = store.resolve("index.html")
        assertNotNull(index)
        assertFalse(index!!.isDirectory)
        assertEquals("index.html", index.name)

        val rootJs = store.resolve("root.js")
        assertNotNull(rootJs)
        assertFalse(rootJs!!.isDirectory)
    }

    @Test
    fun `first level subdirectory files resolve`() {
        assertNotNull(store.resolve("assets/app.js"))
        assertNotNull(store.resolve("data/config.json"))
    }

    @Test
    fun `files two directory levels deep resolve`() {
        val module = store.resolve("assets/nested/module.js")
        assertNotNull(module)
        assertEquals("module.js", module!!.name)
        assertEquals(FakeGameDocumentsProvider.MODULE_JS.size.toLong(), module.size)
        assertFalse(module.isDirectory)
    }

    @Test
    fun `files three directory levels deep resolve`() {
        val config = store.resolve("assets/nested/deep/config.json")
        assertNotNull(config)
        assertEquals("config.json", config!!.name)
        assertEquals(FakeGameDocumentsProvider.CONFIG_JSON.size.toLong(), config.size)
        assertEquals("application/json", config.mimeType)
        assertFalse(config.isDirectory)
    }

    @Test
    fun `multiple nested branches resolve independently`() {
        val deepConfig = store.resolve("assets/nested/deep/config.json")
        val dataConfig = store.resolve("data/config.json")
        assertNotNull(deepConfig)
        assertNotNull(dataConfig)
        assertEquals(
            String(FakeGameDocumentsProvider.CONFIG_JSON, Charsets.UTF_8),
            String(store.openInput(deepConfig!!)!!.readBytes(), Charsets.UTF_8)
        )
        assertEquals(
            String(FakeGameDocumentsProvider.DATA_CONFIG_JSON, Charsets.UTF_8),
            String(store.openInput(dataConfig!!)!!.readBytes(), Charsets.UTF_8)
        )
    }

    @Test
    fun `directories at depth resolve as directories`() {
        for (path in listOf("assets", "assets/nested", "assets/nested/deep", "data")) {
            val entry = store.resolve(path)
            assertNotNull("directory must resolve: $path", entry)
            assertTrue("must be a directory: $path", entry!!.isDirectory)
        }
    }

    // ---------- spaces and unicode ----------

    @Test
    fun `directory names with spaces resolve`() {
        val dir = store.resolve("my folder")
        assertNotNull(dir)
        assertTrue(dir!!.isDirectory)

        val file = store.resolve("my folder/spaced file.js")
        assertNotNull(file)
        assertEquals("spaced file.js", file!!.name)
    }

    @Test
    fun `unicode directory and file names resolve`() {
        val dir = store.resolve("ícones")
        assertNotNull(dir)
        assertTrue(dir!!.isDirectory)

        val file = store.resolve("ícones/häupt.js")
        assertNotNull(file)
        assertEquals("häupt.js", file!!.name)
    }

    @Test
    fun `decomposed unicode names on disk match composed request segments`() {
        // The fixture stores the name in NFD (decomposed) form like a real
        // filesystem may hold it; the request path arrives in NFC (composed)
        // form and must still resolve through the display-name walk.
        val file = store.resolve("café/résumé.js")
        assertNotNull(file)
        assertEquals("re\u0301sume\u0301.js", file!!.name)
        val content = store.openInput(file)?.readBytes()
        assertNotNull(content)
        assertTrue(FakeGameDocumentsProvider.NFD_JS.contentEquals(content!!))
    }

    // ---------- listings ----------

    @Test
    fun `listings include nested directories at every level`() {
        val rootChildren = store.listChildren(store.resolve("")!!).map { it.name }
        assertTrue(rootChildren.containsAll(listOf("assets", "data", "my folder", "ícones", "index.html", "root.js")))

        val assets = store.listChildren(store.resolve("assets")!!)
        assertEquals(setOf("app.js", "nested"), assets.map { it.name }.toSet())
        assertTrue(assets.first { it.name == "nested" }.isDirectory)

        val nested = store.listChildren(store.resolve("assets/nested")!!)
        assertEquals(setOf("module.js", "deep"), nested.map { it.name }.toSet())
        assertTrue(nested.first { it.name == "deep" }.isDirectory)

        val deep = store.listChildren(store.resolve("assets/nested/deep")!!)
        assertEquals(listOf("config.json"), deep.map { it.name })

        val data = store.listChildren(store.resolve("data")!!)
        assertEquals(listOf("config.json"), data.map { it.name })
    }

    // ---------- missing and out-of-tree paths ----------

    @Test
    fun `missing files at depth resolve to null`() {
        assertNull(store.resolve("missing.js"))
        assertNull(store.resolve("assets/missing.js"))
        assertNull(store.resolve("assets/nested/missing.js"))
        assertNull(store.resolve("assets/nested/deep/missing.json"))
    }

    @Test
    fun `paths escaping the selected tree do not resolve`() {
        assertNull(store.resolve("../secret.txt"))
        assertNull(store.resolve("assets/../../secret.txt"))
        assertNull(store.resolve("assets/nested/../../../secret.txt"))
    }

    // ---------- content streaming at depth ----------

    @Test
    fun `content streams correctly at depth`() {
        val config = store.resolve("assets/nested/deep/config.json")!!
        val streamed = store.openInput(config)?.readBytes()
        assertNotNull(streamed)
        assertTrue(FakeGameDocumentsProvider.CONFIG_JSON.contentEquals(streamed!!))

        val spaced = store.resolve("my folder/spaced file.js")!!
        val spacedContent = store.openInput(spaced)?.readBytes()
        assertNotNull(spacedContent)
        assertTrue(FakeGameDocumentsProvider.SPACED_JS.contentEquals(spacedContent!!))
    }
}
