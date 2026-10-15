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

import org.catrobat.catroid.content.Scope
import org.catrobat.catroid.content.Sprite
import org.catrobat.catroid.content.actions.OpenWebPageAction
import org.catrobat.catroid.content.bricks.OpenWebPageBrick
import org.catrobat.catroid.content.localweb.EmbeddedWebViewManager
import org.catrobat.catroid.content.localweb.LocalHttpServer
import org.catrobat.catroid.formulaeditor.Formula
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.net.ServerSocket

/**
 * Regression tests for the reported device problem: the application opened
 * the hosted webpage with an IP-based URL (http://127.0.0.1:8080/) instead of
 * http://localhost:PORT/, even when the server was started with the localhost
 * block. Root cause: the "Open web page" block's default URL was hardcoded to
 * http://127.0.0.1:8080/ - the IP-based hostname was introduced there, not in
 * the server manager (whose baseUrl already follows the bind address).
 *
 * Fix: an empty URL in the "Open web page" block now resolves to the base URL
 * of the RUNNING server (http://localhost:PORT/ for the localhost block,
 * http://127.0.0.1:PORT/ for the original block). These tests verify the
 * actual URL that is generated and would be passed to the WebView, and that
 * the original server block keeps its original URL format.
 */
@RunWith(RobolectricTestRunner::class)
class OpenWebPageUrlFlowTest {

    private val scope: Scope = Scope(null, Sprite(), null)
    private lateinit var rootDir: File

    @Before
    fun setUp() {
        rootDir = java.nio.file.Files.createTempDirectory("open-web-page-test").toFile()
        File(rootDir, "index.html").writeText("<html>page</html>")
        LocalHttpServer.stop()
        LocalHttpServer.setDocumentRoot(null)
        LocalHttpServer.setUrlPrefix(null)
        LocalHttpServer.clearError()
    }

    @After
    fun tearDown() {
        LocalHttpServer.stop()
        LocalHttpServer.setDocumentRoot(null)
        LocalHttpServer.setUrlPrefix(null)
        LocalHttpServer.clearError()
        rootDir.deleteRecursively()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `open web page brick default url is empty`() {
        val brick = OpenWebPageBrick()
        assertEquals(
            "",
            brick.getFormulaWithBrickField(org.catrobat.catroid.content.bricks.Brick.BrickField.WEB_PAGE_URL)
                .interpretString(scope)
        )
    }

    @Test
    fun `explicit urls are used unchanged`() {
        val action = OpenWebPageAction()
        assertEquals("http://127.0.0.1:8080/game.html", action.resolvePageUrl("http://127.0.0.1:8080/game.html"))
        assertEquals("http://localhost:9090/", action.resolvePageUrl("  http://localhost:9090/  "))
    }

    @Test
    fun `empty url resolves to the running localhost server url`() {
        LocalHttpServer.setDocumentRoot(rootDir.absolutePath)
        val port = freePort()
        assertTrue(LocalHttpServer.start(port, "localhost"))
        try {
            val action = OpenWebPageAction()
            val resolved = action.resolvePageUrl("")
            assertEquals("http://localhost:$port/", resolved)
            // The URL the WebView would navigate to is an allowed origin.
            assertTrue(EmbeddedWebViewManager.isAllowedOrigin(resolved))
        } finally {
            LocalHttpServer.stop()
        }
    }

    @Test
    fun `empty url resolves to the running original server url`() {
        LocalHttpServer.setDocumentRoot(rootDir.absolutePath)
        val port = freePort()
        assertTrue(LocalHttpServer.start(port, "127.0.0.1"))
        try {
            val action = OpenWebPageAction()
            assertEquals("http://127.0.0.1:$port/", action.resolvePageUrl(""))
        } finally {
            LocalHttpServer.stop()
        }
    }

    @Test
    fun `empty url without a running server resolves to empty and is reported`() {
        val action = OpenWebPageAction()
        assertEquals("", action.resolvePageUrl(""))
        assertEquals("", action.resolvePageUrl("   "))
    }

    @Test
    fun `block to navigation flow uses the localhost url end to end`() {
        // Simulate the script flow: start server with the localhost bind
        // address (as the localhost block's action does), then resolve the
        // page URL the way the Open web page action does.
        LocalHttpServer.setDocumentRoot(rootDir.absolutePath)
        val port = freePort()
        assertTrue(LocalHttpServer.start(port, "localhost"))
        try {
            assertEquals("http://localhost:$port/", LocalHttpServer.baseUrl)
            val pageUrl = OpenWebPageAction().resolvePageUrl("")
            assertEquals(LocalHttpServer.baseUrl, pageUrl)
            assertEquals("http://localhost:$port/", pageUrl)
            assertTrue(EmbeddedWebViewManager.isAllowedOrigin(pageUrl))
            // The server is reachable through the generated localhost URL and
            // serves the hosted page (loopback only, not exposed to the LAN).
            val connection = java.net.URL(pageUrl).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            assertEquals(200, connection.responseCode)
            assertEquals("<html>page</html>", connection.inputStream.readBytes().toString(Charsets.UTF_8))
            connection.disconnect()
        } finally {
            LocalHttpServer.stop()
        }
    }

    @Test
    fun `open web page action url formula is optional in the brick model`() {
        // A brick built with an explicit URL keeps it (existing scripts and
        // explicit user input are unchanged).
        val brick = OpenWebPageBrick(Formula("http://127.0.0.1:8080/game.html"))
        assertEquals(
            "http://127.0.0.1:8080/game.html",
            brick.getFormulaWithBrickField(org.catrobat.catroid.content.bricks.Brick.BrickField.WEB_PAGE_URL)
                .interpretString(scope)
        )
    }
}
