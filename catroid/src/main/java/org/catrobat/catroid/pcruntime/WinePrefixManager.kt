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
import java.io.IOException

/**
 * Result of preparing a Wine prefix directory.
 *
 * @param winebootRequired always true: only Wine itself can turn the directory
 * skeleton into a functioning prefix by generating the registry hives and the
 * initial C: drive contents. This flag exists so the runtime never reports a
 * "created Windows prefix" that Wine would reject.
 */
data class PcPrefixResult(
    val requestedName: String,
    val root: File?,
    val createdDirectories: List<String>,
    val createdSymlinks: List<String>,
    val winebootRequired: Boolean,
    val error: PcRuntimeError?
) {
    val success: Boolean
        get() = error == null

    fun summary(): String = when {
        error != null -> "prefix '$requestedName' failed: ${error.format()}"
        else -> buildString {
            append("prefix '").append(requestedName).append("' layout ready at ")
            append(root?.absolutePath)
            append(" (").append(createdDirectories.size).append(" directories, ")
            append(createdSymlinks.size).append(" drive links). ")
            append("Not yet a working Windows prefix: Wine must run 'wineboot' to create the registry and C: drive.")
        }
    }
}

/**
 * Creates the on-disk layout Wine expects for a prefix. It deliberately does
 * *not* fabricate registry hives: a bogus system.reg makes Wine fail in
 * confusing ways, so the runtime reports that `wineboot` is still required.
 */
class WinePrefixManager(
    private val prefixesRoot: File,
    private val log: PcRuntimeLog
) {

    fun isValidPrefixName(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > 64) {
            return false
        }
        if (trimmed == "." || trimmed == "..") {
            return false
        }
        return PREPARE_UNSAFE_CHARS.none { trimmed.contains(it) }
    }

    fun prefixDirectory(name: String): File = File(prefixesRoot, name.trim())

    fun exists(name: String): Boolean = File(prefixDirectory(name), "drive_c").isDirectory

    /**
     * Creates the directory skeleton and the DOS drive mapping that Wine reads
     * from `dosdevices/`. Safe to call repeatedly.
     */
    fun prepare(name: String): PcPrefixResult {
        if (!isValidPrefixName(name)) {
            val error = PcErrorCatalog.describe(
                PcErrorCode.PREFIX_PATH_INVALID,
                "invalid prefix name '$name'"
            )
            log.error(TAG, error.format())
            return PcPrefixResult(name, null, emptyList(), emptyList(), true, error)
        }

        val root = prefixDirectory(name)
        val createdDirectories = mutableListOf<String>()
        val createdSymlinks = mutableListOf<String>()

        return try {
            DIRECTORY_LAYOUT.forEach { relative ->
                val dir = File(root, relative)
                if (!dir.isDirectory) {
                    if (dir.mkdirs()) {
                        createdDirectories += relative
                    } else if (!dir.isDirectory) {
                        throw IOException("could not create directory $relative")
                    }
                }
            }

            val dosDevices = File(root, "dosdevices")
            createDriveLink(dosDevices, "c:", "../drive_c", createdSymlinks)

            log.info(
                TAG,
                "Prepared prefix '$name' at ${root.absolutePath} " +
                    "(${createdDirectories.size} new directories, ${createdSymlinks.size} new drive links)"
            )
            PcPrefixResult(name, root, createdDirectories, createdSymlinks, true, null)
        } catch (e: IOException) {
            val error = PcErrorCatalog.describe(PcErrorCode.PREFIX_IO_ERROR, e.message ?: "I/O failure")
            log.error(TAG, error.format())
            PcPrefixResult(name, root, createdDirectories, createdSymlinks, true, error)
        } catch (e: SecurityException) {
            val error = PcErrorCatalog.describe(PcErrorCode.PREFIX_IO_ERROR, e.message ?: "permission denied")
            log.error(TAG, error.format())
            PcPrefixResult(name, root, createdDirectories, createdSymlinks, true, error)
        }
    }

    private fun createDriveLink(
        dosDevices: File,
        linkName: String,
        target: String,
        created: MutableList<String>
    ) {
        val link = File(dosDevices, linkName)
        if (link.exists()) {
            return
        }
        link.delete()
        if (link.linkToDriveTarget(target)) {
            created += linkName
        }
        // A device link is a convenience, not a hard requirement: Wine creates
        // it itself during wineboot. Failing to create it is not an error.
    }

    private fun File.linkToDriveTarget(target: String): Boolean = try {
        java.nio.file.Files.createSymbolicLink(this.toPath(), File(target).toPath())
        true
    } catch (e: IOException) {
        log.warn(TAG, "Could not create drive link ${this.name}: ${e.message}")
        false
    } catch (e: UnsupportedOperationException) {
        log.warn(TAG, "Symbolic links unsupported on this filesystem for ${this.name}")
        false
    }

    companion object {
        private const val TAG = "PcPrefix"

        private val PREPARE_UNSAFE_CHARS = charArrayOf('/', '\\', '\u0000')

        /**
         * The subset of the Wine prefix layout that can be created before Wine
         * itself runs. Names follow the Wine/Windows conventions so that
         * `wineboot` adopts the existing directories instead of replacing them.
         */
        val DIRECTORY_LAYOUT = listOf(
            "drive_c",
            "drive_c/Program Files",
            "drive_c/Program Files (x86)",
            "drive_c/ProgramData",
            "drive_c/users",
            "drive_c/users/Public",
            "drive_c/windows",
            "drive_c/windows/system32",
            "drive_c/windows/syswow64",
            "dosdevices"
        )
    }
}
