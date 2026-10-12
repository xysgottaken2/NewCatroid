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
import org.catrobat.catroid.pcruntime.PeConstants

/**
 * Builds the smallest valid ELF and PE headers that the inspectors accept.
 *
 * Using synthetic headers instead of real binaries keeps the tests independent
 * of the host machine's architecture and makes the "malformed input" cases
 * expressible at all.
 */
object TestBinaries {

    fun elf(
        machine: Int = ElfConstants.EM_AARCH64,
        dynamic: Boolean = true,
        interpreter: String = if (dynamic) "/system/bin/linker64" else "",
        type: Int = ElfConstants.ET_DYN
    ): ByteArray {
        val phOff = 64
        val phEntSize = 56
        val phNum = if (interpreter.isEmpty()) 1 else 2
        val interpOffset = phOff + phEntSize * phNum
        val interpBytes = (interpreter + "\u0000").toByteArray(Charsets.ISO_8859_1)
        val bytes = ByteArray(interpOffset + interpBytes.size)

        bytes[0] = 0x7F
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[ElfConstants.EI_CLASS] = ElfConstants.ELFCLASS64.toByte()
        bytes[ElfConstants.EI_DATA] = ElfConstants.ELFDATA2LSB.toByte()

        short(bytes, 16, type)
        short(bytes, 18, machine)
        int(bytes, 20, 1)
        long(bytes, 32, phOff.toLong())
        short(bytes, 52, 64)
        short(bytes, 54, phEntSize)
        short(bytes, 56, phNum)

        int(bytes, phOff, ElfConstants.PT_LOAD)
        if (interpreter.isNotEmpty()) {
            val second = phOff + phEntSize
            int(bytes, second, ElfConstants.PT_INTERP)
            long(bytes, second + 8, interpOffset.toLong())
            long(bytes, second + 32, interpBytes.size.toLong())
            System.arraycopy(interpBytes, 0, bytes, interpOffset, interpBytes.size)
        }
        return bytes
    }

    fun pe(machine: Int, dll: Boolean = true): ByteArray {
        val peOffset = 0x80
        val optionalHeaderSize = 0x70
        val bytes = ByteArray(peOffset + 24 + optionalHeaderSize)

        // DOS header
        short(bytes, 0, PeConstants.DOS_MAGIC)
        int(bytes, 0x3C, peOffset)

        // PE signature + COFF file header
        int(bytes, peOffset, PeConstants.PE_SIGNATURE)
        short(bytes, peOffset + 4, machine)
        short(bytes, peOffset + 6, 3) // NumberOfSections
        int(bytes, peOffset + 8, 0) // TimeDateStamp
        int(bytes, peOffset + 12, 0) // PointerToSymbolTable
        int(bytes, peOffset + 16, 0) // NumberOfSymbols
        short(bytes, peOffset + 20, optionalHeaderSize)
        var characteristics = PeConstants.IMAGE_FILE_EXECUTABLE_IMAGE
        if (dll) {
            characteristics = characteristics or PeConstants.IMAGE_FILE_DLL
        }
        short(bytes, peOffset + 22, characteristics)

        // Optional header (PE32+ magic, subsystem = GUI)
        short(bytes, peOffset + 24, 0x20B)
        short(bytes, peOffset + 24 + 68, 2)
        return bytes
    }

    private fun short(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun int(bytes: ByteArray, offset: Int, value: Int) {
        for (index in 0 until 4) {
            bytes[offset + index] = ((value shr (index * 8)) and 0xFF).toByte()
        }
    }

    private fun long(bytes: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 8) {
            bytes[offset + index] = ((value shr (index * 8)) and 0xFF).toByte()
        }
    }
}
