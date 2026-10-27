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
 * http://developer.catrobat.org/licenses/additional_term
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

import android.app.Activity
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.badlogic.gdx.scenes.scene2d.Action
import org.catrobat.catroid.CatroidApplication
import org.catrobat.catroid.content.Scope
import org.catrobat.catroid.formulaeditor.Formula
import org.catrobat.catroid.formulaeditor.UserVariable
import org.catrobat.catroid.pcgame.PcGameManager
import org.catrobat.catroid.pcgame.PcPackManager
import org.catrobat.catroid.pcgame.PcPackType
import org.catrobat.catroid.stage.StageActivity

/**
 * Opens the system file picker, imports the selected archive (zip/tar/tzst) as a
 * PC pack and reports the result in the log and (optionally) in a user variable.
 *
 * The pack type can be left empty: it is then detected from the archive
 * content (`libvulkan*.so` -> driver, `d3d11.dll` -> D3D layer, `box64` ->
 * runtime, `bin/sh` -> rootfs).
 */
class PcImportPackAction : Action() {

    var scope: Scope? = null
    var packType: Formula? = null
    var packName: Formula? = null
    var resultVariable: UserVariable? = null

    private var started = false

    @Volatile
    private var finished = false

    override fun act(delta: Float): Boolean {
        if (!started) {
            started = true
            launchPicker()
        }
        return finished
    }

    private fun launchPicker() {
        val activity = StageActivity.activeStageActivity.get()
        if (activity == null) {
            Log.w(TAG, "No active stage activity, cannot show the pack picker")
            finished = true
            return
        }
        val typeName = try {
            packType?.interpretString(scope) ?: ""
        } catch (e: Exception) {
            ""
        }
        val preferredName = try {
            packName?.interpretString(scope) ?: ""
        } catch (e: Exception) {
            ""
        }

        activity.runOnUiThread {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                type = "*/*"
            }
            activity.queueIntent(object : StageActivity.IntentListener {
                override fun getTargetIntent(): Intent = intent

                override fun onIntentResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
                    val uri = if (resultCode == Activity.RESULT_OK) data?.data else null
                    if (uri == null) {
                        Log.i(TAG, "Pack import cancelled")
                        resultVariable?.value = "cancelled"
                        finished = true
                        return true
                    }
                    val context = CatroidApplication.getAppContext()
                    Thread {
                        try {
                            val type = if (typeName.isBlank()) {
                                null
                            } else {
                                PcPackType.fromName(typeName)
                            }
                            val result = PcPackManager.importFromUri(
                                context,
                                type,
                                uri,
                                preferredName.ifBlank { null }
                            )
                            PcGameManager.appendLog(
                                if (result.success) {
                                    "[newcatroid] ${result.message}"
                                } else {
                                    "[newcatroid] import failed: ${result.message}"
                                }
                            )
                            resultVariable?.value = result.message
                            val activityRef = StageActivity.activeStageActivity.get()
                            activityRef?.runOnUiThread {
                                Toast.makeText(activityRef, result.message, Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Import failed", e)
                            resultVariable?.value = "failed: ${e.message}"
                        } finally {
                            finished = true
                        }
                    }.start()
                    return true
                }
            })
        }
    }

    override fun restart() {
        super.restart()
        started = false
        finished = false
    }

    override fun reset() {
        super.reset()
        started = false
        finished = false
        scope = null
        packType = null
        packName = null
        resultVariable = null
    }

    companion object {
        private const val TAG = "PcImportPackAction"
    }
}
