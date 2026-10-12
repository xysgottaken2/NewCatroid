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
 * Stable, test-visible error taxonomy. Every failure produced by the PC runtime
 * is expressed as one of these codes so that blocks can report something more
 * useful than "it did not work".
 */
enum class PcErrorCode {
    NONE,
    NOT_INITIALIZED,
    ALREADY_INITIALIZED,
    UNSUPPORTED_ARCHITECTURE,
    MISSING_COMPONENT,
    EXECUTABLE_NOT_FOUND,
    EXECUTABLE_NOT_REGULAR_FILE,
    EXECUTABLE_FORMAT_INVALID,
    EXECUTABLE_ARCH_MISMATCH,
    EXECUTION_BLOCKED_BY_ANDROID,
    PROCESS_ALREADY_RUNNING,
    PROCESS_LAUNCH_FAILED,
    PROCESS_TIMEOUT,
    PROCESS_CANCELLED,
    NATIVE_PROBE_FAILED,
    VULKAN_UNAVAILABLE,
    PREFIX_PATH_INVALID,
    PREFIX_IO_ERROR,
    USERLAND_NOT_INSTALLED,
    INTERNAL_ERROR
}

data class PcRuntimeError(
    val code: PcErrorCode,
    val detail: String,
    val hint: String
) {
    fun format(): String = "$detail | $hint"

    override fun toString(): String = "${code.name}: ${format()}"
}

/**
 * Human readable, actionable description for every error code.
 * The guidance strings deliberately name the concrete next action instead of
 * restating the problem.
 */
object PcErrorCatalog {

    fun describe(code: PcErrorCode, detail: String = ""): PcRuntimeError {
        val hint = when (code) {
            PcErrorCode.NONE ->
                "No action required."
            PcErrorCode.NOT_INITIALIZED ->
                "Place an 'Init PC runtime' block before this block in the same script."
            PcErrorCode.ALREADY_INITIALIZED ->
                "The runtime is already up; call 'Shut down PC runtime' first if you need a clean restart."
            PcErrorCode.UNSUPPORTED_ARCHITECTURE ->
                "This device does not expose a 64-bit ARM ABI. The PC runtime needs arm64-v8a (AArch64). " +
                    "32-bit x86 translation (Box86) is not possible because Android has no 32-bit host subsystem."
            PcErrorCode.MISSING_COMPONENT ->
                "The component is not installed under the app's 'pcruntime/components' directory. " +
                    "Install it with the documented build script; the runtime never downloads executables by itself."
            PcErrorCode.EXECUTABLE_NOT_FOUND ->
                "Check that the file name and directory are correct and that the file still exists."
            PcErrorCode.EXECUTABLE_NOT_REGULAR_FILE ->
                "Point the block at a regular file, not at a directory or a symlink outside the app sandbox."
            PcErrorCode.EXECUTABLE_FORMAT_INVALID ->
                "The file is not a valid ELF (Linux) or PE (Windows) binary."
            PcErrorCode.EXECUTABLE_ARCH_MISMATCH ->
                "The binary was built for a different CPU architecture than this device. " +
                    "ARM64 Android can only start AArch64 ELF binaries directly."
            PcErrorCode.EXECUTION_BLOCKED_BY_ANDROID ->
                "Android 10+ forbids exec() on files in the app data directory (W^X policy) and static binaries " +
                    "cannot be started through the system linker. Ship the binary in jniLibs with " +
                    "android:extractNativeLibs=true, or build it as a dynamic (PIE) executable."
            PcErrorCode.PROCESS_ALREADY_RUNNING ->
                "Use the 'Stop running program' block before launching a new process."
            PcErrorCode.PROCESS_LAUNCH_FAILED ->
                "Verify the executable permissions, that the interpreter path exists, and check the runtime log."
            PcErrorCode.PROCESS_TIMEOUT ->
                "Increase the timeout or stop the program explicitly; the process has already been destroyed."
            PcErrorCode.PROCESS_CANCELLED ->
                "The process was stopped by a 'Stop running program' block."
            PcErrorCode.NATIVE_PROBE_FAILED ->
                "The native smoke test started but reported a failure (non-zero exit status). " +
                    "Read its output; a 'self_test_ok=0' means the packaged probe does not match this " +
                    "device's ABI or was corrupted, and a self test that cannot pass means no PC binary " +
                    "can be trusted to run here yet."
            PcErrorCode.VULKAN_UNAVAILABLE ->
                "The device did not expose a usable Vulkan driver. Direct3D translation layers need " +
                    "Vulkan 1.3 or newer; there is no software fallback in this runtime."
            PcErrorCode.PREFIX_PATH_INVALID ->
                "Prefix names must not be empty and must not contain path separators or '..'."
            PcErrorCode.PREFIX_IO_ERROR ->
                "Free up storage space and make sure the app has not been denied access to its private directory."
            PcErrorCode.USERLAND_NOT_INSTALLED ->
                "Wine and the CPU translator must be built and installed separately; see docs/PC_RUNTIME.md."
            PcErrorCode.INTERNAL_ERROR ->
                "Report this together with the runtime log; an unexpected exception was caught."
        }
        return PcRuntimeError(code, detail.ifBlank { code.name }, hint)
    }
}
