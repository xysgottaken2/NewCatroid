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
 * Parsed result of the native Vulkan probe. Produced by libcatroid.so from a
 * real vkCreateInstance / vkEnumeratePhysicalDevices round trip, or by the
 * Android SDK fallback when the native loader is unavailable.
 */
data class VulkanDeviceInfo(
    val name: String,
    val deviceType: Int,
    val apiVersionRaw: Int,
    val driverVersionRaw: Int,
    val driverId: Int,
    val driverName: String,
    val driverInfo: String,
    val extensions: List<String>,
    val features: Set<String>
) {
    val apiVersion: VulkanVersion
        get() = VulkanVersion.fromRaw(apiVersionRaw)

    val driverVersion: String
        get() = "${(driverVersionRaw shr 22) and 0x3FF}." +
            "${(driverVersionRaw shr 12) and 0x3FF}." +
            "${driverVersionRaw and 0xFFF}"

    val deviceTypeName: String
        get() = when (deviceType) {
            0 -> "other"
            1 -> "integrated GPU"
            2 -> "discrete GPU"
            3 -> "virtual GPU"
            4 -> "CPU"
            else -> "unknown"
        }

    fun hasExtension(name: String): Boolean = extensions.any { it.equals(name, ignoreCase = true) }

    fun hasFeature(name: String): Boolean = features.any { it.equals(name, ignoreCase = true) }

    fun driverLabel(): String =
        if (driverName.isNotBlank()) driverName else "Vulkan driver (id=$driverId)"
}

data class VulkanVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<VulkanVersion> {

    companion object {
        val NONE = VulkanVersion(0, 0, 0)

        fun fromRaw(raw: Int): VulkanVersion = VulkanVersion(
            (raw shr 22) and 0x7F,
            (raw shr 12) and 0x3FF,
            raw and 0xFFF
        )
    }

    override fun compareTo(other: VulkanVersion): Int {
        if (major != other.major) {
            return major - other.major
        }
        if (minor != other.minor) {
            return minor - other.minor
        }
        return patch - other.patch
    }

    override fun toString(): String = "$major.$minor.$patch"
}

data class VulkanReport(
    val available: Boolean,
    val loaderApiVersion: VulkanVersion,
    val devices: List<VulkanDeviceInfo>,
    val instanceExtensions: List<String>,
    val probeError: String?,
    val source: String
) {
    companion object {
        fun unavailable(error: String, source: String): VulkanReport =
            VulkanReport(false, VulkanVersion.NONE, emptyList(), emptyList(), error, source)
    }

    /** The device the runtime will prefer: first discrete, then integrated. */
    fun preferredDevice(): VulkanDeviceInfo? =
        devices.firstOrNull { it.deviceType == 2 }
            ?: devices.firstOrNull { it.deviceType == 1 }
            ?: devices.firstOrNull()

    fun summary(): String = when {
        !available -> "Vulkan unavailable: ${probeError ?: "unknown reason"}"
        devices.isEmpty() -> "Vulkan loader ${loaderApiVersion} present, but no physical device was enumerated"
        else -> {
            val device = preferredDevice()
            "Vulkan $loaderApiVersion, ${devices.size} device(s); primary: " +
                "${device?.name ?: "?"} (${device?.apiVersion ?: VulkanVersion.NONE}, " +
                "${device?.driverLabel() ?: "?"})"
        }
    }
}
