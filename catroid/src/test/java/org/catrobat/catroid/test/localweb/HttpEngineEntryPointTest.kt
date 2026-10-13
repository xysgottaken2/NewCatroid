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

import org.catrobat.catroid.content.localweb.FileDocumentStore
import org.catrobat.catroid.content.localweb.HttpEngine
import org.catrobat.catroid.content.localweb.HttpServerEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Regression tests for generic entry-point handling when a directory request
 * has no default document - the reported problem: opening the hosted game
 * showed a directory listing ("Index of /play") instead of launching the
 * game whose entry point lives at the root or in a nested directory.
 *
 * The behavior follows established servers (Apache mod_dir, Python
 * http.server): a directory request serves its default document
 * (index.html / index.htm) when present, otherwise the server discovers a
 * deterministic entry point and REDIRECTS to it (302, so relative asset URLs
 * resolve against the entry document's own URL), and only shows a listing
 * when no unambiguous entry point exists. Discovery never guesses: a
 * candidate must be the single unambiguous choice or be declared by the app
 * itself (web app manifest start_url).
 *
 * Pure JVM (FileDocumentStore over a real temp directory + the real
 * HttpServerEngine over loopback).
 */
class HttpEngineEntryPointTest {

    private lateinit var rootDir: File
    private var engine: HttpServerEngine? = null
    private var port = -1
    private var prefix = "/"

    @Before
    fun setUp() {
        rootDir = java.nio.file.Files.createTempDirectory("entry-point-test").toFile()
    }

    @After
    fun tearDown() {
        engine?.stop()
        engine = null
        rootDir.deleteRecursively()
    }

    private fun startServer(urlPrefix: String = "/") {
        prefix = urlPrefix
        val store = FileDocumentStore(rootDir)
        val newEngine = HttpServerEngine(
            storeProvider = { store },
            urlPrefixProvider = { urlPrefix }
        )
        val result = newEngine.start(0)
        engine = newEngine
        assertTrue("server should start", result is HttpServerEngine.Result.Success)
        port = (result as HttpServerEngine.Result.Success).port
    }

    private fun write(relativePath: String, content: String) {
        val file = File(rootDir, relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content, StandardCharsets.UTF_8)
    }

    private fun bodyText(response: RawHttpResponse): String = String(response.body, Charsets.UTF_8)

    // ---------- default documents (established behavior, preserved) ----------

    @Test
    fun `root with index html serves it`() {
        write("index.html", "<html>entry</html>")
        startServer()
        val response = rawHttpRequest(port, "GET", "/")
        assertEquals(200, response.statusCode)
        assertEquals("<html>entry</html>", bodyText(response))
    }

    @Test
    fun `root with only index htm serves it`() {
        write("index.htm", "<html>htm entry</html>")
        startServer()
        val response = rawHttpRequest(port, "GET", "/")
        assertEquals(200, response.statusCode)
        assertEquals("<html>htm entry</html>", bodyText(response))
    }

    // ---------- single html file in the directory ----------

    @Test
    fun `directory with a single html file redirects to it`() {
        write("game.html", "<html>game</html>")
        write("assets/app.js", "console.log('asset');")
        startServer()

        val redirect = rawHttpRequest(port, "GET", "/")
        assertEquals(302, redirect.statusCode)
        assertEquals("/game.html", redirect.headers["location"])

        val entry = rawHttpRequest(port, "GET", "/game.html")
        assertEquals(200, entry.statusCode)
        assertEquals("<html>game</html>", bodyText(entry))
    }

    @Test
    fun `directory with multiple html files keeps the listing`() {
        write("game.html", "<html>game</html>")
        write("about.html", "<html>about</html>")
        startServer()
        val response = rawHttpRequest(port, "GET", "/")
        assertEquals(200, response.statusCode)
        assertTrue(bodyText(response).contains("Index of"))
        assertTrue(bodyText(response).contains("game.html"))
        assertTrue(bodyText(response).contains("about.html"))
    }

    // ---------- nested entry point: single subdirectory ----------

    @Test
    fun `single subdirectory with index html becomes the entry point`() {
        write("play/index.html", "<html>play entry</html>")
        write("play/app.js", "console.log('play asset');")
        startServer()

        val redirect = rawHttpRequest(port, "GET", "/")
        assertEquals(302, redirect.statusCode)
        assertEquals("/play/index.html", redirect.headers["location"])

        val entry = rawHttpRequest(port, "GET", "/play/index.html")
        assertEquals(200, entry.statusCode)
        assertEquals("<html>play entry</html>", bodyText(entry))

        // Relative asset resolution from the entry point's location.
        val asset = rawHttpRequest(port, "GET", "/play/app.js")
        assertEquals(200, asset.statusCode)
        assertEquals("console.log('play asset');", bodyText(asset))
    }

    // ---------- nested entry point: unique entry directory in the subtree ----------

    @Test
    fun `unique entry directory in a multi branch subtree redirects to it`() {
        write("dist/index.html", "<html>dist entry</html>")
        write("assets/style.css", "body {}")
        startServer()
        val redirect = rawHttpRequest(port, "GET", "/")
        assertEquals(302, redirect.statusCode)
        assertEquals("/dist/index.html", redirect.headers["location"])
    }

    @Test
    fun `deeply nested unique entry directory redirects to it`() {
        write("a/b/c/index.html", "<html>deep entry</html>")
        startServer()
        val redirect = rawHttpRequest(port, "GET", "/")
        assertEquals(302, redirect.statusCode)
        assertEquals("/a/b/c/index.html", redirect.headers["location"])
    }

    @Test
    fun `ambiguous entry directories keep the listing`() {
        write("dist/index.html", "<html>dist</html>")
        write("docs/index.html", "<html>docs</html>")
        startServer()
        val response = rawHttpRequest(port, "GET", "/")
        assertEquals(200, response.statusCode)
        assertTrue(bodyText(response).contains("Index of"))
        assertTrue(bodyText(response).contains("dist/"))
        assertTrue(bodyText(response).contains("docs/"))
    }

    // ---------- listings preserved ----------

    @Test
    fun `empty directory keeps the listing`() {
        startServer()
        val response = rawHttpRequest(port, "GET", "/")
        assertEquals(200, response.statusCode)
        assertTrue(bodyText(response).contains("Index of"))
    }

    @Test
    fun `directory without entry candidates keeps the listing`() {
        write("assets/app.js", "console.log('a');")
        write("assets/style.css", "body {}")
        startServer()
        val response = rawHttpRequest(port, "GET", "/assets/")
        assertEquals(200, response.statusCode)
        assertTrue(bodyText(response).contains("app.js"))
        assertTrue(bodyText(response).contains("style.css"))
    }

    // ---------- trailing slash and query handling ----------

    @Test
    fun `directory without trailing slash redirects with slash`() {
        write("play/index.html", "<html>play</html>")
        startServer()
        val response = rawHttpRequest(port, "GET", "/play")
        assertEquals(301, response.statusCode)
        assertEquals("/play/", response.headers["location"])
    }

    @Test
    fun `trailing slash redirect preserves the query string`() {
        write("play/index.html", "<html>play</html>")
        startServer()
        val response = rawHttpRequest(port, "GET", "/play?x=1")
        assertEquals(301, response.statusCode)
        assertEquals("/play/?x=1", response.headers["location"])
    }

    @Test
    fun `entry redirect preserves the query string`() {
        write("game.html", "<html>game</html>")
        startServer()
        val response = rawHttpRequest(port, "GET", "/?a=1")
        assertEquals(302, response.statusCode)
        assertEquals("/game.html?a=1", response.headers["location"])
    }

    // ---------- web app manifest start_url ----------

    @Test
    fun `manifest webmanifest start url redirects to the entry point`() {
        write("manifest.webmanifest", """{"name":"game","start_url":"play/index.html"}""")
        write("play/index.html", "<html>play</html>")
        startServer()
        val redirect = rawHttpRequest(port, "GET", "/")
        assertEquals(302, redirect.statusCode)
        assertEquals("/play/index.html", redirect.headers["location"])
    }

    @Test
    fun `manifest json with relative dot slash start url redirects`() {
        write("manifest.json", """{"name":"game","start_url":"./game.html"}""")
        write("game.html", "<html>game</html>")
        startServer()
        val redirect = rawHttpRequest(port, "GET", "/")
        assertEquals(302, redirect.statusCode)
        assertEquals("/game.html", redirect.headers["location"])
    }

    @Test
    fun `manifest start url escaping the tree is ignored`() {
        write("manifest.webmanifest", """{"name":"game","start_url":"../outside.html"}""")
        startServer()
        val response = rawHttpRequest(port, "GET", "/")
        assertEquals(200, response.statusCode)
        assertTrue(bodyText(response).contains("Index of"))
    }

    @Test
    fun `manifest start url pointing at a missing document is ignored`() {
        write("manifest.webmanifest", """{"name":"game","start_url":"missing.html"}""")
        startServer()
        val response = rawHttpRequest(port, "GET", "/")
        assertEquals(200, response.statusCode)
        assertTrue(bodyText(response).contains("Index of"))
    }

    // ---------- url prefix ----------

    @Test
    fun `entry redirect preserves the configured url prefix`() {
        write("play/index.html", "<html>play</html>")
        startServer("/game")
        val redirect = rawHttpRequest(port, "GET", "/game/")
        assertEquals(302, redirect.statusCode)
        assertEquals("/game/play/index.html", redirect.headers["location"])
    }

    @Test
    fun `absolute manifest start url is confined to the url prefix`() {
        write("manifest.webmanifest", """{"name":"game","start_url":"http://127.0.0.1:8080/game/play/index.html"}""")
        write("play/index.html", "<html>play</html>")
        startServer("/game")
        val redirect = rawHttpRequest(port, "GET", "/game/")
        assertEquals(302, redirect.statusCode)
        assertEquals("/game/play/index.html", redirect.headers["location"])
    }

    @Test
    fun `absolute manifest start url outside the prefix is ignored`() {
        write("manifest.webmanifest", """{"name":"game","start_url":"http://127.0.0.1:8080/other/index.html"}""")
        startServer("/game")
        val response = rawHttpRequest(port, "GET", "/game/")
        assertEquals(200, response.statusCode)
        assertTrue(bodyText(response).contains("Index of"))
    }

    // ---------- HEAD ----------

    @Test
    fun `head request to an entry redirect returns headers without a body`() {
        write("play/index.html", "<html>play</html>")
        startServer()
        val response = rawHttpRequest(port, "HEAD", "/")
        assertEquals(302, response.statusCode)
        assertEquals("/play/index.html", response.headers["location"])
        assertTrue(response.body.isEmpty())
    }

    // ---------- manifest field extraction ----------

    @Test
    fun `extractJsonStringField parses start_url variants`() {
        val engine = HttpEngine(storeProvider = { null })
        assertEquals(
            "play/index.html",
            HttpEngine.extractJsonStringField("""{"start_url": "play/index.html"}""", "start_url")
        )
        assertEquals(
            "/x",
            HttpEngine.extractJsonStringField("""{ "start_url" : "/x" }""", "start_url")
        )
        assertEquals(
            "a b",
            HttpEngine.extractJsonStringField("""{"start_url":"a\\u0020b"}""", "start_url")
        )
        assertNull(HttpEngine.extractJsonStringField("""{"name":"x"}""", "start_url"))
        assertNull(HttpEngine.extractJsonStringField("""{"start_url": 42}""", "start_url"))
        assertNull(HttpEngine.extractJsonStringField("""{"start_url":"unterminated""", "start_url"))
        // A key occurrence that is not followed by a value is skipped.
        assertEquals(
            "second",
            HttpEngine.extractJsonStringField("""{"start_url": {"start_url": "second"}}""", "start_url")
        )
        engine // engine unused beyond type access; extraction is a companion helper
    }
}
