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
import org.catrobat.catroid.content.localweb.RequestLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

/**
 * Tests for the built-in `POST /data/batch` endpoint - the batch byte-range
 * endpoint that hosted games whose engine streams its data through an io
 * worker rely on (the playgta5 build's io worker fetches its whole boot
 * read-set this way: "thousands of small archive pieces cost one round trip
 * per batch instead of one each"; the reference server implements the same
 * contract). Without it, the engine's start-up data arrives one SAF
 * open+Range round trip per piece and the boot stalls right after the engine
 * starts (the reported 22% "Starting the engine" stall).
 *
 * The contract mirrors the reference server: request body = JSON array of
 * [name, start, end] runs (name relative to the hosted data/ directory, byte
 * offsets inclusive); response = the runs concatenated in order with an
 * X-Run-Lengths header (each range clamped at the end of the file), optional
 * ?gz=1 gzip; limits: <=1000 runs, <=1 MiB body, <=64 MiB total; errors
 * 400/404/405/413. Files resolve through the served store (with the
 * nested-site-root fallback), so batch reads stay confined to the tree.
 *
 * Pure JVM (FileDocumentStore over a real temp directory + the real
 * HttpServerEngine over loopback).
 */
class DataBatchEndpointTest {

    private lateinit var rootDir: File
    private var engine: HttpServerEngine? = null
    private var port = -1
    private lateinit var requestLog: RequestLog

    private val archiveA = "common/data/a.rpf".let { name ->
        name to ByteArray(5000) { (it % 251).toByte() }
    }
    private val archiveB = "platform/data/b.rpf".let { name ->
        name to ByteArray(2048) { (it % 253).toByte() }
    }

    @Before
    fun setUp() {
        rootDir = java.nio.file.Files.createTempDirectory("data-batch-test").toFile()
        File(rootDir, "data/${archiveA.first}").let { it.parentFile?.mkdirs(); it.writeBytes(archiveA.second) }
        File(rootDir, "data/${archiveB.first}").let { it.parentFile?.mkdirs(); it.writeBytes(archiveB.second) }
        requestLog = RequestLog()
    }

    @After
    fun tearDown() {
        engine?.stop()
        engine = null
        rootDir.deleteRecursively()
    }

    private fun startServer(storeRoot: File = rootDir, storeIsNested: Boolean = false) {
        val store = if (storeIsNested) {
            // The hosted root contains the site root nested one level down:
            // batch reads must find data/ under the nested site root.
            val nested = File(rootDir, "mirror/site")
            val dataDir = File(nested, "data")
            File(dataDir, archiveA.first).let { it.parentFile?.mkdirs(); it.writeBytes(archiveA.second) }
            File(dataDir, archiveB.first).let { it.parentFile?.mkdirs(); it.writeBytes(archiveB.second) }
            FileDocumentStore(rootDir)
        } else {
            FileDocumentStore(storeRoot)
        }
        val newEngine = HttpServerEngine(
            storeProvider = { store },
            urlPrefixProvider = { "/" },
            requestLog = requestLog
        )
        val result = newEngine.start(0)
        engine = newEngine
        assertTrue("server should start", result is HttpServerEngine.Result.Success)
        port = (result as HttpServerEngine.Result.Success).port
    }

    private fun runsBody(vararg runs: Triple<String, Long, Long>): ByteArray =
        runs.joinToString(",", "[", "]") { (n, s, e) ->
            "[\"" + n.replace("\\", "\\\\").replace("\"", "\\\"") + "\",$s,$e]"
        }.toByteArray(StandardCharsets.UTF_8)

    private fun gunzip(bytes: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }

    // ---------- the contract ----------

    @Test
    fun `batch returns the requested runs concatenated with run lengths`() {
        startServer()
        val body = runsBody(
            Triple(archiveA.first, 100L, 199L),
            Triple(archiveB.first, 0L, 49L)
        )
        val response = rawHttpPost(port, "/data/batch", body)
        assertEquals(200, response.statusCode)
        assertEquals("application/octet-stream", response.headers["content-type"])
        assertEquals("100,50", response.headers["x-run-lengths"])
        val expected = archiveA.second.copyOfRange(100, 200) + archiveB.second.copyOfRange(0, 50)
        assertTrue(expected.contentEquals(response.body))
        assertEquals(expected.size.toString(), response.headers["content-length"])
    }

    @Test
    fun `ranges are clamped at the end of the file`() {
        startServer()
        // end past the file size: clamped to the file size
        val response = rawHttpPost(
            port, "/data/batch",
            runsBody(Triple(archiveB.first, 2000L, 99999L))
        )
        assertEquals(200, response.statusCode)
        assertEquals("48", response.headers["x-run-lengths"])
        assertTrue(archiveB.second.copyOfRange(2000, 2048).contentEquals(response.body))
    }

    @Test
    fun `empty runs and empty ranges are served as zero length`() {
        startServer()
        val response = rawHttpPost(port, "/data/batch", runsBody())
        assertEquals(200, response.statusCode)
        assertEquals("", response.headers["x-run-lengths"])
        assertEquals(0, response.body.size)

        val zero = rawHttpPost(
            port, "/data/batch",
            runsBody(Triple(archiveA.first, 500L, 499L))
        )
        assertEquals(200, zero.statusCode)
        assertEquals("0", zero.headers["x-run-lengths"])
        assertEquals(0, zero.body.size)
    }

