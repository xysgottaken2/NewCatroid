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
import org.catrobat.catroid.content.actions.HostDirectoryAction
import org.catrobat.catroid.content.actions.SelectFolderAndHostAction
import org.catrobat.catroid.content.localweb.LocalHttpServer
import org.catrobat.catroid.content.localweb.SafSelectionManager
import org.catrobat.catroid.formulaeditor.Formula
import org.catrobat.catroid.formulaeditor.UserVariable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.ServerSocket

/**
 * Tests for the combined "Select Folder, Save to Variable and Host" block
 * ([SelectFolderAndHostAction]) and for the hardened "Host Directory" block.
 *
 * The combined block must atomically: write the selected SAF tree URI to the
 * user variable AND configure [LocalHttpServer] with the same URI at the "/"
 * prefix - without depending on a separate hosting block or on formula
 * evaluation. Cancelling or invalid selections must preserve the previous
 * configuration, and the configured root must survive server start and serve
 * the folder's index.html at "/".
 */
@RunWith(RobolectricTestRunner::class)
class SelectFolderAndHostActionTest {

    private lateinit var variable: UserVariable
    private lateinit var action: SelectFolderAndHostAction
    private val treeUri: Uri
        get() = SafTestEnvironment.treeUri

    @Before
    fun setUp() {
        SafTestEnvironment.register()
        variable = UserVariable("gameFolder")
        action = SelectFolderAndHostAction()
        action.variable = variable
        resetManager()
    }

    @After
    fun tearDown() {
        resetManager()
    }

