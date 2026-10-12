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
import android.os.Build
import android.util.Log
import java.io.File

enum class PcRuntimeState {
    NOT_INITIALIZED,
    READY,
    READY_WITHOUT_WINDOWS_LAYER,
    FAILED,
    SHUT_DOWN
}

/**
 * Everything a block can ask the runtime to report. Kept as an enum so the
 * visual block's spinner and this list can never drift apart silently.
 */
enum class PcQuery {
    STATUS,
    ERROR,
    ARCHITECTURE,
    ABIS,
    VULKAN,
    VULKAN_EXTENSIONS,
    GRAPHICS_READINESS,
    COMPONENTS,
    PREFIXES,
    LOG
}

/**
 * Immutable snapshot of runtime state. Blocks read this instead of poking at
 * live objects, which keeps execution of a script deterministic.
 */
data class PcRuntimeSnapshot(
    val state: PcRuntimeState,
    val device: DeviceArchReport?,
    val vulkan: VulkanReport?,
    val components: List<PcComponentGroup>,
    val lastError: PcRuntimeError?,
    val initializedAtMs: Long,
    val backend: GraphicsBackend
) {
    val windowsLayerAvailable: Boolean
        get() = components.any {
            it.kind == PcComponentKind.WINDOWS_COMPATIBILITY && it.usable
        }

    val cpuTranslatorAvailable: Boolean
        get() = components.any {
            it.kind == PcComponentKind.CPU_TRANSLATOR && it.usable
        }
}

/**
 * The P-C runtime façade.
 *
 * Scope, stated plainly: this class manages the *host side* of a PC compatibility
 * environment and answers, from real measurements, what this device can and
 * cannot do. It does not contain Wine, Box64 or DXVK. Those are separate
 * upstream projects that must be built and installed by the user; the runtime
 * refuses to claim they are present until a verified file is found in the
 * component directory, and it never downloads executables.
 */
object PcRuntimeManager {

    private const val TAG = "PcRuntime"

    private var context: Context? = null
    private val log = PcRuntimeLog()

    @Volatile
    private var state = PcRuntimeState.NOT_INITIALIZED

    @Volatile
    private var lastError: PcRuntimeError? = null

    @Volatile
    private var initializedAtMs = 0L

    @Volatile
    private var backend = GraphicsBackend.NONE

    private var deviceReport: DeviceArchReport? = null
    private var vulkanReport: VulkanReport? = null
    private var componentGroups: List<PcComponentGroup> = emptyList()

    private var registry: RuntimeComponentRegistry? = null
    private var prefixManager: WinePrefixManager? = null
    private var executor: PcProcessExecutor? = null
    private var probeRunner: NativeProbeRunner? = null

    fun runtimeLog(): PcRuntimeLog = log

    fun rootDirectory(): File? = context?.let { File(it.filesDir, "pcruntime") }

    fun state(): PcRuntimeState = state

    fun snapshot(): PcRuntimeSnapshot = PcRuntimeSnapshot(
        state = state,
        device = deviceReport,
        vulkan = vulkanReport,
        components = componentGroups,
        lastError = lastError,
        initializedAtMs = initializedAtMs,
        backend = backend
    )

