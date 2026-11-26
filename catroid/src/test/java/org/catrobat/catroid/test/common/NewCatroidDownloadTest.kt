package org.catrobat.catroid.test.common

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.catrobat.catroid.common.NewCatroidHttpManager
import org.catrobat.catroid.common.NewCatroidHttpManager.DownloadStatus
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NewCatroidDownloadTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun streamsLargeBodyToFileAndReportsProgressWithoutReplacingItUntilComplete() {
        val bytes = ByteArray(3 * 1024 * 1024) { index -> (index * 31).toByte() }
        server.enqueue(
            MockResponse()
                .setHeader("ETag", "\"asset-v1\"")
                .setBody(Buffer().write(bytes))
        )
        val destination = File(temporaryFolder.root, "asset.bin")
        destination.writeText("old version")
        val notifications = mutableListOf<DownloadStatus>()

        val state = completeDownload(server.url("/asset.bin").toString(), destination) {
            synchronized(notifications) { notifications.add(it.status) }
        }

        assertEquals(DownloadStatus.COMPLETED, state.status)
        assertEquals(200, state.responseCode)
        assertEquals(bytes.size.toLong(), state.bytesDownloaded)
        assertEquals(bytes.size.toLong(), state.totalBytes)
        assertEquals(100, state.progressPercent)
        assertArrayEquals(bytes, destination.readBytes())
        assertFalse(File(destination.parentFile, destination.name + ".newcatroid.part").exists())
        assertFalse(File(destination.parentFile, destination.name + ".newcatroid.part.meta").exists())
        assertTrue("state callback should include status transitions", notifications.contains(DownloadStatus.DOWNLOADING))
        assertTrue(notifications.contains(DownloadStatus.COMPLETED))
        assertEquals("identity", server.takeRequest().getHeader("Accept-Encoding"))
    }

    @Test
    fun httpErrorIncludesStatusAndBoundedBodyAndPreservesExistingDestination() {
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setBody("upstream worker unavailable")
        )
        val destination = File(temporaryFolder.root, "existing.bin")
        destination.writeText("known-good file")

        val state = completeDownload(server.url("/asset.bin").toString(), destination)

        assertEquals(DownloadStatus.FAILED, state.status)
        assertEquals(503, state.responseCode)
        assertTrue(state.error.orEmpty().contains("503"))
        assertTrue(state.error.orEmpty().contains("upstream worker unavailable"))
        assertEquals("known-good file", destination.readText())
        assertFalse(File(destination.parentFile, destination.name + ".newcatroid.part").exists())
    }

    @Test
    fun truncatedResponseWithStrongValidatorResumesFromExactOffset() {
        val completeBytes = ByteArray(512 * 1024) { index -> (index * 17).toByte() }
        server.enqueue(
            MockResponse()
                .setHeader("ETag", "\"asset-v1\"")
                .setBody(Buffer().write(completeBytes))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        val destination = File(temporaryFolder.root, "resumable.bin")
        val url = server.url("/asset.bin").toString()

        val first = completeDownload(url, destination)
        server.takeRequest()
        assertEquals(DownloadStatus.FAILED, first.status)
        assertTrue(first.error.orEmpty().contains("Incomplete") || first.error.orEmpty().contains("stream"))
        assertFalse(destination.exists())

        val partial = File(destination.parentFile, destination.name + ".newcatroid.part")
        val offset = partial.length()
        assertTrue("server should have delivered a non-empty partial body", offset > 0L)
        assertTrue("partial body must be shorter than the full asset", offset < completeBytes.size.toLong())

        val end = completeBytes.size - 1
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("ETag", "\"asset-v1\"")
                .setHeader("Content-Range", "bytes $offset-$end/${completeBytes.size}")
                .setBody(Buffer().write(completeBytes, offset.toInt(), completeBytes.size - offset.toInt()))
        )
        val resumed = completeDownload(url, destination)
        val request = server.takeRequest()

        assertEquals("bytes=$offset-", request.getHeader("Range"))
        assertEquals("\"asset-v1\"", request.getHeader("If-Range"))
        assertEquals(DownloadStatus.COMPLETED, resumed.status)
        assertEquals(offset, resumed.resumedFromBytes)
        assertArrayEquals(completeBytes, destination.readBytes())
        assertFalse(partial.exists())
        assertFalse(File(destination.parentFile, destination.name + ".newcatroid.part.meta").exists())
    }

    @Test
    fun cancellationDoesNotPublishPartialFileAndValidatorAllowsLaterContinuation() {
        val completeBytes = ByteArray(2 * 1024 * 1024) { index -> (index * 7).toByte() }
        server.enqueue(
            MockResponse()
                .setHeader("ETag", "\"cancel-v1\"")
                .setBody(Buffer().write(completeBytes))
                .throttleBody(32 * 1024, 100, TimeUnit.MILLISECONDS)
        )
        val destination = File(temporaryFolder.root, "cancelled.bin")
        val terminal = CountDownLatch(1)
        val url = server.url("/asset.bin").toString()
        val state = NewCatroidHttpManager.downloadToFile(url, destination) {
            if (it.isFinished) terminal.countDown()
        }
        server.takeRequest()
        assertTrue("download should begin writing", waitUntil(5_000) {
            state.status == DownloadStatus.DOWNLOADING && state.bytesDownloaded > 0L
        })
        state.cancel()
        assertTrue("cancelled transfer should leave its worker promptly", terminal.await(10, TimeUnit.SECONDS))
        assertEquals(DownloadStatus.CANCELLED, state.status)
        assertFalse("partial data must never appear at the final path", destination.exists())

        val partial = File(destination.parentFile, destination.name + ".newcatroid.part")
        val offset = partial.length()
        assertTrue(offset > 0L)
        assertTrue(offset < completeBytes.size.toLong())
        val end = completeBytes.size - 1
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("ETag", "\"cancel-v1\"")
                .setHeader("Content-Range", "bytes $offset-$end/${completeBytes.size}")
                .setBody(Buffer().write(completeBytes, offset.toInt(), completeBytes.size - offset.toInt()))
        )

        val resumed = completeDownload(url, destination)
        val request = server.takeRequest()
        assertEquals("bytes=$offset-", request.getHeader("Range"))
        assertEquals(DownloadStatus.COMPLETED, resumed.status)
        assertArrayEquals(completeBytes, destination.readBytes())
    }

    @Test
    fun unvalidatedTruncatedBodyIsDiscardedAndNeverReplacesAnExistingFile() {
        val original = ByteArray(256 * 1024) { 0x35 }
        server.enqueue(
            MockResponse()
                .setBody(Buffer().write(original))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        val destination = File(temporaryFolder.root, "safe.bin")
        destination.writeText("preserve me")

        val state = completeDownload(server.url("/asset.bin").toString(), destination)
        server.takeRequest()

        assertEquals(DownloadStatus.FAILED, state.status)
        assertEquals("preserve me", destination.readText())
        assertFalse(File(destination.parentFile, destination.name + ".newcatroid.part").exists())
        assertFalse(File(destination.parentFile, destination.name + ".newcatroid.part.meta").exists())
    }

    @Test
    fun suppliedStateIsReturnedAndUsedOnlyForItsOwnRequest() {
        val bytes = "state-owned body".toByteArray()
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val destination = File(temporaryFolder.root, "state.bin")
        val url = server.url("/state.bin").toString()
        val supplied = NewCatroidHttpManager.DownloadState(url, destination)
        val terminal = CountDownLatch(1)
        val returned = NewCatroidHttpManager.downloadToFile(url, destination, supplied) {
            if (it.isFinished) terminal.countDown()
        }

        assertSame(supplied, returned)
        assertTrue(terminal.await(10, TimeUnit.SECONDS))
        assertEquals(DownloadStatus.COMPLETED, supplied.status)
        assertArrayEquals(bytes, destination.readBytes())

        val mismatched = NewCatroidHttpManager.DownloadState(url, File(temporaryFolder.root, "other.bin"))
        val rejected = NewCatroidHttpManager.downloadToFile(url, destination, mismatched)
        assertSame(mismatched, rejected)
        assertEquals(DownloadStatus.FAILED, rejected.status)
        assertNotNull(rejected.error)
        assertNull(rejected.progressCallbackError)
    }

    private fun completeDownload(
        url: String,
        destination: File,
        callback: ((NewCatroidHttpManager.DownloadState) -> Unit)? = null
    ): NewCatroidHttpManager.DownloadState {
        val terminal = CountDownLatch(1)
        val state = NewCatroidHttpManager.downloadToFile(url, destination) {
            callback?.invoke(it)
            if (it.isFinished) terminal.countDown()
        }
        assertTrue("download did not reach a terminal state", terminal.await(20, TimeUnit.SECONDS))
        return state
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }
}
