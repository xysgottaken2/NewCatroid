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
import org.catrobat.catroid.content.localweb.HttpEngine
import org.catrobat.catroid.content.localweb.HttpServerEngine
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ShaderCompatibilityInjectionTest {
    private lateinit var root: File
    private lateinit var server: HttpServerEngine
    private var port = -1
    private val bootstrap = "self.alphaCompatibilityLoaded = true;"
    private val page = "<html><head></head><body></body></html>"

    @Before fun setUp() {
        root = java.nio.file.Files.createTempDirectory("shader-compatibility").toFile()
        File(root, "index.html").writeText(page)
        File(root, "loader.js").writeText("'use strict';\nself.onmessage = function () {};")
        File(root, "wgpu_worker.js").writeText("'use strict';\nself.unknownWorker = true;")
        File(root, "io_worker.js").writeText("self.io = true;")
        server = HttpServerEngine(
            storeProvider = { FileDocumentStore(root) },
            urlPrefixProvider = { "/game" },
            shaderCompatibilityScriptProvider = { bootstrap },
            workerDiagnosticsBootstrapProvider = { "self.diagnostics = true;" }
        )
        port = (server.start(0) as HttpServerEngine.Result.Success).port
    }

    @After fun tearDown() {
        server.stop()
        root.deleteRecursively()
    }

    @Test fun `exact opt-in injects page and loader independently of diagnostics`() {
        for (query in listOf("", "?newcatroid_alpha_fix=0", "?newcatroid_alpha_fix=true", "?newcatroid_adreno_fix=1")) {
            assertEquals(page, rawHttpRequest(port, "GET", "/game/index.html$query").bodyText())
        }
        val response = rawHttpRequest(port, "GET", "/game/index.html?newcatroid_alpha_fix=1")
        assertTrue(response.bodyText().contains("<head><script src=\"/game/__newcatroid_shader_compat.js\"></script>"))
        assertFalse(response.bodyText().contains("diagnostics"))
        assertEquals("no-store", response.headers["cache-control"])
        assertEquals(response.body.size.toString(), response.headers["content-length"])
        val loader = rawHttpRequest(port, "GET", "/game/loader.js?newcatroid_alpha_fix=1")
        assertTrue(loader.bodyText().startsWith("'use strict';"))
        assertTrue(loader.bodyText().endsWith("\n$bootstrap\n"))
        assertEquals("self.io = true;", rawHttpRequest(port, "GET", "/game/io_worker.js?newcatroid_alpha_fix=1").bodyText())
    }

    @Test fun `unknown renderer is never patched and script endpoint supports HEAD`() {
        val original = File(root, "wgpu_worker.js").readText()
        assertEquals(original, rawHttpRequest(port, "GET", "/game/wgpu_worker.js?newcatroid_alpha_fix=1").bodyText())
        val unknownWithDiagnostics = rawHttpRequest(
            port, "GET", "/game/wgpu_worker.js?newcatroid_alpha_fix=1&newcatroid_worker_debug=1"
        ).bodyText()
        assertFalse(unknownWithDiagnostics.contains(bootstrap))
        assertTrue(unknownWithDiagnostics.contains("expectedSha256:\"${HttpEngine.ALPHA_FIX_WORKER_SHA256}\""))
        assertTrue(unknownWithDiagnostics.contains("hashMatches:false,shimInjected:false"))
        assertTrue(unknownWithDiagnostics.endsWith("\nself.diagnostics = true;\n"))
        val response = rawHttpRequest(port, "GET", "/game/__newcatroid_shader_compat.js")
        assertEquals(bootstrap, response.bodyText())
        assertEquals("no-store", response.headers["cache-control"])
        val head = rawHttpRequest(port, "HEAD", "/game/__newcatroid_shader_compat.js")
        assertTrue(head.body.isEmpty())
        assertEquals(response.headers["content-length"], head.headers["content-length"])
    }

    @Test fun `reviewed real worker is patched only on request before diagnostic suffix`() {
        // CI supplies the pinned public JS snapshot; no game assets are vendored.
        val sourcePath = System.getenv("PLAYGTA5_WORKER_SOURCE")
        org.junit.Assume.assumeTrue("Set PLAYGTA5_WORKER_SOURCE for the pinned-client integration test", sourcePath != null)
        val original = File(sourcePath!!).readBytes()
        val originalSha256 = java.security.MessageDigest.getInstance("SHA-256").digest(original)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(HttpEngine.ALPHA_FIX_WORKER_SHA256, originalSha256)
        File(root, "wgpu_worker.js").writeBytes(original)
        val plain = rawHttpRequest(port, "GET", "/game/wgpu_worker.js")
        assertArrayEquals(original, plain.body)
        val patched = rawHttpRequest(port, "GET", "/game/wgpu_worker.js?newcatroid_alpha_fix=1&newcatroid_worker_debug=1")
        assertEquals(200, patched.statusCode)
        val verification = "self.__newcatroidAlphaFixServerVerification = " +
            "{requested:true,expectedSha256:\"${HttpEngine.ALPHA_FIX_WORKER_SHA256}\",actualSha256:\"$originalSha256\"," +
            "hashMatches:true,shimInjected:true};"
        assertEquals(String(original, Charsets.UTF_8) + "\n$bootstrap\n\n$verification\nself.diagnostics = true;\n", patched.bodyText())
        assertEquals(patched.body.size.toString(), patched.headers["content-length"])
        assertEquals("no-store", patched.headers["cache-control"])
        val head = rawHttpRequest(port, "HEAD", "/game/wgpu_worker.js?newcatroid_alpha_fix=1&newcatroid_worker_debug=1")
        assertTrue(head.body.isEmpty())
        assertEquals(patched.headers["content-length"], head.headers["content-length"])
        // One changed byte must prevent a version-dependent renderer hook while preserving hash diagnostics.
        File(root, "wgpu_worker.js").appendText("\n")
        val unknown = rawHttpRequest(port, "GET", "/game/wgpu_worker.js?newcatroid_alpha_fix=1&newcatroid_worker_debug=1")
        assertFalse(unknown.bodyText().contains(bootstrap))
        assertTrue(unknown.bodyText().contains("hashMatches:false,shimInjected:false"))
    }
}
