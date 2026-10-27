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

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.RandomAccessFile

/**
 * Zip archives do not carry the unix executable bit into `java.util.zip`, so
 * imported packs need their binaries marked again. Only small binaries are
 * inspected (ELF magic) to keep the import fast even for multi gigabyte
 * rootfs archives.
 */
object PcPermissions {

    private const val TAG = "PcPermissions"

    private val KNOWN_EXECUTABLES = setOf(
        "box64", "box86", "box64-0.2.4", "wine", "wine64", "wineserver", "wineboot",
        "winecfg", "wine-preloader", "proot", "proot-distro", "sh", "bash", "dash",
        "busybox", "xvfb", "Xvfb", "x11vnc", "startup", "loader", "ld-linux-x86-64.so.2"
    )

    private const val MAX_INSPECT_SIZE = 64L * 1024L * 1024L

    const val FILE_ACCESS_REQUEST_CODE = 4211

    /**
     * Whether the app may read arbitrary folders on the shared storage.
     *
     * The game folder (`/sdcard/Games/...`) is outside the app sandbox, so
     * without "All files access" (Android 11+) every `File.listFiles()` on it
     * returns null and the PC layer reports "game folder not found". This is the
     * check the launcher, the folder browser and the launch path all use.
     */
    fun hasFileAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Asks for the "All files access" special permission. There is no runtime
     * dialog for it on Android 11+, so the system settings screen is opened
     * directly for this app (the user only has to flip one switch).
     */
    fun requestFileAccess(activity: Activity) {
        if (hasFileAccess(activity)) {
            Log.i(TAG, "File access already granted")
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val appIntent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + activity.packageName)
                )
                try {
                    activity.startActivity(appIntent)
                } catch (e: Exception) {
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            } else {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    FILE_ACCESS_REQUEST_CODE
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open the file access settings: ${e.message}")
        }
    }

    /** Human readable hint shown when the game folder cannot be read. */
    fun fileAccessHint(context: Context): String {
        return if (hasFileAccess(context)) {
            "File access is granted."
        } else {
            "NewCatroid needs \"All files access\" to read a game folder outside its sandbox. " +
                "Open the settings and enable it for NewCatroid, then try again."
        }
    }

    fun markExecutable(file: File) {
        try {
            file.setExecutable(true, false)
            file.setReadable(true, false)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot mark ${file.absolutePath} executable: ${e.message}")
        }
    }

    fun isElf(file: File): Boolean {
        if (!file.isFile) return false
        if (file.length() < 4 || file.length() > MAX_INSPECT_SIZE) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val magic = ByteArray(4)
                raf.readFully(magic)
                magic[0] == 0x7F.toByte() && magic[1] == 'E'.code.toByte() &&
                    magic[2] == 'L'.code.toByte() && magic[3] == 'F'.code.toByte()
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Marks the binaries of an imported pack executable.
     * For a runtime pack every ELF file is a candidate, for a rootfs only the
     * usual binary folders are walked so importing a full rootfs stays fast.
     */
    fun markExecutables(root: File, type: PcPackType) {
        when (type) {
            PcPackType.RUNTIME -> walk(root, limit = 4000) { file -> markIfBinary(file) }
            PcPackType.ROOTFS -> {
                val binaryDirs = listOf(
                    "bin", "sbin", "usr/bin", "usr/sbin", "usr/local/bin", "usr/libexec",
                    "usr/lib/wine", "usr/lib/wine/x86_64-unix", "usr/lib/x86_64-linux-gnu/wine"
                )
                for (relative in binaryDirs) {
                    val dir = File(root, relative)
                    if (dir.isDirectory) {
                        walk(dir, limit = 6000) { file -> markIfBinary(file) }
                    }
                }
                markIfBinary(File(root, "lib/ld-linux-x86-64.so.2"))
                markIfBinary(File(root, "lib64/ld-linux-x86-64.so.2"))
            }
            PcPackType.GRAPHICS_DRIVER -> {
                // Driver .so files are loaded with dlopen(), no exec bit needed.
                Log.d(TAG, "Driver pack imported, ${root.listFiles()?.size ?: 0} top level entries")
            }
            PcPackType.D3D_LAYER -> Log.d(TAG, "D3D layer imported")
        }
    }

    private fun markIfBinary(file: File) {
        if (!file.isFile) return
        if (KNOWN_EXECUTABLES.contains(file.name)) {
            markExecutable(file)
            return
        }
        val name = file.name
        if (name.endsWith(".so") || name.endsWith(".so.6") || name.endsWith(".dll")) return
        if (isElf(file)) markExecutable(file)
    }

    private inline fun walk(root: File, limit: Int, action: (File) -> Unit) {
        val stack = ArrayDeque<File>()
        stack.add(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < limit) {
            val current = stack.removeLast()
            visited++
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    stack.add(child)
                } else {
                    action(child)
                }
            }
        }
    }
}
