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

import org.catrobat.catroid.content.localweb.LocalHttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

/**
 * Tests for the application-wide [LocalHttpServer] manager: document root
 * configuration, lifecycle, observable state and error reporting, using a
 * plain filesystem directory as the hosted root.
 */
@RunWith(RobolectricTestRunner::class)
class LocalHttpServerManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var rootDir: File

    @Before
    fun setUp() {
        rootDir = tempFolder.newFolder("hosted")
        File(rootDir, "page.html").writeText("<html>managed</html>")
        LocalHttpServer.stop()
        LocalHttpServer.setDocumentRoot(null)
        LocalHttpServer.setUrlPrefix(null)
    }

    @After
    fun tearDown() {
        LocalHttpServer.stop()
        LocalHttpServer.setDocumentRoot(null)
        LocalHttpServer.setUrlPrefix(null)
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun httpGet(url: String): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        val code = connection.responseCode
        val body = if (code < 400) {
            connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } else {
            connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        }
        connection.disconnect()
        return code to body
    }

    @Test
    fun `manager is stopped initially`() {
        assertFalse(LocalHttpServer.isRunning)
        assertEquals("", LocalHttpServer.baseUrl)
        assertEquals(-1, LocalHttpServer.port)
    }

    @Test
    fun `start with a hosted directory serves files and updates state`() {
        LocalHttpServer.setDocumentRoot(rootDir.absolutePath)
        assertEquals(rootDir.absolutePath, LocalHttpServer.documentRoot)

        val port = freePort()
        assertTrue(LocalHttpServer.start(port))
        assertTrue(LocalHttpServer.isRunning)
        assertEquals(port, LocalHttpServer.port)
        assertEquals("http://127.0.0.1:$port/", LocalHttpServer.baseUrl)
        assertNull(LocalHttpServer.lastError)

        val (code, body) = httpGet("http://127.0.0.1:$port/page.html")
        assertEquals(200, code)
        assertEquals("<html>managed</html>", body)
    }

    @Test
    fun `stop shuts the server down and is idempotent`() {
        LocalHttpServer.setDocumentRoot(rootDir.absolutePath)
        val port = freePort()
        assertTrue(LocalHttpServer.start(port))

        LocalHttpServer.stop()
        assertFalse(LocalHttpServer.isRunning)
        assertEquals("", LocalHttpServer.baseUrl)

        LocalHttpServer.stop()
        LocalHttpServer.stop()
        assertFalse(LocalHttpServer.isRunning)
    }

    @Test
    fun `repeated start on the same endpoint is idempotent`() {
        LocalHttpServer.setDocumentRoot(rootDir.absolutePath)
        val port = freePort()
        assertTrue(LocalHttpServer.start(port))
        assertTrue(LocalHttpServer.start(port))
        assertTrue(LocalHttpServer.isRunning)
        assertEquals(port, LocalHttpServer.port)
    }

    @Test
    fun `start on an occupied port reports an error and keeps state observable`() {
        LocalHttpServer.setDocumentRoot(rootDir.absolutePath)
        val port = freePort()
        assertTrue(LocalHttpServer.start(port))

        // Occupy the port from "outside" to simulate another app.
        ServerSocket(port).use { blocker ->
            LocalHttpServer.stop()
            val success = LocalHttpServer.start(port)
            assertFalse(success)
            assertFalse(LocalHttpServer.isRunning)
            assertNotNull(LocalHttpServer.lastError)
            assertTrue(LocalHttpServer.lastError!!.contains("bind", ignoreCase = true))
        }
    }

    @Test
    fun `invalid ports are rejected with an error`() {
        assertFalse(LocalHttpServer.start(0))
        assertFalse(LocalHttpServer.start(-5))
        assertFalse(LocalHttpServer.start(70000))
        assertNotNull(LocalHttpServer.lastError)
        assertTrue(LocalHttpServer.lastError!!.contains("port", ignoreCase = true))
        assertFalse(LocalHttpServer.isRunning)
    }

    @Test
    fun `unsupported root scheme is reported as an error`() {
        LocalHttpServer.setDocumentRoot("ftp://example.com/files")
        assertNotNull(LocalHttpServer.lastError)
        assertTrue(LocalHttpServer.lastError!!.contains("scheme", ignoreCase = true))
    }

    @Test
    fun `clearing the document root makes requests answer 404`() {
        LocalHttpServer.setDocumentRoot(rootDir.absolutePath)
        val port = freePort()
        assertTrue(LocalHttpServer.start(port))
        LocalHttpServer.setDocumentRoot(null)
        assertEquals("", LocalHttpServer.documentRoot)

        val (code, _) = httpGet("http://127.0.0.1:$port/page.html")
        assertEquals(404, code)
    }

    @Test
    fun `url prefix is applied`() {
        LocalHttpServer.setDocumentRoot(rootDir.absolutePath)
        LocalHttpServer.setUrlPrefix("/mysite")
        assertEquals("/mysite", LocalHttpServer.currentUrlPrefix)

        val port = freePort()
        assertTrue(LocalHttpServer.start(port))

        val (code, body) = httpGet("http://127.0.0.1:$port/mysite/page.html")
        assertEquals(200, code)
        assertEquals("<html>managed</html>", body)

        val (codeOutside, _) = httpGet("http://127.0.0.1:$port/page.html")
        assertEquals(404, codeOutside)
    }

    @Test
    fun `startAsync completes and reports success`() {
        LocalHttpServer.setDocumentRoot(rootDir.absolutePath)
        val port = freePort()
        val latch = java.util.concurrent.CountDownLatch(1)
        var callbackResult: Boolean? = null
        LocalHttpServer.startAsync(port) { success ->
            callbackResult = success
            latch.countDown()
        }
        assertTrue(latch.await(15, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(true, callbackResult)
        assertTrue(LocalHttpServer.isRunning)
    }
}