    private fun resetManager() {
        LocalHttpServer.stop()
        LocalHttpServer.setDocumentRoot(null)
        LocalHttpServer.setUrlPrefix(null)
        LocalHttpServer.clearError()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    // ---------- the combined block ----------

    @Test
    fun `selected uri is written to the variable`() {
        action.handleFolderSelection(treeUri)
        assertEquals(treeUri.toString(), variable.value)
    }

    @Test
    fun `the same uri is passed to the hosting manager`() {
        action.handleFolderSelection(treeUri)
        assertEquals(treeUri.toString(), LocalHttpServer.documentRoot)
    }

    @Test
    fun `hosting is configured with the root url prefix`() {
        LocalHttpServer.setUrlPrefix("/old-prefix")
        action.handleFolderSelection(treeUri)
        assertEquals("/", LocalHttpServer.currentUrlPrefix)
    }

    @Test
    fun `manager acceptance is confirmed with no error reported`() {
        action.handleFolderSelection(treeUri)
        assertNull(LocalHttpServer.lastError)
    }

    @Test
    fun `configured root survives server start and serves index html at root without a separate host block`() {
        // Only the combined block configured the root - no HostDirectoryAction ran.
        action.handleFolderSelection(treeUri)

        val port = freePort()
        assertTrue("server should start", LocalHttpServer.start(port))
        try {
            assertEquals("http://127.0.0.1:$port/", LocalHttpServer.baseUrl)
            val response = rawHttpRequest(port, "GET", "/")
            assertEquals(200, response.statusCode)
            assertEquals(
                String(FakeGameDocumentsProvider.INDEX_HTML, Charsets.UTF_8),
                response.bodyText()
            )
            assertTrue(response.headers["content-type"]!!.startsWith("text/html"))
        } finally {
            LocalHttpServer.stop()
        }
    }

    @Test
    fun `cancelling selection preserves the previous configuration`() {
        // Establish a valid configuration first.
        action.handleFolderSelection(treeUri)
        assertEquals(treeUri.toString(), variable.value)
        assertEquals(treeUri.toString(), LocalHttpServer.documentRoot)

        // Cancel: nothing may change.
        action.handleFolderSelection(null)

        assertEquals(treeUri.toString(), variable.value)
        assertEquals(treeUri.toString(), LocalHttpServer.documentRoot)
        assertEquals("/", LocalHttpServer.currentUrlPrefix)
        assertNull(LocalHttpServer.lastError)
        assertTrue(SafSelectionManager.lastDirectoryCancelled)
        assertEquals(treeUri, SafSelectionManager.lastDirectoryUri)
    }

    @Test
    fun `cancelling without a previous selection keeps everything empty and safe`() {
        action.handleFolderSelection(null)
        assertEquals(0.0, variable.value)
        assertEquals("", LocalHttpServer.documentRoot)
        assertTrue(SafSelectionManager.lastDirectoryCancelled)
    }

    @Test
    fun `a non folder uri produces a useful error and preserves the configuration`() {
        action.handleFolderSelection(treeUri)
        val fileUri = Uri.parse(
            "content://${FakeGameDocumentsProvider.AUTHORITY}/document/primary%3ADocuments%2Fgame%2FGame%2Findex.html"
        )

        action.handleFolderSelection(fileUri)

        assertEquals("variable must keep the previous valid URI", treeUri.toString(), variable.value)
        assertEquals("hosting must keep the previous valid root", treeUri.toString(), LocalHttpServer.documentRoot)
        assertNotNull(LocalHttpServer.lastError)
        assertTrue(LocalHttpServer.lastError!!.contains("folder"))
    }

    @Test
    fun `an inaccessible folder uri produces a useful error and preserves the configuration`() {
        action.handleFolderSelection(treeUri)
        val unknownAuthority = Uri.parse("content://com.example.unknown/tree/primary%3ADocuments%2Fgame")

        action.handleFolderSelection(unknownAuthority)

        assertEquals(treeUri.toString(), variable.value)
        assertEquals(treeUri.toString(), LocalHttpServer.documentRoot)
        assertNotNull(LocalHttpServer.lastError)
        assertTrue(LocalHttpServer.lastError!!.contains("accessible"))
    }

    @Test
    fun `a missing variable is reported but hosting still configured`() {
        action.variable = null
        action.handleFolderSelection(treeUri)

        // Hosting works independently of the variable operation...
        assertEquals(treeUri.toString(), LocalHttpServer.documentRoot)
        // ...and the missing variable is reported as a meaningful error.
        assertNotNull(LocalHttpServer.lastError)
        assertTrue(LocalHttpServer.lastError!!.contains("variable"))
    }

    // ---------- hardened "Host Directory" block ----------

    @Test
    fun `host directory block with empty input does not clear a configured root`() {
        LocalHttpServer.setDocumentRoot(treeUri.toString())
        val hostAction = HostDirectoryAction()
        hostAction.directory = Formula("")

        hostAction.update(0f)

        assertEquals("empty input must not clear the configured root", treeUri.toString(), LocalHttpServer.documentRoot)
        assertNotNull("empty input must report a meaningful error", LocalHttpServer.lastError)
    }

    @Test
    fun `host directory block with null input does not clear a configured root`() {
        LocalHttpServer.setDocumentRoot(treeUri.toString())
        val hostAction = HostDirectoryAction()

        hostAction.update(0f)

        assertEquals(treeUri.toString(), LocalHttpServer.documentRoot)
        assertNotNull(LocalHttpServer.lastError)
    }

    @Test
    fun `host directory block with a valid directory configures the root`() {
        val hostAction = HostDirectoryAction()
        hostAction.directory = Formula(treeUri.toString())

        hostAction.update(0f)

        assertEquals(treeUri.toString(), LocalHttpServer.documentRoot)
        assertNull(LocalHttpServer.lastError)
    }

    @Test
    fun `host directory block with an invalid directory reports an error and keeps the previous root`() {
        LocalHttpServer.setDocumentRoot(treeUri.toString())
        val hostAction = HostDirectoryAction()
        hostAction.directory = Formula(
            "content://${FakeGameDocumentsProvider.AUTHORITY}/document/primary%3ADocuments%2Fgame%2FGame%2Findex.html"
        )

        hostAction.update(0f)

        assertEquals(treeUri.toString(), LocalHttpServer.documentRoot)
        assertNotNull(LocalHttpServer.lastError)
        assertTrue(LocalHttpServer.lastError!!.contains("tree"))
    }
}
