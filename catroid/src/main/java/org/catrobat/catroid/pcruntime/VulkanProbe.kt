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

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import org.catrobat.catroid.utils.NativeLibraryManager
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Queries the device's real Vulkan capabilities.
 *
 * The primary source is a native probe inside libcatroid.so that dlopen()s the
 * platform Vulkan loader and performs an actual vkCreateInstance +
 * vkEnumeratePhysicalDevices round trip. When the native library is not
 * available (for example a stripped build, or a device without a loader) the
 * probe falls back to the Android SDK's own Vulkan feature flags, which report
 * the API level of the driver Intel/Qualcomm/ARM installed. The fallback is
 * clearly labelled in [VulkanReport.source] so no caller can mistake the two.
 */
object VulkanProbe {

    private const val TAG = "PcVulkan"

    fun probe(context: Context, log: PcRuntimeLog): VulkanReport {
        val native = probeNative(log)
        if (native.available) {
            return native
        }
        log.warn(TAG, "Native Vulkan probe unavailable (${native.probeError}); using SDK feature flags")
        return probeViaPackageManager(context, native.probeError ?: "native probe unavailable", log)
    }

    private fun probeNative(log: PcRuntimeLog): VulkanReport {
        if (!NativeLibraryManager.isLoaded(NativeLibraryManager.Feature.CORE)) {
            return VulkanReport.unavailable(
                "libcatroid.so is not loaded, native Vulkan probe cannot run",
                SOURCE_NONE
            )
        }
        return try {
            val raw = nativeProbeVulkan()
            log.debug(TAG, "native Vulkan probe returned ${raw.length} bytes")
            parse(raw, SOURCE_NATIVE)
        } catch (e: UnsatisfiedLinkError) {
            VulkanReport.unavailable("native method missing: ${e.message}", SOURCE_NONE)
        } catch (e: Exception) {
            VulkanReport.unavailable("native probe threw ${e.javaClass.simpleName}: ${e.message}", SOURCE_NONE)
        }
    }

    private fun parse(raw: String, source: String): VulkanReport {
        return try {
            val root = JSONObject(raw)
            if (!root.optBoolean("available", false)) {
                return VulkanReport.unavailable(
                    root.optString("error", "native probe reported no Vulkan support"),
                    source
                )
            }
            val loaderVersion = VulkanVersion.fromRaw(root.optInt("loaderApiVersion", 0))
            val instanceExtensions = root.optJSONArray("instanceExtensions").toStringList()

            val devices = mutableListOf<VulkanDeviceInfo>()
            val deviceArray = root.optJSONArray("devices") ?: JSONArray()
            for (index in 0 until deviceArray.length()) {
                val device = deviceArray.optJSONObject(index) ?: continue
                devices += VulkanDeviceInfo(
                    name = device.optString("name", "unknown"),
                    deviceType = device.optInt("deviceType", 0),
                    apiVersionRaw = device.optInt("apiVersion", 0),
                    driverVersionRaw = device.optInt("driverVersion", 0),
                    driverId = device.optInt("driverId", 0),
                    driverName = device.optString("driverName", ""),
                    driverInfo = device.optString("driverInfo", ""),
                    extensions = device.optJSONArray("extensions").toStringList(),
                    features = parseFeatureMap(device.optJSONObject("features"))
                )
            }

            VulkanReport(
                available = true,
                loaderApiVersion = loaderVersion,
                devices = devices,
                instanceExtensions = instanceExtensions,
                probeError = null,
                source = source
            )
        } catch (e: JSONException) {
            VulkanReport.unavailable("could not parse the native probe output: ${e.message}", source)
        }
    }

    private fun parseFeatureMap(features: JSONObject?): Set<String> {
        if (features == null) {
            return emptySet()
        }
        val result = LinkedHashSet<String>()
        val iterator = features.keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            val value = features.opt(key)
            when (value) {
                is Boolean -> if (value) {
                    result += key
                }
                is Int -> if (value == 1) {
                    result += key
                } else {
                    result += "$key=$value"
                }
                is Long -> result += "$key=$value"
                is String -> result += "$key=$value"
                else -> Unit
            }
        }
        return result
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) {
            return emptyList()
        }
        val result = mutableListOf<String>()
        for (index in 0 until length()) {
            val value = optString(index, "")
            if (value.isNotEmpty()) {
                result += value
            }
        }
        return result
    }

    /**
     * Fallback used when the native probe cannot run. Android 10 exposes the
     * Vulkan version Qualcomm/ARM advertise for the device through package
     * manager feature flags; that is weaker evidence than a live instance, so
     * the source string says so explicitly.
     */
    private fun probeViaPackageManager(context: Context, reason: String, log: PcRuntimeLog): VulkanReport {
        val packageManager = context.packageManager
        val hasVulkan = packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)
        if (!hasVulkan) {
            log.warn(TAG, "device does not advertise FEATURE_VULKAN_HARDWARE_VERSION")
            return VulkanReport.unavailable(
                "no Vulkan driver reported by the platform ($reason)",
                SOURCE_PACKAGE_MANAGER
            )
        }

        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            packageManager.systemAvailableFeatures
                .firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }
                ?.version
        } else {
            null
        }

        val level = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            packageManager.systemAvailableFeatures
                .firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL }
                ?.version
        } else {
            null
        }

        val reportedVersion = versionCode?.let { VulkanVersion.fromRaw(it) } ?: VulkanVersion.NONE
        val device = VulkanDeviceInfo(
            name = "${Build.MANUFACTURER} ${Build.MODEL} (platform reported)",
            deviceType = 1,
            apiVersionRaw = versionCode ?: 0,
            driverVersionRaw = 0,
            driverId = 0,
            driverName = "",
            driverInfo = if (level != null) "platform Vulkan hardware level $level" else "",
            extensions = emptyList(),
            features = emptySet()
        )

        return VulkanReport(
            available = true,
            loaderApiVersion = reportedVersion,
            devices = listOf(device),
            instanceExtensions = emptyList(),
            probeError = "extension coverage unknown: native probe unavailable ($reason)",
            source = SOURCE_PACKAGE_MANAGER
        )
    }

    /** Native diagnostic for the in-process half of the smoke test. */
    fun describeCpu(): String = try {
        if (NativeLibraryManager.isLoaded(NativeLibraryManager.Feature.CORE)) {
            nativeDescribeCpu()
        } else {
            "libcatroid.so not loaded"
        }
    } catch (e: Exception) {
        "native CPU description unavailable: ${e.message}"
    }

    const val SOURCE_NATIVE = "libcatroid.so (live Vulkan instance)"
    const val SOURCE_PACKAGE_MANAGER = "Android PackageManager feature flags (no live instance)"
    private const val SOURCE_NONE = "unavailable"

    @JvmStatic
    private external fun nativeProbeVulkan(): String

    @JvmStatic
    private external fun nativeDescribeCpu(): String

    @JvmStatic
    private external fun nativePcRuntimeApiVersion(): Int
}
