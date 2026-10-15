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
 * End-to-end tests for generic entry-point discovery through Android SAF
 * resolution: a selected folder whose entry point is NOT at the root (the
 * reported problem - opening the hosted game showed a directory listing
 * instead of launching it). The tree is served through
 * [FakeFileSystemDocumentsProvider] (real on-disk tree, real
 * ExternalStorageProvider semantics) and [SafDocumentStore], so discovery,
 * redirects and asset resolution all run through genuine SAF document
 * resolution at every depth.
 *
 * Simulated (Robolectric) - physical-device verification steps are documented
 * in docs/LOCAL_HTTP_SERVER_BLOCKS.md.
 */
@RunWith(RobolectricTestRunner::class)
class SafEntryPointHostingTest {

    private lateinit var storageRoot: File
    private lateinit var store: SafDocumentStore
    private var engine: HttpServerEngine? = null
    private var port = -1
    private val treeUri: Uri
        get() = Uri.parse(FakeFileSystemDocumentsProvider.treeUriFor("Game"))

    @Before
    fun setUp() {
        storageRoot = java.nio.file.Files.createTempDirectory("saf-entry-test").toFile()
    }

    @After
    fun tearDown() {
        engine?.stop()
        engine = null
        storageRoot.deleteRecursively()
    }

    private fun write(relativePath: String, content: String) {
        val file = File(File(storageRoot, "Game"), relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content, StandardCharsets.UTF_8)
    }

    private fun startServer() {
        SafTestEnvironment.registerProvider(
            FakeFileSystemDocumentsProvider.AUTHORITY,
            FakeFileSystemDocumentsProvider(storageRoot, "primary:Game")
        )
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        File(storageRoot, "Game").walkTopDown().filter { it.isFile }.forEach { file ->
            val docId = "primary:Game/" + file.relativeTo(File(storageRoot, "Game")).invariantSeparatorsPath
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

    private fun bodyText(response: RawHttpResponse): String = String(response.body, Charsets.UTF_8)

    @Test
    fun `nested entry point launches instead of showing a listing over saf`() {
        // The reported structure: the game entry is inside a nested directory
        // of the selected folder; there is no index.html at the root.
        write("play/index.html", "<html><body>the game</body></html>")
        write("play/app.js", "console.log('game asset');")
        write("assets/style.css", "body { margin: 0; }")
        startServer()

        val redirect = rawHttpRequest(port, "GET", "/")
        assertEquals(302, redirect.statusCode)
        assertEquals("/play/index.html", redirect.headers["location"])

        val entry = rawHttpRequest(port, "GET", "/play/index.html")
        assertEquals(200, entry.statusCode)
        assertEquals("<html><body>the game</body></html>", bodyText(entry))
        assertTrue(entry.headers["content-type"]!!.startsWith("text/html"))

        // The entry's relative asset resolves from the entry's own location.
        val asset = rawHttpRequest(port, "GET", "/play/app.js")
        assertEquals(200, asset.statusCode)
        assertEquals("console.log('game asset');", bodyText(asset))
    }

    @Test
    fun `single html file at the root of a saf tree launches over saf`() {
        write("game.html", "<html><body>single entry</body></html>")
        write("game.js", "console.log('g');")
        startServer()

        val redirect = rawHttpRequest(port, "GET", "/")
        assertEquals(302, redirect.statusCode)
        assertEquals("/game.html", redirect.headers["location"])

        val entry = rawHttpRequest(port, "GET", "/game.html")
        assertEquals(200, entry.statusCode)
        assertEquals("<html><body>single entry</body></html>", bodyText(entry))
    }

    @Test
    fun `manifest start url launches a nested entry point over saf`() {
        write("manifest.webmanifest", """{"name":"game","start_url":"dist/index.html"}""")
        write("dist/index.html", "<html><body>dist game</body></html>")
        write("dist/data.bin", "binary")
        startServer()

        val redirect = rawHttpRequest(port, "GET", "/")
        assertEquals(302, redirect.statusCode)
        assertEquals("/dist/index.html", redirect.headers["location"])

        val entry = rawHttpRequest(port, "GET", "/dist/index.html")
        assertEquals(200, entry.statusCode)
        assertEquals("<html><body>dist game</body></html>", bodyText(entry))
    }

    @Test
    fun `saf tree with root index html still serves it directly`() {
        write("index.html", "<html><body>root entry</body></html>")
        write("nested/app.js", "console.log('nested');")
        startServer()

        val response = rawHttpRequest(port, "GET", "/")
        assertEquals(200, response.statusCode)
        assertEquals("<html><body>root entry</body></html>", bodyText(response))

        val nested = rawHttpRequest(port, "GET", "/nested/app.js")
        assertEquals(200, nested.statusCode)
        assertEquals("console.log('nested');", bodyText(nested))
    }
}
