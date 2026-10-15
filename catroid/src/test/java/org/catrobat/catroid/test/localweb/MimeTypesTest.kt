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

import org.catrobat.catroid.content.localweb.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test

class MimeTypesTest {

    @Test
    fun `web resources`() {
        assertEquals("text/html; charset=utf-8", MimeTypes.forPath("index.html"))
        assertEquals("text/html; charset=utf-8", MimeTypes.forPath("/a/b/page.htm"))
        assertEquals("text/css; charset=utf-8", MimeTypes.forPath("style.css"))
        assertEquals("text/javascript; charset=utf-8", MimeTypes.forPath("app.js"))
        assertEquals("text/javascript; charset=utf-8", MimeTypes.forPath("app.mjs"))
        assertEquals("application/json; charset=utf-8", MimeTypes.forPath("data.json"))
        assertEquals("application/wasm", MimeTypes.forPath("module.wasm"))
        assertEquals("text/plain; charset=utf-8", MimeTypes.forPath("notes.txt"))
        assertEquals("image/svg+xml; charset=utf-8", MimeTypes.forPath("icon.svg"))
    }

    @Test
    fun `images fonts media`() {
        assertEquals("image/png", MimeTypes.forPath("a.png"))
        assertEquals("image/jpeg", MimeTypes.forPath("a.jpg"))
        assertEquals("image/jpeg", MimeTypes.forPath("a.jpeg"))
        assertEquals("image/gif", MimeTypes.forPath("a.gif"))
        assertEquals("image/webp", MimeTypes.forPath("a.webp"))
        assertEquals("image/x-icon", MimeTypes.forPath("favicon.ico"))
        assertEquals("font/woff2", MimeTypes.forPath("f.woff2"))
        assertEquals("font/woff", MimeTypes.forPath("f.woff"))
        assertEquals("font/ttf", MimeTypes.forPath("f.ttf"))
        assertEquals("audio/mpeg", MimeTypes.forPath("s.mp3"))
        assertEquals("audio/ogg", MimeTypes.forPath("s.ogg"))
        assertEquals("video/mp4", MimeTypes.forPath("v.mp4"))
        assertEquals("video/webm", MimeTypes.forPath("v.webm"))
    }

    @Test
    fun `documents and archives`() {
        assertEquals("application/pdf", MimeTypes.forPath("doc.pdf"))
        assertEquals("application/zip", MimeTypes.forPath("a.zip"))
        assertEquals("application/vnd.android.package-archive", MimeTypes.forPath("app.apk"))
        assertEquals("application/xml", MimeTypes.forPath("feed.xml"))
        assertEquals("text/csv; charset=utf-8", MimeTypes.forPath("table.csv"))
    }

    @Test
    fun `extension matching is case insensitive and path aware`() {
        assertEquals("text/html; charset=utf-8", MimeTypes.forPath("/deep/DIR/INDEX.HTML"))
        assertEquals("image/png", MimeTypes.forPath("dir.with.dot/file.PNG"))
    }

    @Test
    fun `unknown or missing extensions fall back to octet-stream`() {
        assertEquals("application/octet-stream", MimeTypes.forPath("noextension"))
        assertEquals("application/octet-stream", MimeTypes.forPath("file.unknownext"))
        assertEquals("application/octet-stream", MimeTypes.forPath(".hidden"))
        assertEquals("application/octet-stream", MimeTypes.forPath("trailingdot."))
        assertEquals("application/octet-stream", MimeTypes.forPath(""))
        assertEquals("application/octet-stream", MimeTypes.forPath(null))
    }
}
