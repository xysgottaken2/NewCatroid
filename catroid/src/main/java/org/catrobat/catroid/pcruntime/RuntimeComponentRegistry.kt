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
import java.security.MessageDigest

/**
 * The role a runtime component plays in the PC compatibility stack. These are
 * deliberately *not* interchangeable: a CPU translator is not a Windows
 * compatibility layer and neither of them is a graphics translator.
 */
enum class PcComponentKind(val directoryName: String, val displayName: String) {
    CPU_TRANSLATOR("cpu-translator", "CPU translator (Box64)"),
    WINDOWS_COMPATIBILITY("wine", "Windows compatibility layer (Wine)"),
    LINUX_USERLAND("userland", "Linux userland / glibc rootfs"),
    DIRECT3D_TRANSLATOR("dxvk", "Direct3D-to-Vulkan translator (DXVK)"),
    DIRECT3D12_TRANSLATOR("vkd3d", "Direct3D 12 translator (VKD3D-Proton)"),
    DISPLAY_SERVER("display", "X11 display server")
}

/**
 * A single inspected component file.
 *
 * @param problems empty when the file is usable for its declared purpose.
 */
data class PcComponentFile(
    val kind: PcComponentKind,
    val file: File,
    val format: PcBinaryFormat,
    val arch: PcCpuArch,
    val description: String,
    val sizeBytes: Long,
    val sha256Prefix: String,
    val problems: List<String>
) {
    val isValid: Boolean
        get() = problems.isEmpty()

    fun tableRow(): String = "${file.name}\t${description}\t${sizeBytes}B\tsha256:${sha256Prefix}\t" +
        if (isValid) "ok" else "invalid (${problems.joinToString(", ")})"
}

data class PcComponentGroup(
    val kind: PcComponentKind,
    val directory: File,
    val files: List<PcComponentFile>
) {
    val installed: Boolean
        get() = files.isNotEmpty()

    val usable: Boolean
        get() = files.isNotEmpty() && files.all { it.isValid }

    fun summary(): String = when {
        files.isEmpty() -> "${kind.displayName}: not installed"
        usable -> "${kind.displayName}: ${files.size} file(s) verified"
        else -> "${kind.displayName}: ${files.count { !it.isValid }} of ${files.size} file(s) invalid"
    }
}

/**
 * Discovers and *validates* PC runtime components that the user (or a build
 * script) placed in the app's private storage. Nothing is downloaded here and
 * nothing is executed here: this class only answers "what is present and is it
 * plausible for its role?".
 *
 * Validation is intentionally strict, because the whole point of the registry
 * is to stop the runtime from pretending that a component is functional:
 *
 *  * A CPU translator must be an AArch64 ELF binary. Box64 is an AArch64 host
 *    binary that interprets/translates x86-64 guest code, so an x86-64 ELF
 *    here means the user grabbed the wrong build.
 *  * Wine's PE side and the Direct3D translators are PE files. Their machine
 *    field must match the architecture the user intends to run (x86-64 for
 *    modern titles, i386 for 32-bit ones) and the two must not be mixed.
 */
class RuntimeComponentRegistry(
    private val componentsRoot: File,
    private val log: PcRuntimeLog
) {

    fun directoryFor(kind: PcComponentKind): File = File(componentsRoot, kind.directoryName)

    /**
     * Creates the component root and one directory per [PcComponentKind], so a
     * user (or a later download step) has a defined place to drop Box64, Wine,
     * DXVK and friends. Returns true when the complete layout exists afterwards.
     */
    fun ensureLayout(): Boolean {
        var allPresent = true
        if (componentsRoot.mkdirs()) {
            log.info(TAG, "Created component root ${componentsRoot.absolutePath}")
        }
        PcComponentKind.entries.forEach { kind ->
            val directory = directoryFor(kind)
            if (directory.mkdirs()) {
                log.info(TAG, "Created component directory ${directory.absolutePath}")
            }
            if (!directory.isDirectory) {
                allPresent = false
                log.warn(TAG, "Component directory ${directory.absolutePath} could not be created")
            }
        }
        return allPresent
    }

    fun scanAll(): List<PcComponentGroup> = PcComponentKind.entries.map { scan(it) }

    fun scan(kind: PcComponentKind): PcComponentGroup {
        val directory = directoryFor(kind)
        val files = if (directory.isDirectory) {
            (directory.listFiles() ?: emptyArray())
                .filter { it.isFile }
                .sortedBy { it.name }
                .map { inspect(kind, it) }
        } else {
            emptyList()
        }
        return PcComponentGroup(kind, directory, files)
    }

    fun inspect(kind: PcComponentKind, file: File): PcComponentFile {
        val format = BinaryInspector.detectFormat(file)
        val problems = mutableListOf<String>()
        var arch = PcCpuArch.UNKNOWN
        var description = "unrecognised binary"

        when (format) {
            PcBinaryFormat.ELF -> {
                val info = ElfInspector.inspect(file)
                if (info == null) {
                    problems += "ELF header unreadable"
                } else {
                    arch = info.arch
                    description = info.describe()
                    if (kind == PcComponentKind.CPU_TRANSLATOR && arch != PcCpuArch.AARCH64) {
                        problems += "CPU translator must be an AArch64 host binary, found ${arch.displayName}"
                    }
                    if (kind == PcComponentKind.CPU_TRANSLATOR && !info.isDynamicExecutable) {
                        problems += "statically linked binaries cannot be launched from app storage on Android 10+"
                    }
                }
            }
            PcBinaryFormat.PE -> {
                val info = PeInspector.inspect(file)
                if (info == null) {
                    problems += "PE header unreadable"
                } else {
                    arch = info.arch
                    description = info.describe()
                    if (kind == PcComponentKind.CPU_TRANSLATOR) {
                        problems += "expected an AArch64 ELF CPU translator, found a PE file"
                    }
                    if ((kind == PcComponentKind.DIRECT3D_TRANSLATOR ||
                            kind == PcComponentKind.DIRECT3D12_TRANSLATOR) &&
                        arch != PcCpuArch.X86_64
                    ) {
                        problems += "translator DLL is ${arch.displayName}; the win64 pipeline needs x86-64 PE DLLs"
                    }
                }
            }
            PcBinaryFormat.UNKNOWN -> problems += "not an ELF or PE binary"
        }

        return PcComponentFile(
            kind = kind,
            file = file,
            format = format,
            arch = arch,
            description = description,
            sizeBytes = file.length(),
            sha256Prefix = sha256Prefix(file),
            problems = problems
        )
    }

    /**
     * First [PREFIX_LENGTH] hex characters of the SHA-256 digest. A short
     * prefix is enough to let users cross-check a file against a published
     * checksum without turning the runtime log into a wall of hex.
     */
    fun sha256Prefix(file: File, prefixLength: Int = PREFIX_LENGTH): String = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var read = input.read(buffer)
            while (read > 0) {
                digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }.take(prefixLength)
    } catch (e: Exception) {
        "unavailable"
    }

    companion object {
        private const val TAG = "PcComponents"
        const val PREFIX_LENGTH = 16
    }
}
