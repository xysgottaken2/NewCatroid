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

import android.content.Intent
import org.catrobat.catroid.content.actions.ShowHttpDiagnosticsAction
import org.catrobat.catroid.content.localweb.LocalDiagnostics
import org.catrobat.catroid.content.localweb.LocalHttpServer
import org.catrobat.catroid.ui.DiagnosticsActivity
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
import java.io.File
import java.net.ServerSocket

/**
 * Integration tests for the in-app diagnostics: the shared
 * [LocalDiagnostics.requestLog] is fed by the REAL server when it is started
 * through [LocalHttpServer] (the same path the blocks use), so the
 * diagnostics screen always reflects the actual running server - and the
 * "Show HTTP diagnostics" block action opens the diagnostics activity.
 */
@RunWith(RobolectricTestRunner::class)
class LocalDiagnosticsIntegrationTest {

    private lateinit var rootDir: File

    @Before
    fun setUp() {
        rootDir = java.nio.file.Files.createTempDirectory("diagnostics-test").toFile()
        File(rootDir, "index.html").writeText("<html>diag</html>")
        File(rootDir, "app.js").writeText("console.log('diag');")
        LocalHttpServer.stop()
        LocalHttpServer.setDocumentRoot(null)
        LocalHttpServer.setUrlPrefix(null)
        LocalHttpServer.clearError()
        LocalDiagnostics.clear()
    }

    @After
    fun tearDown() {
        LocalHttpServer.stop()
        LocalHttpServer.setDocumentRoot(null)
        LocalHttpServer.setUrlPrefix(null)
        LocalHttpServer.clearError()
        LocalDiagnostics.clear()
        rootDir.deleteRecursively()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `the shared request log is fed by the real running server`() {
        LocalHttpServer.setDocumentRoot(rootDir.absolutePath)
        val port = freePort()
        assertTrue(LocalHttpServer.start(port, "localhost"))
        try {
            val ok = rawHttpRequest(port, "GET", "/app.js")
            assertEquals(200, ok.statusCode)
            rawHttpRequest(port, "GET", "/missing.js")

            val entries = LocalDiagnostics.requestLog.snapshot()
            assertEquals(2, entries.size)
            assertEquals("/app.js", entries[0].path)
            assertEquals(200, entries[0].status)
            assertEquals("text/javascript; charset=utf-8", entries[0].mimeType)
            assertEquals("console.log('diag');".length.toLong(), entries[0].bytesSent)
            assertEquals(404, entries[1].status)
            assertTrue(entries[1].detail!!.contains("document not resolved"))
        } finally {
            LocalHttpServer.stop()
        }
    }

    @Test
    fun `format for copy contains server state and request details`() {
        LocalHttpServer.setDocumentRoot(rootDir.absolutePath)
        val port = freePort()
        assertTrue(LocalHttpServer.start(port, "localhost"))
        try {
            rawHttpRequest(port, "GET", "/index.html")
            val text = LocalDiagnostics.formatForCopy()
            assertTrue(text.contains("Server running: true"))
            assertTrue(text.contains("http://localhost:$port/"))
            assertTrue(text.contains("GET /index.html -> 200"))
            assertTrue(text.contains("text/html"))
            assertTrue(text.contains("WebView events"))
        } finally {
            LocalHttpServer.stop()
        }
    }

    @Test
    fun `clear empties the shared logs`() {
        LocalHttpServer.setDocumentRoot(rootDir.absolutePath)
        val port = freePort()
        assertTrue(LocalHttpServer.start(port))
        try {
            rawHttpRequest(port, "GET", "/index.html")
            assertTrue(LocalDiagnostics.requestLog.snapshot().isNotEmpty())
            LocalDiagnostics.clear()
            assertTrue(LocalDiagnostics.requestLog.snapshot().isEmpty())
        } finally {
            LocalHttpServer.stop()
        }
    }

    @Test
    fun `show http diagnostics action opens the diagnostics activity`() {
        val action = ShowHttpDiagnosticsAction()
        action.act(0f)
        val intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertNotNull("the action must start an activity", intent)
        assertEquals(
            DiagnosticsActivity::class.java.name,
            intent!!.component?.className
        )
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
}
