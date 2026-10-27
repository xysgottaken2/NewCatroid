/*
 * Catroid: An on-device visual programming system for Android devices
 * Copyright (C) 2010-2026 The Catrobat Team
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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.catrobat.catroid.pcgame

import android.content.Context
import android.net.Uri
import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.ZipFile

/**
 * Kinds of user supplied packages the PC compatibility layer needs.
 *
 * Nothing in this package ships proprietary or GPL binaries. The user imports
 * the components as ordinary `.zip` files ("packs"), exactly like Winlator,
 * GameHub or Mobox expect them: a Vulkan driver (Turnip/AdrenoTools style),
 * a D3D translation layer (DXVK / VKD3D-Proton / D8VK), an aarch64 Wine +
 * Box64 runtime and a Linux rootfs.
 */
enum class PcPackType(val folderName: String, val label: String) {
    GRAPHICS_DRIVER("drivers", "graphics driver"),
    D3D_LAYER("d3d", "D3D layer"),
    RUNTIME("runtime", "Wine/Box64 runtime"),
    ROOTFS("rootfs", "container rootfs");

    companion object {
        /** Accepts the values users write into the "import pack" block. */
        fun fromName(value: String?): PcPackType {
            val normalized = value?.trim()?.lowercase() ?: ""
            return when {
                normalized == "driver" || normalized == "graphics" ||
                    normalized == "graphics_driver" || normalized == "vulkan" ||
                    normalized == "turnip" || normalized == "adrenotools" ||
                    normalized == "gpu" || normalized == "gpu_driver" -> GRAPHICS_DRIVER
                normalized == "d3d" || normalized == "dxvk" || normalized == "vkd3d" ||
                    normalized == "layer" || normalized == "d3d_layer" ||
                    normalized == "vkd3d-proton" || normalized == "d8vk" -> D3D_LAYER
                normalized == "runtime" || normalized == "wine" || normalized == "box64" ||
                    normalized == "winebox64" -> RUNTIME
                normalized == "rootfs" || normalized == "container" ||
                    normalized == "image" -> ROOTFS
                else -> GRAPHICS_DRIVER
            }
        }

        fun all(): List<PcPackType> = listOf(GRAPHICS_DRIVER, D3D_LAYER, RUNTIME, ROOTFS)
    }
}

/**
 * One imported package. [id] is the stable directory name used by the blocks,
 * [displayName] is what the user sees (taken from the pack manifest when the
 * pack has one, otherwise from the directory/zip name).
 */
data class PcPack(
    val id: String,
    val type: PcPackType,
    val directory: File,
    val displayName: String,
    val version: String = "",
    val vendor: String = "",
    val notes: String = ""
) {
    /** All `*icd*.json` files of a Vulkan driver pack (Android ICD metadata). */
    fun icdFiles(): List<File> {
        val result = ArrayList<File>()
        val stack = ArrayDeque<File>()
        stack.add(directory)
        var visited = 0
        while (stack.isNotEmpty() && visited < 20000) {
            val current = stack.removeLast()
            visited++
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    stack.add(child)
                } else if (child.name.endsWith(".json", ignoreCase = true)) {
                    val name = child.name.lowercase()
                    if (name.contains("icd") || name.contains("vk") || name.contains("vulkan")) {
                        result.add(child)
                    }
                }
            }
        }
        return result.sortedBy { it.absolutePath }
    }

    /** Directories that hold the `libvulkan_*.so` driver libraries. */
    fun libraryDirs(): List<File> {
        val result = LinkedHashSet<File>()
        val stack = ArrayDeque<File>()
        stack.add(directory)
        var visited = 0
        while (stack.isNotEmpty() && visited < 20000) {
            val current = stack.removeLast()
            visited++
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    stack.add(child)
                } else if (child.name.startsWith("libvulkan", ignoreCase = true) ||
                    child.name.startsWith("libGL", ignoreCase = true) ||
                    child.name.startsWith("libEGL", ignoreCase = true)
                ) {
                    result.add(current)
                }
            }
        }
        return result.toList()
    }

    fun summary(): String {
        val builder = StringBuilder(displayName)
        if (version.isNotEmpty()) builder.append(" (").append(version).append(")")
        if (vendor.isNotEmpty()) builder.append(" — ").append(vendor)
        return builder.toString()
    }
}

