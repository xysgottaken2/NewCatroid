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

package org.catrobat.catroid.pcruntime

/**
 * Which C library an ELF binary was linked against.
 *
 * This matters because the two worlds are not interchangeable: a binary built
 * against Android's bionic libc can be started through `/system/bin/linker64`
 * on this device, while a glibc or musl build (Termux, Debian, a Wine tarball
 * from a Linux distribution) needs its own userland and can never be started
 * by the Android linker. Recognising that difference early is what keeps the
 * runtime from reporting "component ready" for a binary it cannot run.
 */
enum class PcToolchain(val displayName: String) {
    BIONIC("Android/bionic"),
    GLIBC("glibc"),
    MUSL("musl"),
    STATIC("statically linked"),
    UNKNOWN("unknown toolchain")
}

/**
 * Classifies a `PT_INTERP` value. Pure string logic, no device access, so it
 * can be unit tested and reused by the component validator.
 */
object ToolchainCompatibility {

    private val BIONIC_LINKERS = listOf("/system/bin/linker", "linker64", "linker")

    fun classify(interpreter: String): PcToolchain {
        val value = interpreter.trim()
        if (value.isEmpty()) {
            return PcToolchain.STATIC
        }
        // Android's dynamic linker: /system/bin/linker64, /system/bin/linker,
        // and the bare "linker64" some toolchains record.
        if (BIONIC_LINKERS.any { value == it || value.endsWith("/$it") || value.startsWith(it) }) {
            return PcToolchain.BIONIC
        }
        if (value.contains("ld-musl")) {
            return PcToolchain.MUSL
        }
        // glibc: /lib64/ld-linux-aarch64.so.1, /lib/ld-linux-armhf.so.3,
        // /lib64/ld-linux-x86-64.so.2. Also accept the plain ld.so spelling.
        if (value.contains("ld-linux") || value.contains("/ld.so") || value.endsWith("ld.so")) {
            return PcToolchain.GLIBC
        }
        return PcToolchain.UNKNOWN
    }

    /** True when the Android linker can start the binary on this device. */
    fun canUseSystemLinker(toolchain: PcToolchain): Boolean = toolchain == PcToolchain.BIONIC

    /**
     * A short explanation of what is missing, shown by the diagnostics blocks
     * instead of a vague "unsupported binary" message.
     */
    fun limitation(toolchain: PcToolchain): String? = when (toolchain) {
        PcToolchain.BIONIC -> null
        PcToolchain.STATIC ->
            "a statically linked binary has no interpreter to start it through; " +
                "it can only run from the app's native library directory"
        PcToolchain.GLIBC ->
            "a glibc build cannot be started by the Android linker; it needs a " +
                "bundled glibc userland (rootfs) and a bionic entry point"
        PcToolchain.MUSL ->
            "a musl build cannot be started by the Android linker; it needs its " +
                "own loader inside a bundled userland"
        PcToolchain.UNKNOWN ->
            "the program interpreter is not one this runtime recognises"
    }
}
