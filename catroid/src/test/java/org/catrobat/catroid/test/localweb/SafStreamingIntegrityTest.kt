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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.function.Supplier

/**
 * Streaming-integrity tests through the actual SAF file-serving pipeline:
 * the real [HttpServerEngine] serves binary fixtures (WASM, PNG, JSON, JS,
 * large binary) stored at multiple directory levels of a SAF-selected tree,
 * resolved and opened through [SafDocumentStore] and
 * [FakeFileSystemDocumentsProvider] (real on-disk files, real
 * ExternalStorageProvider semantics). Also covers a provider-side stream
 * that fails partway through reading and an opaque-id provider.
 *
 * Reported problem context: the hosted application got stuck on an infinite
 * loading screen - consistent with corrupted or truncated resource delivery
 * (a script/WASM/texture delivered as error text or truncated bytes). These
 * tests pin the contract: binary resources are delivered byte-for-byte, and
 * a failing stream aborts the response cleanly instead of corrupting it.
 *
 * Simulated (Robolectric) - physical-device verification steps are documented
 * in docs/LOCAL_HTTP_SERVER_BLOCKS.md.
 */
@RunWith(RobolectricTestRunner::class)
class SafStreamingIntegrityTest {

    private lateinit var storageRoot: File
    private lateinit var store: SafDocumentStore
    private var engine: HttpServerEngine? = null
    private var port = -1
    private val logLines = mutableListOf<String>()
    private val logLock = Any()
    private val treeUri: Uri
        get() = Uri.parse(FakeFileSystemDocumentsProvider.treeUriFor("Game"))

