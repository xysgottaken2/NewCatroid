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
import java.io.File
import java.io.IOException

data class PcNativeProbeResult(
    val available: Boolean,
    val exitCode: Int,
    val output: String,
    val execMode: PcExecMode?,
    val error: PcRuntimeError?
) {
    fun summary(): String = when {
        error != null -> "native probe failed: ${error.format()}"
        else -> "native probe exit=$exitCode via ${execMode?.displayName ?: "?"}"
    }
}

/**
 * Extracts and runs the `pcrt_probe` helper binary.
 *
 * This is a genuine out-of-process native smoke test: a small AArch64
 * executable compiled from `tools/pcrt_probe/pcrt_probe.c`, packaged as an
 * asset by CI, copied into app storage and started through the system dynamic
 * linker. It validates the exact launch mechanism a CPU translator such as
 * Box64 will need, on the device it will need it on.
 *
 * If the asset is missing (a build that did not run the probe build step) the
 * result is an explicit MISSING_COMPONENT error. A missing smoke test is never
 * reported as a pass.
 */
class NativeProbeRunner(
    private val context: Context,
    private val root: File,
    private val log: PcRuntimeLog,
    private val executor: PcProcessExecutor
) {

    fun binaryFile(): File = File(File(root, "bin"), BINARY_NAME)

    fun isInstalled(): Boolean = binaryFile().isFile

    /**
     * Copies the ABI-specific probe out of the APK assets when it is not
     * present yet or when the packaged copy differs from the installed one.
     */
    fun install(): Result<File> {
        val abi = PcAbiMapper.selectTargetAbi(android.os.Build.SUPPORTED_ABIS.toList())
        val abiDirectory = PcAbiMapper.nativeLibrarySubdirectory(abi)
        val assetPath = "$ASSET_ROOT/$abiDirectory/$BINARY_NAME"
        val destination = binaryFile()

        return try {
            val assetFiles = context.assets.list("$ASSET_ROOT/$abiDirectory")?.toList() ?: emptyList()
            if (!assetFiles.contains(BINARY_NAME)) {
                val error = PcErrorCatalog.describe(
                    PcErrorCode.MISSING_COMPONENT,
                    "asset $assetPath is not packaged in this APK"
                )
                log.error(TAG, error.format())
                return Result.failure(IOException(error.format()))
            }

            destination.parentFile?.mkdirs()

            val needsCopy = !destination.isFile || destination.length() == 0L
            if (needsCopy) {
                context.assets.open(assetPath).use { input ->
                    destination.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                destination.setReadable(true, true)
                log.info(TAG, "Installed native probe for $abi at ${destination.absolutePath}")
            }
            Result.success(destination)
        } catch (e: IOException) {
            val error = PcErrorCatalog.describe(PcErrorCode.MISSING_COMPONENT, e.message ?: "asset copy failed")
            log.error(TAG, error.format())
            Result.failure(e)
        }
    }

    /**
     * Runs the probe and waits for it. Callers must be on a background thread:
     * the runtime never blocks the Android main thread.
     */
    fun run(timeoutMs: Long = DEFAULT_TIMEOUT_MS): PcNativeProbeResult {
        val binary = install().getOrElse { failure ->
            return PcNativeProbeResult(
                false,
                -1,
                "",
                null,
                PcErrorCatalog.describe(
                    PcErrorCode.MISSING_COMPONENT,
                    failure.message ?: "probe binary unavailable"
                )
            )
        }

        // setExecutable is advisory: the actual launch goes through the system
        // linker, which does not consult the file mode of the target. It helps
        // the DIRECT path when the app is running on Android 9 or older.
        binary.setExecutable(true, true)

        val plan = PcExecPlanner.plan(
            executable = binary,
            nativeLibraryDir = context.applicationInfo.nativeLibraryDir,
            linkerPath = PcExecPlanner.DEFAULT_LINKER_64,
            arguments = listOf("--json")
        )

        if (plan.mode == PcExecMode.BLOCKED) {
            return PcNativeProbeResult(
                false,
                -1,
                "",
                plan.mode,
                PcErrorCatalog.describe(
                    PcErrorCode.EXECUTION_BLOCKED_BY_ANDROID,
                    plan.blockedReason ?: "unknown reason"
                )
            )
        }

        val result = executor.runBlocking("pcrt_probe", plan, timeoutMs)
        if (result.error != null) {
            return PcNativeProbeResult(false, result.exitCode, result.output, plan.mode, result.error)
        }
        if (result.exitCode != 0) {
            // The probe uses a non-zero exit status to say "I ran, but the
            // result is not trustworthy". Reporting that as a pass would be
            // exactly the kind of fake success this runtime must not produce.
            val error = PcErrorCatalog.describe(
                PcErrorCode.NATIVE_PROBE_FAILED,
                "pcrt_probe exited with status ${result.exitCode}"
            )
            log.error(TAG, error.format())
            return PcNativeProbeResult(false, result.exitCode, result.output, plan.mode, error)
        }
        log.info(TAG, "Native probe exit=${result.exitCode} in ${result.durationMs}ms")
        return PcNativeProbeResult(true, result.exitCode, result.output, plan.mode, null)
    }

    companion object {
        private const val TAG = "PcProbe"
        const val ASSET_ROOT = "pcruntime"
        const val BINARY_NAME = "pcrt_probe"
        const val DEFAULT_TIMEOUT_MS = 10_000L
    }
}
