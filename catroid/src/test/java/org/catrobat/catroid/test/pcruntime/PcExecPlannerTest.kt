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
import org.catrobat.catroid.pcruntime.PcExecMode
import org.catrobat.catroid.pcruntime.PcExecPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The exec planner encodes Android's W^X rule. Getting it wrong is the
 * difference between a working native runtime and a stream of "Permission
 * denied" failures that look like a bug in the emulator, so every branch of the
 * decision table is pinned down here.
 */
class PcExecPlannerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun aarch64Pie(): ByteArray = Elfs.aarch64(dynamic = true)

    private fun aarch64Static(): ByteArray = Elfs.aarch64(dynamic = false)

    private fun x86_64Pie(): ByteArray = Elfs.x86_64()

    private fun write(name: String, bytes: ByteArray): File {
        val file = temporaryFolder.newFile(name)
        file.writeBytes(bytes)
        return file
    }

    @Test
    fun testMissingFileIsBlocked() {
        val missing = File(temporaryFolder.root, "does-not-exist")
        val plan = PcExecPlanner.plan(missing, null)
        assertEquals(PcExecMode.BLOCKED, plan.mode)
        assertTrue(plan.blockedReason!!.contains("does not exist"))
    }

    @Test
    fun testDirectoryIsBlocked() {
        val directory = temporaryFolder.newFolder("a-directory")
        val plan = PcExecPlanner.plan(directory, null)
        assertEquals(PcExecMode.BLOCKED, plan.mode)
        assertTrue(plan.blockedReason!!.contains("regular file"))
    }

    @Test
    fun testNonElfIsBlocked() {
        val file = write("script.sh", "#!/system/bin/sh\necho hi\n".toByteArray())
        val plan = PcExecPlanner.plan(file, null)
        assertEquals(PcExecMode.BLOCKED, plan.mode)
        assertTrue(plan.blockedReason!!.contains("not an ELF"))
    }

    @Test
    fun testX86_64BinaryIsBlockedWithTranslationHint() {
        val file = write("box64-guest", x86_64Pie())
        val plan = PcExecPlanner.plan(file, null)
        assertEquals(PcExecMode.BLOCKED, plan.mode)
        assertTrue(plan.blockedReason!!.contains("Box64"))
    }

    @Test
    fun testDynamicAarch64OutsideNativeLibDirUsesSystemLinker() {
        val file = write("pcrt_probe", aarch64Pie())
        val plan = PcExecPlanner.plan(file, nativeLibraryDir = null, arguments = listOf("--json"))

        assertEquals(PcExecMode.SYSTEM_LINKER, plan.mode)
        assertEquals(PcExecPlanner.DEFAULT_LINKER_64, plan.command[0])
        assertEquals(file.absolutePath, plan.command[1])
        assertEquals("--json", plan.command[2])
        assertTrue(plan.environment.containsKey("PATH"))
    }

    @Test
    fun testStaticBinaryOutsideNativeLibDirIsBlocked() {
        val file = write("static-tool", aarch64Static())
        val plan = PcExecPlanner.plan(file, null)
        assertEquals(PcExecMode.BLOCKED, plan.mode)
        assertTrue(plan.blockedReason!!.contains("statically linked"))
    }

    @Test
    fun testExecutableInsideNativeLibDirIsLaunchedDirectly() {
        val nativeLibraryDir = temporaryFolder.newFolder("lib", "arm64")
        val file = File(nativeLibraryDir, "libhelper.so")
        file.writeBytes(aarch64Pie())

        val plan = PcExecPlanner.plan(
            file,
            nativeLibraryDir = nativeLibraryDir.absolutePath,
            arguments = listOf("arg1")
        )

        assertEquals(PcExecMode.DIRECT, plan.mode)
        assertEquals(file.absolutePath, plan.command[0])
        assertEquals(listOf(file.absolutePath, "arg1"), plan.command)
    }

    @Test
    fun testStaticBinaryInsideNativeLibDirIsStillDirectlyLaunchable() {
        // The native library directory is read-only from the app's point of view
        // and is explicitly permitted to be executed, so a static binary there
        // is fine even though the same binary in app data would be blocked.
        val nativeLibraryDir = temporaryFolder.newFolder("lib", "arm64")
        val file = File(nativeLibraryDir, "libstatic.so")
        file.writeBytes(aarch64Static())

        val plan = PcExecPlanner.plan(file, nativeLibraryDir.absolutePath)
        assertEquals(PcExecMode.DIRECT, plan.mode)
    }

    @Test
    fun testEnvironmentCarriesCustomVariablesAndLibraryPath() {
        val file = write("wine", aarch64Pie())
        val libraryDir = temporaryFolder.newFolder("wine-lib")

        val plan = PcExecPlanner.plan(
            executable = file,
            nativeLibraryDir = null,
            extraLibraries = listOf(libraryDir),
            extraEnvironment = mapOf("WINEPREFIX" to "/data/prefix", "BOX64_LOG" to "1")
        )

        assertEquals(libraryDir.absolutePath, plan.environment["LD_LIBRARY_PATH"])
        assertEquals("/data/prefix", plan.environment["WINEPREFIX"])
        assertEquals("1", plan.environment["BOX64_LOG"])
    }

    @Test
    fun testNativeLibraryDirDetectionRejectsSiblingPrefixes() {
        val nativeLibraryDir = temporaryFolder.newFolder("lib", "arm64")
        val sibling = temporaryFolder.newFolder("lib", "arm64-extra")
        val file = File(sibling, "libhelper.so")
        file.writeBytes(aarch64Pie())

        assertTrue(
            PcExecPlanner.isInsideNativeLibraryDir(
                File(nativeLibraryDir, "x.so"),
                nativeLibraryDir.absolutePath
            )
        )
        assertFalse(PcExecPlanner.isInsideNativeLibraryDir(file, nativeLibraryDir.absolutePath))
        assertFalse(PcExecPlanner.isInsideNativeLibraryDir(file, null))
    }

    @Test
    fun testPlanDescribeIsHumanReadable() {
        val file = write("pcrt_probe", aarch64Pie())
        val plan = PcExecPlanner.plan(file, null)
        val description = plan.describe()
        assertNotNull(description)
        assertTrue(description.contains("pcrt_probe"))
        assertTrue(description.contains("system linker"))
    }

    @Test
    fun testBlockedPlanIsNotExecutable() {
        val file = write("guest-x86", x86_64Pie())
        val plan = PcExecPlanner.plan(file, null)
        assertTrue(plan.command.isEmpty())
        assertEquals(PcExecMode.BLOCKED, plan.mode)
    }

    @Test
    fun testBlockedPlanHasNoCommandEvenWhenArgumentsAreGiven() {
        val file = write("guest-x86-2", x86_64Pie())
        val plan = PcExecPlanner.plan(file, null, arguments = listOf("--help"))
        assertTrue(plan.command.isEmpty())
    }

    private object Elfs {
        fun aarch64(dynamic: Boolean): ByteArray = build(ElfConstants.EM_AARCH64, dynamic)
        fun x86_64(): ByteArray = build(ElfConstants.EM_X86_64, dynamic = true)

        private fun build(machine: Int, dynamic: Boolean): ByteArray {
            val interpreter = if (dynamic) "/system/bin/linker64" else ""
            val phOff = 64
            val phEntSize = 56
            val phNum = if (dynamic) 2 else 1
            val interpOffset = phOff + phEntSize * phNum
            val interpBytes = (interpreter + "\u0000").toByteArray(Charsets.ISO_8859_1)
            val total = interpOffset + interpBytes.size
            val bytes = ByteArray(total)

            bytes[0] = 0x7F
            bytes[1] = 'E'.code.toByte()
            bytes[2] = 'L'.code.toByte()
            bytes[3] = 'F'.code.toByte()
            bytes[ElfConstants.EI_CLASS] = ElfConstants.ELFCLASS64.toByte()
            bytes[ElfConstants.EI_DATA] = ElfConstants.ELFDATA2LSB.toByte()

            short(bytes, 16, ElfConstants.ET_DYN)
            short(bytes, 18, machine)
            long(bytes, 32, phOff.toLong())
            short(bytes, 54, phEntSize)
            short(bytes, 56, phNum)

            int(bytes, phOff, ElfConstants.PT_LOAD)
            if (dynamic) {
                val second = phOff + phEntSize
                int(bytes, second, ElfConstants.PT_INTERP)
                long(bytes, second + 8, interpOffset.toLong())
                long(bytes, second + 32, interpBytes.size.toLong())
                System.arraycopy(interpBytes, 0, bytes, interpOffset, interpBytes.size)
            }
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
}
