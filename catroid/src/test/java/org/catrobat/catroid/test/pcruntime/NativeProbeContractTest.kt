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

import org.catrobat.catroid.pcruntime.GraphicsBackend
import org.catrobat.catroid.pcruntime.GraphicsRequirementCatalog
import org.catrobat.catroid.pcruntime.VulkanProbe
import org.catrobat.catroid.pcruntime.VulkanVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Pins the contract between the C++ Vulkan probe (catroid/src/main/cpp/
 * pcruntime_native.cpp) and the Kotlin code that consumes its JSON.
 *
 * This test exists because the two halves are easy to break apart without a
 * compiler noticing: the native probe emitted its capabilities as a JSON
 * *array* while [VulkanProbe.parse] reads a JSON *object*, which silently turned
 * every Direct3D requirement into "not reported by the driver" on any device.
 * Neither file is wrong on its own, so only a test spanning both catches it.
 *
 * The fixture is not hand-written: it was produced by the production serializer
 * (FeatureBag::toJson) driven by the feature names that pcruntime_native.cpp
 * emits, with the values an Adreno-class driver reports. The *values* are
 * therefore representative rather than captured (the build host has no Vulkan
 * loader); shape, names and parsing are what the assertions cover.
 */
class NativeProbeContractTest {

    private fun locateNativeSource(): File {
        val relative = "src/main/cpp/pcruntime_native.cpp"
        val candidates = listOf(
            File(relative),
            File("catroid/$relative"),
            File(System.getProperty("user.dir"), relative),
            File(File(System.getProperty("user.dir"), "catroid"), relative)
        )
        val found = candidates.firstOrNull { it.isFile }
        assumeTrue(
            "pcruntime_native.cpp not reachable from ${System.getProperty("user.dir")}",
            found != null
        )
        return found!!
    }

