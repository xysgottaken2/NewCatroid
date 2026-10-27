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
 * http://www.catrobat.org/license_additional_term
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.catrobat.catroid.content.actions

import android.util.Log
import android.widget.Toast
import com.badlogic.gdx.scenes.scene2d.actions.TemporalAction
import org.catrobat.catroid.pcgame.PcEnvEditorDialog
import org.catrobat.catroid.pcgame.PcFileBrowser
import org.catrobat.catroid.pcgame.PcGameManager
import org.catrobat.catroid.pcgame.PcPackManager
import org.catrobat.catroid.pcgame.PcPackType
import org.catrobat.catroid.pcgame.PcPermissions
import org.catrobat.catroid.stage.StageActivity
import java.io.File

/**
 * Interactive blocks of the PC layer: they open a picker or an editor on the
 * stage, and every value the user chooses is written straight into the project
 * configuration (`<project>/files/pcgame/config.json`), so a launcher project
 * remembers the folders, packs and variables afterwards.
 */

/** "Choose the game folder" - opens the built in folder browser. */
class PcChooseGameFolderAction : TemporalAction() {
    override fun update(percent: Float) {
        val activity = StageActivity.activeStageActivity.get() ?: return
        activity.runOnUiThread {
            if (!PcPermissions.hasFileAccess(activity)) {
                PcPermissions.requestFileAccess(activity)
                Toast.makeText(
                    activity,
                    "Allow \"All files access\" for NewCatroid, then choose the folder again",
                    Toast.LENGTH_LONG
                ).show()
                return@runOnUiThread
            }
            val start = PcGameManager.config.gameDir
            PcFileBrowser.show(activity, PcFileBrowser.Mode.FOLDER, start) { folder ->
                chooseFolder(folder)
            }
        }
    }

    private fun chooseFolder(folder: File) {
        PcGameManager.setGameDir(folder.absolutePath)
        PcGameManager.saveConfig()
        val executables = PcGameManager.detectExecutables(folder)
        val activity = StageActivity.activeStageActivity.get()
        val message = when {
            executables.isEmpty() ->
                "Folder set: ${folder.absolutePath} (no .exe found inside)"
            else -> {
                if (PcGameManager.config.executable.isBlank()) {
                    PcGameManager.config.executable = executables.first()
                    PcGameManager.saveConfig()
                }
                "Folder set: ${folder.absolutePath} (${executables.size} exe found, " +
                    "first: ${executables.first()})"
            }
        }
        PcGameManager.appendLog("[newcatroid] $message")
        if (activity != null) {
            activity.runOnUiThread { Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
        }
    }
}

/**
 * "Choose a pack archive" - opens the archive browser, imports the selected
 * file as a pack and selects it for the next launch.
 *
 * [packType] is set by the brick: `driver`, `d3d`, `runtime` or `rootfs`.
 */
class PcChoosePackAction : TemporalAction() {
    var packType: String = "driver"

    override fun update(percent: Float) {
        val activity = StageActivity.activeStageActivity.get() ?: return
        activity.runOnUiThread {
            val start = defaultStartPath()
            PcFileBrowser.show(activity, PcFileBrowser.Mode.ARCHIVE, start) { archive ->
                importArchive(archive)
            }
        }
    }

    private fun defaultStartPath(): String {
        val config = PcGameManager.config
        if (config.gameDir.isNotBlank()) return config.gameDir
        return File("/sdcard/Download").absolutePath
    }

    private fun importArchive(archive: File) {
        val activity = StageActivity.activeStageActivity.get() ?: return
        val type = PcPackType.fromName(packType)
        Thread {
            val result = try {
                PcPackManager.importFromPath(activity, type, archive.absolutePath)
            } catch (e: Exception) {
                Log.e(TAG, "Pack import failed", e)
                null
            }
            activity.runOnUiThread {
                if (result == null || !result.success) {
                    val message = result?.message ?: "Import failed"
                    PcGameManager.appendLog("[newcatroid] import failed: $message")
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val pack = result.pack
                val selected = when (type) {
                    PcPackType.GRAPHICS_DRIVER -> {
                        PcGameManager.setGraphicsDriver(pack?.id ?: "system")
                        "graphics driver"
                    }
                    PcPackType.D3D_LAYER -> {
                        PcGameManager.setD3dLayer(pack?.id ?: "")
                        "D3D layer"
                    }
                    PcPackType.RUNTIME -> {
                        PcGameManager.setRuntime(pack?.id ?: "auto")
                        "runtime"
                    }
                    PcPackType.ROOTFS -> {
                        PcGameManager.setContainer(pack?.directory?.absolutePath ?: "")
                        "container"
                    }
                }
                PcGameManager.saveConfig()
                PcGameManager.appendLog("[newcatroid] ${result.message} -> selected as $selected")
                Toast.makeText(
                    activity,
                    "${result.message}\nSelected as $selected",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    companion object {
        private const val TAG = "PcChoosePackAction"
    }
}

/** "Grant file access" - opens the "All files access" system screen. */
class PcRequestFileAccessAction : TemporalAction() {
    override fun update(percent: Float) {
        val activity = StageActivity.activeStageActivity.get() ?: return
        activity.runOnUiThread {
            if (PcPermissions.hasFileAccess(activity)) {
                Toast.makeText(activity, "File access is already granted", Toast.LENGTH_SHORT).show()
                return@runOnUiThread
            }
            Toast.makeText(activity, PcPermissions.fileAccessHint(activity), Toast.LENGTH_LONG).show()
            PcPermissions.requestFileAccess(activity)
        }
    }
}

/** "Open the PC environment editor" - add, edit and remove variables. */
class PcEditEnvAction : TemporalAction() {
    override fun update(percent: Float) {
        val activity = StageActivity.activeStageActivity.get() ?: return
        activity.runOnUiThread { PcEnvEditorDialog.show(activity) }
    }
}
