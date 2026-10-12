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
 * http://www.catrobat.org/license_additional_term
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
import org.catrobat.catroid.pcruntime.PcToolchain
import org.catrobat.catroid.pcruntime.ToolchainCompatibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A glibc binary handed to this runtime is the normal case in the wild (every
 * Termux or Debian Wine tarball is one) and it can never be started by the
 * Android linker. These tests pin that classification down.
 */
class ToolchainCompatibilityTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun write(name: String, bytes: ByteArray): File {
        val file = temporaryFolder.newFile(name)
        file.writeBytes(bytes)
        return file
    }

    @Test
    fun testAndroidLinkerIsBionic() {
        assertEquals(PcToolchain.BIONIC, ToolchainCompatibility.classify("/system/bin/linker64"))
        assertEquals(PcToolchain.BIONIC, ToolchainCompatibility.classify("/system/bin/linker"))
        assertEquals(PcToolchain.BIONIC, ToolchainCompatibility.classify("linker64"))
        assertTrue(ToolchainCompatibility.canUseSystemLinker(PcToolchain.BIONIC))
        assertNull(ToolchainCompatibility.limitation(PcToolchain.BIONIC))
    }

    @Test
    fun testGlibcInterpretersAreRejected() {
        val interpreters = listOf(
            "/lib64/ld-linux-aarch64.so.1",
            "/lib/ld-linux-armhf.so.3",
            "/lib64/ld-linux-x86-64.so.2",
            "/lib/ld-linux.so.2",
            "/lib64/ld.so"
        )
        interpreters.forEach { interpreter ->
            assertEquals(interpreter, PcToolchain.GLIBC, ToolchainCompatibility.classify(interpreter))
            assertFalse(interpreter, ToolchainCompatibility.canUseSystemLinker(PcToolchain.GLIBC))
            assertNotNull(interpreter, ToolchainCompatibility.limitation(PcToolchain.GLIBC))
        }
    }

    @Test
    fun testMuslIsDistinguishedFromGlibc() {
        assertEquals(PcToolchain.MUSL, ToolchainCompatibility.classify("/lib/ld-musl-aarch64.so.1"))
        assertFalse(ToolchainCompatibility.canUseSystemLinker(PcToolchain.MUSL))
        assertTrue(ToolchainCompatibility.limitation(PcToolchain.MUSL)!!.contains("musl"))
    }

    @Test
    fun testEmptyInterpreterMeansStatic() {
        assertEquals(PcToolchain.STATIC, ToolchainCompatibility.classify(""))
        assertEquals(PcToolchain.STATIC, ToolchainCompatibility.classify("   "))
        assertFalse(ToolchainCompatibility.canUseSystemLinker(PcToolchain.STATIC))
        assertTrue(ToolchainCompatibility.limitation(PcToolchain.STATIC)!!.contains("statically linked"))
    }

    @Test
    fun testUnknownInterpreterIsNotOptimistic() {
        assertEquals(PcToolchain.UNKNOWN, ToolchainCompatibility.classify("/opt/weird/loader"))
        assertFalse(ToolchainCompatibility.canUseSystemLinker(PcToolchain.UNKNOWN))
        assertNotNull(ToolchainCompatibility.limitation(PcToolchain.UNKNOWN))
    }

    @Test
    fun testElfInfoExposesTheToolchain() {
        val bionic = write("bionic", TestBinaries.elf(interpreter = "/system/bin/linker64"))
        val glibc = write("glibc", TestBinaries.elf(interpreter = "/lib/ld-linux-aarch64.so.1"))

        val bionicInfo = ElfInspector.inspect(bionic)
        val glibcInfo = ElfInspector.inspect(glibc)

        assertNotNull(bionicInfo)
        assertNotNull(glibcInfo)
        assertEquals(PcToolchain.BIONIC, bionicInfo!!.toolchain)
        assertEquals(PcToolchain.GLIBC, glibcInfo!!.toolchain)

        // The description shown by the inspect-binary block has to name it,
        // otherwise a user cannot tell why a Wine build is refused.
        assertTrue(bionicInfo.describe().contains("Android/bionic"))
        assertTrue(glibcInfo.describe().contains("glibc"))
    }

    @Test
    fun testAarch64BionicProbeIsTheAcceptedShape() {
        // The pcrt_probe asset and Box64 must both look like this.
        val file = write(
            "pcrt_probe",
            TestBinaries.elf(
                machine = ElfConstants.EM_AARCH64,
                dynamic = true,
                interpreter = "/system/bin/linker64"
            )
        )
        val info = ElfInspector.inspect(file)
        assertNotNull(info)
        assertTrue(info!!.isDynamicExecutable)
        assertEquals(PcToolchain.BIONIC, info.toolchain)
    }
}
