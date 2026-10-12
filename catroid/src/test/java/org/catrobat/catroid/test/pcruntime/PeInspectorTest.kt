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

import org.catrobat.catroid.pcruntime.BinaryInspector
import org.catrobat.catroid.pcruntime.ElfConstants
import org.catrobat.catroid.pcruntime.PcBinaryFormat
import org.catrobat.catroid.pcruntime.PcCpuArch
import org.catrobat.catroid.pcruntime.PeConstants
import org.catrobat.catroid.pcruntime.PeInspector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PeInspectorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun write(name: String, bytes: ByteArray): File {
        val file = temporaryFolder.newFile(name)
        file.writeBytes(bytes)
        return file
    }

    @Test
    fun testDetectsX86_64Dll() {
        val file = write("d3d11.dll", TestBinaries.pe(PeConstants.IMAGE_FILE_MACHINE_AMD64, dll = true))
        val info = PeInspector.inspect(file)
        assertNotNull(info)
        assertEquals(PcCpuArch.X86_64, info!!.arch)
        assertTrue(info.isDll)
        assertTrue(info.describe().contains("DLL"))
    }

    @Test
    fun testDetectsI386Executable() {
        val file = write("game.exe", TestBinaries.pe(PeConstants.IMAGE_FILE_MACHINE_I386, dll = false))
        val info = PeInspector.inspect(file)
        assertNotNull(info)
        assertEquals(PcCpuArch.X86, info!!.arch)
        assertTrue(info.isExecutable)
        assertFalse(info.isDll)
    }

    @Test
    fun testDetectsArm64Pe() {
        val file = write("arm64.dll", TestBinaries.pe(PeConstants.IMAGE_FILE_MACHINE_ARM64))
        assertEquals(PcCpuArch.AARCH64, PeInspector.inspect(file)?.arch)
    }

    @Test
    fun testRejectsFileWithoutDosMagic() {
        val file = write("nope.bin", ByteArray(200) { 0x41 })
        assertNull(PeInspector.inspect(file))
    }

    @Test
    fun testRejectsFileWithBrokenPeSignature() {
        val bytes = TestBinaries.pe(PeConstants.IMAGE_FILE_MACHINE_AMD64)
        bytes[0x80] = 0x00 // corrupt "PE\0\0"
        val file = write("broken.exe", bytes)
        assertNull(PeInspector.inspect(file))
    }

    @Test
    fun testTruncatedFileDoesNotThrow() {
        val full = TestBinaries.pe(PeConstants.IMAGE_FILE_MACHINE_AMD64)
        for (length in 1 until 64) {
            val file = write("trunc_$length.dll", full.copyOfRange(0, length))
            assertNull(PeInspector.inspect(file))
        }
    }

    @Test
    fun testFormatDetectionDistinguishesElfAndPe() {
        val elf = write("probe", TestBinaries.elf())
        val pe = write("d3d9.dll", TestBinaries.pe(PeConstants.IMAGE_FILE_MACHINE_I386))
        val text = write("notes.txt", "plain text".toByteArray())

        assertEquals(PcBinaryFormat.ELF, BinaryInspector.detectFormat(elf))
        assertEquals(PcBinaryFormat.PE, BinaryInspector.detectFormat(pe))
        assertEquals(PcBinaryFormat.UNKNOWN, BinaryInspector.detectFormat(text))
    }

    @Test
    fun testBinaryInspectorDescribesBothFormats() {
        val elf = write("wine", TestBinaries.elf(machine = ElfConstants.EM_AARCH64))
        val pe = write("dxgi.dll", TestBinaries.pe(PeConstants.IMAGE_FILE_MACHINE_AMD64))

        assertTrue(BinaryInspector.describe(elf).contains("AArch64"))
        assertTrue(BinaryInspector.describe(pe).contains("x86-64"))
    }

    @Test
    fun testDetectFormatOnDirectoryIsUnknown() {
        val directory = temporaryFolder.newFolder("a-dir")
        assertEquals(PcBinaryFormat.UNKNOWN, BinaryInspector.detectFormat(directory))
    }
}