data class PcImportResult(
    val success: Boolean,
    val message: String,
    val pack: PcPack? = null
) {
    companion object {
        fun failure(message: String) = PcImportResult(false, message)
    }
}

/**
 * Imports, lists, resolves and removes packs.
 *
 * Packs live in the app private storage, in one folder per pack type, not in
 * the project, so the same driver/runtime can be reused by every launcher
 * project and is never part of a project export or of a built APK.
 */
object PcPackManager {

    private const val TAG = "PcPackManager"
    private const val MANIFEST_NAME = "pack.json"
    private const val MAX_ENTRIES = 400000L

    fun packsRoot(context: Context): File {
        val dir = File(PcGamePaths.root(context), "packs")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun packsDir(context: Context, type: PcPackType): File {
        val dir = File(packsRoot(context), type.folderName)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun packs(context: Context, type: PcPackType): List<PcPack> {
        val dir = packsDir(context, type)
        val children = dir.listFiles() ?: return emptyList()
        val result = ArrayList<PcPack>()
        for (child in children) {
            if (!child.isDirectory) continue
            result.add(readPack(child, type))
        }
        return result.sortedBy { it.displayName.lowercase() }
    }

    fun allPacks(context: Context): List<PcPack> {
        val result = ArrayList<PcPack>()
        for (type in PcPackType.all()) {
            result.addAll(packs(context, type))
        }
        return result
    }

    /**
     * Resolves a pack the user referenced from a block.
     * [reference] may be an id, a display name, a directory name, "auto"/"first"
     * (oldest pack of that type) or "none"/"system"/"" (returns null).
     */
    fun resolve(context: Context, type: PcPackType, reference: String?): PcPack? {
        val requested = reference?.trim() ?: ""
        val lowered = requested.lowercase()
        if (lowered == "none" || lowered == "system" || lowered == "off" || lowered == "default") {
            return null
        }
        val available = packs(context, type)
        if (available.isEmpty()) return null
        if (lowered.isEmpty() || lowered == "auto" || lowered == "first") return available.first()

        for (pack in available) {
            if (pack.id.equals(requested, ignoreCase = true)) return pack
        }
        for (pack in available) {
            if (pack.displayName.equals(requested, ignoreCase = true)) return pack
        }
        for (pack in available) {
            if (pack.displayName.lowercase().contains(lowered)) return pack
        }
        return null
    }

    private fun readPack(directory: File, type: PcPackType): PcPack {
        var displayName = directory.name
        var version = ""
        var vendor = ""
        var notes = ""

        val manifest = File(directory, MANIFEST_NAME)
        if (manifest.isFile) {
            try {
                val json = JSONObject(manifest.readText())
                displayName = json.optString("name", displayName)
                version = json.optString("version", "")
                vendor = json.optString("vendor", "")
                notes = json.optString("notes", "")
            } catch (e: Exception) {
                Log.w(TAG, "Cannot read manifest of $directory: ${e.message}")
            }
        }

        if (version.isEmpty() || vendor.isEmpty() || displayName == directory.name) {
            val meta = File(directory, "meta.json")
            if (meta.isFile) {
                try {
                    val json = JSONObject(meta.readText())
                    val metaName = json.optString("name", json.optString("driverName", ""))
                    if (metaName.isNotEmpty() && displayName == directory.name) displayName = metaName
                    if (version.isEmpty()) {
                        version = json.optString("driverVersion", json.optString("version", ""))
                    }
                    if (vendor.isEmpty()) {
                        vendor = json.optString("vendor", json.optString("driverVendor", ""))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot read meta.json of $directory: ${e.message}")
                }
            }
        }

        if (version.isEmpty()) {
            val driverJson = File(directory, "driver.json")
            if (driverJson.isFile) {
                try {
                    val json = JSONObject(driverJson.readText())
                    version = json.optString("version", "")
                    if (vendor.isEmpty()) vendor = json.optString("vendor", "")
                    if (notes.isEmpty()) notes = json.optString("notes", "")
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot read driver.json of $directory: ${e.message}")
                }
            }
        }

        return PcPack(
            id = directory.name,
            type = type,
            directory = directory,
            displayName = displayName,
            version = version,
            vendor = vendor,
            notes = notes
        )
    }

    /**
     * Guesses the pack type from the zip content so "import pack" can be used
     * without the user knowing the internal categories.
     */
    fun detectType(archive: File): PcPackType {
        var hasVulkan = false
        var hasD3d = false
        var hasRuntime = false
        var hasRootfs = false
        try {
            forEachEntryName(archive) { rawName ->
                val name = rawName.replace('\\', '/').lowercase()
                val base = name.substringAfterLast('/')
                    if (base.startsWith("libvulkan") || (base.endsWith(".json") && base.contains("icd"))) {
                        hasVulkan = true
                    }
                    if (base == "d3d11.dll" || base == "d3d9.dll" || base == "dxgi.dll" ||
                        base == "d3d12.dll" || base == "d3d10core.dll" || base == "d3d12core.dll"
                    ) {
                        hasD3d = true
                    }
                    if (base == "box64" || base == "box86" || base == "wine64" ||
                        base == "wine" || base == "proot"
                    ) {
                        hasRuntime = true
                    }
                    if (name == "bin/sh" || name == "usr/bin/wine" || name == "etc/os-release" ||
                        name.endsWith("/bin/sh") || name.endsWith("/etc/os-release")
                    ) {
                        hasRootfs = true
                    }
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot inspect ${archive.name}: ${e.message}")
        }
        return when {
            hasD3d -> PcPackType.D3D_LAYER
            hasVulkan -> PcPackType.GRAPHICS_DRIVER
            hasRuntime -> PcPackType.RUNTIME
            hasRootfs -> PcPackType.ROOTFS
            else -> PcPackType.GRAPHICS_DRIVER
        }
    }

    /**
     * Visits the entry names of any supported archive. Returning `false` from
     * [visitor] stops the scan early, which keeps the type detection cheap for
     * multi-gigabyte rootfs archives.
     */
    private fun forEachEntryName(archive: File, visitLimit: Int = 6000, visitor: (String) -> Boolean) {
        var count = 0
        when (detectArchiveKind(archive)) {
            PcArchiveKind.ZIP -> ZipFile(archive).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements() && count < visitLimit) {
                    val entry = entries.nextElement()
                    count++
                    if (!visitor(entry.name)) return
                }
            }
            else -> openTarStream(archive).use { stream ->
                while (count < visitLimit) {
                    val entry = stream.nextEntry ?: break
                    count++
                    if (!visitor(entry.name)) return
                }
            }
        }
    }

    fun importFromUri(
        context: Context,
        type: PcPackType?,
        uri: Uri,
        preferredId: String? = null
    ): PcImportResult {
        return try {
            val temp = File(PcGamePaths.cache(context), "import_" + System.currentTimeMillis() + ".zip")
            val input: InputStream? = context.contentResolver.openInputStream(uri)
            if (input == null) {
                return PcImportResult.failure("Cannot open the selected file")
            }
            input.use { source ->
                FileOutputStream(temp).use { target -> source.copyTo(target, 1024 * 256) }
            }
            val result = importZip(context, type, temp, preferredId)
            temp.delete()
            result
        } catch (e: Exception) {
            Log.e(TAG, "Import from uri failed", e)
            PcImportResult.failure("Import failed: ${e.message}")
        }
    }

    fun importFromPath(
        context: Context,
        type: PcPackType?,
        path: String,
        preferredId: String? = null
    ): PcImportResult {
        val source = PcGamePaths.resolveExternalFile(context, path)
            ?: return PcImportResult.failure("File not found: $path")
        return importZip(context, type, source, preferredId)
    }

    /**
     * Extracts [zipFile] into `filesDir/pcgame/packs/<type>/<id>`.
     * The archive may contain a single top level directory, in which case the
     * content is flattened so every pack has the same layout.
     */
    fun importZip(
        context: Context,
        type: PcPackType?,
        zipFile: File,
        preferredId: String? = null
    ): PcImportResult {
        if (!zipFile.isFile) return PcImportResult.failure("File not found: ${zipFile.absolutePath}")
        if (zipFile.length() < 64) return PcImportResult.failure("The archive is empty")

        val resolvedType = type ?: detectType(zipFile)
        val id = makePackId(context, preferredId, zipFile.nameWithoutExtension, resolvedType)
        val target = File(packsDir(context, resolvedType), id)

        if (target.exists()) return PcImportResult.failure("A pack named '$id' already exists")

        val staging = File(PcGamePaths.cache(context), "unpack_" + System.currentTimeMillis())
        try {
            if (!staging.mkdirs()) return PcImportResult.failure("Cannot create a temporary directory")
            val extracted = extractArchive(zipFile, staging)
            if (extracted <= 0) return PcImportResult.failure("The archive has no files")

            val contentRoot = singleTopLevelDirectory(staging) ?: staging
            val parent = target.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            if (!contentRoot.renameTo(target)) {
                if (!copyRecursively(contentRoot, target)) {
                    target.deleteRecursively()
                    return PcImportResult.failure("Cannot move the extracted files into place")
                }
            }

            PcPermissions.markExecutables(target, resolvedType)
            writeImportManifest(target, zipFile.name)

            val pack = readPack(target, resolvedType)
            Log.i(TAG, "Imported ${resolvedType.label} '${pack.displayName}' from ${zipFile.name}")
            return PcImportResult(
                true,
                "Imported ${resolvedType.label}: ${pack.displayName} (id: ${pack.id})",
                pack
            )
        } catch (e: Exception) {
            Log.e(TAG, "Import failed for ${zipFile.name}", e)
            target.deleteRecursively()
            return PcImportResult.failure("Import failed: ${e.message}")
        } finally {
            staging.deleteRecursively()
        }
    }

    fun delete(context: Context, pack: PcPack): Boolean {
        return try {
            pack.directory.deleteRecursively()
        } catch (e: Exception) {
            Log.e(TAG, "Cannot delete ${pack.directory}", e)
            false
        }
    }

    /**
     * Copies the DLLs of a D3D layer pack into a Wine prefix
     * (`drive_c/windows/system32` for 64 bit, `syswow64` for 32 bit).
     *
     * Returns the number of copied files. A marker file remembers the installed
     * pack so switching layers does not leave a mix of two DXVK versions.
     */
    fun installD3dLayer(pack: PcPack, prefixDir: File): Int {
        val marker = File(prefixDir, ".newcatroid_d3d_layer")
        val system32 = File(prefixDir, "drive_c/windows/system32")
        val syswow64 = File(prefixDir, "drive_c/windows/syswow64")
        system32.mkdirs()
        syswow64.mkdirs()

        val previous = if (marker.isFile) marker.readText().trim() else ""
        if (previous == pack.id) return 0

        var copied = 0
        val stack = ArrayDeque<File>()
        stack.add(pack.directory)
        var visited = 0
        while (stack.isNotEmpty() && visited < 20000) {
            val current = stack.removeLast()
            visited++
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    stack.add(child)
                    continue
                }
                if (!child.name.endsWith(".dll", ignoreCase = true)) continue
                val lowerPath = child.absolutePath.lowercase()
                val is32Bit = lowerPath.contains("/x86/") || lowerPath.contains("\\x86\\") ||
                    lowerPath.contains("/i386/") || lowerPath.contains("/32/")
                val destinationDir = if (is32Bit) syswow64 else system32
                val destination = File(destinationDir, child.name)
                try {
                    child.copyTo(destination, overwrite = true)
                    PcPermissions.markExecutable(destination)
                    copied++
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot copy ${child.name} into the prefix: ${e.message}")
                }
            }
        }
        try {
            marker.writeText(pack.id)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot write the D3D marker file: ${e.message}")
        }
        Log.i(TAG, "Installed D3D layer '${pack.displayName}' ($copied DLLs) into ${prefixDir.absolutePath}")
        return copied
    }