    private val wasmBytes = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00) +
        ByteArray(4096) { (it % 253).toByte() }
    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4) +
        ByteArray(2000) { (it * 13 % 256).toByte() }
    private val jsonBytes = """{"name":"game","levels":[1,2,3],"binary":"\u0000\u00ff"}"""
        .toByteArray(StandardCharsets.UTF_8)
    private val jsBytes = "import * as m from './module.js'; console.log(m);".toByteArray(StandardCharsets.UTF_8)
    private val bigBytes = ByteArray(300_000) { (it % 251).toByte() }
    private val failingBytes = ByteArray(5 * 1024 * 1024) { (it % 241).toByte() }

    @Before
    fun setUp() {
        storageRoot = java.nio.file.Files.createTempDirectory("saf-stream-test").toFile()
        val game = File(storageRoot, "Game")
        write(game, "index.html", "<html><body>game</body></html>")
        write(game, "assets/game.wasm", wasmBytes)
        write(game, "assets/textures/texture.png", pngBytes)
        write(game, "data/config.json", jsonBytes)
        write(game, "scripts/app.js", jsBytes)
        write(game, "assets/big.bin", bigBytes)
        write(game, "assets/fail.bin", failingBytes)
    }

    @After
    fun tearDown() {
        engine?.stop()
        engine = null
        storageRoot.deleteRecursively()
    }

    private fun write(game: File, relativePath: String, content: ByteArray) {
        val file = File(game, relativePath)
        file.parentFile?.mkdirs()
        file.writeBytes(content)
    }

    private fun write(game: File, relativePath: String, content: String) =
        write(game, relativePath, content.toByteArray(StandardCharsets.UTF_8))

    private fun startServer(
        streamOverride: (String, ByteArray) -> InputStream? = { _, content -> null }
    ) {
        SafTestEnvironment.registerProvider(
            FakeFileSystemDocumentsProvider.AUTHORITY,
            FakeFileSystemDocumentsProvider(storageRoot, "primary:Game")
        )
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        File(storageRoot, "Game").walkTopDown().filter { it.isFile }.forEach { file ->
            val docId = "primary:Game/" + file.relativeTo(File(storageRoot, "Game")).invariantSeparatorsPath
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            val content = file.readBytes()
            val override = streamOverride(docId, content)
            shadowOf(resolver).registerInputStreamSupplier(
                uri,
                Supplier { override ?: ByteArrayInputStream(content) }
            )
        }
        store = SafDocumentStore(RuntimeEnvironment.getApplication(), treeUri)
        val newEngine = HttpServerEngine(
            storeProvider = { store },
            urlPrefixProvider = { "/" },
            logger = { level, message, _ ->
                synchronized(logLock) { logLines.add("$level $message") }
            }
        )
        val result = newEngine.start(0)
        engine = newEngine
        assertTrue("server should start", result is HttpServerEngine.Result.Success)
        port = (result as HttpServerEngine.Result.Success).port
    }

    private fun loggedLines(): List<String> = synchronized(logLock) { logLines.toList() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `binary fixtures at multiple depths are served byte for byte through saf`() {
        startServer()

        val wasm = rawHttpRequest(port, "GET", "/assets/game.wasm")
        assertEquals(200, wasm.statusCode)
        assertEquals("application/wasm", wasm.headers["content-type"])
        assertTrue(wasmBytes.contentEquals(wasm.body))
        assertEquals(sha256(wasmBytes), sha256(wasm.body))

        val png = rawHttpRequest(port, "GET", "/assets/textures/texture.png")
        assertEquals(200, png.statusCode)
        assertEquals("image/png", png.headers["content-type"])
        assertTrue(pngBytes.contentEquals(png.body))

        val json = rawHttpRequest(port, "GET", "/data/config.json")
        assertEquals(200, json.statusCode)
        assertTrue(jsonBytes.contentEquals(json.body))

        val js = rawHttpRequest(port, "GET", "/scripts/app.js")
        assertEquals(200, js.statusCode)
        assertTrue(jsBytes.contentEquals(js.body))

        val big = rawHttpRequest(port, "GET", "/assets/big.bin")
        assertEquals(200, big.statusCode)
        assertEquals(bigBytes.size.toString(), big.headers["content-length"])
        assertTrue(bigBytes.contentEquals(big.body))
        assertEquals(sha256(bigBytes), sha256(big.body))
    }

    @Test
    fun `range requests through saf return exact byte slices at depth`() {
        startServer()
        val response = rawHttpRequest(port, "GET", "/assets/big.bin", mapOf("Range" to "bytes=100-199"))
        assertEquals(206, response.statusCode)
        assertEquals("bytes 100-199/${bigBytes.size}", response.headers["content-range"])
        assertTrue(bigBytes.copyOfRange(100, 200).contentEquals(response.body))
    }

    @Test
    fun `a saf stream failing mid read aborts the response without corrupting it`() {
        // The provider side delivers only half of the document, then the
        // stream ends early - the engine must abort the started response
        // instead of appending an error response to the truncated body.
        startServer { docId, content ->
            if (docId.endsWith("fail.bin")) {
                object : InputStream() {
                    var delivered = 0
                    val limit = content.size / 2
                    override fun read(): Int = if (delivered < limit) content[delivered++].toInt() else -1
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (delivered >= limit) return -1
                        val n = minOf(len, limit - delivered)
                        System.arraycopy(content, delivered, b, off, n)
                        delivered += n
                        return n
                    }
                }
            } else null
        }

        val raw = rawHttpRequestBytes(port, "GET", "/assets/fail.bin")
        val text = String(raw, StandardCharsets.UTF_8)
        assertTrue(text.startsWith("HTTP/1.1 200"))
        assertFalse("no second response may be appended", text.contains("HTTP/1.1 500"))
        assertFalse("no error text may be appended", text.contains("Internal Server Error"))
        val bodyStart = text.indexOf("\r\n\r\n") + 4
        val body = raw.copyOfRange(bodyStart, raw.size)
        assertEquals(failingBytes.size / 2, body.size)
        assertTrue(failingBytes.copyOfRange(0, failingBytes.size / 2).contentEquals(body))
        assertTrue(loggedLines().any { it.contains("ERROR") && it.contains("aborted mid-stream") })
    }

    @Test
    fun `an opaque id provider serves deep binary files byte for byte`() {
        val opaque = FakeOpaqueIdDocumentsProvider()
        SafTestEnvironment.registerProvider(FakeOpaqueIdDocumentsProvider.AUTHORITY, opaque)
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val opaqueTreeUri = Uri.parse(FakeOpaqueIdDocumentsProvider.TREE_URI)
        for ((documentId, content) in FakeOpaqueIdDocumentsProvider.allFiles) {
            val uri = DocumentsContract.buildDocumentUriUsingTree(opaqueTreeUri, documentId)
            shadowOf(resolver).registerInputStreamSupplier(uri, Supplier { ByteArrayInputStream(content) })
        }
        val opaqueStore = SafDocumentStore(RuntimeEnvironment.getApplication(), opaqueTreeUri)
        val newEngine = HttpServerEngine(storeProvider = { opaqueStore }, urlPrefixProvider = { "/" })
        val result = newEngine.start(0)
        engine = newEngine
        assertTrue("server should start", result is HttpServerEngine.Result.Success)
        val opaquePort = (result as HttpServerEngine.Result.Success).port

        val deep = FakeOpaqueIdDocumentsProvider.allFiles.first { it.first == "doc-7" }.second
        val response = rawHttpRequest(opaquePort, "GET", "/assets/nested/deep/config.json")
        assertEquals(200, response.statusCode)
        assertTrue(deep.contentEquals(response.body))
        assertEquals(sha256(deep), sha256(response.body))
    }
}
