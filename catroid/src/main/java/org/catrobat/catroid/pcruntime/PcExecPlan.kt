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

import java.io.File

/**
 * How a native executable will actually be started on this device.
 *
 * Android 10 (API 29) introduced a SELinux enforced W^X rule: an untrusted app
 * that targets API 29 or newer cannot call execve() on a file inside its own
 * writable home directory. NewCatroid targets API 33, so the rule applies.
 * There are exactly two sanctioned ways to run a bundled executable:
 *
 *  1. [DIRECT] - the file lives in the read-only extracted native library
 *     directory (/data/app/.../lib/arm64). Android permits exec() there.
 *  2. [SYSTEM_LINKER] - execute /system/bin/linker64 with the real binary as
 *     argv[1]. The kernel only sees the system linker being executed, which is
 *     allowed, and the linker then maps the binary. This is the same
 *     mechanism termux-exec uses and the mechanism the existing QEMU
 *     integration in this repository relies on.
 *
 * A statically linked binary in the app home directory cannot be started at
 * all, because there is no dynamic linker for it to go through; that case is
 * reported as [BLOCKED] rather than silently attempted.
 */
enum class PcExecMode(val displayName: String) {
    DIRECT("direct exec"),
    SYSTEM_LINKER("system linker exec"),
    BLOCKED("blocked by Android W^X policy")
}

data class PcExecPlan(
    val mode: PcExecMode,
    val command: List<String>,
    val executable: File,
    val environment: Map<String, String>,
    val blockedReason: String? = null
) {
    val executableName: String
        get() = executable.name

    /** Human readable form; shown by the "inspect binary" block to the user. */
    fun describe(): String =
        "${mode.displayName} ($executableName)" + (blockedReason?.let { " - $it" } ?: "")
}

/**
 * Pure planning logic: given a target executable, the app's native library
 * directory and the platform linker path, decide how to launch it. This is
 * kept free of Android APIs so the decision table can be unit tested.
 */
object PcExecPlanner {

    const val DEFAULT_LINKER_64 = "/system/bin/linker64"

    /**
     * @param executable the file the user or a component registry wants to run.
     * @param nativeLibraryDir `Context.applicationInfo.nativeLibraryDir`, or null when unknown.
     * @param linkerPath absolute path of the platform dynamic linker.
     * @param extraLibraries directories to place on LD_LIBRARY_PATH.
     */
    fun plan(
        executable: File,
        nativeLibraryDir: String?,
        linkerPath: String = DEFAULT_LINKER_64,
        extraLibraries: List<File> = emptyList(),
        extraEnvironment: Map<String, String> = emptyMap(),
        arguments: List<String> = emptyList()
    ): PcExecPlan {
        val baseEnv = LinkedHashMap<String, String>()
        baseEnv["PATH"] = "/system/bin:/system/xbin"
        if (extraLibraries.isNotEmpty()) {
            baseEnv["LD_LIBRARY_PATH"] = extraLibraries.joinToString(":") { it.absolutePath }
        }
        baseEnv.putAll(extraEnvironment)

        if (!executable.exists()) {
            return PcExecPlan(
                PcExecMode.BLOCKED,
                emptyList(),
                executable,
                baseEnv,
                "file does not exist"
            )
        }
        if (!executable.isFile) {
            return PcExecPlan(
                PcExecMode.BLOCKED,
                emptyList(),
                executable,
                baseEnv,
                "not a regular file"
            )
        }

        val info = ElfInspector.inspect(executable)
        if (info == null) {
            return PcExecPlan(
                PcExecMode.BLOCKED,
                emptyList(),
                executable,
                baseEnv,
                "not an ELF executable"
            )
        }

        if (info.arch != PcCpuArch.AARCH64) {
            return PcExecPlan(
                PcExecMode.BLOCKED,
                emptyList(),
                executable,
                baseEnv,
                "binary targets ${info.arch.displayName}, this device executes AArch64 natively. " +
                    "x86/x86-64 Linux binaries require a CPU translator (Box64) to be launched explicitly."
            )
        }

        if (isInsideNativeLibraryDir(executable, nativeLibraryDir)) {
            val command = mutableListOf(executable.absolutePath)
            command += arguments
            return PcExecPlan(PcExecMode.DIRECT, command, executable, baseEnv)
        }

        if (!info.isDynamicExecutable) {
            return PcExecPlan(
                PcExecMode.BLOCKED,
                emptyList(),
                executable,
                baseEnv,
                "statically linked binary in the app data directory cannot be executed on Android 10+ " +
                    "(W^X policy). Rebuild it as a PIE/dynamic executable or ship it in jniLibs."
            )
        }

        val command = mutableListOf(linkerPath, executable.absolutePath)
        command += arguments
        return PcExecPlan(PcExecMode.SYSTEM_LINKER, command, executable, baseEnv)
    }

    fun isInsideNativeLibraryDir(file: File, nativeLibraryDir: String?): Boolean {
        if (nativeLibraryDir.isNullOrBlank()) {
            return false
        }
        return try {
            val canonicalFile = file.canonicalPath
            val canonicalDir = File(nativeLibraryDir).canonicalPath.trimEnd('/') + "/"
            canonicalFile.startsWith(canonicalDir)
        } catch (e: java.io.IOException) {
            false
        }
    }
}
