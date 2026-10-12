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

import org.catrobat.catroid.pcruntime.GraphicsBackend
import org.catrobat.catroid.pcruntime.GraphicsRequirementCatalog
import org.catrobat.catroid.pcruntime.VulkanDeviceInfo
import org.catrobat.catroid.pcruntime.VulkanReport
import org.catrobat.catroid.pcruntime.VulkanVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These tests exist to make one class of mistake impossible: reporting that
 * Direct3D games can run just because a backend was selected. Every assertion
 * here is about the runtime refusing to be optimistic.
 */
class GraphicsRequirementCatalogTest {

    private fun version(major: Int, minor: Int): Int = (major shl 22) or (minor shl 12)

    private fun device(
        apiMajor: Int,
        apiMinor: Int,
        extensions: List<String> = emptyList(),
        features: Set<String> = emptySet()
    ) = VulkanDeviceInfo(
        name = "Adreno test device",
        deviceType = 1,
        apiVersionRaw = version(apiMajor, apiMinor),
        driverVersionRaw = version(512, 615),
        driverId = 1,
        driverName = "Qualcomm proprietary",
        driverInfo = "test",
        extensions = extensions,
        features = features
    )

    private fun report(vararg devices: VulkanDeviceInfo) = VulkanReport(
        available = true,
        loaderApiVersion = VulkanVersion(1, 3, 0),
        devices = devices.toList(),
        instanceExtensions = emptyList(),
        probeError = null,
        source = "test"
    )

    @Test
    fun testVulkan13AloneIsNotEnoughForDxvkAnymore() {
        // DXVK 2.5+ requires VK_KHR_maintenance5 in addition to Vulkan 1.3.
        val readiness = GraphicsRequirementCatalog.evaluate(
            GraphicsBackend.DXVK_D3D9,
            report(device(1, 3))
        )
        assertFalse(readiness.isReady)
        assertTrue(readiness.missing().any { it.name == "VK_KHR_maintenance5" })
    }

    @Test
    fun testVulkan12DriverFailsDxvk() {
        val readiness = GraphicsRequirementCatalog.evaluate(
            GraphicsBackend.DXVK_D3D11,
            report(device(1, 2, listOf("VK_KHR_maintenance5", "VK_EXT_graphics_pipeline_library")))
        )
        assertFalse(readiness.isReady)
        assertTrue(readiness.missing().any { it.name == "Vulkan 1.3 driver" })
    }

    @Test
    fun testFullyCapableDxvkDeviceIsReady() {
        val readiness = GraphicsRequirementCatalog.evaluate(
            GraphicsBackend.DXVK_D3D9,
            report(
                device(
                    1, 3,
                    // The extension must be advertised *and* its feature enabled;
                    // a driver that only lists the extension is not enough.
                    extensions = listOf("VK_KHR_maintenance5", "VK_EXT_graphics_pipeline_library"),
                    features = setOf("graphicsPipelineLibrary")
                )
            )
        )
        assertTrue(readiness.isReady)
        assertTrue(readiness.summary().contains("requirements met"))
    }

    @Test
    fun testDxvk8RequiresBothD3d8AndD3d9Dlls() {
        assertEquals(listOf("d3d8.dll", "d3d9.dll"), GraphicsBackend.DXVK_D3D8.requiredDllNames)
        assertEquals(listOf("d3d9.dll"), GraphicsBackend.DXVK_D3D9.requiredDllNames)
        assertEquals(
            listOf("d3d10core.dll", "d3d11.dll", "dxgi.dll"),
            GraphicsBackend.DXVK_D3D10.requiredDllNames
        )
        assertEquals(listOf("d3d11.dll", "dxgi.dll"), GraphicsBackend.DXVK_D3D11.requiredDllNames)
    }

    @Test
    fun testVkd3dProtonNeedsVulkan13AndDescriptorIndexing() {
        val readiness = GraphicsRequirementCatalog.evaluate(
            GraphicsBackend.VKD3D_PROTON,
            report(device(1, 2))
        )
        assertFalse(readiness.isReady)
        val missing = readiness.missing().map { it.name }
        assertTrue(missing.contains("Vulkan 1.3 driver"))
        assertTrue(missing.contains("VK_EXT_robustness2"))
        assertTrue(missing.contains("VK_KHR_push_descriptor"))
        assertTrue(missing.contains("descriptorIndexing"))
    }