    /** Archive containers that occur in the wild for the PC layer packs. */
    enum class PcArchiveKind { ZIP, TAR, TAR_GZ, TAR_XZ, TAR_ZST }

    /**
     * Sniffs the container by its magic bytes, so a user can import a `.zip`, a
     * plain `.tar`, a `.tar.gz`/`.tgz`, a `.tar.xz`/`.txz` or a Winlator style
     * `.tzst` rootfs without renaming anything.
     */
    fun detectArchiveKind(file: File): PcArchiveKind {
        val head = ByteArray(6)
        val read = try {
            FileInputStream(file).use { stream -> stream.read(head) }
        } catch (e: Exception) {
            0
        }
        val name = file.name.lowercase()
        if (read >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()) return PcArchiveKind.ZIP
        if (read >= 2 && head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte()) return PcArchiveKind.TAR_GZ
        if (read >= 6 && head[0] == 0xFD.toByte() && head[1] == 0x37.toByte() &&
            head[2] == 0x7A.toByte() && head[3] == 0x58.toByte() && head[4] == 0x5A.toByte()
        ) {
            return PcArchiveKind.TAR_XZ
        }
        if (read >= 4 && head[0] == 0x28.toByte() && head[1] == 0xB5.toByte() &&
            head[2] == 0x2F.toByte() && head[3] == 0xFD.toByte()
        ) {
            return PcArchiveKind.TAR_ZST
        }
        if (name.endsWith(".zip") || name.endsWith(".apks")) return PcArchiveKind.ZIP
        if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) return PcArchiveKind.TAR_GZ
        if (name.endsWith(".tar.xz") || name.endsWith(".txz")) return PcArchiveKind.TAR_XZ
        if (name.endsWith(".tzst") || name.endsWith(".tar.zst") || name.endsWith(".zst")) {
            return PcArchiveKind.TAR_ZST
        }
        return if (name.endsWith(".tar")) PcArchiveKind.TAR else PcArchiveKind.ZIP
    }

    fun extractArchive(archive: File, targetDir: File): Long {
        return when (detectArchiveKind(archive)) {
            PcArchiveKind.ZIP -> extractZip(archive, targetDir)
            else -> openTarStream(archive).use { stream -> extractTar(stream, targetDir) }
        }
    }

    private fun openTarStream(archive: File): TarArchiveInputStream {
        val raw: InputStream = BufferedInputStream(FileInputStream(archive), 1024 * 256)
        val decompressed: InputStream = when (detectArchiveKind(archive)) {
            PcArchiveKind.TAR_GZ -> GzipCompressorInputStream(raw)
            PcArchiveKind.TAR_XZ -> XZCompressorInputStream(raw)
            PcArchiveKind.TAR_ZST -> ZstdCompressorInputStream(raw)
            else -> raw
        }
        return TarArchiveInputStream(decompressed)
    }

    /**
     * Extracts a tar (optionally compressed) archive. Symlinks and hard links of
     * a Linux rootfs are recreated as real links, otherwise `bin/sh` and the
     * dynamic linker of an imported rootfs would be broken. The exec bit of the
     * original mode is restored as well.
     */
    private fun extractTar(stream: TarArchiveInputStream, targetDir: File): Long {
        val canonicalRoot = targetDir.canonicalPath
        val canonicalPrefix = canonicalRoot + File.separator
        var extracted = 0L
        var count = 0L
        while (true) {
            val entry: TarArchiveEntry = stream.nextEntry ?: break
            count++
            if (count > MAX_ENTRIES) throw IllegalStateException("The archive has too many entries")
            val name = entry.name.replace('\\', '/').removePrefix("./").trimEnd('/')
            if (name.isEmpty() || name.contains("..")) continue
            val outFile = File(targetDir, name)
            val canonical = try {
                outFile.canonicalPath
            } catch (e: Exception) {
                continue
            }
            if (canonical != canonicalRoot && !canonical.startsWith(canonicalPrefix)) continue

            when {
                entry.isDirectory -> outFile.mkdirs()
                entry.isSymbolicLink -> createSymbolicLink(outFile, entry.linkName)
                entry.isLink -> {
                    val source = File(targetDir, entry.linkName.removePrefix("./"))
                    if (source.isFile) {
                        try {
                            source.copyTo(outFile, overwrite = true)
                            extracted++
                        } catch (e: Exception) {
                            Log.w(TAG, "Cannot link ${outFile.name}: ${e.message}")
                        }
                    }
                }
                else -> {
                    val parent = outFile.parentFile
                    if (parent != null && !parent.exists()) parent.mkdirs()
                    try {
                        FileOutputStream(outFile).use { output ->
                            stream.copyTo(output, 1024 * 128)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Cannot extract ${outFile.absolutePath}: ${e.message}")
                        continue
                    }
                    // 0x49 == any of the three execute bits of a tar mode
                    if (entry.mode and 0x49 != 0) {
                        outFile.setExecutable(true, false)
                    }
                    extracted++
                }
            }
        }
        return extracted
    }

    private fun createSymbolicLink(link: File, target: String) {
        if (target.isEmpty()) return
        try {
            val parent = link.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            if (link.exists()) link.delete()
            Files.createSymbolicLink(Paths.get(link.absolutePath), Paths.get(target))
        } catch (e: Exception) {
            // Some file systems (and older kernels) refuse links: fall back to a
            // copy so the pack is still usable.
            Log.w(TAG, "Cannot create the link ${link.name} -> $target: ${e.message}")
            try {
                val source = if (target.startsWith("/")) null else File(link.parentFile, target)
                if (source != null && source.isFile) {
                    source.copyTo(link, overwrite = true)
                }
            } catch (copyError: Exception) {
                Log.w(TAG, "Cannot copy ${link.name}: ${copyError.message}")
            }
        }
    }

    private fun extractZip(zipFile: File, targetDir: File): Long {
        val canonicalTarget = targetDir.canonicalPath + File.separator
        var extracted = 0L
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries()
            var count = 0L
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                count++
                if (count > MAX_ENTRIES) throw IllegalStateException("The archive has too many entries")
                val name = entry.name.replace('\\', '/')
                if (name.isEmpty() || name.contains("..")) continue
                val outFile = File(targetDir, name)
                if (!outFile.canonicalPath.startsWith(canonicalTarget)) continue
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    val parent = outFile.parentFile
                    if (parent != null && !parent.exists()) parent.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(outFile).use { output -> input.copyTo(output, 1024 * 128) }
                    }
                    extracted++
                }
            }
        }
        return extracted
    }

    private fun singleTopLevelDirectory(root: File): File? {
        val children = root.listFiles() ?: return null
        if (children.size != 1) return null
        val only = children[0]
        return if (only.isDirectory) only else null
    }

    private fun copyRecursively(source: File, target: File): Boolean {
        if (source.isDirectory) {
            if (!target.exists() && !target.mkdirs()) return false
            val children = source.listFiles() ?: return true
            for (child in children) {
                if (!copyRecursively(child, File(target, child.name))) return false
            }
            source.delete()
            return true
        }
        return try {
            source.copyTo(target, overwrite = true)
            source.delete()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Cannot copy ${source.absolutePath}: ${e.message}")
            false
        }
    }

    private fun writeImportManifest(target: File, originalFileName: String) {
        val manifest = File(target, MANIFEST_NAME)
        if (manifest.exists()) return
        try {
            val json = JSONObject()
            json.put("name", originalFileName)
            json.put("importedAt", System.currentTimeMillis())
            json.put("importedBy", "NewCatroid PC compatibility layer")
            manifest.writeText(json.toString(2))
        } catch (e: Exception) {
            Log.w(TAG, "Cannot write import manifest: ${e.message}")
        }
    }

    private fun makePackId(
        context: Context,
        preferredId: String?,
        fallback: String,
        type: PcPackType
    ): String {
        val base = (preferredId?.trim()?.takeIf { it.isNotEmpty() } ?: fallback)
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(48)
            .ifEmpty { type.folderName + "_" + System.currentTimeMillis() }
        var candidate = base
        var counter = 2
        val dir = packsDir(context, type)
        while (File(dir, candidate).exists()) {
            candidate = "${base}_$counter"
            counter++
            if (counter > 999) break
        }
        return candidate
    }
}