    /**
     * Brings the runtime up. Safe to call repeatedly; the second call is a
     * no-op that keeps the existing measurements.
     */
    @Synchronized
    fun initialize(appContext: Context): PcRuntimeSnapshot {
        if (state == PcRuntimeState.READY || state == PcRuntimeState.READY_WITHOUT_WINDOWS_LAYER) {
            log.debug(TAG, "initialize() ignored, runtime is already up")
            return snapshot()
        }

        context = appContext.applicationContext
        initializedAtMs = System.currentTimeMillis()
        log.clear()
        log.info(TAG, "PC runtime initializing (app id ${appContext.packageName})")

        return try {
            val root = rootDirectory() ?: throw IllegalStateException("files dir unavailable")
            listOf("bin", "components", "prefixes").forEach { relative ->
                File(root, relative).mkdirs()
            }

            val componentsRoot = File(root, "components")
            val componentRegistry = RuntimeComponentRegistry(componentsRoot, log)
            componentRegistry.ensureLayout()
            registry = componentRegistry

            prefixManager = WinePrefixManager(File(root, "prefixes"), log)
            executor = PcProcessExecutor(log)
            probeRunner = NativeProbeRunner(context!!, root, log, executor!!)

            deviceReport = describeDevice()
            log.info(TAG, "Device: ${deviceReport?.summary()}")

            if (deviceReport?.hostArch != PcCpuArch.AARCH64) {
                lastError = PcErrorCatalog.describe(
                    PcErrorCode.UNSUPPORTED_ARCHITECTURE,
                    "primary ABI is ${deviceReport?.primaryAbi}"
                )
                state = PcRuntimeState.FAILED
                log.error(TAG, lastError!!.format())
                return snapshot()
            }

            vulkanReport = VulkanProbe.probe(appContext, log)
            log.info(TAG, "Vulkan: ${vulkanReport?.summary()}")

            componentGroups = componentRegistry.scanAll()
            componentGroups.forEach { log.info(TAG, it.summary()) }

            state = if (componentGroups.any {
                    it.kind == PcComponentKind.WINDOWS_COMPATIBILITY && it.usable
                }
            ) {
                PcRuntimeState.READY
            } else {
                PcRuntimeState.READY_WITHOUT_WINDOWS_LAYER
            }

            if (state == PcRuntimeState.READY_WITHOUT_WINDOWS_LAYER) {
                log.warn(
                    TAG,
                    "Windows compatibility layer not installed. Windows programs cannot be launched yet; " +
                        "see docs/PC_RUNTIME.md for the supported component sources."
                )
            }
            lastError = null
            log.info(TAG, "PC runtime state: $state")
            snapshot()
        } catch (e: Exception) {
            lastError = PcErrorCatalog.describe(PcErrorCode.INTERNAL_ERROR, e.message ?: e.javaClass.simpleName)
            state = PcRuntimeState.FAILED
            log.error(TAG, lastError!!.format())
            Log.e(TAG, "PC runtime initialization failed", e)
            snapshot()
        }
    }

    @Synchronized
    fun shutdown(): PcRuntimeSnapshot {
        log.info(TAG, "PC runtime shutting down")
        probeRunner = null
        executor = null
        prefixManager = null
        registry = null
        vulkanReport = null
        componentGroups = emptyList()
        state = PcRuntimeState.SHUT_DOWN
        initializedAtMs = 0L
        return snapshot()
    }

    /**
     * Answers one [PcQuery] from the current snapshot. Returns a plain string so
     * it can be dropped straight into a Catrobat user variable.
     */
    fun query(query: PcQuery): String = when (query) {
        PcQuery.STATUS -> statusText()
        PcQuery.ERROR -> lastError?.let { "${it.code.name}: ${it.format()}" } ?: "no error"
        PcQuery.ARCHITECTURE -> deviceReport?.let {
            "${it.hostArch.displayName}; primary ABI ${it.primaryAbi}; ${it.cpuCores} cores; " +
                "${it.pointerSizeBits}-bit pointers; API ${it.sdkInt}"
        } ?: "runtime not initialized"
        PcQuery.ABIS -> deviceReport?.supportedAbiDescriptions() ?: "runtime not initialized"
        PcQuery.VULKAN -> vulkanReport?.let {
            "${it.summary()}\nSource: ${it.source}"
        } ?: "runtime not initialized"
        PcQuery.VULKAN_EXTENSIONS -> describeVulkanCapabilities()
        PcQuery.GRAPHICS_READINESS -> describeGraphicsReadiness()
        PcQuery.COMPONENTS -> describeComponents()
        PcQuery.PREFIXES -> describePrefixes()
        PcQuery.LOG -> log.userTail(LOG_TAIL_LINES)
    }

    fun setGraphicsBackend(requested: GraphicsBackend): BackendReadiness {
        backend = requested
        val report = vulkanReport
            ?: return BackendReadiness(requested, emptyList())
        val readiness = GraphicsRequirementCatalog.evaluate(requested, report)
        if (readiness.isReady) {
            log.info(TAG, "Graphics backend ${requested.displayName}: driver requirements satisfied")
        } else {
            log.warn(TAG, readiness.summary())
        }
        return readiness
    }

    fun graphicsBackend(): GraphicsBackend = backend

    fun preparePrefix(name: String): PcPrefixResult {
        val manager = prefixManager
        if (manager == null) {
            return PcPrefixResult(
                name,
                null,
                emptyList(),
                emptyList(),
                true,
                PcErrorCatalog.describe(PcErrorCode.NOT_INITIALIZED, "prefix manager unavailable")
            )
        }
        return manager.prepare(name)
    }

