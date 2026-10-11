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

import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.DocumentsContract
import org.catrobat.catroid.content.localweb.HttpServerEngine
import org.catrobat.catroid.content.localweb.LocalHttpServer
import org.catrobat.catroid.content.localweb.SafDocumentStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayInputStream
import java.net.ServerSocket
import java.util.function.Supplier

/**
 * End-to-end regression tests for the local game-hosting workflow:
 * select folder (SAF tree URI) -> host it -> start the HTTP server -> open
 * the game in the WebView. The tests drive the real [HttpServerEngine] over
 * loopback sockets with a [SafDocumentStore] on top of
 * [FakeGameDocumentsProvider] (real `DocumentsProvider` URI matching), so the
 * full request path is exercised: URL routing, SAF document resolution,
 * MIME types, streaming, ranges and traversal protection.
 *
 * Regression: hosting a selected folder used to answer 404 for "/" because
 * the tree root was queried through the bare tree URI, which providers reject.
 */
@RunWith(RobolectricTestRunner::class)
class SafHostingEndToEndTest {

    private lateinit var treeUri: Uri
    private var engine: HttpServerEngine? = null

    @Before
    fun setUp() {
        registerFakeProvider()
        treeUri = Uri.parse(FakeGameDocumentsProvider.TREE_URI)
        registerDocumentStreams()
    }

    @After
    fun tearDown() {
        engine?.stop()
        engine = null
        LocalHttpServer.stop()
        LocalHttpServer.setDocumentRoot(null)
        LocalHttpServer.setUrlPrefix(null)
        LocalHttpServer.clearError()
    }

    /**
     * Registers the fake provider with Robolectric's content resolver. The
     * provider must be attached (attachInfo) before use: DocumentsProvider
     * builds its URI matcher in attachInfo, and querying an unattached
     * provider would NPE instead of exercising the matcher.
     */
    private fun registerFakeProvider() {
        val provider = FakeGameDocumentsProvider()
        val info = ProviderInfo()
        info.authority = FakeGameDocumentsProvider.AUTHORITY
        info.name = FakeGameDocumentsProvider::class.java.name
        provider.attachInfo(RuntimeEnvironment.getApplication(), info)
        ShadowContentResolver.registerProviderInternal(FakeGameDocumentsProvider.AUTHORITY, provider)
    }

    private fun registerDocumentStreams() {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        for ((documentId, content) in FakeGameDocumentsProvider.allFiles) {
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            shadowOf(resolver).registerInputStreamSupplier(uri, Supplier { ByteArrayInputStream(content) })
        }
    }