    /** Feature names the native probe reports. */
    private fun nativeFeatureNames(): Set<String> {
        val source = locateNativeSource().readText()
        return Regex("""features\.(?:flag|limit)\("([A-Za-z0-9_]+)"""").findAll(source)
            .map { it.groupValues[1] }
            .toSet()
    }

    @Test
    fun testNativeReportsEveryFeatureTheCatalogRequires() {
        val names = nativeFeatureNames()
        val required = setOf(
            "graphicsPipelineLibrary",
            "descriptorIndexing",
            "shaderDrawParameters",
            "samplerMirrorClampToEdge",
            "robustImageAccess2",
            "sampledImageUpdateAfterBind",
            "storageImageUpdateAfterBind"
        )
        required.forEach { feature ->
            assertTrue(
                "the native probe never reports '$feature', so it can never be satisfied. " +
                    "reported = $names",
                names.contains(feature)
            )
        }
    }

    @Test
    fun testNativeSerialisesFeaturesAsAJsonObject() {
        val source = locateNativeSource().readText()
        assertTrue(
            "the device document must be built from FeatureBag",
            source.contains("features.toJson()")
        )
        // The parser calls optJSONObject("features"); an array yields an empty set.
        assertFalse(
            "features must not be emitted as a JSON array again",
            Regex("""features\\":\[""").containsMatchIn(source)
        )
        assertTrue(
            "FeatureBag must own the braces of the object",
            Regex("""std::string toJson\(\) const""").containsMatchIn(source)
        )
    }

    @Test
    fun testNativeExportsMatchTheKotlinExternalDeclarations() {
        val source = locateNativeSource().readText()
        val searchRoot = System.getProperty("user.dir")
        val probeCandidates = listOf(
            File("src/main/java/org/catrobat/catroid/pcruntime/VulkanProbe.kt"),
            File("catroid/src/main/java/org/catrobat/catroid/pcruntime/VulkanProbe.kt"),
            File(searchRoot, "src/main/java/org/catrobat/catroid/pcruntime/VulkanProbe.kt"),
            File(File(searchRoot, "catroid"), "src/main/java/org/catrobat/catroid/pcruntime/VulkanProbe.kt")
        )
        val probe = probeCandidates.firstOrNull { it.isFile }
        assumeTrue("VulkanProbe.kt not reachable", probe != null)

        val externals = Regex("""external fun (native[A-Za-z0-9_]+)\(""").findAll(probe!!.readText())
            .map { it.groupValues[1] }
            .toSet()
        assertTrue("no external declarations found in VulkanProbe.kt", externals.isNotEmpty())
        externals.forEach { name ->
            val jni = "Java_org_catrobat_catroid_pcruntime_VulkanProbe_$name"
            assertTrue("the shared library does not export $jni", source.contains(jni))
        }
    }

    @Test
    fun testCpuCapabilitiesComeFromTheKernelNotFromCompilerMacros() {
        val source = locateNativeSource().readText()
        listOf("arch", "pointerSize", "neon", "crc32", "dotprod", "androidApi").forEach { key ->
            assertTrue("the CPU report no longer contains '$key'", source.contains(key))
        }
        // The NDK compiles for the baseline ABI, so __ARM_FEATURE_CRC32 is 0 even
        // on a Snapdragon 8 Gen 2; the kernel has to be asked instead.
        assertTrue(
            "CPU capabilities must be read from getauxval(AT_HWCAP)",
            source.contains("getauxval(AT_HWCAP)")
        )
    }

    @Test
    fun testAnAdrenoClassProbeMakesDxvkAndVkd3dReady() {
        val report = VulkanProbe.parse(NATIVE_ADRENO_FIXTURE, "test")
        assertTrue("the fixture must parse as available: ${report.probeError}", report.available)
        assertNull(report.probeError)
        assertEquals(VulkanVersion(1, 3, 0), report.loaderApiVersion)

        val device = report.preferredDevice()
        assertNotNull(device)
        assertTrue(device!!.name.contains("Adreno"))
        assertTrue(
            "graphicsPipelineLibrary must survive the parse into the feature set",
            device.hasFeature("graphicsPipelineLibrary")
        )
        assertEquals(1048576, GraphicsRequirementCatalog.capabilityValue(device, "sampledImageUpdateAfterBind"))
        assertEquals(1048576, GraphicsRequirementCatalog.capabilityValue(device, "storageImageUpdateAfterBind"))

        val dxvk = GraphicsRequirementCatalog.evaluate(GraphicsBackend.DXVK_D3D9, report)
        assertTrue("DXVK must be ready for this capability set: ${dxvk.missing()}", dxvk.isReady)

        val vkd3d = GraphicsRequirementCatalog.evaluate(GraphicsBackend.VKD3D_PROTON, report)
        assertTrue("VKD3D-Proton must be ready for this capability set: ${vkd3d.missing()}", vkd3d.isReady)
    }

    @Test
    fun testAVulkan12DriverIsNotReadyForDxvk() {
        // Same capability set, older driver: the negative control proving the
        // readiness answer comes from the parsed document and not from defaults.
        val older = NATIVE_ADRENO_FIXTURE
            .replace("\"apiVersion\":4206592", "\"apiVersion\":4202496")
            .replace("\"loaderApiVersion\":4206592", "\"loaderApiVersion\":4202496")
        val report = VulkanProbe.parse(older, "test")
        assertTrue(report.available)
        assertEquals(VulkanVersion(1, 2, 0), report.loaderApiVersion)

        val readiness = GraphicsRequirementCatalog.evaluate(GraphicsBackend.DXVK_D3D9, report)
        assertFalse(readiness.isReady)
        assertTrue(readiness.missing().any { it.name == "Vulkan 1.3 driver" })
    }

    @Test
    fun testAMissingLoaderIsReportedAsUnavailable() {
        // Captured from the real native code on a host without libvulkan.so: a
        // missing loader must produce an unavailable report carrying the reason,
        // never a crash and never a silent "no devices" answer.
        val raw = "{\"available\":false,\"error\":\"libvulkan.so could not be loaded on this device\"}"
        val report = VulkanProbe.parse(raw, "test")
        assertFalse(report.available)
        assertTrue(report.devices.isEmpty())
        assertNotNull(report.probeError)
        assertTrue(report.probeError!!.contains("libvulkan.so"))
    }

    @Test
    fun testUnparseableOutputIsReportedAsAnError() {
        val report = VulkanProbe.parse("not json at all", "test")
        assertFalse(report.available)
        assertTrue(
            "a broken probe document must name the reason",
            report.probeError!!.contains("could not parse")
        )
    }


    @Test
    fun testCapturedCpuReportFromTheNativeHelperIsParsed() {
        // Verbatim output of describeCpuFeatures(), captured by running the real
        // translation unit in the offline harness (see the PR description). The
        // values come from a build host without an ARM kernel, so this asserts
        // the decoding, not the hardware.
        val captured =
            "{\"arch\":\"x86_64\",\"pointerSize\":8,\"neon\":0,\"crc32\":0,\"dotprod\":0," +
                "\"sve\":0,\"capabilitiesSource\":\"compile-time macros\",\"androidApi\":26}"

        val caps = VulkanProbe.parseCpu(captured)
        assertNotNull("the captured report must decode", caps)
        assertEquals("x86_64", caps!!.arch)
        assertEquals(8, caps.pointerSize)
        assertFalse(caps.neon)
        assertFalse(caps.crc32)
        assertFalse(caps.dotprod)
        assertEquals("compile-time macros", caps.capabilitiesSource)
        assertEquals(26, caps.androidApi)
        assertTrue("the summary must name where the values came from", caps.summary().contains("compile-time macros"))
    }

    @Test
    fun testKernelSourcedReportIsMarkedAsSuch() {
        // What the helper emits on an arm64 device whose kernel answers
        // getauxval(AT_HWCAP): the capabilities are hardware facts, and the
        // summary has to say so, because a macro-derived "no" and a
        // hardware-derived "no" mean different things to a translator.
        val report =
            "{\"arch\":\"aarch64\",\"pointerSize\":8,\"neon\":1,\"crc32\":1,\"dotprod\":1," +
                "\"sve\":0,\"capabilitiesSource\":\"AT_HWCAP\",\"androidApi\":33}"

        val caps = VulkanProbe.parseCpu(report)
        assertNotNull(caps)
        assertEquals("aarch64", caps!!.arch)
        assertTrue(caps.neon)
        assertTrue(caps.crc32)
        assertTrue(caps.dotprod)
        assertFalse(caps.sve)
        assertEquals("AT_HWCAP", caps.capabilitiesSource)
        val summary = caps.summary()
        assertTrue(summary.contains("aarch64"))
        assertTrue(summary.contains("NEON yes"))
        assertTrue(summary.contains("CRC32 yes"))
        assertTrue(summary.contains("dot-product yes"))
    }

    @Test
    fun testBrokenCpuReportIsRejectedInsteadOfGuessed() {
        assertNull(VulkanProbe.parseCpu("not json"))
        assertNull(VulkanProbe.parseCpu("[1,2,3]"))
    }

    @Test
    fun testCpuCapabilitiesAreUnavailableWithoutTheNativeLibrary() {
        // Off device the shared library is not loaded, which must yield null
        // (the caller then reports "unavailable") and never an optimistic
        // default like "no capabilities".
        assertNull(VulkanProbe.cpuCapabilities())
    }


    @Test
    fun testFeatureQueriesUseTheStructureThatOwnsTheFeature() {
        // Regression test for the defect that broke the debug APK build: three
        // features were read from VkPhysicalDeviceFeatures, which has no such
        // members, so the native target did not compile at all.
        //   * samplerMirrorClampToEdge  -> VkPhysicalDeviceVulkan12Features
        //   * graphicsPipelineLibrary   -> VkPhysicalDeviceGraphicsPipelineLibraryFeaturesEXT
        //   * robustImageAccess2        -> VkPhysicalDeviceRobustness2FeaturesEXT
        val source = locateNativeSource().readText()

        assertFalse(
            "VkPhysicalDeviceFeatures has no 'samplerMirrorClampToEdge' member",
            source.contains("core.features.samplerMirrorClampToEdge")
        )
        assertFalse(
            "VkPhysicalDeviceFeatures has no 'robustImageAccess2' member",
            source.contains("core.features.robustImageAccess2")
        )

        listOf(
            "VkPhysicalDeviceVulkan12Features",
            "VkPhysicalDeviceGraphicsPipelineLibraryFeaturesEXT",
            "VkPhysicalDeviceRobustness2FeaturesEXT"
        ).forEach { structure ->
            assertTrue("the probe no longer queries $structure", source.contains(structure))
        }

        // The extension features may only be asked for when the extension is
        // advertised; asking anyway is a validation error waiting to happen.
        assertTrue(
            "graphicsPipelineLibrary must be gated on VK_EXT_graphics_pipeline_library",
            source.contains("hasExtension(\"VK_EXT_graphics_pipeline_library\")")
        )
        assertTrue(
            "robustImageAccess2 must be gated on VK_EXT_robustness2",
            source.contains("hasExtension(\"VK_EXT_robustness2\")")
        )
    }

    @Test
    fun testProbeLogsItsDecisionPointsForDeviceDiagnosis() {
        // A probe that fails on a physical device has to leave a trace in
        // logcat; the block's return value alone is not enough to debug it.
        val source = locateNativeSource().readText()
        assertTrue(source.contains("PCRUN_LOG("))
        assertFalse(
            "the logging macro must actually be used, not just defined",
            Regex("""PCRUN_LOG\(""").findAll(source).count() < 2
        )
        assertTrue(
            "a build without Vulkan headers must say so in the log",
            source.contains("built without Vulkan headers")
        )
    }

    companion object {
        /**
         * Shape-exact sample of the native probe output, produced by running the
         * production FeatureBag serializer over the feature names that
         * pcruntime_native.cpp emits.
         */
        private const val NATIVE_ADRENO_FIXTURE = "{\"available\":true,\"source\":\"libcatroid.so dlopen(libvulkan.so)\",\"loaderApiVersion\":4206592,\"instanceExtensions\":[\"VK_KHR_surface\",\"VK_KHR_get_physical_device_properties2\"] ,\"devices\":[{\"name\":\"Adreno (TM) 740\",\"deviceType\":1,\"deviceTypeName\":\"integrated\",\"apiVersion\":4206592,\"driverVersion\":1141964800,\"vendorId\":20803,\"driverId\":1,\"driverName\":\"Qualcomm proprietary\",\"driverInfo\":\"Adreno 740, driver 615.0\",\"extensions\":[\"VK_KHR_maintenance5\",\"VK_EXT_graphics_pipeline_library\",\"VK_EXT_robustness2\",\"VK_KHR_push_descriptor\",\"VK_EXT_descriptor_indexing\"],\"features\":{\"descriptorBindingSampledImageUpdateAfterBind\":1048576,\"descriptorBindingStorageImageUpdateAfterBind\":1048576,\"descriptorBindingUniformBufferUpdateAfterBind\":1048576,\"descriptorIndexing\":1,\"graphicsPipelineLibrary\":1,\"nullDescriptor\":1,\"robustImageAccess2\":1,\"runtimeDescriptorArray\":1,\"sampledImageUpdateAfterBind\":1048576,\"samplerMirrorClampToEdge\":1,\"samplerUpdateAfterBind\":1048576,\"shaderDrawParameters\":1,\"storageImageUpdateAfterBind\":1048576,\"timelineSemaphore\":1,\"uniformBufferUpdateAfterBind\":1048576}}]}"
    }
}
