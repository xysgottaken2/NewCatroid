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

import org.catrobat.catroid.pcruntime.DeviceArchReport
import org.catrobat.catroid.pcruntime.PcAbiMapper
import org.catrobat.catroid.pcruntime.PcCpuArch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceArchitectureTest {

    @Test
    fun testAbiMapping() {
        assertEquals(PcCpuArch.AARCH64, PcAbiMapper.toArch("arm64-v8a"))
        assertEquals(PcCpuArch.ARM32, PcAbiMapper.toArch("armeabi-v7a"))
        assertEquals(PcCpuArch.ARM32, PcAbiMapper.toArch("armeabi"))
        assertEquals(PcCpuArch.X86_64, PcAbiMapper.toArch("x86_64"))
        assertEquals(PcCpuArch.X86, PcAbiMapper.toArch("x86"))
        assertEquals(PcCpuArch.UNKNOWN, PcAbiMapper.toArch("mips"))
        assertEquals(PcCpuArch.UNKNOWN, PcAbiMapper.toArch(""))
    }

    @Test
    fun testAbiMappingIsCaseInsensitiveAndTrimsWhitespace() {
        assertEquals(PcCpuArch.AARCH64, PcAbiMapper.toArch("ARM64-V8A"))
        assertEquals(PcCpuArch.AARCH64, PcAbiMapper.toArch("  arm64-v8a "))
    }

    @Test
    fun testTargetAbiPrefersArm64() {
        assertEquals(
            "arm64-v8a",
            PcAbiMapper.selectTargetAbi(listOf("armeabi-v7a", "arm64-v8a"))
        )
    }

    @Test
    fun testTargetAbiFallsBackToFirstEntry() {
        assertEquals("armeabi-v7a", PcAbiMapper.selectTargetAbi(listOf("armeabi-v7a")))
        assertEquals("unknown", PcAbiMapper.selectTargetAbi(emptyList()))
    }

    @Test
    fun testNativeLibrarySubdirectories() {
        assertEquals("arm64-v8a", PcAbiMapper.nativeLibrarySubdirectory("arm64-v8a"))
        assertEquals("armeabi-v7a", PcAbiMapper.nativeLibrarySubdirectory("armeabi-v7a"))
        assertEquals("x86_64", PcAbiMapper.nativeLibrarySubdirectory("x86_64"))
        assertEquals("unknown", PcAbiMapper.nativeLibrarySubdirectory("mips"))
    }

    @Test
    fun testArm64Flag() {
        assertTrue(PcCpuArch.AARCH64.isArm64)
        assertFalse(PcCpuArch.ARM32.isArm64)
        assertFalse(PcCpuArch.X86_64.isArm64)
        assertFalse(PcCpuArch.UNKNOWN.isArm64)
    }

    @Test
    fun testReportSummaryMentionsTheEssentialFacts() {
        val report = DeviceArchReport(
            primaryAbi = "arm64-v8a",
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
            hostArch = PcCpuArch.AARCH64,
            is64BitAbi = true,
            pointerSizeBits = 64,
            cpuCores = 8,
            sdkInt = 33,
            socModel = "SM8550",
            socManufacturer = "QCOM"
        )

        val summary = report.summary()
        assertTrue(summary.contains("API 33"))
        assertTrue(summary.contains("arm64-v8a"))
        assertTrue(summary.contains("8 cores"))
        assertTrue(summary.contains("SM8550"))
    }

    @Test
    fun testSupportedAbiDescriptionsCoverEveryAbi() {
        val report = DeviceArchReport(
            primaryAbi = "arm64-v8a",
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a", "x86"),
            hostArch = PcCpuArch.AARCH64,
            is64BitAbi = true,
            pointerSizeBits = 64,
            cpuCores = 4,
            sdkInt = 31,
            socModel = null,
            socManufacturer = null
        )

        val descriptions = report.supportedAbiDescriptions()
        assertTrue(descriptions.contains("AArch64"))
        assertTrue(descriptions.contains("ARM (32-bit)"))
        assertTrue(descriptions.contains("x86 (32-bit)"))
    }

    @Test
    fun testSummaryOmitsSocWhenUnknown() {
        val report = DeviceArchReport(
            primaryAbi = "arm64-v8a",
            supportedAbis = listOf("arm64-v8a"),
            hostArch = PcCpuArch.AARCH64,
            is64BitAbi = true,
            pointerSizeBits = 64,
            cpuCores = 8,
            sdkInt = 29,
            socModel = null,
            socManufacturer = null
        )
        assertFalse(report.summary().contains("SoC"))
    }
}
