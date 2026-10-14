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

import org.catrobat.catroid.content.localweb.DocumentEntry
import org.catrobat.catroid.content.localweb.DocumentStore
import org.catrobat.catroid.content.localweb.FileDocumentStore
import org.catrobat.catroid.content.localweb.HttpServerEngine
import org.catrobat.catroid.content.localweb.PathSuffixSearcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Regression tests for the device-confirmed failure pattern: the hosted page
 * (a downloaded playgta5.com snapshot) references absolute URLs under
 * `/b/8b0b5899ed/title/...`, but the selected folder is the download's ROOT,
 * while the actual site files live in a nested site root
 * (`mirror/playgta5.com/b/8b0b5899ed/title/...`). Direct resolution under the
 * selected root fails ("document not resolved", HTTP 404) while root-level
 * files like `homepage.html` succeed.
 *
 * The fix is a deterministic path-suffix search: the full requested path must
 * exist (uniquely) somewhere under the selected root. These tests reproduce
 * the exact reported paths and verify the files are served with their correct
 * contents, plus the safety properties (no prefix stripping, unique match
 * only, traversal impossible, genuine misses still reported as null).
 *
 * Pure JVM (FileDocumentStore over a real temp directory + the real
 * HttpServerEngine over loopback).
 */
class PathSuffixSearchTest {

    private lateinit var rootDir: File
    private lateinit var store: FileDocumentStore
    private var engine: HttpServerEngine? = null
    private var port = -1

