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

import org.catrobat.catroid.content.localweb.FileDocumentStore
import org.catrobat.catroid.content.localweb.HttpServerEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets

/** Pure-JVM coverage for the two independent, removable game URL opt-ins. */
class GameDiagnosticsInjectionTest {

    private lateinit var rootDir: File
    private var engine: HttpServerEngine? = null
    private var port = -1

    private val gameScript = """
        (function () {
            if (new URLSearchParams(location.search).get('newcatroid_debug') === '1') {
                window.__newcatroidGameDiagnostics = true;
            }
        })();
    """.trimIndent()

    private val workerBootstrap = "self.__newcatroidWorkerDiagnostics = true;"
    private val touchScript = "window.__newcatroidTouchControls = true;"

    @Before
    fun setUp() {
        rootDir = java.nio.file.Files.createTempDirectory("game-diagnostics-test").toFile()
        File(rootDir, "index.html").writeText(
            "<!doctype html><html><head><title>test</title></head><body><canvas id=\"game\"></canvas></body></html>",
            StandardCharsets.UTF_8
        )
        File(rootDir, "loader.js").writeText("self.loaderStarted = true;", StandardCharsets.UTF_8)
        File(rootDir, "wgpu_worker.js").writeText("self.gpuWorkerStarted = true;", StandardCharsets.UTF_8)
        File(rootDir, "io_worker.js").writeText("self.ioWorkerStarted = true;", StandardCharsets.UTF_8)
        File(rootDir, "other.js").writeText("self.otherStarted = true;", StandardCharsets.UTF_8)
        File(rootDir, "style.css").writeText("body { margin: 0; }", StandardCharsets.UTF_8)
    }

    @After
    fun tearDown() {
        engine?.stop()
        engine = null
        rootDir.deleteRecursively()
    }

    private fun startServer(urlPrefix: String = "/", withAssets: Boolean = true) {
        val storeProvider: () -> org.catrobat.catroid.content.localweb.DocumentStore? =
            { FileDocumentStore(rootDir) }
        val newEngine = HttpServerEngine(
            storeProvider = storeProvider,
            urlPrefixProvider = { urlPrefix },
            touchControlsScriptProvider = { touchScript },
            gameDiagnosticsScriptProvider = { if (withAssets) gameScript else null },
            workerDiagnosticsBootstrapProvider = { if (withAssets) workerBootstrap else null }
        )
        val result = newEngine.start(0)
        engine = newEngine
        assertTrue("server should start", result is HttpServerEngine.Result.Success)
        port = (result as HttpServerEngine.Result.Success).port
    }

    @Test
    fun `page diagnostics resource is a private no-store javascript route`() {
        startServer()
        val pageScript = rawHttpRequest(port, "GET", "/__newcatroid_game_diagnostics.js")
        assertEquals(200, pageScript.statusCode)
        assertEquals("text/javascript; charset=utf-8", pageScript.headers["content-type"])
        assertEquals("no-store", pageScript.headers["cache-control"])
        assertEquals(gameScript, pageScript.bodyText())

        val head = rawHttpRequest(port, "HEAD", "/__newcatroid_game_diagnostics.js")
        assertEquals(200, head.statusCode)
        assertEquals(gameScript.toByteArray(StandardCharsets.UTF_8).size.toString(), head.headers["content-length"])
        assertTrue(head.body.isEmpty())
    }

    @Test
    fun `debug and experimental graphics parameters independently inject only the early bootstrap`() {
        startServer()
        val expectedPage = "<head><script src=\"/__newcatroid_game_diagnostics.js\"></script>"

        val debug = rawHttpRequest(port, "GET", "/index.html?newcatroid_debug=1")
        assertEquals(200, debug.statusCode)
        assertTrue(debug.bodyText().contains(expectedPage))
        assertTrue(debug.bodyText().contains("</body>"))
        assertFalse(debug.bodyText().contains("newcatroid_touch_controls"))
        assertEquals(debug.body.size.toString(), debug.headers["content-length"])

        val graphics = rawHttpRequest(port, "GET", "/index.html?newcatroid_adreno_fix=1")
        assertEquals(200, graphics.statusCode)
        assertTrue(graphics.bodyText().contains(expectedPage))
        assertFalse(graphics.bodyText().contains("newcatroid_touch_controls"))

        val both = rawHttpRequest(port, "GET", "/index.html?newcatroid_debug=1&newcatroid_adreno_fix=1")
        assertEquals(1, Regex("__newcatroid_game_diagnostics.js").findAll(both.bodyText()).count())
    }

