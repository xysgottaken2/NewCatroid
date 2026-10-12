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
import java.net.ServerSocket

/**
 * End-to-end regression tests for hosting arbitrarily deep nested directories
 * of a SAF-selected tree: the real [HttpServerEngine] serves the deep-tree
 * fixture ([SafTestEnvironment.registerDeep]) over loopback sockets through
 * [SafDocumentStore], exercising the complete pipeline (request parsing,
 * path normalization/decoding, SAF document resolution, streaming, listings,
 * ranges and error handling) at every depth.
 *
 * Modeled structure (see [SafDeepTreeTest] for the full tree):
 *
 * ```
 * Game/
 * |- index.html, root.js
 * |- assets/app.js
 * |- assets/nested/module.js
 * |- assets/nested/deep/config.json   (three directory levels deep)
 * |- data/config.json                 (second independent branch)
 * |- my folder/spaced file.js         (spaces)
 * `- ícones/häupt.js                  (Unicode)
 * ```
 */
@RunWith(RobolectricTestRunner::class)
class SafDeepHostingEndToEndTest {

    private lateinit var store: SafDocumentStore
    private var engine: HttpServerEngine? = null
    private var port = -1

    @Before
    fun setUp() {
        SafTestEnvironment.registerDeep()
        store = SafDocumentStore(RuntimeEnvironment.getApplication(), SafTestEnvironment.treeUri)
        port = freePort()
        val result = startEngine()
        assertTrue("server should start, got: $result", result is HttpServerEngine.Result.Success)
        port = (result as HttpServerEngine.Result.Success).port
    }

    @After
    fun tearDown() {
        engine?.stop()
        engine = null
    }