    @Test
    fun testVkd3dProtonRejectsDriverWithTooFewUpdateAfterBindDescriptors() {
        val readiness = GraphicsRequirementCatalog.evaluate(
            GraphicsBackend.VKD3D_PROTON,
            report(
                device(
                    1, 3,
                    extensions = listOf("VK_EXT_robustness2", "VK_KHR_push_descriptor"),
                    features = setOf(
                        "descriptorIndexing",
                        "shaderDrawParameters",
                        "samplerMirrorClampToEdge",
                        "sampledImageUpdateAfterBind=500000",
                        "storageImageUpdateAfterBind=500000"
                    )
                )
            )
        )
        assertFalse(readiness.isReady)
        val mismatches = readiness.missing().map { it.name }
        assertTrue(mismatches.contains("sampledImageUpdateAfterBind"))
        assertTrue(mismatches.contains("storageImageUpdateAfterBind"))
    }

    @Test
    fun testVkd3dProtonAcceptsFullyFeaturedDriver() {
        val readiness = GraphicsRequirementCatalog.evaluate(
            GraphicsBackend.VKD3D_PROTON,
            report(
                device(
                    1, 3,
                    extensions = listOf("VK_EXT_robustness2", "VK_KHR_push_descriptor"),
                    features = setOf(
                        "descriptorIndexing",
                        "shaderDrawParameters",
                        "samplerMirrorClampToEdge",
                        "sampledImageUpdateAfterBind=1000000",
                        "storageImageUpdateAfterBind=1000000"
                    )
                )
            )
        )
        assertTrue(readiness.isReady)
    }

    @Test
    fun testNoVulkanDeviceYieldsNoEvaluatedRequirements() {
        val unavailable = VulkanReport.unavailable("no loader", "test")
        val readiness = GraphicsRequirementCatalog.evaluate(GraphicsBackend.DXVK_D3D11, unavailable)
        assertFalse(readiness.isReady)
        assertTrue(readiness.checks.isEmpty())
        assertTrue(readiness.summary().contains("no Vulkan device"))
    }

    @Test
    fun testNoneBackendHasNoRequirements() {
        val readiness = GraphicsRequirementCatalog.evaluate(GraphicsBackend.NONE, report(device(1, 3)))
        assertTrue(readiness.checks.isEmpty())
        assertFalse(readiness.isReady)
    }

    @Test
    fun testPreferredDevicePrefersDiscreteThenIntegrated() {
        val integrated = device(1, 3).copy(deviceType = 1, name = "integrated")
        val discrete = device(1, 3).copy(deviceType = 2, name = "discrete")
        assertEquals("discrete", report(integrated, discrete).preferredDevice()?.name)
        assertEquals("integrated", report(integrated).preferredDevice()?.name)
    }

    @Test
    fun testVersionComparisonIsOrdered() {
        assertTrue(VulkanVersion(1, 4, 0) > VulkanVersion(1, 3, 2))
        assertTrue(VulkanVersion(1, 3, 0) >= VulkanVersion(1, 3, 0))
        assertTrue(VulkanVersion(1, 2, 0) < VulkanVersion(1, 3, 0))
        assertEquals("1.3.0", VulkanVersion(1, 3, 0).toString())
    }

    @Test
    fun testVersionIsDecodedFromRawValue() {
        val raw = version(1, 3)
        val decoded = VulkanVersion.fromRaw(raw)
        assertEquals(1, decoded.major)
        assertEquals(3, decoded.minor)
    }

    @Test
    fun testDxvk3RequiresVulkan14() {
        val check = GraphicsRequirementCatalog.dxvk3RequiresVulkan14(device(1, 3))
        assertFalse(check.satisfied)
        assertTrue(GraphicsRequirementCatalog.dxvk3RequiresVulkan14(device(1, 4)).satisfied)
    }

    @Test
    fun testEvaluateAllCoversEveryBackend() {
        val all = GraphicsRequirementCatalog.evaluateAll(report(device(1, 3)))
        assertEquals(GraphicsBackend.entries.size, all.size)
    }

    @Test
    fun testCapabilityValueParsesNumericFeatureEntries() {
        val target = device(1, 3, features = setOf("sampledImageUpdateAfterBind=1000000"))
        assertEquals(1000000, GraphicsRequirementCatalog.capabilityValue(target, "sampledImageUpdateAfterBind"))
        assertEquals(null, GraphicsRequirementCatalog.capabilityValue(target, "unknownCapability"))
    }
}
