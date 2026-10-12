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
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.function.Supplier

/**
 * Minimal reproduction of the reported nested-folder hosting issue using a
 * REALISTIC directory tree on a REAL filesystem, served through
 * [FakeFileSystemDocumentsProvider] - a fixture that models the actual AOSP
 * `ExternalStorageProvider` semantics (document ids derived from file paths,
 * canonical file resolution, real directory listings, scoped-storage
 * `Android/data` hiding, canonical-containment `isChildDocument`, real file
 * streams). The tree has files at several nesting depths, multiple branches,
 * spaces, Unicode, an empty directory and a hidden `Android/data` folder:
 *
 * ```
 * <storage>/Game/                       (selected tree: primary:Game)
 * |- index.html, root.js
 * |- assets/app.js
 * |- assets/nested/module.js
 * |- assets/nested/deep/config.json     (three directory levels deep)
 * |- data/config.json                   (independent second branch)
 * |- my folder/spaced file.js           (spaces)
 * |- ícones/häupt.js                    (Unicode)
 * |- empty/                             (empty directory)
 * `- Android/data/hidden.txt            (hidden from listings by the provider)
 * ```
 *
 * The fake extends the real `android.provider.DocumentsProvider`, so the
 * genuine AOSP URI matcher and tree-descendant enforcement run. This is a
 * simulated provider on the JVM, not a physical device - on-device
 * verification steps are documented in docs/LOCAL_HTTP_SERVER_BLOCKS.md.
 */
@RunWith(RobolectricTestRunner::class)
class SafFileSystemProviderTest {

    private lateinit var storageRoot: File
    private lateinit var store: SafDocumentStore
    private val treeUri: Uri
        get() = Uri.parse(FakeFileSystemDocumentsProvider.treeUriFor("Game"))

    @Before
    fun setUp() {
        storageRoot = java.nio.file.Files.createTempDirectory("saf-storage").toFile()
        buildGameTree(File(storageRoot, "Game"))
        SafTestEnvironment.registerProvider(
            FakeFileSystemDocumentsProvider.AUTHORITY,
            FakeFileSystemDocumentsProvider(storageRoot, "primary:Game")
        )
        registerDocumentStreams()
        store = SafDocumentStore(RuntimeEnvironment.getApplication(), treeUri)
    }

    @After
    fun tearDown() {
        storageRoot.deleteRecursively()
    }

    private fun buildGameTree(game: File) {
        game.mkdirs()
        write(File(game, "index.html"), "<html><body>fs index</body></html>")
        write(File(game, "root.js"), "console.log('fs root');")
        write(File(game, "assets/app.js"), "console.log('fs app');")
        write(File(game, "assets/nested/module.js"), "console.log('fs module');")
        write(File(game, "assets/nested/deep/config.json"), "{\"name\":\"fs-deep\",\"level\":3}")
        write(File(game, "data/config.json"), "{\"name\":\"fs-data\"}")
        write(File(game, "my folder/spaced file.js"), "console.log('fs spaced');")
        write(File(game, "ícones/häupt.js"), "console.log('fs unicode');")
        File(game, "empty").mkdirs()
        write(File(game, "Android/data/hidden.txt"), "secret")
    }

    private fun write(file: File, content: String) {
        file.parentFile?.mkdirs()
        file.writeText(content, StandardCharsets.UTF_8)
    }

    private fun registerDocumentStreams() {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        fun register(file: File) {
            val docId = "primary:Game/" + file.relativeTo(File(storageRoot, "Game")).invariantSeparatorsPath
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            shadowOf(resolver).registerInputStreamSupplier(uri, Supplier { ByteArrayInputStream(file.readBytes()) })
        }
        File(storageRoot, "Game").walkTopDown().filter { it.isFile }.forEach { register(it) }
    }

    // ---------- resolution at every depth ----------

