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
import org.catrobat.catroid.pcruntime.PcBinaryFormat
import org.catrobat.catroid.pcruntime.PcComponentKind
import org.catrobat.catroid.pcruntime.PcCpuArch
import org.catrobat.catroid.pcruntime.PcRuntimeLog
import org.catrobat.catroid.pcruntime.PeConstants
import org.catrobat.catroid.pcruntime.RuntimeComponentRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The component registry is the runtime's honesty mechanism: it is what stops
 * the app from announcing "Wine and Box64 are installed" when the user actually
 * dropped an x86-64 binary into the wrong folder.
 */
class RuntimeComponentRegistryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val log = PcRuntimeLog(50)

    private fun registry(): RuntimeComponentRegistry =
        RuntimeComponentRegistry(temporaryFolder.newFolder("components"), log)

    private fun aarch64Translator(): ByteArray = TestBinaries.elf(
        machine = ElfConstants.EM_AARCH64,
        dynamic = true
    )

    private fun x86_64Pe(dll: Boolean = true): ByteArray = TestBinaries.pe(PeConstants.IMAGE_FILE_MACHINE_AMD64, dll)

    private fun i386Pe(dll: Boolean = true): ByteArray = TestBinaries.pe(PeConstants.IMAGE_FILE_MACHINE_I386, dll)

    @Test
    fun testScanReportsMissingComponent() {
        val registry = registry()
        val group = registry.scan(PcComponentKind.CPU_TRANSLATOR)
        assertFalse(group.installed)
        assertFalse(group.usable)
        assertTrue(group.summary().contains("not installed"))
    }

    @Test
    fun testEnsureLayoutCreatesEveryKindDirectory() {
        val registry = registry()
        assertTrue(registry.ensureLayout())
        PcComponentKind.entries.forEach { kind ->
            assertTrue(
                "missing directory for $kind",
                registry.directoryFor(kind).isDirectory
            )
        }
    }

    @Test
    fun testAarch64DynamicTranslatorIsAccepted() {
        val registry = registry()
        val file = File(registry.directoryFor(PcComponentKind.CPU_TRANSLATOR), "box64")
        registry.ensureLayout()
        file.writeBytes(aarch64Translator())

        val inspected = registry.inspect(PcComponentKind.CPU_TRANSLATOR, file)
        assertTrue(inspected.problems.toString(), inspected.isValid)
        assertEquals(PcCpuArch.AARCH64, inspected.arch)
        assertEquals(PcBinaryFormat.ELF, inspected.format)
    }

    @Test
    fun testX86_64TranslatorIsRejectedWithExplanation() {
        val registry = registry()
        registry.ensureLayout()
        val file = File(registry.directoryFor(PcComponentKind.CPU_TRANSLATOR), "box64")
        // The classic mistake: grabbing the x86-64 build of a translator that
        // is meant to run *on* ARM64.
        file.writeBytes(TestBinaries.elf(machine = ElfConstants.EM_X86_64, dynamic = true))

        val inspected = registry.inspect(PcComponentKind.CPU_TRANSLATOR, file)
        assertFalse(inspected.isValid)
        assertTrue(inspected.problems.any { it.contains("AArch64 host binary") })
    }

    @Test
    fun testStaticTranslatorIsRejected() {
        val registry = registry()
        registry.ensureLayout()
        val file = File(registry.directoryFor(PcComponentKind.CPU_TRANSLATOR), "box64-static")
        file.writeBytes(TestBinaries.elf(machine = ElfConstants.EM_AARCH64, dynamic = false))

        val inspected = registry.inspect(PcComponentKind.CPU_TRANSLATOR, file)
        assertFalse(inspected.isValid)
        assertTrue(inspected.problems.any { it.contains("statically linked") })
    }

    @Test
    fun testX86_64PeDllIsAcceptedForDxvk() {
        val registry = registry()
        registry.ensureLayout()
        val file = File(registry.directoryFor(PcComponentKind.DIRECT3D_TRANSLATOR), "d3d11.dll")
        file.writeBytes(x86_64Pe())

        val inspected = registry.inspect(PcComponentKind.DIRECT3D_TRANSLATOR, file)
        assertTrue(inspected.problems.toString(), inspected.isValid)
        assertEquals(PcCpuArch.X86_64, inspected.arch)
        assertEquals(PcBinaryFormat.PE, inspected.format)
    }

    @Test
    fun testI386DllIsRejectedForTheWin64Pipeline() {
        val registry = registry()
        registry.ensureLayout()
        val file = File(registry.directoryFor(PcComponentKind.DIRECT3D_TRANSLATOR), "d3d11.dll")
        file.writeBytes(i386Pe())

        val inspected = registry.inspect(PcComponentKind.DIRECT3D_TRANSLATOR, file)
        assertFalse(inspected.isValid)
        assertTrue(inspected.problems.any { it.contains("x86-64") })
    }

    @Test
    fun testTranslatorKindRejectsPeFile() {
        val registry = registry()
        registry.ensureLayout()
        val file = File(registry.directoryFor(PcComponentKind.CPU_TRANSLATOR), "whoops.exe")
        file.writeBytes(x86_64Pe(dll = false))

        val inspected = registry.inspect(PcComponentKind.CPU_TRANSLATOR, file)
        assertFalse(inspected.isValid)
        assertTrue(inspected.problems.any { it.contains("expected an AArch64 ELF") })
    }

    @Test
    fun testUnrecognisedFileIsReported() {
        val registry = registry()
        registry.ensureLayout()
        val file = File(registry.directoryFor(PcComponentKind.WINDOWS_COMPATIBILITY), "readme.txt")
        file.writeBytes("just text".toByteArray())

        val inspected = registry.inspect(PcComponentKind.WINDOWS_COMPATIBILITY, file)
        assertFalse(inspected.isValid)
        assertEquals(PcBinaryFormat.UNKNOWN, inspected.format)
    }

    @Test
    fun testSha256PrefixMatchesKnownDigest() {
        val registry = registry()
        val file = temporaryFolder.newFile("hash-me.bin")
        file.writeBytes("abc".toByteArray(Charsets.US_ASCII))

        // Well known SHA-256 of "abc".
        assertEquals(
            "ba7816bf8f01cfea",
            registry.sha256Prefix(file, 16)
        )
    }

    @Test
    fun testSha256PrefixLengthIsConfigurable() {
        val registry = registry()
        val file = temporaryFolder.newFile("hash-me-2.bin")
        file.writeBytes("abc".toByteArray(Charsets.US_ASCII))
        assertEquals(8, registry.sha256Prefix(file, 8).length)
        assertEquals(64, registry.sha256Prefix(file, 64).length)
    }

    @Test
    fun testScanAllEnumeratesEveryKind() {
        val registry = registry()
        registry.ensureLayout()
        val groups = registry.scanAll()
        assertEquals(PcComponentKind.entries.size, groups.size)
        assertTrue(groups.all { !it.installed })
    }

    @Test
    fun testGroupBecomesUsableOnlyWhenEveryFileIsValid() {
        val registry = registry()
        registry.ensureLayout()
        val directory = registry.directoryFor(PcComponentKind.WINDOWS_COMPATIBILITY)
        File(directory, "wine").writeBytes(TestBinaries.elf(ElfConstants.EM_AARCH64, dynamic = true))
        File(directory, "broken").writeBytes("not a binary".toByteArray())

        val group = registry.scan(PcComponentKind.WINDOWS_COMPATIBILITY)
        assertTrue(group.installed)
        assertFalse(group.usable)
        assertEquals(1, group.files.count { !it.isValid })
    }

    @Test
    fun testSha256PrefixIsStableAndFileSpecific() {
        val registry = registry()
        val first = temporaryFolder.newFile("one.bin").apply { writeBytes("one".toByteArray()) }
        val second = temporaryFolder.newFile("two.bin").apply { writeBytes("two".toByteArray()) }

        val firstHash = registry.sha256Prefix(first, 16)
        assertEquals(firstHash, registry.sha256Prefix(first, 16))
        assertNotEquals(firstHash, registry.sha256Prefix(second, 16))
    }

    @Test
    fun testTableRowMentionsValidity() {
        val registry = registry()
        registry.ensureLayout()
        val healthy = File(registry.directoryFor(PcComponentKind.CPU_TRANSLATOR), "box64")
        healthy.writeBytes(aarch64Translator())

        val row = registry.inspect(PcComponentKind.CPU_TRANSLATOR, healthy).tableRow()
        assertTrue(row.contains("ok"))
        assertTrue(row.contains("box64"))
    }

    @Test
    fun testSha256OfMissingFileIsNotACrash() {
        val registry = registry()
        val missing = File(temporaryFolder.root, "not-here")
        assertEquals("unavailable", registry.sha256Prefix(missing, 16))
    }
}
