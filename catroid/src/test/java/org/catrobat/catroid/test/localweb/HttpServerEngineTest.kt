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

import org.catrobat.catroid.content.localweb.DocumentEntry
import org.catrobat.catroid.content.localweb.DocumentStore
import org.catrobat.catroid.content.localweb.FileDocumentStore
import org.catrobat.catroid.content.localweb.HttpEngine
import org.catrobat.catroid.content.localweb.HttpServerEngine
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * End-to-end tests for the embedded HTTP server: real sockets, real files in
 * a temporary directory, raw HTTP requests. Runs as a plain JVM unit test.
 */
class HttpServerEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var rootDir: File
    private lateinit var store: FileDocumentStore
    private var engine: HttpServerEngine? = null

    private val indexHtml = "<html><head><link rel=\"stylesheet\" href=\"css/style.css\">" +
        "<script src=\"js/app.js\"></script></head><body>hello</body></html>"
    private val css = "body { color: red; }"
    private val js = "console.log('hi');"
    private val nestedText = "nested content"

    @Before
    fun setUp() {
        rootDir = tempFolder.newFolder("site")
        File(rootDir, "index.html").writeText(indexHtml)
        File(rootDir, "css").mkdirs()
        File(rootDir, "css/style.css").writeText(css)
        File(rootDir, "js").mkdirs()
        File(rootDir, "js/app.js").writeText(js)
        File(rootDir, "docs").mkdirs()
        File(rootDir, "docs/guide.txt").writeText(nestedText)
        store = FileDocumentStore(rootDir)
    }

    @After
    fun tearDown() {
        engine?.stop()
        engine = null
    }

    private fun startServer(port: Int = 0, prefix: String = "/"): HttpServerEngine {
        val server = HttpServerEngine(storeProvider = { store }, urlPrefixProvider = { prefix })
        val result = server.start(port, "127.0.0.1")
        assertTrue("server should start, got: $result", result is HttpServerEngine.Result.Success)
        engine = server
        return server
    }

    // ---------- raw HTTP helpers ----------

    data class RawResponse(
        val statusCode: Int,
        val statusLine: String,
        val headers: Map<String, String>,
        val body: ByteArray
    ) {
        val header get() = headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
    }

    private fun rawRequest(
        port: Int,
        method: String,
        path: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): RawResponse {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 10000
            val request = buildString {
                append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                append("Host: 127.0.0.1:").append(port).append("\r\n")
                for ((name, value) in extraHeaders) {
                    append(name).append(": ").append(value).append("\r\n")
                }
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().write(request.toByteArray(StandardCharsets.US_ASCII))
            socket.getOutputStream().flush()
            val raw = socket.getInputStream().readBytes()
            return parseResponse(raw)
        }
    }

    private fun parseResponse(raw: ByteArray): RawResponse {
        val text = raw.toString(StandardCharsets.UTF_8)
        val headerEnd = text.indexOf("\r\n\r\n")
        assertTrue("response has header terminator", headerEnd >= 0)
        val headerBlock = text.substring(0, headerEnd)
        val lines = headerBlock.split("\r\n")
        val statusLine = lines[0]
        val statusCode = statusLine.split(' ')[1].toInt()
        val headers = lines.drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon > 0) line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim() else null
        }.toMap()
        // Body: bytes after the header terminator (headers are ASCII).
        val bodyStart = headerEnd + 4
        val body = raw.copyOfRange(bodyStart, raw.size)
        return RawResponse(statusCode, statusLine, headers, body)
    }

    private fun bodyString(response: RawResponse): String = response.body.toString(StandardCharsets.UTF_8)

    // ---------- basic serving ----------

    @Test
    fun `serves html file with correct mime type`() {
        val server = startServer()
        val response = rawRequest(server.boundPort, "GET", "/index.html")
        assertEquals(200, response.statusCode)
        assertEquals("text/html; charset=utf-8", response.headers["content-type"])
        assertEquals(indexHtml, bodyString(response))
        assertEquals(indexHtml.length.toLong(), response.headers["content-length"]?.toLong())
        assertEquals("bytes", response.headers["accept-ranges"])
    }

    @Test
    fun `serves nested css and js resources with correct mime types`() {
        val server = startServer()
        val cssResponse = rawRequest(server.boundPort, "GET", "/css/style.css")
        assertEquals(200, cssResponse.statusCode)
        assertEquals("text/css; charset=utf-8", cssResponse.headers["content-type"])
        assertEquals(css, bodyString(cssResponse))

        val jsResponse = rawRequest(server.boundPort, "GET", "/js/app.js")
        assertEquals(200, jsResponse.statusCode)
        assertEquals("text/javascript; charset=utf-8", jsResponse.headers["content-type"])
        assertEquals(js, bodyString(jsResponse))
    }

    @Test
    fun `serves deeply nested text file`() {
        val server = startServer()
        val response = rawRequest(server.boundPort, "GET", "/docs/guide.txt")
        assertEquals(200, response.statusCode)
        assertEquals("text/plain; charset=utf-8", response.headers["content-type"])
        assertEquals(nestedText, bodyString(response))
    }

    @Test
    fun `root serves index html`() {
        val server = startServer()
        val response = rawRequest(server.boundPort, "GET", "/")
        assertEquals(200, response.statusCode)
        assertEquals(indexHtml, bodyString(response))
    }

    @Test
    fun `missing file returns 404`() {
        val server = startServer()
        val response = rawRequest(server.boundPort, "GET", "/nope.txt")
        assertEquals(404, response.statusCode)
        assertTrue(bodyString(response).contains("404"))
    }

    @Test
    fun `unsupported method returns 405 with allow header`() {
        val server = startServer()
        val response = rawRequest(server.boundPort, "POST", "/index.html")
        assertEquals(405, response.statusCode)
        assertEquals("GET, HEAD", response.headers["allow"])
    }

    @Test
    fun `head request returns headers without body`() {
        val server = startServer()
        val response = rawRequest(server.boundPort, "HEAD", "/index.html")
        assertEquals(200, response.statusCode)
        assertEquals("text/html; charset=utf-8", response.headers["content-type"])
        assertEquals(indexHtml.length.toLong(), response.headers["content-length"]?.toLong())
        assertEquals(0, response.body.size)
    }

    // ---------- ranges ----------

    @Test
    fun `byte range returns 206 with content range`() {
        val server = startServer()
        val response = rawRequest(server.boundPort, "GET", "/index.html", mapOf("Range" to "bytes=0-9"))
        assertEquals(206, response.statusCode)
        assertEquals("bytes 0-9/${indexHtml.length}", response.headers["content-range"])
        assertEquals("10", response.headers["content-length"])
        assertEquals(indexHtml.substring(0, 10), bodyString(response))
    }

    @Test
    fun `open ended range returns the rest of the file`() {
        val server = startServer()
        val response = rawRequest(server.boundPort, "GET", "/index.html", mapOf("Range" to "bytes=10-"))
        assertEquals(206, response.statusCode)
        assertEquals("bytes 10-${indexHtml.length - 1}/${indexHtml.length}", response.headers["content-range"])
        assertEquals(indexHtml.substring(10), bodyString(response))
    }

    @Test
    fun `suffix range returns the last bytes`() {
        val server = startServer()
        val response = rawRequest(server.boundPort, "GET", "/index.html", mapOf("Range" to "bytes=-5"))
        assertEquals(206, response.statusCode)
        assertEquals(indexHtml.substring(indexHtml.length - 5), bodyString(response))
    }

    @Test
    fun `unsatisfiable range returns 416 with content range`() {
        val server = startServer()
        val response = rawRequest(
            server.boundPort, "GET", "/index.html",
            mapOf("Range" to "bytes=${indexHtml.length + 100}-")
        )
        assertEquals(416, response.statusCode)
        assertEquals("bytes */${indexHtml.length}", response.headers["content-range"])
    }

    @Test
    fun `head with range returns 206 headers without body`() {
        val server = startServer()
        val response = rawRequest(server.boundPort, "HEAD", "/index.html", mapOf("Range" to "bytes=0-9"))
        assertEquals(206, response.statusCode)
        assertEquals("bytes 0-9/${indexHtml.length}", response.headers["content-range"])
        assertEquals(0, response.body.size)
    }

    // ---------- large file streaming ----------

    @Test
    fun `large file is streamed and range requests return exact bytes`() {
        // 3 MB of deterministic content, much larger than the 64 KB stream buffer.
        val largeFile = File(rootDir, "big.bin")
        val size = 3 * 1024 * 1024
        val expected = ByteArray(size) { (it % 251).toByte() }
        largeFile.writeBytes(expected)

        val server = startServer()

        val full = rawRequest(server.boundPort, "GET", "/big.bin")
        assertEquals(200, full.statusCode)
        assertEquals(size.toLong(), full.headers["content-length"]?.toLong())
        assertArrayEquals(expected, full.body)

        val range = rawRequest(server.boundPort, "GET", "/big.bin", mapOf("Range" to "bytes=1000000-1000999"))
        assertEquals(206, range.statusCode)
        assertEquals("bytes 1000000-1000999/$size", range.headers["content-range"])
        assertArrayEquals(expected.copyOfRange(1000000, 1001000), range.body)

        val tail = rawRequest(server.boundPort, "GET", "/big.bin", mapOf("Range" to "bytes=-1024"))
        assertEquals(206, tail.statusCode)
        assertArrayEquals(expected.copyOfRange(size - 1024, size), tail.body)
    }

    // ---------- directories ----------

    @Test
    fun `directory without trailing slash redirects`() {
        val server = startServer()
        val response = rawRequest(server.boundPort, "GET", "/docs")
        assertEquals(301, response.statusCode)
        assertEquals("/docs/", response.headers["location"])
    }

    @Test
    fun `directory without index file returns a listing`() {
        val server = startServer()
        val response = rawRequest(server.boundPort, "GET", "/docs/")
        assertEquals(200, response.statusCode)
        assertEquals("text/html; charset=utf-8", response.headers["content-type"])
        val body = bodyString(response)
        assertTrue(body.contains("guide.txt"))
        assertTrue(body.contains("Index of"))
    }

    @Test
    fun `directory with index file serves the index`() {
        File(rootDir, "docs/index.html").writeText("<html>docs index</html>")
        val server = startServer()
        val response = rawRequest(server.boundPort, "GET", "/docs/")
        assertEquals(200, response.statusCode)
        assertEquals("<html>docs index</html>", bodyString(response))
    }

    @Test
    fun `listing escapes html in file names`() {
        // '<' and '>' are illegal in file names on Linux/CI, so exercise the
        // escaping with '&' and '"' instead.
        File(rootDir, "docs/a&b\"c.txt").writeText("x")
        val server = startServer()
        val response = rawRequest(server.boundPort, "GET", "/docs/")
        assertEquals(200, response.statusCode)
        val body = bodyString(response)
        assertFalse(body.contains("a&b\"c.txt"))
        assertTrue(body.contains("a&amp;b&quot;c.txt"))
    }

    // ---------- security ----------

    @Test
    fun `directory traversal is rejected`() {
        // A secret file outside the served root must never be reachable.
        val secret = File(tempFolder.root, "secret.txt")
        secret.writeText("top secret")

        val server = startServer()
        assertEquals(403, rawRequest(server.boundPort, "GET", "/../secret.txt").statusCode)
        assertEquals(403, rawRequest(server.boundPort, "GET", "/docs/../../secret.txt").statusCode)
        assertEquals(403, rawRequest(server.boundPort, "GET", "/%2e%2e/secret.txt").statusCode)
        assertEquals(403, rawRequest(server.boundPort, "GET", "/docs/%2e%2e/%2e%2e/secret.txt").statusCode)
        assertEquals(403, rawRequest(server.boundPort, "GET", "/..%2fsecret.txt").statusCode)
        assertEquals(403, rawRequest(server.boundPort, "GET", "/%2e%2e%5csecret.txt").statusCode)
    }

    @Test
    fun `absolute path injection is rejected`() {
        val server = startServer()
        assertEquals(403, rawRequest(server.boundPort, "GET", "//etc/passwd").statusCode)
        assertEquals(403, rawRequest(server.boundPort, "GET", "/a\\b.txt").statusCode)
    }

    @Test
    fun `url prefix confines the served paths`() {
        val server = startServer(prefix = "/site")
        assertEquals(200, rawRequest(server.boundPort, "GET", "/site/index.html").statusCode)
        assertEquals(200, rawRequest(server.boundPort, "GET", "/site/css/style.css").statusCode)
        assertEquals(404, rawRequest(server.boundPort, "GET", "/index.html").statusCode)
        assertEquals(404, rawRequest(server.boundPort, "GET", "/site/../index.html").statusCode)
    }

    // ---------- lifecycle ----------

    @Test
    fun `server state and base url reflect the lifecycle`() {
        val server = HttpServerEngine(storeProvider = { store })
        assertFalse(server.isRunning)
        assertEquals("", server.baseUrl)

        val result = server.start(0, "127.0.0.1")
        assertTrue(result is HttpServerEngine.Result.Success)
        engine = server
        assertTrue(server.isRunning)
        assertTrue(server.boundPort > 0)
        assertEquals("http://127.0.0.1:${server.boundPort}/", server.baseUrl)

        server.stop()
        assertFalse(server.isRunning)
        assertEquals("", server.baseUrl)
    }

    @Test
    fun `stop is idempotent`() {
        val server = startServer()
        server.stop()
        server.stop()
        server.stop()
        assertFalse(server.isRunning)
    }

    @Test
    fun `restart on the same engine works`() {
        val server = startServer()
        val port = server.boundPort
        server.stop()
        val result = server.start(port, "127.0.0.1")
        assertTrue(result is HttpServerEngine.Result.Success)
        assertEquals(200, rawRequest(server.boundPort, "GET", "/index.html").statusCode)
    }

    @Test
    fun `occupied port is reported as an error`() {
        val first = startServer()
        val second = HttpServerEngine(storeProvider = { store })
        val result = second.start(first.boundPort, "127.0.0.1")
        assertTrue(result is HttpServerEngine.Result.Error)
        assertFalse(second.isRunning)
        // The first server is unaffected.
        assertTrue(first.isRunning)
        assertEquals(200, rawRequest(first.boundPort, "GET", "/index.html").statusCode)
    }

    @Test
    fun `invalid ports and addresses are rejected`() {
        val server = HttpServerEngine(storeProvider = { store })
        assertTrue(server.start(-1) is HttpServerEngine.Result.Error)
        assertTrue(server.start(65536) is HttpServerEngine.Result.Error)
        assertTrue(server.start(8080, "256.256.256.256") is HttpServerEngine.Result.Error)
        assertFalse(server.isRunning)
    }

    @Test
    fun `concurrent requests are all served`() {
        val server = startServer()
        val pool = Executors.newFixedThreadPool(16)
        val latch = CountDownLatch(16)
        val results = java.util.Collections.synchronizedList(ArrayList<Int>())
        repeat(16) {
            pool.execute {
                try {
                    val response = rawRequest(server.boundPort, "GET", "/index.html")
                    results.add(response.statusCode)
                } finally {
                    latch.countDown()
                }
            }
        }
        assertTrue(latch.await(30, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(16, results.size)
        assertTrue(results.all { it == 200 })
    }

    @Test
    fun `no document store answers 404`() {
        val server = HttpServerEngine(storeProvider = { null })
        val result = server.start(0, "127.0.0.1")
        assertTrue(result is HttpServerEngine.Result.Success)
        engine = server
        val response = rawRequest(server.boundPort, "GET", "/index.html")
        assertEquals(404, response.statusCode)
        assertTrue(bodyString(response).contains("no directory"))
    }

    // ---------- engine level (no sockets) ----------

    @Test
    fun `unknown size is streamed with chunked transfer encoding`() {
        val payload = "chunked-content".toByteArray(StandardCharsets.UTF_8)
        val memoryStore = object : DocumentStore {
            override val rootDisplayName = "mem"
            override fun resolve(relativePath: String): DocumentEntry? {
                if (relativePath != "stream.bin") return null
                return DocumentEntry("stream.bin", "stream.bin", false, -1L, null)
            }
            override fun openInput(entry: DocumentEntry): InputStream =
                payload.inputStream()
            override fun listChildren(directory: DocumentEntry) = emptyList<DocumentEntry>()
        }
        val httpEngine = HttpEngine({ memoryStore })
        val out = ByteArrayOutputStream()
        httpEngine.handle("GET", "/stream.bin", emptyMap(), out)
        val raw = out.toString(StandardCharsets.UTF_8)
        assertTrue(raw.startsWith("HTTP/1.1 200 OK"))
        assertTrue(raw.contains("Transfer-Encoding: chunked"))
        // chunk size in hex, chunk data, terminating zero chunk
        assertTrue(raw.contains("${Integer.toHexString(payload.size)}\r\n"))
        assertTrue(raw.contains("chunked-content"))
        assertTrue(raw.trimEnd().endsWith("0\r\n\r\n") || raw.contains("0\r\n\r\n"))
    }

    @Test
    fun `engine handles malformed targets gracefully`() {
        val httpEngine = HttpEngine({ store })
        val out = ByteArrayOutputStream()
        httpEngine.handle("GET", "", emptyMap(), out)
        assertTrue(out.toString(StandardCharsets.UTF_8).startsWith("HTTP/1.1 200 OK"))

        val out2 = ByteArrayOutputStream()
        httpEngine.handle("BREW", "/index.html", emptyMap(), out2)
        assertTrue(out2.toString(StandardCharsets.UTF_8).startsWith("HTTP/1.1 405"))
    }

    @Test
    fun `store is never asked for paths escaping the root`() {
        val recordingStore = object : DocumentStore by store {
            val requested = mutableListOf<String>()
            override fun resolve(relativePath: String): DocumentEntry? {
                requested.add(relativePath)
                return store.resolve(relativePath)
            }
        }
        val httpEngine = HttpEngine({ recordingStore })
        val out = ByteArrayOutputStream()
        httpEngine.handle("GET", "/../secret.txt", emptyMap(), out)
        assertTrue(out.toString(StandardCharsets.UTF_8).startsWith("HTTP/1.1 403"))
        assertTrue("store was not queried for traversal path", recordingStore.requested.isEmpty())
    }

    @Test
    fun `document entry defaults are sane`() {
        val entry = DocumentEntry("id", "name.txt", false, 42L, "text/plain")
        assertEquals("id", entry.id)
        assertEquals("name.txt", entry.name)
        assertFalse(entry.isDirectory)
        assertEquals(42L, entry.size)
        assertEquals("text/plain", entry.mimeType)
        assertNotNull(store.resolve("index.html"))
        assertNotNull(store.resolve(""))
    }
}