    // The exact paths reported as failing on the device.
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
        rootDir = java.nio.file.Files.createTempDirectory("suffix-search-test").toFile()
        // The selected folder = the download root (entry page at its root).
        write("homepage.html", "<html>entry page</html>")
        write("game.js", "console.log('repo-root copy');")
        // The nested site root with the actual files (mirror layout).
        write("mirror/playgta5.com/index.html", "<html>site index</html>")
        write("mirror/playgta5.com/b/8b0b5899ed/loader.js", "console.log('loader');")
        for ((path, content) in failingPaths) {
            write("mirror/playgta5.com/$path", content)
        }
        write("mirror/playgta5.com/data/bootset.json", "{\"files\":[]}")
        store = FileDocumentStore(rootDir)
    }

    @After
    fun tearDown() {
        engine?.stop()
        engine = null
        rootDir.deleteRecursively()
    }

    private fun write(relativePath: String, content: String) {
        val file = File(rootDir, relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content, StandardCharsets.UTF_8)
    }

    private fun startServer() {
        val newEngine = HttpServerEngine(storeProvider = { store }, urlPrefixProvider = { "/" })
        val result = newEngine.start(0)
        engine = newEngine
        assertTrue("server should start", result is HttpServerEngine.Result.Success)
        port = (result as HttpServerEngine.Result.Success).port
    }

    @Test
    fun `root level entry file still resolves directly`() {
        val entry = store.resolve("homepage.html")
        assertNotNull(entry)
        assertEquals("homepage.html", entry!!.name)
        assertEquals("<html>entry page</html>", String(store.openInput(entry)!!.readBytes(), Charsets.UTF_8))
    }

    @Test
    fun `all device-reported nested paths resolve with their exact contents`() {
        // The fallback lives at the request-mapping layer (the engine), so it
        // is exercised through PathSuffixSearcher and through HTTP below.
        val searcher = PathSuffixSearcher(store)
        for ((path, content) in failingPaths) {
            val entry = searcher.find(path)
            assertNotNull("must resolve: $path", entry)
            val bytes = store.openInput(entry!!)?.readBytes()
            assertNotNull("must open: $path", bytes)
            assertEquals("exact content for $path", content, String(bytes!!, Charsets.UTF_8))
        }
    }

    @Test
    fun `nested engine files resolve through the nested site root`() {
        val entry = PathSuffixSearcher(store).find("b/8b0b5899ed/loader.js")
        assertNotNull(entry)
        assertEquals(
            "console.log('loader');",
            String(store.openInput(entry!!)!!.readBytes(), Charsets.UTF_8)
        )
    }

    @Test
    fun `nested data files resolve through the nested site root`() {
        val entry = PathSuffixSearcher(store).find("data/bootset.json")
        assertNotNull(entry)
        assertEquals("{\"files\":[]}", String(store.openInput(entry!!)!!.readBytes(), Charsets.UTF_8))
    }

    @Test
    fun `genuinely missing files still resolve to null`() {
        val searcher = PathSuffixSearcher(store)
        assertNull(searcher.find("b/8b0b5899ed/title/does-not-exist.png"))
        assertNull(searcher.find("no/such/file.js"))
    }

    @Test
    fun `ambiguous suffix matches resolve to null`() {
        // A second site root at the same depth with the same relative path:
        // two candidates, no guess.
        write("mirror2/playgta5.com/b/8b0b5899ed/title/logo.png", "different-logo")
        assertNull(PathSuffixSearcher(store).find("b/8b0b5899ed/title/logo.png"))
    }

    @Test
    fun `anchor search resolves the nested site root with few lookups`() {
        // The device showed the first nested asset request taking ~16 s: the
        // suffix search scanned a huge tree. The anchor fast path (find the
        // first path segment as a directory near the top, resolve the rest
        // directly inside it) must cost a handful of lookups, and the site
        // root hint must make later requests a single lookup.
        var resolves = 0
        var listings = 0
        val counting = object : DocumentStore by store {
            override fun resolve(relativePath: String): DocumentEntry? {
                resolves++
                return store.resolve(relativePath)
            }

            override fun listChildren(directory: DocumentEntry): List<DocumentEntry> {
                listings++
                return store.listChildren(directory)
            }
        }
        val searcher = PathSuffixSearcher(counting)
        val entry = searcher.find("b/8b0b5899ed/title/logo.png")
        assertNotNull(entry)
        val firstPassLookups = resolves + listings
        assertTrue(
            "first search must be cheap (resolves=$resolves listings=$listings)",
            firstPassLookups in 1..20
        )

        resolves = 0
        listings = 0
        assertNotNull(searcher.find("b/8b0b5899ed/title/spinner.png"))
        assertEquals("later requests resolve via the site root hint in one lookup", 1, resolves)
        assertEquals(0, listings)
    }

    @Test
    fun `a miss with existing anchors is authoritative and cheap`() {
        // The game's io worker probes a per-batch cache URL (data/batchc/<hash>.bin)
        // that does not exist on a plain mirror: the miss must stay cheap (the
        // anchor search found the site root but the path does not resolve
        // inside it), never a full expensive tree scan.
        var resolves = 0
        var listings = 0
        val counting = object : DocumentStore by store {
            override fun resolve(relativePath: String): DocumentEntry? {
                resolves++
                return store.resolve(relativePath)
            }

            override fun listChildren(directory: DocumentEntry): List<DocumentEntry> {
                listings++
                return store.listChildren(directory)
            }
        }
        val searcher = PathSuffixSearcher(counting)
        assertNull(searcher.find("data/batchc/deadbeef.bin"))
        assertTrue(
            "miss must be cheap (resolves=$resolves listings=$listings)",
            resolves + listings <= 20
        )
    }

    @Test
    fun `traversal attempts still do not resolve`() {
        val searcher = PathSuffixSearcher(store)
        assertNull(searcher.find("../outside.txt"))
        assertNull(searcher.find("b/8b0b5899ed/title/../../escape.png"))
    }

    @Test
    fun `end to end http serves the device-reported paths with correct bytes and mime`() {
        startServer()
        val expectedMime = mapOf(
            "logo.png" to "image/png",
            "spinner.png" to "image/png",
            "chalet.woff" to "font/woff",
            "beach_bg.webp" to "image/webp",
            "beach_fg.webp" to "image/webp",
            "ls2_background.webp" to "image/webp",
            "ls2_foreground.webp" to "image/webp"
        )
        for ((path, content) in failingPaths) {
            val response = rawHttpRequest(port, "GET", "/$path")
            assertEquals("GET /$path", 200, response.statusCode)
            assertEquals("exact bytes for /$path", content, response.bodyText())
            val fileName = path.substringAfterLast('/')
            assertEquals("mime for $fileName", expectedMime[fileName], response.headers["content-type"])
        }
        // The entry page still loads at the root.
        val entry = rawHttpRequest(port, "GET", "/homepage.html")
        assertEquals(200, entry.statusCode)
        assertEquals("<html>entry page</html>", entry.bodyText())
        // A genuinely missing file still answers 404.
        assertEquals(404, rawHttpRequest(port, "GET", "/b/8b0b5899ed/title/missing.png").statusCode)
    }

    @Test
    fun `nested directory request resolves to the nested directory`() {
        startServer()
        val response = rawHttpRequest(port, "GET", "/b/8b0b5899ed/title/art/")
        assertEquals(200, response.statusCode)
        assertTrue(response.bodyText().contains("beach_bg.webp"))
    }
}
