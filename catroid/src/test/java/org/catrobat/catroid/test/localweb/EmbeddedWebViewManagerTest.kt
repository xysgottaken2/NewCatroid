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

import org.catrobat.catroid.content.localweb.EmbeddedWebViewManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Tests for the navigation origin policy of the embedded WebView: only
 * loopback origins are allowed by default, everything else is blocked.
 */
@RunWith(RobolectricTestRunner::class)
class EmbeddedWebViewManagerTest {

    @Test
    fun `loopback origins are allowed on any port`() {
        assertTrue(EmbeddedWebViewManager.isAllowedOrigin("http://127.0.0.1:8080/"))
        assertTrue(EmbeddedWebViewManager.isAllowedOrigin("http://127.0.0.1/"))
        assertTrue(EmbeddedWebViewManager.isAllowedOrigin("http://localhost:8080/index.html"))
        assertTrue(EmbeddedWebViewManager.isAllowedOrigin("https://localhost/"))
        assertTrue(EmbeddedWebViewManager.isAllowedOrigin("http://[::1]:8080/"))
    }

    @Test
    fun `external origins are blocked by default`() {
        assertFalse(EmbeddedWebViewManager.isAllowedOrigin("https://example.com/"))
        assertFalse(EmbeddedWebViewManager.isAllowedOrigin("http://192.168.1.10:8080/"))
        assertFalse(EmbeddedWebViewManager.isAllowedOrigin("http://10.0.0.2/"))
        // Host names that merely contain a loopback substring must not pass.
        assertFalse(EmbeddedWebViewManager.isAllowedOrigin("http://127.0.0.1.evil.com/"))
        assertFalse(EmbeddedWebViewManager.isAllowedOrigin("http://localhost.evil.com/"))
    }

    @Test
    fun `non http schemes are blocked`() {
        assertFalse(EmbeddedWebViewManager.isAllowedOrigin("file:///etc/passwd"))
        assertFalse(EmbeddedWebViewManager.isAllowedOrigin("content://media/external/file"))
        assertFalse(EmbeddedWebViewManager.isAllowedOrigin("javascript:alert(1)"))
        assertFalse(EmbeddedWebViewManager.isAllowedOrigin("ftp://127.0.0.1/"))
    }

    @Test
    fun `empty and invalid urls are blocked`() {
        assertFalse(EmbeddedWebViewManager.isAllowedOrigin(null))
        assertFalse(EmbeddedWebViewManager.isAllowedOrigin(""))
        assertFalse(EmbeddedWebViewManager.isAllowedOrigin("   "))
    }

    @Test
    fun `open rejects blocked urls and records an error`() {
        val context = RuntimeEnvironment.getApplication()
        assertFalse(EmbeddedWebViewManager.open(context, "https://example.com/"))
        assertNotNull(EmbeddedWebViewManager.lastError)
        assertTrue(EmbeddedWebViewManager.lastError!!.contains("blocked", ignoreCase = true))
    }

    @Test
    fun `open rejects empty urls and records an error`() {
        val context = RuntimeEnvironment.getApplication()
        assertFalse(EmbeddedWebViewManager.open(context, ""))
        assertNotNull(EmbeddedWebViewManager.lastError)
    }

    @Test
    fun `no webview is open initially and close is safe`() {
        assertFalse(EmbeddedWebViewManager.isOpen)
        EmbeddedWebViewManager.close()
        EmbeddedWebViewManager.close()
        assertFalse(EmbeddedWebViewManager.isOpen)
        assertNull(EmbeddedWebViewManager.lastError)
    }
}
