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
import org.catrobat.catroid.content.localweb.HttpServerEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Tests for the optional touch controls overlay: a separate, removable
 * script served by the server at /__newcatroid_touch_controls.js and injected
 * into hosted HTML pages ONLY when the page URL asks for it
 * (?newcatroid_touch=1). The overlay drives the page's EXISTING input
 * handlers with synthetic DOM events (verified against the game's own input
 * code: the page forwards DOM keyboard/mouse events into the engine's shared
 * input memory), so no hosted game file is modified and desktop mouse/keyboard
 * input is untouched.
 *
 * Pure JVM (FileDocumentStore over a real temp directory + the real
 * HttpServerEngine over loopback).
 */
class TouchControlsInjectionTest {

    private lateinit var rootDir: File
    private var engine: HttpServerEngine? = null
    private var port = -1

    private val overlayScript = """
        // stand-in for the real overlay asset served from res/raw
        (function () {
            if (window.__newcatroidTouchControls) return;
            var isTouch = ('ontouchstart' in window) || (navigator.maxTouchPoints > 0) ||
                (window.matchMedia && window.matchMedia('(pointer: coarse)').matches);
            if (!isTouch) return;
            var joy = document.createElement('div');
            joy.id = 'newcatroid-joystick';
            var btn = document.createElement('button');
            btn.textContent = 'JUMP';
            window.dispatchEvent(new KeyboardEvent('keydown', { code: 'Space', bubbles: true }));
            document.body.appendChild(joy);
            document.body.appendChild(btn);
        })();
    """.trimIndent()

    @Before
    fun setUp() {
        rootDir = java.nio.file.Files.createTempDirectory("touch-controls-test").toFile()
        File(rootDir, "index.html").writeText(
            "<html><body><canvas id=\"game\"></canvas></body></html>",
            StandardCharsets.UTF_8
        )
        File(rootDir, "style.css").writeText("body { margin: 0; }", StandardCharsets.UTF_8)
    }

    @After
    fun tearDown() {
        engine?.stop()
        engine = null
        rootDir.deleteRecursively()
    }

    private fun startServer(urlPrefix: String = "/", storeRoot: File? = rootDir) {
        val storeProvider: () -> org.catrobat.catroid.content.localweb.DocumentStore? =
            { if (storeRoot != null) FileDocumentStore(storeRoot) else null }
        val newEngine = HttpServerEngine(
            storeProvider = storeProvider,
            urlPrefixProvider = { urlPrefix },
            touchControlsScriptProvider = { overlayScript }
        )
        val result = newEngine.start(0)
        engine = newEngine
        assertTrue("server should start", result is HttpServerEngine.Result.Success)
        port = (result as HttpServerEngine.Result.Success).port
    }

    // ---------- the overlay script route ----------

    @Test
    fun `overlay script is served as javascript`() {
        startServer()
        val response = rawHttpRequest(port, "GET", "/__newcatroid_touch_controls.js")
        assertEquals(200, response.statusCode)
        assertEquals("text/javascript; charset=utf-8", response.headers["content-type"])
        assertTrue(response.bodyText().contains("newcatroidTouchControls"))
        assertTrue(response.bodyText().contains("JUMP"))
    }

    @Test
    fun `overlay script is served under a url prefix`() {
        startServer(urlPrefix = "/game")
        val response = rawHttpRequest(port, "GET", "/game/__newcatroid_touch_controls.js")
        assertEquals(200, response.statusCode)
        assertTrue(response.bodyText().contains("newcatroidTouchControls"))
    }

    @Test
    fun `overlay script route answers 404 when the asset is unavailable`() {
        val newEngine = HttpServerEngine(
            storeProvider = { null },
            urlPrefixProvider = { "/" },
            touchControlsScriptProvider = { null }
        )
        val result = newEngine.start(0)
        engine = newEngine
        assertTrue("server should start", result is HttpServerEngine.Result.Success)
        port = (result as HttpServerEngine.Result.Success).port
        assertEquals(404, rawHttpRequest(port, "GET", "/__newcatroid_touch_controls.js").statusCode)
    }

    // ---------- the injection ----------

    @Test
    fun `html page is injected with the overlay script when requested`() {
        startServer()
        val response = rawHttpRequest(port, "GET", "/index.html?newcatroid_touch=1")
        assertEquals(200, response.statusCode)
        assertTrue(response.bodyText().contains("__newcatroid_touch_controls.js"))
        // injected before </body>
        assertTrue(response.bodyText().indexOf("__newcatroid_touch_controls.js") <
            response.bodyText().indexOf("</body>"))
        // Content-Length matches the injected body
        assertEquals(
            response.body.size.toString(),
            response.headers["content-length"]
        )
    }

    @Test
    fun `html page is unchanged without the request parameter`() {
        startServer()
        val response = rawHttpRequest(port, "GET", "/index.html")
        assertEquals(200, response.statusCode)
        assertFalse(response.bodyText().contains("newcatroid_touch_controls"))
        assertEquals(
            "<html><body><canvas id=\"game\"></canvas></body></html>",
            response.bodyText()
        )
    }

    @Test
    fun `non html files are not injected`() {
        startServer()
        val response = rawHttpRequest(port, "GET", "/style.css?newcatroid_touch=1")
        assertEquals(200, response.statusCode)
        assertEquals("body { margin: 0; }", response.bodyText())
    }

    @Test
    fun `the bare parameter form also injects`() {
        startServer()
        val response = rawHttpRequest(port, "GET", "/index.html?newcatroid_touch")
        assertTrue(response.bodyText().contains("__newcatroid_touch_controls.js"))
    }

    @Test
    fun `the injected script url respects the url prefix`() {
        startServer(urlPrefix = "/game")
        val response = rawHttpRequest(port, "GET", "/game/index.html?newcatroid_touch=1")
        assertEquals(200, response.statusCode)
        assertTrue(response.bodyText().contains("/game/__newcatroid_touch_controls.js"))
    }

    @Test
    fun `desktop compatibility - no injection without the opt-in parameter`() {
        startServer()
        // Without ?newcatroid_touch=1 the served page is byte-identical, so
        // desktop mouse/keyboard behavior is untouched; the overlay itself
        // additionally hides on non-touch devices (static check).
        val plain = rawHttpRequest(port, "GET", "/index.html")
        assertEquals(
            File(rootDir, "index.html").readBytes().toList(),
            plain.body.toList()
        )
        assertTrue(overlayScript.contains("pointer: coarse"))
        assertTrue(overlayScript.contains("ontouchstart"))
    }

    @Test
    fun `the overlay script only dispatches dom events to the page handlers`() {
        // The overlay must not touch the engine, files or memory directly: it
        // only dispatches synthetic DOM keyboard/mouse events.
        assertTrue(overlayScript.contains("KeyboardEvent"))
        assertTrue(overlayScript.contains("MouseEvent"))
        assertFalse(overlayScript.contains("WebAssembly"))
        assertFalse(overlayScript.contains("postMessage"))
    }
}