    private fun startEngine(): HttpServerEngine.Result {
        val newEngine = HttpServerEngine(storeProvider = { store }, urlPrefixProvider = { "/" })
        val result = newEngine.start(0)
        engine = newEngine
        return result
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun bodyText(response: RawHttpResponse): String = String(response.body, Charsets.UTF_8)

    // ---------- files at every depth ----------

    @Test
    fun `root level files are served`() {
        val index = rawHttpRequest(port, "GET", "/index.html")
        assertEquals(200, index.statusCode)
        assertEquals(
            String(FakeGameDocumentsProvider.INDEX_HTML, Charsets.UTF_8),
            bodyText(index)
        )

        val rootJs = rawHttpRequest(port, "GET", "/root.js")
        assertEquals(200, rootJs.statusCode)
        assertTrue(rootJs.headers["content-type"]!!.startsWith("text/javascript"))
        assertEquals(
            String(FakeGameDocumentsProvider.ROOT_JS, Charsets.UTF_8),
            bodyText(rootJs)
        )
    }

    @Test
    fun `first level subdirectory files are served`() {
        val appJs = rawHttpRequest(port, "GET", "/assets/app.js")
        assertEquals(200, appJs.statusCode)
        assertTrue(appJs.headers["content-type"]!!.startsWith("text/javascript"))
        assertEquals(String(FakeGameDocumentsProvider.APP_JS, Charsets.UTF_8), bodyText(appJs))

        val dataConfig = rawHttpRequest(port, "GET", "/data/config.json")
        assertEquals(200, dataConfig.statusCode)
        assertTrue(dataConfig.headers["content-type"]!!.startsWith("application/json"))
        assertEquals(
            String(FakeGameDocumentsProvider.DATA_CONFIG_JSON, Charsets.UTF_8),
            bodyText(dataConfig)
        )
    }

    @Test
    fun `files two directory levels deep are served`() {
        val module = rawHttpRequest(port, "GET", "/assets/nested/module.js")
        assertEquals(200, module.statusCode)
        assertTrue(module.headers["content-type"]!!.startsWith("text/javascript"))
        assertEquals(
            String(FakeGameDocumentsProvider.MODULE_JS, Charsets.UTF_8),
            bodyText(module)
        )
    }

    @Test
    fun `files three directory levels deep are served`() {
        val config = rawHttpRequest(port, "GET", "/assets/nested/deep/config.json")
        assertEquals(200, config.statusCode)
        assertTrue(config.headers["content-type"]!!.startsWith("application/json"))
        assertEquals(
            String(FakeGameDocumentsProvider.CONFIG_JSON, Charsets.UTF_8),
            bodyText(config)
        )
        assertEquals(
            FakeGameDocumentsProvider.CONFIG_JSON.size.toString(),
            config.headers["content-length"]
        )
    }

    @Test
    fun `nested branches work independently over http`() {
        val deepConfig = rawHttpRequest(port, "GET", "/assets/nested/deep/config.json")
        val dataConfig = rawHttpRequest(port, "GET", "/data/config.json")
        assertEquals(200, deepConfig.statusCode)
        assertEquals(200, dataConfig.statusCode)
        assertTrue(bodyText(deepConfig).contains("deep-config"))
        assertTrue(bodyText(dataConfig).contains("data-config"))
    }

    // ---------- listings at depth ----------

    @Test
    fun `directory listing at depth includes subdirectories and files`() {
        val assets = rawHttpRequest(port, "GET", "/assets/")
        assertEquals(200, assets.statusCode)
        assertTrue(bodyText(assets).contains("app.js"))
        assertTrue(bodyText(assets).contains("nested/"))

        val nested = rawHttpRequest(port, "GET", "/assets/nested/")
        assertEquals(200, nested.statusCode)
        assertTrue(bodyText(nested).contains("module.js"))
        assertTrue(bodyText(nested).contains("deep/"))

        val deep = rawHttpRequest(port, "GET", "/assets/nested/deep/")
        assertEquals(200, deep.statusCode)
        assertTrue(bodyText(deep).contains("config.json"))

        val data = rawHttpRequest(port, "GET", "/data/")
        assertEquals(200, data.statusCode)
        assertTrue(bodyText(data).contains("config.json"))
    }

    @Test
    fun `nested directory without trailing slash redirects`() {
        val response = rawHttpRequest(port, "GET", "/assets/nested")
        assertEquals(301, response.statusCode)
        assertEquals("/assets/nested/", response.headers["location"])
    }

    // ---------- spaces and unicode over http ----------

    @Test
    fun `url encoded spaces in nested paths are served`() {
        val response = rawHttpRequest(port, "GET", "/my%20folder/spaced%20file.js")
        assertEquals(200, response.statusCode)
        assertEquals(
            String(FakeGameDocumentsProvider.SPACED_JS, Charsets.UTF_8),
            bodyText(response)
        )
    }

    @Test
    fun `url encoded unicode nested paths are served`() {
        // í = U+00ED -> %C3%AD, ä = U+00E4 -> %C3%A4 (UTF-8 percent-encoding)
        val response = rawHttpRequest(port, "GET", "/%C3%ADcones/h%C3%A4upt.js")
        assertEquals(200, response.statusCode)
        assertEquals(
            String(FakeGameDocumentsProvider.UNICODE_JS, Charsets.UTF_8),
            bodyText(response)
        )
    }

    // ---------- errors ----------

    @Test
    fun `missing files at depth return 404`() {
        assertEquals(404, rawHttpRequest(port, "GET", "/missing.js").statusCode)
        assertEquals(404, rawHttpRequest(port, "GET", "/assets/missing.js").statusCode)
        assertEquals(404, rawHttpRequest(port, "GET", "/assets/nested/missing.js").statusCode)
        assertEquals(404, rawHttpRequest(port, "GET", "/assets/nested/deep/missing.json").statusCode)
    }

    @Test
    fun `traversal attempts escaping the root are rejected`() {
        assertEquals(403, rawHttpRequest(port, "GET", "/assets/../..").statusCode)
        assertEquals(403, rawHttpRequest(port, "GET", "/assets/%2e%2e/").statusCode)
        assertEquals(403, rawHttpRequest(port, "GET", "/assets/nested/../../../etc/passwd").statusCode)
        assertEquals(403, rawHttpRequest(port, "GET", "/%2e%2e/%2e%2e/").statusCode)
    }

    // ---------- HEAD, ranges, streaming ----------

    @Test
    fun `head request works at depth`() {
        val response = rawHttpRequest(port, "HEAD", "/assets/nested/deep/config.json")
        assertEquals(200, response.statusCode)
        assertEquals(
            FakeGameDocumentsProvider.CONFIG_JSON.size.toString(),
            response.headers["content-length"]
        )
        assertTrue(response.body.isEmpty())
    }

    @Test
    fun `range request works at depth`() {
        val content = FakeGameDocumentsProvider.CONFIG_JSON
        val response = rawHttpRequest(
            port, "GET", "/assets/nested/deep/config.json",
            mapOf("Range" to "bytes=0-9")
        )
        assertEquals(206, response.statusCode)
        assertEquals("bytes 0-9/${content.size}", response.headers["content-range"])
        assertEquals(10, response.body.size)
        assertTrue(content.copyOfRange(0, 10).contentEquals(response.body))
    }
}
