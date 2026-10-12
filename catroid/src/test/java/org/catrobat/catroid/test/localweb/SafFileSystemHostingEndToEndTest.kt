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
 * End-to-end HTTP tests for the realistic-tree reproduction: the real
 * [HttpServerEngine] serves a real on-disk directory tree (see
 * [SafFileSystemProviderTest] for the structure) through
 * [FakeFileSystemDocumentsProvider], which models the actual AOSP
 * `ExternalStorageProvider` behavior. Verifies GET/HEAD/Range, MIME types,
 * listings at depth, 404s and traversal protection over HTTP for deeply
 * nested files.
 */
@RunWith(RobolectricTestRunner::class)
class SafFileSystemHostingEndToEndTest {

    private lateinit var storageRoot: File
    private lateinit var store: SafDocumentStore
    private var engine: HttpServerEngine? = null
    private var port = -1
    private val treeUri: Uri
        get() = Uri.parse(FakeFileSystemDocumentsProvider.treeUriFor("Game"))

    @Before
    fun setUp() {
        storageRoot = java.nio.file.Files.createTempDirectory("saf-storage-e2e").toFile()
        val game = File(storageRoot, "Game")
        game.mkdirs()
        write(File(game, "index.html"), "<html><body>fs index</body></html>")
        write(File(game, "assets/app.js"), "console.log('fs app');")
        write(File(game, "assets/nested/module.js"), "console.log('fs module');")
        write(File(game, "assets/nested/deep/config.json"), "{\"name\":\"fs-deep\",\"level\":3,\"pad\":\"0123456789\"}")
        write(File(game, "data/config.json"), "{\"name\":\"fs-data\"}")
        write(File(game, "Android/data/hidden.txt"), "secret")

        SafTestEnvironment.registerProvider(
            FakeFileSystemDocumentsProvider.AUTHORITY,
            FakeFileSystemDocumentsProvider(storageRoot, "primary:Game")
        )
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        File(storageRoot, "Game").walkTopDown().filter { it.isFile }.forEach { file ->
            val docId = "primary:Game/" + file.relativeTo(game).invariantSeparatorsPath
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            shadowOf(resolver).registerInputStreamSupplier(uri, Supplier { ByteArrayInputStream(file.readBytes()) })
        }

        store = SafDocumentStore(RuntimeEnvironment.getApplication(), treeUri)
        val newEngine = HttpServerEngine(storeProvider = { store }, urlPrefixProvider = { "/" })
        val result = newEngine.start(0)
        engine = newEngine
        assertTrue("server should start", result is HttpServerEngine.Result.Success)
        port = (result as HttpServerEngine.Result.Success).port
    }

    @After
    fun tearDown() {
        engine?.stop()
        engine = null
        storageRoot.deleteRecursively()
    }

    private fun write(file: File, content: String) {
        file.parentFile?.mkdirs()
        file.writeText(content, StandardCharsets.UTF_8)
    }

    private fun bodyText(response: RawHttpResponse): String = String(response.body, Charsets.UTF_8)

    @Test
    fun `root request serves the index html of the selected folder`() {
        val response = rawHttpRequest(port, "GET", "/")
        assertEquals(200, response.statusCode)
        assertEquals("<html><body>fs index</body></html>", bodyText(response))
        assertTrue(response.headers["content-type"]!!.startsWith("text/html"))
    }

    @Test
    fun `file three directory levels deep is served over http`() {
        val response = rawHttpRequest(port, "GET", "/assets/nested/deep/config.json")
        assertEquals(200, response.statusCode)
        assertTrue(response.headers["content-type"]!!.startsWith("application/json"))
        assertEquals("{\"name\":\"fs-deep\",\"level\":3,\"pad\":\"0123456789\"}", bodyText(response))
    }

    @Test
    fun `directory listing at depth includes subdirectories over http`() {
        val nested = rawHttpRequest(port, "GET", "/assets/nested/")
        assertEquals(200, nested.statusCode)
        assertTrue(bodyText(nested).contains("module.js"))
        assertTrue(bodyText(nested).contains("deep/"))

        val deep = rawHttpRequest(port, "GET", "/assets/nested/deep/")
        assertEquals(200, deep.statusCode)
        assertTrue(bodyText(deep).contains("config.json"))
    }

    @Test
    fun `android data folder inside the selected tree is served like the real provider`() {
        // The real provider only hides Android/data directly under the storage
        // root; a nested Android/data inside the selected tree is listed and
        // directly accessible, exactly like on a device.
        val android = rawHttpRequest(port, "GET", "/Android/")
        assertEquals(200, android.statusCode)
        assertTrue(bodyText(android).contains("data/"))

        val hidden = rawHttpRequest(port, "GET", "/Android/data/hidden.txt")
        assertEquals(200, hidden.statusCode)
        assertEquals("secret", bodyText(hidden))
    }

    @Test
    fun `head and range requests work at depth`() {
        val head = rawHttpRequest(port, "HEAD", "/assets/nested/deep/config.json")
        assertEquals(200, head.statusCode)
        assertTrue(head.body.isEmpty())

        val content = File(File(File(File(File(storageRoot, "Game"), "assets"), "nested"), "deep"), "config.json").readBytes()
        val range = rawHttpRequest(port, "GET", "/assets/nested/deep/config.json", mapOf("Range" to "bytes=0-9"))
        assertEquals(206, range.statusCode)
        assertEquals("bytes 0-9/${content.size}", range.headers["content-range"])
        assertTrue(content.copyOfRange(0, 10).contentEquals(range.body))
    }

    @Test
    fun `missing files at depth return 404 and traversal is rejected`() {
        assertEquals(404, rawHttpRequest(port, "GET", "/assets/nested/deep/missing.json").statusCode)
        assertEquals(403, rawHttpRequest(port, "GET", "/assets/nested/../../../secret.txt").statusCode)
        assertEquals(403, rawHttpRequest(port, "GET", "/%2e%2e/%2e%2e/").statusCode)
    }
}
