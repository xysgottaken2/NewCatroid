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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.function.Supplier

/**
 * SAF-level regression tests for the device-confirmed failure: the hosted
 * page's site root is nested inside the selected folder, so absolute URLs
 * like `/b/8b0b5899ed/title/logo.png` do not exist directly under the selected
 * SAF tree root. The deterministic path-suffix fallback must locate them
 * through the REAL SAF resolution path (FakeFileSystemDocumentsProvider: real
 * on-disk files, real ExternalStorageProvider semantics), with exact bytes.
 *
 * Simulated (Robolectric) - physical-device verification steps are documented
 * in docs/LOCAL_HTTP_SERVER_BLOCKS.md.
 */
@RunWith(RobolectricTestRunner::class)
class SafNestedSiteRootTest {

    private lateinit var storageRoot: File
    private lateinit var store: SafDocumentStore
    private var engine: HttpServerEngine? = null
    private var port = -1
    private val treeUri: Uri
        get() = Uri.parse(FakeFileSystemDocumentsProvider.treeUriFor("play"))

    private val failingPaths = listOf(
        "b/8b0b5899ed/title/logo.png" to "logo-bytes",
        "b/8b0b5899ed/title/spinner.png" to "spinner-bytes",
        "b/8b0b5899ed/title/chalet.woff" to "chalet-woff-bytes",
        "b/8b0b5899ed/title/art/beach_bg.webp" to "beach-bg-bytes",
        "b/8b0b5899ed/title/art/beach_fg.webp" to "beach-fg-bytes",
        "b/8b0b5899ed/title/art/ls2_background.webp" to "ls2-bg-bytes",
        "b/8b0b5899ed/title/art/ls2_foreground.webp" to "ls2-fg-bytes"
    )

    @Before
    fun setUp() {
        storageRoot = java.nio.file.Files.createTempDirectory("saf-nested-site-test").toFile()
        // The selected folder (SAF tree root "primary:play") = the download
        // root: entry page at its root, the actual site files nested below.
        val play = File(storageRoot, "play")
        write(play, "homepage.html", "<html>entry page</html>")
        write(play, "mirror/playgta5.com/index.html", "<html>site index</html>")
        write(play, "mirror/playgta5.com/b/8b0b5899ed/loader.js", "console.log('loader');")
        for ((path, content) in failingPaths) {
            write(play, "mirror/playgta5.com/$path", content)
        }
        write(play, "mirror/playgta5.com/data/bootset.json", "{\"files\":[]}")
    }

    @After
    fun tearDown() {
        engine?.stop()
        engine = null
        storageRoot.deleteRecursively()
    }

    private fun write(game: File, relativePath: String, content: String) {
        val file = File(game, relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content, StandardCharsets.UTF_8)
    }

    private fun startServer() {
        SafTestEnvironment.registerProvider(
            FakeFileSystemDocumentsProvider.AUTHORITY,
            FakeFileSystemDocumentsProvider(storageRoot, "primary:play")
        )
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        File(storageRoot, "play").walkTopDown().filter { it.isFile }.forEach { file ->
            val docId = "primary:play/" + file.relativeTo(File(storageRoot, "play")).invariantSeparatorsPath
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            shadowOf(resolver).registerInputStreamSupplier(uri, Supplier { ByteArrayInputStream(file.readBytes()) })
        }
        store = SafDocumentStore(RuntimeEnvironment.getApplication(), treeUri)
        val newEngine = HttpServerEngine(storeProvider = { store }, urlPrefixProvider = { "/" })
        val result = newEngine.start(0)
        engine = newEngine
        assertTrue("server should start", result is HttpServerEngine.Result.Success)
        port = (result as HttpServerEngine.Result.Success).port
    }

    @Test
    fun `device reported nested paths resolve through saf with exact contents`() {
        startServer()
        for ((path, content) in failingPaths) {
            val entry = store.resolve(path)
            assertNotNull("must resolve through SAF: $path", entry)
            val bytes = store.openInput(entry!!)?.readBytes()
            assertNotNull("must open through SAF: $path", bytes)
            assertEquals("exact content for $path", content, String(bytes!!, Charsets.UTF_8))
        }
    }

    @Test
    fun `entry page at the selected root still resolves directly`() {
        startServer()
        val entry = store.resolve("homepage.html")
        assertNotNull(entry)
        assertEquals("homepage.html", entry!!.name)
    }

    @Test
    fun `genuinely missing files do not resolve through the suffix search`() {
        startServer()
        assertNull(store.resolve("b/8b0b5899ed/title/missing.png"))
        assertNull(store.resolve("no/such/file.js"))
    }

    @Test
    fun `end to end http serves the device reported paths through saf`() {
        startServer()
        for ((path, content) in failingPaths) {
            val response = rawHttpRequest(port, "GET", "/$path")
            assertEquals("GET /$path", 200, response.statusCode)
            assertEquals("exact bytes for /$path", content, String(response.body, Charsets.UTF_8))
        }
        val entry = rawHttpRequest(port, "GET", "/homepage.html")
        assertEquals(200, entry.statusCode)
        assertEquals(404, rawHttpRequest(port, "GET", "/b/8b0b5899ed/title/missing.png").statusCode)
    }
}