    private fun startEngine(prefix: String = "/"): Int {
        val store = SafDocumentStore(RuntimeEnvironment.getApplication(), treeUri)
        val server = HttpServerEngine(storeProvider = { store }, urlPrefixProvider = { prefix })
        val result = server.start(0, "127.0.0.1")
        assertTrue("server should start, got: $result", result is HttpServerEngine.Result.Success)
        engine = server
        return server.boundPort
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    // ---------- root requests ----------

    @Test
    fun `root request serves the index html of the selected folder`() {
        val port = startEngine()
        val response = rawHttpRequest(port, "GET", "/")
        assertEquals(200, response.statusCode)
        assertEquals(
            String(FakeGameDocumentsProvider.INDEX_HTML, Charsets.UTF_8),
            response.bodyText()
        )
        assertTrue(response.headers["content-type"]!!.startsWith("text/html"))
    }

    @Test
    fun `explicit index html request serves the same document`() {
        val port = startEngine()
        val response = rawHttpRequest(port, "GET", "/index.html")
        assertEquals(200, response.statusCode)
        assertEquals(
            String(FakeGameDocumentsProvider.INDEX_HTML, Charsets.UTF_8),
            response.bodyText()
        )
    }

    @Test
    fun `non index entry file is served at its own url`() {
        val port = startEngine()
        val response = rawHttpRequest(port, "GET", "/game.html")
        assertEquals(200, response.statusCode)
        assertEquals(
            String(FakeGameDocumentsProvider.GAME_HTML, Charsets.UTF_8),
            response.bodyText()
        )
        assertTrue(response.headers["content-type"]!!.startsWith("text/html"))
    }

    // ---------- nested assets ----------

    @Test
    fun `nested javascript asset is served with the right mime type`() {
        val port = startEngine()
        val response = rawHttpRequest(port, "GET", "/nested/asset.js")
        assertEquals(200, response.statusCode)
        assertEquals(String(FakeGameDocumentsProvider.JS, Charsets.UTF_8), response.bodyText())
        assertTrue(response.headers["content-type"]!!.startsWith("text/javascript"))
    }

    @Test
    fun `nested css asset is served with the right mime type`() {
        val port = startEngine()
        val response = rawHttpRequest(port, "GET", "/style.css")
        assertEquals(200, response.statusCode)
        assertEquals(String(FakeGameDocumentsProvider.CSS, Charsets.UTF_8), response.bodyText())
        assertTrue(response.headers["content-type"]!!.startsWith("text/css"))
    }

    @Test
    fun `nested wasm asset is served with the right mime type`() {
        val port = startEngine()
        val response = rawHttpRequest(port, "GET", "/assets/game.wasm")
        assertEquals(200, response.statusCode)
        assertEquals("application/wasm", response.headers["content-type"])
        assertEquals(FakeGameDocumentsProvider.WASM.size, response.body.size)
    }

    @Test
    fun `directory without an index file renders a listing`() {
        val port = startEngine()
        val response = rawHttpRequest(port, "GET", "/nested/")
        assertEquals(200, response.statusCode)
        assertTrue(response.bodyText().contains("asset.js"))
        assertTrue(response.bodyText().contains("texture.png"))
    }

    // ---------- diagnostics ----------

    @Test
    fun `missing file produces a 404 diagnostic`() {
        val port = startEngine()
        val response = rawHttpRequest(port, "GET", "/missing.txt")
        assertEquals(404, response.statusCode)
        assertTrue(response.bodyText().contains("404"))
    }

    @Test
    fun `path traversal attempts are rejected`() {
        val port = startEngine()
        assertEquals(403, rawHttpRequest(port, "GET", "/../secret.txt").statusCode)
        assertEquals(403, rawHttpRequest(port, "GET", "/%2e%2e/secret.txt").statusCode)
        assertEquals(403, rawHttpRequest(port, "GET", "/nested/../../secret.txt").statusCode)
        assertEquals(403, rawHttpRequest(port, "GET", "//evil.example/x").statusCode)
    }

    // ---------- range / streaming / HEAD ----------

    @Test
    fun `range request streams exactly the requested byte range`() {
        val port = startEngine()
        val response = rawHttpRequest(port, "GET", "/data.bin", mapOf("Range" to "bytes=100-199"))
        assertEquals(206, response.statusCode)
        assertEquals(
            "bytes 100-199/${FakeGameDocumentsProvider.BIN.size}",
            response.headers["content-range"]
        )
        assertEquals(100, response.body.size)
        val expected = FakeGameDocumentsProvider.BIN.copyOfRange(100, 200)
        assertTrue(expected.contentEquals(response.body))
    }

    @Test
    fun `large file streams completely`() {
        val port = startEngine()
        val response = rawHttpRequest(port, "GET", "/data.bin")
        assertEquals(200, response.statusCode)
        assertEquals(FakeGameDocumentsProvider.BIN.size.toString(), response.headers["content-length"])
        assertTrue(FakeGameDocumentsProvider.BIN.contentEquals(response.body))
    }

    @Test
    fun `head request returns headers without a body`() {
        val port = startEngine()
        val response = rawHttpRequest(port, "HEAD", "/index.html")
        assertEquals(200, response.statusCode)
        assertEquals(
            FakeGameDocumentsProvider.INDEX_HTML.size.toString(),
            response.headers["content-length"]
        )
        assertEquals(0, response.body.size)
    }

    // ---------- URL prefix ----------

    @Test
    fun `url prefix mounts the selected folder below the prefix`() {
        val port = startEngine("/game")
        val root = rawHttpRequest(port, "GET", "/game/")
        assertEquals(200, root.statusCode)
        assertEquals(
            String(FakeGameDocumentsProvider.INDEX_HTML, Charsets.UTF_8),
            root.bodyText()
        )
        val nested = rawHttpRequest(port, "GET", "/game/nested/asset.js")
        assertEquals(200, nested.statusCode)
        assertEquals(String(FakeGameDocumentsProvider.JS, Charsets.UTF_8), nested.bodyText())
        // Outside the prefix nothing is served.
        assertEquals(404, rawHttpRequest(port, "GET", "/").statusCode)
    }

    // ---------- the manager path used by the blocks ----------

    @Test
    fun `manager hosts the selected folder and serves its root`() {
        LocalHttpServer.setDocumentRoot(FakeGameDocumentsProvider.TREE_URI)
        LocalHttpServer.setUrlPrefix(null)
        val port = freePort()
        assertTrue("server should start", LocalHttpServer.start(port))
        try {
            assertEquals("http://127.0.0.1:$port/", LocalHttpServer.baseUrl)
            val response = rawHttpRequest(port, "GET", "/")
            assertEquals(200, response.statusCode)
            assertEquals(
                String(FakeGameDocumentsProvider.INDEX_HTML, Charsets.UTF_8),
                response.bodyText()
            )
            val game = rawHttpRequest(port, "GET", "/game.html")
            assertEquals(200, game.statusCode)
        } finally {
            LocalHttpServer.stop()
        }
    }

    @Test
    fun `starting without a selected folder answers 404 with a diagnostic`() {
        LocalHttpServer.setDocumentRoot(null)
        val port = freePort()
        assertTrue(LocalHttpServer.start(port))
        try {
            val response = rawHttpRequest(port, "GET", "/")
            assertEquals(404, response.statusCode)
            assertTrue(response.bodyText().contains("no directory"))
        } finally {
            LocalHttpServer.stop()
        }
    }

    @Test
    fun `a non tree content uri is rejected with a clear error`() {
        LocalHttpServer.setDocumentRoot(
            "content://${FakeGameDocumentsProvider.AUTHORITY}/document/primary%3ADocuments%2Fgame%2FGame%2Findex.html"
        )
        assertNotNull(LocalHttpServer.lastError)
        assertTrue(LocalHttpServer.lastError!!.contains("tree"))
        assertEquals("", LocalHttpServer.documentRoot)
    }
}
