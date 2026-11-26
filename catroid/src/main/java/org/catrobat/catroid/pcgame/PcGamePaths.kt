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
import org.catrobat.catroid.CatroidApplication
import org.catrobat.catroid.ProjectManager
import org.catrobat.catroid.content.Project
import java.io.File
import java.io.FileOutputStream

/**
 * Every directory the PC compatibility layer touches.
 *
 * All heavy components (rootfs, Wine/Box64 runtime, drivers, D3D layers and the
 * Wine prefix) live in the app private storage. Only the game folder may live
 * outside of it (usually `/sdcard/Games/...`), it is bind mounted read/write
 * into the container.
 */
object PcGamePaths {

    private const val TAG = "PcGamePaths"
    const val ROOT_NAME = "pcgame"
    const val CONTAINER_NAME = "container"
    const val PREFIX_NAME = "prefix"
    const val SCRIPT_NAME = "scripts"
    const val LOG_NAME = "logs"
    const val PROJECT_FOLDER = "pcgame"

    fun appContext(): Context = CatroidApplication.getAppContext()

    fun root(context: Context): File = ensure(File(context.filesDir, ROOT_NAME))

    fun packs(context: Context): File = ensure(File(root(context), "packs"))

    fun cache(context: Context): File = ensure(File(context.cacheDir, ROOT_NAME))

    fun containerRoot(context: Context): File = ensure(File(root(context), CONTAINER_NAME))

    fun prefixRoot(context: Context): File = ensure(File(root(context), PREFIX_NAME))

    fun scripts(context: Context): File = ensure(File(root(context), SCRIPT_NAME))

    fun logs(context: Context): File = ensure(File(root(context), LOG_NAME))

    fun tmp(context: Context): File = ensure(File(root(context), "tmp"))

    /** Project side folder: keeps the launcher settings and touch profiles. */
    fun projectFolder(project: Project?): File? {
        val current = project ?: return null
        val dir = File(current.filesDir, PROJECT_FOLDER)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun projectFolder(): File? = projectFolder(ProjectManager.getInstance().currentProject)

    fun prefixFor(projectName: String): File {
        val safeName = projectName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "project" }
        return ensure(File(prefixRoot(appContext()), safeName))
    }

    fun logFile(context: Context, name: String): File = File(logs(context), name)

    private fun ensure(dir: File): File {
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Resolves a user supplied path.
     *
     * Accepted forms:
     *  * absolute path (`/sdcard/Games/GTAV`, `/storage/emulated/0/...`)
     *  * `file://...`
     *  * `content://...` (the document is copied into the app cache)
     *  * relative path, resolved against the project files folder first and the
     *    app external files folder second
     */
    fun resolve(context: Context, path: String?): File? {
        val raw = path?.trim() ?: return null
        if (raw.isEmpty()) return null
        if (raw.startsWith("content://")) {
            return copyContentUriToCache(context, Uri.parse(raw))
        }
        val cleaned = when {
            raw.startsWith("file://") -> Uri.parse(raw).path ?: raw.removePrefix("file://")
            raw.startsWith("sdcard/") -> "/" + raw
            else -> raw
        }
        val direct = File(cleaned)
        if (direct.isAbsolute) return if (direct.exists()) direct else direct

        val projectDir = try {
            ProjectManager.getInstance().currentProject.filesDir
        } catch (e: Exception) {
            null
        }
        if (projectDir != null) {
            val inProject = File(projectDir, cleaned)
            if (inProject.exists()) return inProject
        }
        val externalDir = context.getExternalFilesDir(null)
        if (externalDir != null) {
            val external = File(externalDir, cleaned)
            if (external.exists()) return external
        }
        return direct
    }

    /**
     * Same as [resolve] but the result must exist, and directories that are not
     * readable produce `null` instead of a dangling [File].
     */
    fun resolveExisting(context: Context, path: String?): File? {
        val file = resolve(context, path) ?: return null
        return if (file.exists() && file.canRead()) file else null
    }

    fun resolveExternalFile(context: Context, path: String?): File? = resolveExisting(context, path)

    private fun copyContentUriToCache(context: Context, uri: Uri): File? {
        return try {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "imported_${System.currentTimeMillis()}"
            val target = File(cache(context), name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { source ->
                FileOutputStream(target).use { output -> source.copyTo(output, 1024 * 256) }
            }
            target
        } catch (e: Exception) {
            Log.e(TAG, "Cannot copy $uri into the cache", e)
            null
        }
    }

    /**
     * Best effort conversion of a Storage Access Framework reference into a real
     * filesystem path. Only the "primary" external volume can be mapped without
     * asking the user for the mounted volume list; everything else returns null
     * and the caller keeps the URI string as is.
     */
    fun safReferenceToPath(reference: String?): String? {
        val raw = reference?.trim() ?: return null
        if (raw.isEmpty()) return null
        if (raw.startsWith("/")) return raw

        if (raw.startsWith("content://")) {
            val decoded = try {
                Uri.decode(raw)
            } catch (e: Exception) {
                raw
            }
            val marker = "primary:"
            val index = decoded.indexOf(marker)
            if (index >= 0) {
                val tail = decoded.substring(index + marker.length)
                    .substringBefore("/document/")
                    .trimEnd('/')
                if (tail.isNotEmpty()) {
                    return "/storage/emulated/0/" + tail
                }
            }
            val homeMarker = "home:"
            val homeIndex = decoded.indexOf(homeMarker)
            if (homeIndex >= 0) {
                val tail = decoded.substring(homeIndex + homeMarker.length)
                    .substringBefore("/document/")
                    .trimEnd('/')
                if (tail.isNotEmpty()) {
                    return "/storage/emulated/0/Documents/" + tail
                }
            }
        }
        return null
    }
}
