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

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Static description of a Portable Executable (Windows .exe or .dll).
 *
 * The PC runtime needs this to answer a question that users hit constantly:
 * "are these DXVK DLLs the 64-bit build?" DXVK, VKD3D-Proton and Wine's PE
 * side are all mingw built PE files, so the machine field is authoritative and
 * requires no Windows host to inspect.
 */
data class PeInfo(
    val machine: Int,
    val isDll: Boolean,
    val isExecutable: Boolean,
    val sections: Int,
    val subsystem: Int
) {
    val arch: PcCpuArch
        get() = when (machine) {
            PeConstants.IMAGE_FILE_MACHINE_AMD64 -> PcCpuArch.X86_64
            PeConstants.IMAGE_FILE_MACHINE_I386 -> PcCpuArch.X86
            PeConstants.IMAGE_FILE_MACHINE_ARM64 -> PcCpuArch.AARCH64
            PeConstants.IMAGE_FILE_MACHINE_ARM -> PcCpuArch.ARM32
            else -> PcCpuArch.UNKNOWN
        }

    fun describe(): String {
        val kind = when {
            isDll -> "DLL"
            isExecutable -> "EXE"
            else -> "PE"
        }
        return "$kind ${arch.displayName} (machine=0x${machine.toString(16)})"
    }
}

object PeConstants {
    const val IMAGE_FILE_MACHINE_I386 = 0x014C
    const val IMAGE_FILE_MACHINE_ARM = 0x01C0
    const val IMAGE_FILE_MACHINE_ARM64 = 0xAA64
    const val IMAGE_FILE_MACHINE_AMD64 = 0x8664
    const val DOS_MAGIC = 0x5A4D
    const val PE_SIGNATURE = 0x00004550
    const val IMAGE_FILE_DLL = 0x2000
    const val IMAGE_FILE_EXECUTABLE_IMAGE = 0x0002
}

/**
 * Dependency free PE reader supporting both PE32 and PE32+ layouts.
 */
object PeInspector {

    fun inspect(file: File): PeInfo? {
        if (!file.isFile || file.length() < 64) {
            return null
        }
        return try {
            RandomAccessFile(file, "r").use { inspect(it) }
        } catch (e: IOException) {
            null
        }
    }

    fun inspect(raf: RandomAccessFile): PeInfo? {
        if (raf.length() < 64) {
            return null
        }
        raf.seek(0)
        val dos = ByteArray(64)
        raf.readFully(dos)
        if (readUShort(dos, 0) != PeConstants.DOS_MAGIC) {
            return null
        }

        val peOffset = readInt(dos, 0x3C)
        if (peOffset <= 0 || peOffset.toLong() + 24 > raf.length()) {
            return null
        }

        raf.seek(peOffset.toLong())
        val coff = ByteArray(24)
        raf.readFully(coff)
        if (readInt(coff, 0) != PeConstants.PE_SIGNATURE) {
            return null
        }

        val machine = readUShort(coff, 4)
        val sections = readUShort(coff, 6)
        val characteristics = readUShort(coff, 22)

        // The optional header follows the COFF header. Its magic tells us
        // whether this is PE32 (0x10B) or PE32+ (0x20B); the subsystem field
        // sits at different offsets in the two layouts.
        var subsystem = 0
        val optionalHeaderSize = readUShort(coff, 20)
        if (optionalHeaderSize >= 70 && peOffset.toLong() + 24 + optionalHeaderSize <= raf.length()) {
            raf.seek(peOffset.toLong() + 24)
            val optional = ByteArray(minOf(optionalHeaderSize, 96))
            raf.readFully(optional)
            val magic = readUShort(optional, 0)
            if (magic == OPTIONAL_HEADER_MAGIC_PE32 || magic == OPTIONAL_HEADER_MAGIC_PE32_PLUS) {
                // Subsystem sits at the same offset in both optional header
                // layouts: PE32+ widens ImageBase but drops BaseOfData.
                if (optional.size >= SUBSYSTEM_OFFSET + 2) {
                    subsystem = readUShort(optional, SUBSYSTEM_OFFSET)
                }
            }
        }

        return PeInfo(
            machine = machine,
            isDll = (characteristics and PeConstants.IMAGE_FILE_DLL) != 0,
            isExecutable = (characteristics and PeConstants.IMAGE_FILE_EXECUTABLE_IMAGE) != 0,
            sections = sections,
            subsystem = subsystem
        )
    }

    private const val OPTIONAL_HEADER_MAGIC_PE32 = 0x10B
    private const val OPTIONAL_HEADER_MAGIC_PE32_PLUS = 0x20B
    private const val SUBSYSTEM_OFFSET = 68

    private fun readUShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}

/**
 * Detects the format of a binary and dispatches to the correct inspector.
 */
object BinaryInspector {

    fun detectFormat(file: File): PcBinaryFormat {
        if (!file.isFile || file.length() < 4) {
            return PcBinaryFormat.UNKNOWN
        }
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val head = ByteArray(4)
                raf.readFully(head)
                val magic = (head[0].toInt() and 0xFF shl 24) or
                    (head[1].toInt() and 0xFF shl 16) or
                    (head[2].toInt() and 0xFF shl 8) or
                    (head[3].toInt() and 0xFF)
                when {
                    magic == ElfConstants.MAGIC -> PcBinaryFormat.ELF
                    (head[0].toInt() and 0xFF) == 0x4D && (head[1].toInt() and 0xFF) == 0x5A -> PcBinaryFormat.PE
                    else -> PcBinaryFormat.UNKNOWN
                }
            }
        } catch (e: IOException) {
            PcBinaryFormat.UNKNOWN
        }
    }

    fun describe(file: File): String = when (detectFormat(file)) {
        PcBinaryFormat.ELF -> ElfInspector.inspect(file)?.describe() ?: "ELF (unreadable header)"
        PcBinaryFormat.PE -> PeInspector.inspect(file)?.describe() ?: "PE (unreadable header)"
        PcBinaryFormat.UNKNOWN -> "unknown format"
    }
}
