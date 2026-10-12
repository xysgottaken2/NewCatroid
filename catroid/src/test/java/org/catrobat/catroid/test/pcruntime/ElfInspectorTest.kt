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

import org.catrobat.catroid.pcruntime.ElfConstants
import org.catrobat.catroid.pcruntime.ElfInspector
import org.catrobat.catroid.pcruntime.PcCpuArch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/**
 * The ELF reader decides whether a binary may be executed at all, so its
 * behaviour on valid, truncated and hostile headers is tested directly with
 * synthetic ELF files rather than with binaries from the host system.
 */
class ElfInspectorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun writeFile(name: String, bytes: ByteArray): File {
        val file = temporaryFolder.newFile(name)
        file.writeBytes(bytes)
        return file
    }

    private fun buildElf64(
        machine: Int = ElfConstants.EM_AARCH64,
        type: Int = ElfConstants.ET_DYN,
        interpreter: String = "/system/bin/linker64",
        littleEndian: Boolean = true
    ): ByteArray {
        val phOff = 64
        val phEntSize = 56
        val phNum = if (interpreter.isEmpty()) 1 else 2
        val interpOffset = phOff + phEntSize * phNum
        val interpBytes = (interpreter + "\u0000").toByteArray(Charsets.ISO_8859_1)
        val total = interpOffset + interpBytes.size

        val bytes = ByteArray(total)
        // e_ident
        bytes[0] = 0x7F
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[ElfConstants.EI_CLASS] = ElfConstants.ELFCLASS64.toByte()
        bytes[ElfConstants.EI_DATA] = ElfConstants.ELFDATA2LSB.toByte()

        putShort(bytes, 16, type, littleEndian)
        putShort(bytes, 18, machine, littleEndian)
        putInt(bytes, 20, 1, littleEndian)          // e_version
        putLong(bytes, 24, 0, littleEndian)         // e_entry
        putLong(bytes, 32, phOff.toLong(), littleEndian)  // e_phoff
        putShort(bytes, 52, if (littleEndian) 64 else 0, littleEndian) // e_ehsize
        putShort(bytes, 54, phEntSize, littleEndian)      // e_phentsize
        putShort(bytes, 56, phNum, littleEndian)          // e_phnum

        // first program header: a PT_LOAD
        putInt(bytes, phOff, ElfConstants.PT_LOAD, littleEndian)

        if (interpreter.isNotEmpty()) {
            val second = phOff + phEntSize
            putInt(bytes, second, ElfConstants.PT_INTERP, littleEndian)
            putInt(bytes, second + 4, 5, littleEndian) // p_flags = R|X
            putLong(bytes, second + 8, interpOffset.toLong(), littleEndian)
            putLong(bytes, second + 32, interpBytes.size.toLong(), littleEndian)
            System.arraycopy(interpBytes, 0, bytes, interpOffset, interpBytes.size)
        }
        return bytes
    }

    private fun buildElf32(machine: Int): ByteArray {
        val bytes = ByteArray(52)
        bytes[0] = 0x7F
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[ElfConstants.EI_CLASS] = ElfConstants.ELFCLASS32.toByte()
        bytes[ElfConstants.EI_DATA] = ElfConstants.ELFDATA2LSB.toByte()
        putShort(bytes, 16, ElfConstants.ET_DYN, true)
        putShort(bytes, 18, machine, true)
        putInt(bytes, 28, 0, true)   // e_phoff = 0 -> no program headers
        putShort(bytes, 40, 52, true)
        putShort(bytes, 42, 32, true)
        putShort(bytes, 44, 0, true)
        return bytes
    }

    private fun putShort(bytes: ByteArray, offset: Int, value: Int, little: Boolean) {
        if (little) {
            bytes[offset] = (value and 0xFF).toByte()
            bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        } else {
            bytes[offset] = ((value shr 8) and 0xFF).toByte()
            bytes[offset + 1] = (value and 0xFF).toByte()
        }
    }

    private fun putInt(bytes: ByteArray, offset: Int, value: Int, little: Boolean) {
        for (index in 0 until 4) {
            val byteIndex = if (little) offset + index else offset + 3 - index
            bytes[byteIndex] = ((value shr (index * 8)) and 0xFF).toByte()
        }
    }

    private fun putLong(bytes: ByteArray, offset: Int, value: Long, little: Boolean) {
        for (index in 0 until 8) {
            val byteIndex = if (little) offset + index else offset + 7 - index
            bytes[byteIndex] = ((value shr (index * 8)) and 0xFF).toByte()
        }
    }

    @Test
    fun testDetectsAarch64PieWithSystemLinker() {
        val file = writeFile("probe", buildElf64())
        val info = ElfInspector.inspect(file)
        assertNotNull(info)
        info!!
        assertEquals(PcCpuArch.AARCH64, info.arch)
        assertEquals(ElfConstants.ET_DYN, info.type)
        assertEquals("/system/bin/linker64", info.interpreter)
        assertTrue(info.isDynamicExecutable)
    }

    @Test
    fun testDetectsX8664Binary() {
        val file = writeFile("wine", buildElf64(machine = ElfConstants.EM_X86_64, interpreter = "/lib64/ld-linux.so"))
        val info = ElfInspector.inspect(file)
        assertNotNull(info)
        assertEquals(PcCpuArch.X86_64, info!!.arch)
    }

    @Test
    fun testDetectsI386Binary() {
        val file = writeFile("box32", buildElf64(machine = ElfConstants.EM_386))
        assertEquals(PcCpuArch.X86, ElfInspector.inspect(file)?.arch)
    }

    @Test
    fun testStaticBinaryHasNoInterpreter() {
        val file = writeFile("static", buildElf64(interpreter = ""))
        val info = ElfInspector.inspect(file)
        assertNotNull(info)
        assertTrue(info!!.isStaticExecutable)
        assertEquals("", info.interpreter)
    }

    @Test
    fun testElf32IsParsed() {
        val file = writeFile("arm32", buildElf32(ElfConstants.EM_ARM))
        val info = ElfInspector.inspect(file)
        assertNotNull(info)
        assertEquals(PcCpuArch.ARM32, info!!.arch)
        assertEquals(1, info.elfClass)
    }

    @Test
    fun testNonElfFileReturnsNull() {
        val file = writeFile("text.txt", "this is definitely not an ELF file".toByteArray())
        assertNull(ElfInspector.inspect(file))
    }

    @Test
    fun testTruncatedHeaderDoesNotThrow() {
        val full = buildElf64()
        for (length in 1 until 64) {
            val file = writeFile("truncated_$length", full.copyOfRange(0, length))
            assertNull(ElfInspector.inspect(file))
        }
    }

    @Test
    fun testCorruptProgramHeaderCountIsRejected() {
        val bytes = buildElf64()
        putShort(bytes, 56, 5000, true) // e_phnum far beyond the file size
        val file = writeFile("corrupt", bytes)
        val info = ElfInspector.inspect(file)
        // The header itself is still readable; only the interpreter lookup must
        // give up rather than reading out of bounds.
        assertNotNull(info)
        assertEquals("", info!!.interpreter)
    }

    @Test
    fun testDescribeMentionsArchitectureAndInterpreter() {
        val file = writeFile("describe", buildElf64())
        val description = ElfInspector.inspect(file)!!.describe()
        assertTrue(description.contains("AArch64"))
        assertTrue(description.contains("/system/bin/linker64"))
    }

    @Test
    fun testRandomAccessFileOverloadMatchesFileOverload() {
        val file = writeFile("raf", buildElf64())
        RandomAccessFile(file, "r").use { raf ->
            val fromStream = ElfInspector.inspect(raf)!!
            val fromFile = ElfInspector.inspect(file)!!
            assertEquals(fromFile.interpreter, fromStream.interpreter)
            assertEquals(fromFile.machine, fromStream.machine)
        }
    }
}
