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

/**
 * CPU architectures the PC runtime reasons about. These are *guest/host* CPU
 * families, not Android ABI strings.
 */
enum class PcCpuArch(val displayName: String) {
    AARCH64("AArch64 (64-bit ARM)"),
    ARM32("ARM (32-bit)"),
    X86_64("x86-64"),
    X86("x86 (32-bit)"),
    UNKNOWN("unknown");

    val isArm64: Boolean
        get() = this == AARCH64
}

/**
 * Format of an inspected binary. ELF covers Android/Linux executables, PE
 * covers Windows executables and DLLs (which is what DXVK, VKD3D-Proton and
 * Wine's PE side ship).
 */
enum class PcBinaryFormat {
    ELF,
    PE,
    UNKNOWN
}

/**
 * Static, side-effect free device description. Built from Android APIs by
 * [PcDeviceInfoFactory] but kept free of Android imports so that the
 * classification logic is unit testable on the JVM.
 */
data class DeviceArchReport(
    val primaryAbi: String,
    val supportedAbis: List<String>,
    val hostArch: PcCpuArch,
    val is64BitAbi: Boolean,
    val pointerSizeBits: Int,
    val cpuCores: Int,
    val sdkInt: Int,
    val socModel: String?,
    val socManufacturer: String?
) {
    /** Full descriptions of every ABI Android reports for this device. */
    fun supportedAbiDescriptions(): String =
        supportedAbis.joinToString(", ") { abi -> "$abi -> ${PcAbiMapper.toArch(abi).displayName}" }

    fun summary(): String = buildString {
        append("Android API ").append(sdkInt)
        append(", ABI ").append(primaryAbi)
        append(" (").append(hostArch.displayName).append(")")
        append(", ").append(cpuCores).append(" cores")
        if (!socManufacturer.isNullOrBlank() || !socModel.isNullOrBlank()) {
            append(", SoC ")
            append(listOfNotNull(socManufacturer?.takeIf { it.isNotBlank() }, socModel?.takeIf { it.isNotBlank() })
                .joinToString(" "))
        }
    }
}

/**
 * Android ABI string to CPU family mapping. Kept as pure functions so the rules
 * can be tested without a device.
 */
object PcAbiMapper {

    fun toArch(abi: String): PcCpuArch = when (abi.lowercase().trim()) {
        "arm64-v8a" -> PcCpuArch.AARCH64
        "armeabi-v7a", "armeabi" -> PcCpuArch.ARM32
        "x86_64" -> PcCpuArch.X86_64
        "x86" -> PcCpuArch.X86
        else -> PcCpuArch.UNKNOWN
    }

    /**
     * Picks the ABI the PC runtime should target: the first entry of
     * [supportedAbis] that is 64-bit ARM, or the first entry overall.
     */
    fun selectTargetAbi(supportedAbis: List<String>): String =
        supportedAbis.firstOrNull { toArch(it) == PcCpuArch.AARCH64 }
            ?: supportedAbis.firstOrNull()
            ?: "unknown"

    /** Directory name used inside the APK assets / jniLibs for an ABI. */
    fun nativeLibrarySubdirectory(abi: String): String = when (toArch(abi)) {
        PcCpuArch.AARCH64 -> "arm64-v8a"
        PcCpuArch.ARM32 -> "armeabi-v7a"
        PcCpuArch.X86_64 -> "x86_64"
        PcCpuArch.X86 -> "x86"
        PcCpuArch.UNKNOWN -> "unknown"
    }
}
