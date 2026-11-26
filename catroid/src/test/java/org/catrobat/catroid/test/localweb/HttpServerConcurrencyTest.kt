package org.catrobat.catroid.test.localweb

import org.catrobat.catroid.content.localweb.DocumentEntry
import org.catrobat.catroid.content.localweb.DocumentStore
import org.catrobat.catroid.content.localweb.HttpServerEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Verifies that concurrent HTTP reads reach the bounded worker pool concurrently. */
class HttpServerConcurrencyTest {
    @Test
    fun `range client lanes are not serialized behind the connection queue`() {
        val concurrentRequests = 16
        val expectedBytes = ByteArray(128 * 1024) { index -> (index * 13).toByte() }
        val openCount = AtomicInteger()
        val allStreamsOpened = CountDownLatch(concurrentRequests)
        val releaseStreams = CountDownLatch(1)
        val store = object : DocumentStore {
            override val rootDisplayName: String = "concurrency-test"

            override fun resolve(relativePath: String): DocumentEntry? =
                if (relativePath == "asset.bin") {
                    DocumentEntry("asset", "asset.bin", false, expectedBytes.size.toLong(), "application/octet-stream")
                } else {
                    null
                }

            override fun openInput(entry: DocumentEntry) = ByteArrayInputStream(expectedBytes).also {
                openCount.incrementAndGet()
                allStreamsOpened.countDown()
                check(releaseStreams.await(10, TimeUnit.SECONDS)) { "test did not release blocked streams" }
            }

            override fun listChildren(directory: DocumentEntry): List<DocumentEntry> = emptyList()
        }
        val server = HttpServerEngine(storeProvider = { store })
        val start = server.start(0, "127.0.0.1")
        assertTrue("server should start: $start", start is HttpServerEngine.Result.Success)
        val port = (start as HttpServerEngine.Result.Success).port
        val clients = Executors.newFixedThreadPool(concurrentRequests)
        val completed = CountDownLatch(concurrentRequests)
        val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())

        try {
            repeat(concurrentRequests) {
                clients.execute {
                    try {
                        val connection = URL("http://127.0.0.1:$port/asset.bin").openConnection() as HttpURLConnection
                        connection.connectTimeout = 5_000
                        connection.readTimeout = 10_000
                        connection.requestMethod = "GET"
                        val status = connection.responseCode
                        val body = connection.inputStream.use { it.readBytes() }
                        connection.disconnect()
                        assertEquals(200, status)
                        assertArrayEquals(expectedBytes, body)
                    } catch (failure: Throwable) {
                        failures.add(failure)
                    } finally {
                        completed.countDown()
                    }
                }
            }

            assertTrue(
                "all $concurrentRequests requests should open their sources concurrently; " +
                    "only ${openCount.get()} opened before release",
                allStreamsOpened.await(5, TimeUnit.SECONDS)
            )
        } finally {
            releaseStreams.countDown()
            clients.shutdown()
            assertTrue("client tasks should finish", completed.await(15, TimeUnit.SECONDS))
            server.stop()
        }
        assertTrue("HTTP requests failed: $failures", failures.isEmpty())
    }
}
