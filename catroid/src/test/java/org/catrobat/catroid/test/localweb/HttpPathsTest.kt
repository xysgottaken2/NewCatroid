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

import org.catrobat.catroid.content.localweb.HttpPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpPathsTest {

    @Test
    fun `root path normalizes to empty string`() {
        assertEquals("", HttpPaths.normalize("/"))
        assertEquals("", HttpPaths.normalize(""))
        assertEquals("", HttpPaths.normalize("//"))
    }

    @Test
    fun `simple and nested paths are preserved`() {
        assertEquals("index.html", HttpPaths.normalize("/index.html"))
        assertEquals("sub/dir/style.css", HttpPaths.normalize("/sub/dir/style.css"))
        assertEquals("sub/dir/style.css", HttpPaths.normalize("sub/dir/style.css"))
    }

    @Test
    fun `dot segments are removed`() {
        assertEquals("a/b.txt", HttpPaths.normalize("/a/./b.txt"))
        assertEquals("a/b.txt", HttpPaths.normalize("/a/././b.txt"))
        assertEquals("", HttpPaths.normalize("/."))
    }

    @Test
    fun `parent segments pop within the root`() {
        assertEquals("b.txt", HttpPaths.normalize("/a/../b.txt"))
        assertEquals("a/c.txt", HttpPaths.normalize("/a/b/../c.txt"))
    }

    @Test
    fun `traversal above the root is rejected`() {
        assertNull(HttpPaths.normalize("/../secret.txt"))
        assertNull(HttpPaths.normalize("/.."))
        assertNull(HttpPaths.normalize("/a/../../secret.txt"))
        assertNull(HttpPaths.normalize("../secret.txt"))
    }

    @Test
    fun `encoded traversal is rejected`() {
        assertNull(HttpPaths.normalize("/%2e%2e/secret.txt"))
        assertNull(HttpPaths.normalize("/%2E%2E%2Fsecret.txt"))
        assertNull(HttpPaths.normalize("/a/%2e%2e/%2e%2e/secret.txt"))
        assertNull(HttpPaths.normalize("/%2e%2e%5csecret.txt"))
    }

    @Test
    fun `encoded normal segments are decoded`() {
        assertEquals("my file.txt", HttpPaths.normalize("/my%20file.txt"))
        assertEquals("a+b.txt", HttpPaths.normalize("/a%2Bb.txt"))
        assertEquals("a+b.txt", HttpPaths.normalize("/a+b.txt"))
        assertEquals("ümlaut.txt", HttpPaths.normalize("/%C3%BCmlaut.txt"))
    }

    @Test
    fun `backslashes and NUL bytes are rejected`() {
        assertNull(HttpPaths.normalize("/a\\b.txt"))
        assertNull(HttpPaths.normalize("/..%5csecret.txt"))
        assertNull(HttpPaths.normalize("/a%00b.txt"))
    }

    @Test
    fun `invalid percent encoding is rejected`() {
        assertNull(HttpPaths.normalize("/%zz.txt"))
        assertNull(HttpPaths.normalize("/%"))
    }

    @Test
    fun `prefix normalization`() {
        assertEquals("/", HttpPaths.normalizePrefix(null))
        assertEquals("/", HttpPaths.normalizePrefix(""))
        assertEquals("/", HttpPaths.normalizePrefix("  "))
        assertEquals("/", HttpPaths.normalizePrefix("/"))
        assertEquals("/site", HttpPaths.normalizePrefix("site"))
        assertEquals("/site", HttpPaths.normalizePrefix("/site/"))
        assertEquals("/site", HttpPaths.normalizePrefix("//site//"))
    }

    @Test
    fun `strip prefix`() {
        assertEquals("", HttpPaths.stripPrefix("/", "/"))
        assertEquals("a/b.txt", HttpPaths.stripPrefix("/a/b.txt", "/"))
        assertEquals("page.html", HttpPaths.stripPrefix("/site/page.html", "/site"))
        assertEquals("", HttpPaths.stripPrefix("/site", "/site"))
        assertNull(HttpPaths.stripPrefix("/other/page.html", "/site"))
        assertNull(HttpPaths.stripPrefix("/sitex/page.html", "/site"))
    }
}
