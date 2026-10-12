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

package org.catrobat.catroid.pcruntime

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Result of a static ELF inspection.
 *
 * @param elfClass 1 for ELF32, 2 for ELF64.
 * @param machine value of e_machine (0xB7 = AArch64, 0x3E = x86-64, 0x03 = i386, 0x28 = ARM).
 * @param type value of e_type (2 = ET_EXEC, 3 = ET_DYN/PIE).
 * @param interpreter contents of the PT_INTERP segment, empty for static binaries.
 */
data class ElfInfo(
    val elfClass: Int,
    val littleEndian: Boolean,
    val machine: Int,
    val type: Int,
    val interpreter: String
) {
    val arch: PcCpuArch
        get() = when (machine) {
            0xB7 -> PcCpuArch.AARCH64
            0x28 -> PcCpuArch.ARM32
            0x3E -> PcCpuArch.X86_64
            0x03 -> PcCpuArch.X86
            else -> PcCpuArch.UNKNOWN
        }

    val isDynamicExecutable: Boolean
        get() = interpreter.isNotEmpty()

    val isStaticExecutable: Boolean
        get() = interpreter.isEmpty() && (type == ElfConstants.ET_EXEC || type == ElfConstants.ET_DYN)

    /** Which C library the binary expects; decides whether it can start here. */
    val toolchain: PcToolchain
        get() = ToolchainCompatibility.classify(interpreter)

    fun describe(): String = buildString {
        append("ELF").append(if (elfClass == 2) "64" else "32")
        append(" ").append(arch.displayName)
        append(if (type == ElfConstants.ET_DYN) " PIE" else " exec")
        if (isDynamicExecutable) {
            append(" interp=").append(interpreter)
            if (toolchain != PcToolchain.UNKNOWN) {
                append(" (").append(toolchain.displayName).append(")")
            }
        } else {
            append(" static")
        }
    }
}

object ElfConstants {
    const val EI_CLASS = 4
    const val EI_DATA = 5
    const val ELFCLASS32 = 1
    const val ELFCLASS64 = 2
    const val ELFDATA2LSB = 1
    const val ELFDATA2MSB = 2
    const val ET_EXEC = 2
    const val ET_DYN = 3
    const val PT_LOAD = 1
    const val PT_INTERP = 3
    const val EM_386 = 0x03
    const val EM_ARM = 0x28
    const val EM_X86_64 = 0x3E
    const val EM_AARCH64 = 0xB7
    const val MAGIC = 0x7F454C46
}

/**
 * Minimal, dependency free ELF reader. It only reads what the runtime needs:
 * target architecture, executable kind and the program interpreter. No attempt
 * is made to validate sections, symbols or to execute anything.
 */
object ElfInspector {

    private const val EI_NIDENT = 16
    private const val ELF32_PHENT_SIZE = 32
    private const val ELF64_PHENT_SIZE = 56
    private const val MAX_INTERPRETER_LENGTH = 256

    fun inspect(file: File): ElfInfo? {
        if (!file.isFile || file.length() < EI_NIDENT) {
            return null
        }
        return try {
            RandomAccessFile(file, "r").use { inspect(it) }
        } catch (e: IOException) {
            null
        }
    }

    fun inspect(raf: RandomAccessFile): ElfInfo? {
        if (raf.length() < EI_NIDENT) {
            return null
        }
        raf.seek(0)
        val ident = ByteArray(EI_NIDENT)
        raf.readFully(ident)

        // EI_MAG0..EI_MAG3 is the fixed byte sequence 0x7F 'E' 'L' 'F', so it is
        // always read in file order and never according to EI_DATA.
        if (readInt(ident, 0, false) != ElfConstants.MAGIC) {
            return null
        }
        val elfClass = ident[ElfConstants.EI_CLASS].toInt()
        val littleEndian = ident[ElfConstants.EI_DATA].toInt() == ElfConstants.ELFDATA2LSB
        if (elfClass != ElfConstants.ELFCLASS32 && elfClass != ElfConstants.ELFCLASS64) {
            return null
        }

        val is64 = elfClass == ElfConstants.ELFCLASS64
        val headerSize = if (is64) 64 else 52
        if (raf.length() < headerSize) {
            return null
        }

        raf.seek(0)
        val header = ByteArray(headerSize)
        raf.readFully(header)

        val type = readShort(header, 16, littleEndian)
        val machine = readShort(header, 18, littleEndian)

        val phOff = if (is64) readLong(header, 32, littleEndian) else readInt(header, 28, littleEndian).toLong()
        val phEntSize = if (is64) readShort(header, 54, littleEndian) else readShort(header, 42, littleEndian)
        val phNum = if (is64) readShort(header, 56, littleEndian) else readShort(header, 44, littleEndian)

        val interpreter = if (phOff > 0 && phEntSize > 0 && phNum > 0) {
            findInterpreter(raf, littleEndian, is64, phOff, phEntSize, phNum)
        } else {
            ""
        }

        return ElfInfo(elfClass, littleEndian, machine, type, interpreter)
    }

    private fun findInterpreter(
        raf: RandomAccessFile,
        littleEndian: Boolean,
        is64: Boolean,
        phOff: Long,
        phEntSize: Int,
        phNum: Int
    ): String {
        // Guard against hostile or corrupted headers before seeking.
        if (phNum > 512 || phOff > raf.length()) {
            return ""
        }
        val entrySize = if (is64) ELF64_PHENT_SIZE else ELF32_PHENT_SIZE
        if (phEntSize < entrySize) {
            return ""
        }

        for (index in 0 until phNum) {
            val offset = phOff + index.toLong() * phEntSize
            if (offset + entrySize > raf.length()) {
                return ""
            }
            raf.seek(offset)
            val entry = ByteArray(entrySize)
            raf.readFully(entry)

            val pType = readInt(entry, 0, littleEndian)
            if (pType != ElfConstants.PT_INTERP) {
                continue
            }
            val fileOffset = if (is64) readLong(entry, 8, littleEndian) else readInt(entry, 4, littleEndian).toLong()
            val fileSize = if (is64) readLong(entry, 32, littleEndian) else readInt(entry, 16, littleEndian).toLong()

            if (fileSize <= 0 || fileSize > MAX_INTERPRETER_LENGTH || fileOffset < 0 ||
                fileOffset + fileSize > raf.length()
            ) {
                return ""
            }
            raf.seek(fileOffset)
            val raw = ByteArray(fileSize.toInt())
            raf.readFully(raw)
            return raw.toString(Charsets.ISO_8859_1).trimEnd('\u0000')
        }
        return ""
    }

    private fun readInt(bytes: ByteArray, offset: Int, littleEndian: Boolean): Int {
        ensureAvailable(bytes, offset, 4)
        return if (littleEndian) {
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
        } else {
            ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
        }
    }

    private fun readShort(bytes: ByteArray, offset: Int, littleEndian: Boolean): Int {
        ensureAvailable(bytes, offset, 2)
        return if (littleEndian) {
            (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
        } else {
            ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
        }
    }

    private fun readLong(bytes: ByteArray, offset: Int, littleEndian: Boolean): Long {
        ensureAvailable(bytes, offset, 8)
        var result = 0L
        for (index in 0 until 8) {
            val byteIndex = if (littleEndian) offset + index else offset + 7 - index
            result = result or ((bytes[byteIndex].toLong() and 0xFF) shl (index * 8))
        }
        return result
    }

    private fun ensureAvailable(bytes: ByteArray, offset: Int, length: Int) {
        if (offset < 0 || offset + length > bytes.size) {
            throw EOFException("ELF structure truncated at $offset")
        }
    }
}