    fun listPrefixes(): List<String> {
        val root = rootDirectory() ?: return emptyList()
        val prefixes = File(root, "prefixes")
        return (prefixes.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .map { it.name }
            .sorted()
    }

    fun runNativeProbe(): PcNativeProbeResult {
        val runner = probeRunner
            ?: return PcNativeProbeResult(
                false,
                -1,
                "",
                null,
                PcErrorCatalog.describe(PcErrorCode.NOT_INITIALIZED, "probe runner unavailable")
            )
        val result = runner.run()
        if (!result.available) {
            lastError = result.error
        }
        return result
    }

    fun componentRegistry(): RuntimeComponentRegistry? = registry

    fun processExecutor(): PcProcessExecutor? = executor

    private fun statusText(): String = when (state) {
        PcRuntimeState.NOT_INITIALIZED -> "not initialized"
        PcRuntimeState.READY -> "ready (Windows layer present)"
        PcRuntimeState.READY_WITHOUT_WINDOWS_LAYER ->
            "ready for diagnostics; Windows layer NOT installed, so no Windows program can be launched yet"
        PcRuntimeState.FAILED -> "failed: ${lastError?.format() ?: "unknown"}"
        PcRuntimeState.SHUT_DOWN -> "shut down"
    }

    private fun describeVulkanCapabilities(): String {
        val report = vulkanReport ?: return "runtime not initialized"
        if (!report.available) {
            return "unavailable: ${report.probeError}"
        }
        val device = report.preferredDevice() ?: return "no physical device enumerated"
        val interesting = listOf(
            "VK_KHR_maintenance5",
            "VK_EXT_robustness2",
            "VK_KHR_push_descriptor",
            "VK_EXT_graphics_pipeline_library",
            "VK_EXT_descriptor_indexing",
            "VK_KHR_driver_properties"
        )
        return buildString {
            append(device.name).append('\n')
            append("API ").append(device.apiVersion).append('\n')
            append("driver ").append(device.driverLabel()).append(' ').append(device.driverVersion).append('\n')
            append("device type ").append(device.deviceTypeName).append('\n')
            append("extensions: ").append(device.extensions.size).append('\n')
            interesting.forEach { extension ->
                append(if (device.hasExtension(extension)) "[+] " else "[-] ")
                append(extension).append('\n')
            }
            val counts = listOf(
                "sampledImageUpdateAfterBind",
                "storageImageUpdateAfterBind",
                "samplerUpdateAfterBind"
            )
            counts.forEach { name ->
                val value = GraphicsRequirementCatalog.capabilityValue(device, name)
                append(name).append(" = ").append(value ?: "not reported").append('\n')
            }
        }.trimEnd()
    }

    private fun describeGraphicsReadiness(): String {
        val report = vulkanReport ?: return "runtime not initialized"
        if (!report.available) {
            return "Vulkan unavailable: ${report.probeError}\n" +
                "No Direct3D translation layer can run without a Vulkan driver."
        }
        return GraphicsRequirementCatalog.evaluateAll(report)
            .filter { it.backend != GraphicsBackend.NONE }
            .joinToString(separator = "\n") { it.summary() }
    }

    private fun describeComponents(): String {
        if (componentGroups.isEmpty()) {
            return "runtime not initialized"
        }
        return componentGroups.joinToString(separator = "\n") { group ->
            buildString {
                append(group.summary())
                if (group.files.isNotEmpty()) {
                    append('\n')
                    append(group.files.joinToString(separator = "\n") { "  " + it.tableRow() })
                }
            }
        }
    }

    private fun describePrefixes(): String {
        val prefixes = listPrefixes()
        return if (prefixes.isEmpty()) {
            "no prefixes prepared"
        } else {
            prefixes.joinToString(separator = "\n") { name ->
                val manager = prefixManager
                val ready = manager?.exists(name) ?: false
                "$name (layout ${if (ready) "present" else "incomplete"}; wineboot still required)"
            }
        }
    }

    private fun describeDevice(): DeviceArchReport {
        val abis = Build.SUPPORTED_ABIS.toList()
        val primaryAbi = abis.firstOrNull() ?: "unknown"
        val arch = PcAbiMapper.toArch(primaryAbi)
        return DeviceArchReport(
            primaryAbi = primaryAbi,
            supportedAbis = abis,
            hostArch = arch,
            is64BitAbi = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty(),
            pointerSizeBits = if (arch == PcCpuArch.AARCH64 || arch == PcCpuArch.X86_64) 64 else 32,
            cpuCores = Runtime.getRuntime().availableProcessors(),
            sdkInt = Build.VERSION.SDK_INT,
            socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else Build.MODEL,
            socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MANUFACTURER
            } else {
                Build.MANUFACTURER
            }
        )
    }

    const val LOG_TAIL_LINES = 40
}