    @Test
    fun `files at every depth resolve through real filesystem documents`() {
        val index = store.resolve("index.html")
        assertNotNull(index)
        assertEquals("index.html", index!!.name)
        assertFalse(index.isDirectory)

        val app = store.resolve("assets/app.js")
        assertNotNull(app)
        assertEquals("app.js", app!!.name)

        val module = store.resolve("assets/nested/module.js")
        assertNotNull(module)
        assertEquals("module.js", module!!.name)
        assertEquals("console.log('fs module');".length.toLong(), module.size)

        val config = store.resolve("assets/nested/deep/config.json")
        assertNotNull(config)
        assertEquals("config.json", config!!.name)
        assertEquals("application/json", config.mimeType)
        assertFalse(config.isDirectory)
    }

    @Test
    fun `directories at depth resolve as directories`() {
        for (path in listOf("assets", "assets/nested", "assets/nested/deep", "data", "empty")) {
            val entry = store.resolve(path)
            assertNotNull("directory must resolve: $path", entry)
            assertTrue("must be a directory: $path", entry!!.isDirectory)
        }
    }

    @Test
    fun `multiple nested branches resolve independently`() {
        val deep = store.resolve("assets/nested/deep/config.json")
        val data = store.resolve("data/config.json")
        assertNotNull(deep)
        assertNotNull(data)
        assertEquals("{\"name\":\"fs-deep\",\"level\":3}", String(store.openInput(deep!!)!!.readBytes(), Charsets.UTF_8))
        assertEquals("{\"name\":\"fs-data\"}", String(store.openInput(data!!)!!.readBytes(), Charsets.UTF_8))
    }

    // ---------- listings ----------

    @Test
    fun `listings include nested subdirectories at every level`() {
        val rootChildren = store.listChildren(store.resolve("")!!).map { it.name }
        assertTrue(rootChildren.containsAll(listOf("assets", "data", "my folder", "ícones", "empty", "index.html", "root.js")))

        val assets = store.listChildren(store.resolve("assets")!!)
        assertEquals(setOf("app.js", "nested"), assets.map { it.name }.toSet())
        assertTrue(assets.first { it.name == "nested" }.isDirectory)

        val nested = store.listChildren(store.resolve("assets/nested")!!)
        assertEquals(setOf("module.js", "deep"), nested.map { it.name }.toSet())

        val deep = store.listChildren(store.resolve("assets/nested/deep")!!)
        assertEquals(listOf("config.json"), deep.map { it.name })
    }

    @Test
    fun `empty directories list as empty`() {
        val empty = store.resolve("empty")!!
        assertTrue(store.listChildren(empty).isEmpty())
    }

    @Test
    fun `android data style folders are hidden from listings like the real provider`() {
        // The real ExternalStorageProvider hides Android/data|obb|sandbox from
        // queryChildDocuments (scoped-storage privacy); direct document access
        // still works, exactly like on a device.
        val android = store.resolve("Android")
        assertNotNull(android)
        assertTrue(store.listChildren(android!!).isEmpty())
        assertNotNull("direct access to a hidden document still resolves", store.resolve("Android/data/hidden.txt"))
    }

    // ---------- spaces, unicode, missing, traversal ----------

    @Test
    fun `names with spaces and unicode resolve through the real filesystem`() {
        val spaced = store.resolve("my folder/spaced file.js")
        assertNotNull(spaced)
        assertEquals("spaced file.js", spaced!!.name)

        val unicode = store.resolve("ícones/häupt.js")
        assertNotNull(unicode)
        assertEquals("häupt.js", unicode!!.name)
    }

    @Test
    fun `missing files at depth resolve to null`() {
        assertNull(store.resolve("missing.js"))
        assertNull(store.resolve("assets/missing.js"))
        assertNull(store.resolve("assets/nested/deep/missing.json"))
    }

    @Test
    fun `paths escaping the selected tree do not resolve`() {
        assertNull(store.resolve("../secret.txt"))
        assertNull(store.resolve("assets/../../secret.txt"))
        assertNull(store.resolve("assets/nested/../../../secret.txt"))
    }

    // ---------- streaming ----------

    @Test
    fun `content streams from the real files at depth`() {
        val config = store.resolve("assets/nested/deep/config.json")!!
        val streamed = store.openInput(config)?.readBytes()
        assertNotNull(streamed)
        assertEquals(
            File(File(File(File(storageRoot, "Game"), "assets"), "nested"), "deep")
                .let { File(it, "config.json") }.readBytes().toList(),
            streamed!!.toList()
        )
    }
}
