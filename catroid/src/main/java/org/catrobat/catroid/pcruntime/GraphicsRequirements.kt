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
 * Direct3D translation layers the runtime can reason about. Every one of them
 * requires a working Wine userland *and* a Vulkan driver that meets the
 * upstream documented minimum. Availability of a configuration option is never
 * treated as evidence that the layer works.
 */
enum class GraphicsBackend(val displayName: String, val requiredDllNames: List<String>) {
    NONE("None (Wine built-in wined3d)", emptyList()),
    DXVK_D3D8("DXVK Direct3D 8", listOf("d3d8.dll", "d3d9.dll")),
    DXVK_D3D9("DXVK Direct3D 9", listOf("d3d9.dll")),
    DXVK_D3D10("DXVK Direct3D 10", listOf("d3d10core.dll", "d3d11.dll", "dxgi.dll")),
    DXVK_D3D11("DXVK Direct3D 11", listOf("d3d11.dll", "dxgi.dll")),
    VKD3D_PROTON("VKD3D-Proton Direct3D 12", listOf("d3d12.dll", "d3d12core.dll"))
}

data class RequirementCheck(
    val name: String,
    val satisfied: Boolean,
    val detail: String
)

data class BackendReadiness(
    val backend: GraphicsBackend,
    val checks: List<RequirementCheck>
) {
    val satisfiedCount: Int
        get() = checks.count { it.satisfied }

    val isReady: Boolean
        get() = checks.isNotEmpty() && checks.all { it.satisfied }

    fun missing(): List<RequirementCheck> = checks.filter { !it.satisfied }

    fun summary(): String = if (checks.isEmpty()) {
        "${backend.displayName}: no driver requirements to evaluate (no Vulkan device)"
    } else if (isReady) {
        "${backend.displayName}: driver requirements met (${satisfiedCount}/${checks.size})"
    } else {
        "${backend.displayName}: not usable - " +
            missing().joinToString("; ") { "${it.name} (${it.detail})" }
    }
}

/**
 * Requirement catalogue derived from the upstream project documentation:
 *
 *  * DXVK 2.0 requires a Vulkan 1.3 driver.
 *  * DXVK 2.5 additionally requires VK_KHR_maintenance5.
 *  * DXVK 3.x requires Vulkan 1.4 era driver support.
 *  * VKD3D-Proton requires Vulkan 1.3, descriptor indexing with at least
 *    1,000,000 UpdateAfterBind descriptors of every type except uniform
 *    buffers, the samplerMirrorClampToEdge and shaderDrawParameters device
 *    features, VK_EXT_robustness2 and VK_KHR_push_descriptor.
 *
 * The thresholds are evaluated against data collected from the real driver, so
 * a report can only ever be as optimistic as the device actually allows.
 */
object GraphicsRequirementCatalog {

    const val REQUIRED_DESCRIPTOR_COUNT = 1_000_000
    private val VULKAN_1_3 = VulkanVersion(1, 3, 0)
    private val VULKAN_1_4 = VulkanVersion(1, 4, 0)

    fun evaluate(backend: GraphicsBackend, report: VulkanReport): BackendReadiness {
        val device = report.preferredDevice()
            ?: return BackendReadiness(backend, emptyList())

        val checks = mutableListOf<RequirementCheck>()

        when (backend) {
            GraphicsBackend.NONE -> return BackendReadiness(backend, emptyList())

            GraphicsBackend.DXVK_D3D8,
            GraphicsBackend.DXVK_D3D9,
            GraphicsBackend.DXVK_D3D10,
            GraphicsBackend.DXVK_D3D11 -> {
                checks += versionCheck("Vulkan 1.3 driver", device.apiVersion, VULKAN_1_3)
                checks += extensionCheck(device, "VK_KHR_maintenance5")
                checks += featureCheck(device, "graphicsPipelineLibrary", "VK_EXT_graphics_pipeline_library")
            }

            GraphicsBackend.VKD3D_PROTON -> {
                checks += versionCheck("Vulkan 1.3 driver", device.apiVersion, VULKAN_1_3)
                checks += extensionCheck(device, "VK_EXT_robustness2")
                checks += extensionCheck(device, "VK_KHR_push_descriptor")
                checks += featureCheck(device, "descriptorIndexing", "VkPhysicalDeviceVulkan12Features")
                checks += featureCheck(device, "shaderDrawParameters", "VkPhysicalDeviceVulkan11Features")
                checks += featureCheck(device, "samplerMirrorClampToEdge", "VkPhysicalDeviceVulkan11Features")
                checks += descriptorCountCheck(device, "sampledImageUpdateAfterBind")
                checks += descriptorCountCheck(device, "storageImageUpdateAfterBind")
            }
        }

        return BackendReadiness(backend, checks)
    }

    fun evaluateAll(report: VulkanReport): List<BackendReadiness> =
        GraphicsBackend.entries.map { evaluate(it, report) }

    /**
     * Reads a numeric capability that the native probe publishes into the
     * device feature set as "name=value" entries.
     */
    fun capabilityValue(device: VulkanDeviceInfo, name: String): Int? =
        device.features.firstOrNull { it.substringBefore('=').equals(name, ignoreCase = true) }
            ?.substringAfter('=', "")
            ?.trim()
            ?.toIntOrNull()

    private fun versionCheck(name: String, actual: VulkanVersion, required: VulkanVersion): RequirementCheck {
        val ok = actual >= required
        return RequirementCheck(
            name,
            ok,
            if (ok) "driver reports $actual" else "driver reports $actual, need >= $required"
        )
    }

    private fun extensionCheck(device: VulkanDeviceInfo, extension: String): RequirementCheck {
        val ok = device.hasExtension(extension)
        return RequirementCheck(
            extension,
            ok,
            if (ok) "advertised by the driver" else "not advertised by the driver"
        )
    }

    private fun featureCheck(device: VulkanDeviceInfo, feature: String, source: String): RequirementCheck {
        val ok = device.hasFeature(feature)
        return RequirementCheck(
            feature,
            ok,
            if (ok) "enabled ($source)" else "not reported by $source"
        )
    }

    private fun descriptorCountCheck(device: VulkanDeviceInfo, property: String): RequirementCheck {
        val value = capabilityValue(device, property)
        if (value == null) {
            return RequirementCheck(property, false, "not reported by the driver")
        }
        val ok = value >= REQUIRED_DESCRIPTOR_COUNT
        return RequirementCheck(
            property,
            ok,
            "driver reports $value, VKD3D-Proton needs >= $REQUIRED_DESCRIPTOR_COUNT"
        )
    }

    /** Vulkan 1.4 era check kept separate because DXVK 3.x raised the bar. */
    fun dxvk3RequiresVulkan14(device: VulkanDeviceInfo): RequirementCheck =
        versionCheck("Vulkan 1.4 driver (DXVK 3.x)", device.apiVersion, VULKAN_1_4)
}
