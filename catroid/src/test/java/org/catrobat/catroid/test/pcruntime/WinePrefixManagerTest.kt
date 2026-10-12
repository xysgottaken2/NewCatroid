/*
 * Catroid: An on-device visual programming system for Android devices
 * Copyright (C) 2010-2024 The Catrobat Team
 * (<http://developer.catrobat.org/credits>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * An additional term exception under section 7 of the GNU Affero
 * General Public License, version 3, is available at
 * http://developer.catrobat.org/license_additional_term
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.catrobat.catroid.test.pcruntime

import org.catrobat.catroid.pcruntime.PcErrorCode
import org.catrobat.catroid.pcruntime.PcRuntimeLog
import org.catrobat.catroid.pcruntime.WinePrefixManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WinePrefixManagerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val log = PcRuntimeLog(50)

    private fun manager(): WinePrefixManager =
        WinePrefixManager(temporaryFolder.newFolder("prefixes"), log)

    @Test
    fun testValidPrefixNames() {
        val manager = manager()
        assertTrue(manager.isValidPrefixName("newcatroid"))
        assertTrue(manager.isValidPrefixName("game-1"))
        assertTrue(manager.isValidPrefixName("a"))
        assertTrue(manager.isValidPrefixName("  padded  "))
    }

    @Test
    fun testNamesThatWouldEscapeThePrefixRootAreRejected() {
        val manager = manager()
        assertFalse(manager.isValidPrefixName(""))
        assertFalse(manager.isValidPrefixName("   "))
        assertFalse(manager.isValidPrefixName("."))
        assertFalse(manager.isValidPrefixName(".."))
        assertFalse(manager.isValidPrefixName("../../etc"))
        assertFalse(manager.isValidPrefixName("a/b"))
        assertFalse(manager.isValidPrefixName("a\\b"))
        assertFalse(manager.isValidPrefixName("a\u0000b"))
        assertFalse(manager.isValidPrefixName("x".repeat(65)))
    }

    @Test
    fun testPrepareCreatesTheDocumentedLayout() {
        val manager = manager()
        val result = manager.prepare("game1")

        assertTrue(result.success)
        assertNotNull(result.root)
        WinePrefixManager.DIRECTORY_LAYOUT.forEach { relative ->
            assertTrue(
                "missing $relative",
                File(result.root, relative).isDirectory
            )
        }
    }

    @Test
    fun testPrepareReportsThatWinebootIsStillRequired() {
        val manager = manager()
        val result = manager.prepare("game2")

        assertTrue(result.winebootRequired)
        assertTrue(result.summary().contains("wineboot"))
        // Configured as a reminder that the runtime must never claim a working
        // Windows prefix was created.
        assertTrue(result.summary().contains("Not yet a working Windows prefix"))
    }

    @Test
    fun testPrepareIsIdempotent() {
        val manager = manager()
        val first = manager.prepare("same")
        val second = manager.prepare("same")

        assertTrue(first.success)
        assertTrue(second.success)
        assertTrue(second.createdDirectories.isEmpty())
        assertEquals(first.root, second.root)
    }

    @Test
    fun testInvalidNameProducesPathError() {
        val manager = manager()
        val result = manager.prepare("../escape")

        assertFalse(result.success)
        assertEquals(PcErrorCode.PREFIX_PATH_INVALID, result.error?.code)
        assertNull(result.root)
    }

    @Test
    fun testExistsReflectsDriveCLayout() {
        val manager = manager()
        assertFalse(manager.exists("notprepared"))
        manager.prepare("prepared")
        assertTrue(manager.exists("prepared"))
    }

    @Test
    fun testPrefixDirectoryIsNestedUnderTheRoot() {
        val manager = manager()
        val directory = manager.prefixDirectory("nested")
        assertEquals("nested", directory.name)
        assertTrue(directory.absolutePath.contains("prefixes"))
    }

    @Test
    fun testDriveCDeviceLinkIsCreatedWhenSymlinksAreSupported() {
        val manager = manager()
        val result = manager.prepare("links")
        val link = File(result.root, "dosdevices/c:")

        if (result.createdSymlinks.isNotEmpty()) {
            assertTrue(link.exists())
            assertEquals("../drive_c", java.nio.file.Files.readSymbolicLink(link.toPath()).toString())
        } else {
            // Filesystems without symlink support must not fail the call.
            assertTrue(result.success)
        }
    }

    @Test
    fun testSystem32AndSyswow64AreBothPresent() {
        val manager = manager()
        val result = manager.prepare("arch")
        assertTrue(File(result.root, "drive_c/windows/system32").isDirectory)
        assertTrue(File(result.root, "drive_c/windows/syswow64").isDirectory)
    }
}