    @Test
    fun `gz parameter serves a gzip encoded body`() {
        startServer()
        val plain = runsBody(Triple(archiveA.first, 0L, 999L))
        val response = rawHttpPost(port, "/data/batch?gz=1", plain)
        assertEquals(200, response.statusCode)
        assertEquals("gzip", response.headers["content-encoding"])
        assertEquals("1000", response.headers["x-run-lengths"])
        assertTrue(
            archiveA.second.copyOfRange(0, 1000).contentEquals(gunzip(response.body))
        )
    }

    @Test
    fun `batch reads resolve under a nested site root`() {
        startServer(storeIsNested = true)
        val response = rawHttpPost(
            port, "/data/batch",
            runsBody(Triple(archiveA.first, 10L, 19L))
        )
        assertEquals(200, response.statusCode)
        assertEquals("10", response.headers["x-run-lengths"])
        assertTrue(archiveA.second.copyOfRange(10, 20).contentEquals(response.body))
    }

    @Test
    fun `the batch request is recorded in the request log`() {
        startServer()
        rawHttpPost(
            port, "/data/batch",
            runsBody(Triple(archiveA.first, 0L, 9L), Triple(archiveB.first, 0L, 9L))
        )
        val entries = requestLog.snapshot()
        assertEquals(1, entries.size)
        assertEquals(200, entries[0].status)
        assertEquals("POST", entries[0].method)
        assertEquals("/data/batch", entries[0].path)
        assertTrue(entries[0].detail!!.contains("2 runs"))
        assertTrue(entries[0].bytesSent > 0)
    }

    // ---------- errors and limits ----------

    @Test
    fun `missing data file answers 404`() {
        startServer()
        val response = rawHttpPost(
            port, "/data/batch",
            runsBody(Triple("no/such/file.rpf", 0L, 9L))
        )
        assertEquals(404, response.statusCode)
        assertNull(requestLog.snapshot().firstOrNull()?.detail?.let { null })
        assertTrue(requestLog.snapshot()[0].detail!!.contains("not found"))
    }

    @Test
    fun `invalid batch bodies answer 400`() {
        startServer()
        assertEquals(400, rawHttpPost(port, "/data/batch", "not json".toByteArray()).statusCode)
        assertEquals(400, rawHttpPost(port, "/data/batch", "{}".toByteArray()).statusCode)
        assertEquals(400, rawHttpPost(port, "/data/batch", "[[1,2]]".toByteArray()).statusCode)
        assertEquals(400, rawHttpPost(port, "/data/batch", "[[\"a\",\"x\",1]]".toByteArray()).statusCode)
        // empty body
        assertEquals(400, rawHttpPost(port, "/data/batch", ByteArray(0)).statusCode)
    }

    @Test
    fun `path traversal in run names answers 400`() {
        startServer()
        val response = rawHttpPost(
            port, "/data/batch",
            runsBody(Triple("../secret.rpf", 0L, 9L))
        )
        assertEquals(400, response.statusCode)
    }

    @Test
    fun `invalid ranges answer 400`() {
        startServer()
        assertEquals(
            400,
            rawHttpPost(port, "/data/batch", runsBody(Triple(archiveA.first, 10L, 5L))).statusCode
        )
        assertEquals(
            400,
            rawHttpPost(port, "/data/batch", runsBody(Triple(archiveA.first, -1L, 5L))).statusCode
        )
    }

    @Test
    fun `too many runs answer 400`() {
        startServer()
        val runs = (0 until 1001).joinToString(",", "[", "]") {
            "[\"${archiveA.first}\",0,0]"
        }
        val response = rawHttpPost(port, "/data/batch", runs.toByteArray(StandardCharsets.UTF_8))
        assertEquals(400, response.statusCode)
    }

    @Test
    fun `a body over 1 MiB answers 413`() {
        startServer()
        val big = ByteArray(1024 * 1024 + 10) { '['.code.toByte() }
        val response = rawHttpPost(port, "/data/batch", big)
        assertEquals(413, response.statusCode)
    }

    @Test
    fun `non post methods answer 405 and other posts keep their errors`() {
        startServer()
        assertEquals(405, rawHttpRequest(port, "GET", "/data/batch").statusCode)
        assertEquals(405, rawHttpRequest(port, "HEAD", "/data/batch").statusCode)
        // other POST endpoints still answer 405 (method not allowed)
        assertEquals(405, rawHttpPost(port, "/anything", "{}".toByteArray()).statusCode)
    }

    @Test
    fun `batch endpoint works without a hosted directory with a clear error`() {
        val newEngine = HttpServerEngine(storeProvider = { null }, urlPrefixProvider = { "/" })
        val result = newEngine.start(0)
        engine = newEngine
        assertTrue("server should start", result is HttpServerEngine.Result.Success)
        port = (result as HttpServerEngine.Result.Success).port
        val response = rawHttpPost(
            port, "/data/batch",
            runsBody(Triple(archiveA.first, 0L, 9L))
        )
        assertEquals(404, response.statusCode)
        assertTrue(response.bodyText().contains("no directory is being hosted"))
    }

    // ---------- existing behavior is preserved ----------

    @Test
    fun `range requests and normal files still work alongside the endpoint`() {
        startServer()
        val range = rawHttpRequest(port, "GET", "/data/${archiveA.first}", mapOf("Range" to "bytes=0-99"))
        assertEquals(206, range.statusCode)
        assertTrue(archiveA.second.copyOfRange(0, 100).contentEquals(range.body))

        val whole = rawHttpRequest(port, "GET", "/data/${archiveB.first}")
        assertEquals(200, whole.statusCode)
        assertTrue(archiveB.second.contentEquals(whole.body))

        val missing = rawHttpRequest(port, "GET", "/data/nope.rpf")
        assertEquals(404, missing.statusCode)
    }
}