    @Test
    fun `diagnostics script route and injected source respect the hosted url prefix`() {
        startServer(urlPrefix = "/game")
        val page = rawHttpRequest(port, "GET", "/game/index.html?newcatroid_debug=1")
        assertEquals(200, page.statusCode)
        assertTrue(page.bodyText().contains("/game/__newcatroid_game_diagnostics.js"))
        val script = rawHttpRequest(port, "GET", "/game/__newcatroid_game_diagnostics.js")
        assertEquals(200, script.statusCode)
        assertEquals(gameScript, script.bodyText())
    }

    @Test
    fun `no parameter or an inexact value leaves the HTML byte-for-byte unchanged`() {
        startServer()
        val original = File(rootDir, "index.html").readBytes().toList()
        listOf(
            "/index.html",
            "/index.html?newcatroid_debug=0",
            "/index.html?newcatroid_debug=true",
            "/index.html?newcatroid_adreno_fix=0",
            "/index.html?newcatroid_adreno_fix=true"
        ).forEach { path ->
            val response = rawHttpRequest(port, "GET", path)
            assertEquals(path, 200, response.statusCode)
            assertEquals(path, original, response.body.toList())
        }

        val nonHtml = rawHttpRequest(port, "GET", "/style.css?newcatroid_debug=1&newcatroid_adreno_fix=1")
        assertEquals("body { margin: 0; }", nonHtml.bodyText())
    }

    @Test
    fun `worker bootstrap is appended only to known workers with the internal marker`() {
        startServer()
        val plain = rawHttpRequest(port, "GET", "/loader.js")
        assertEquals("self.loaderStarted = true;", plain.bodyText())

        listOf("loader.js", "wgpu_worker.js", "io_worker.js").forEach { name ->
            val response = rawHttpRequest(port, "GET", "/$name?newcatroid_worker_debug=1")
            assertEquals(200, response.statusCode)
            assertEquals("no-store", response.headers["cache-control"])
            assertEquals("${File(rootDir, name).readText()}\n$workerBootstrap\n", response.bodyText())
        }

        val other = rawHttpRequest(port, "GET", "/other.js?newcatroid_worker_debug=1")
        assertEquals("self.otherStarted = true;", other.bodyText())
        val unmarked = rawHttpRequest(port, "GET", "/wgpu_worker.js?newcatroid_worker_debug=0")
        assertEquals(File(rootDir, "wgpu_worker.js").readText(), unmarked.bodyText())

        val pipelineProbe = rawHttpRequest(port, "GET", "/loader.js?newcatroid_pipeline_probe=1")
        assertEquals("${File(rootDir, "loader.js").readText()}\n$workerBootstrap\n", pipelineProbe.bodyText())
        val pipelineProbeOnGpuWorker = rawHttpRequest(port, "GET", "/wgpu_worker.js?newcatroid_pipeline_probe=1")
        assertEquals(File(rootDir, "wgpu_worker.js").readText(), pipelineProbeOnGpuWorker.bodyText())
    }

    @Test
    fun `diagnostics and graphics modes do not implicitly enable touch controls`() {
        startServer()
        val touchOnly = rawHttpRequest(port, "GET", "/index.html?newcatroid_touch=1")
        assertFalse(touchOnly.bodyText().contains("__newcatroid_game_diagnostics.js"))
        assertTrue(touchOnly.bodyText().contains("__newcatroid_touch_controls.js"))

        val debugOnly = rawHttpRequest(port, "GET", "/index.html?newcatroid_debug=1")
        val graphicsOnly = rawHttpRequest(port, "GET", "/index.html?newcatroid_adreno_fix=1")
        assertFalse(debugOnly.bodyText().contains("__newcatroid_touch_controls.js"))
        assertFalse(graphicsOnly.bodyText().contains("__newcatroid_touch_controls.js"))
    }

    @Test
    fun `built-in diagnostics route is missing when its asset provider is unavailable`() {
        startServer(withAssets = false)
        assertEquals(404, rawHttpRequest(port, "GET", "/__newcatroid_game_diagnostics.js").statusCode)
        val unchanged = rawHttpRequest(port, "GET", "/index.html?newcatroid_debug=1")
        assertEquals(File(rootDir, "index.html").readBytes().toList(), unchanged.body.toList())
    }
}
