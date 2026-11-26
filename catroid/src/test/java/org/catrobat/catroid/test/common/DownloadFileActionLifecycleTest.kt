package org.catrobat.catroid.test.common

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.catrobat.catroid.common.NewCatroidHttpManager.DownloadStatus
import org.catrobat.catroid.content.actions.DownloadFileAction
import org.catrobat.catroid.formulaeditor.Formula
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class DownloadFileActionLifecycleTest {
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
    fun resetCancelsOnlyTheOldTransferAndAReusedActionCanFinishANewTransfer() {
        val destination = File(temporaryFolder.root, "project-file.bin")
        val firstBody = ByteArray(2 * 1024 * 1024) { index -> (index * 11).toByte() }
        val secondBody = "second transfer wins".toByteArray()
        server.enqueue(
            MockResponse()
                .setHeader("ETag", "\"first\"")
                .setBody(Buffer().write(firstBody))
                .throttleBody(32 * 1024, 100, TimeUnit.MILLISECONDS)
        )
        val action = TestDownloadAction(destination)
        action.url = Formula(server.url("/first").toString())
        action.name = Formula("project-file.bin")

        assertFalse("the action waits while its transfer is active", action.act(0f))
        val firstState = action.currentDownloadState
        assertNotNull(firstState)
        server.takeRequest()
        assertTrue("first transfer should start", waitUntil(5_000) {
            firstState!!.status == DownloadStatus.DOWNLOADING && firstState.bytesDownloaded > 0L
        })

        action.reset()
        assertEquals(DownloadStatus.CANCELLED, firstState!!.status)
        assertFalse("cancelled transfer must not publish its partial file", destination.exists())

        server.enqueue(MockResponse().setBody(Buffer().write(secondBody)))
        action.url = Formula(server.url("/second").toString())
        action.name = Formula("project-file.bin")
        assertFalse("reused action starts a fresh transfer", action.act(0f))
        val secondState = action.currentDownloadState
        assertNotNull(secondState)
        assertTrue("new state should differ from the cancelled state", secondState !== firstState)

        val request = server.takeRequest()
        assertTrue(request.path.orEmpty().startsWith("/second"))
        assertTrue("second transfer should reach completion", waitUntil(10_000) {
            secondState!!.status == DownloadStatus.COMPLETED || secondState.status == DownloadStatus.FAILED
        })
        assertEquals(secondBody.toList(), destination.readBytes().toList())
        assertEquals(DownloadStatus.COMPLETED, secondState!!.status)
        action.reset()
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    private class TestDownloadAction(private val destination: File) : DownloadFileAction() {
        override fun resolveDestination(fileName: String): File = destination
    }
}
