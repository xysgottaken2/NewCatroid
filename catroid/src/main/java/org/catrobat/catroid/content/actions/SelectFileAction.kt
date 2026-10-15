/*
 * Catroid: An on-device visual programming system for Android devices
 * Copyright (C) 2010-2025 The Catrobat Team
 * (<http://developer.catrobat.org/credits>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * An additional term exception under section 7 of the GNU Affero
 * General Public License, version 3, is available at
 * http://www.gnu.org/licenses/.
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
import android.net.Uri
import android.util.Log
import com.badlogic.gdx.scenes.scene2d.Action
import org.catrobat.catroid.content.Scope
import org.catrobat.catroid.content.localweb.SafSelectionManager
import org.catrobat.catroid.formulaeditor.Formula
import org.catrobat.catroid.formulaeditor.UserVariable
import org.catrobat.catroid.stage.StageActivity

/**
 * Launches the system file picker (`ACTION_OPEN_DOCUMENT`) and stores the
 * selected file URI in a user variable.
 *
 * The stored value is a stable content URI (e.g.
 * `content://.../document/primary%3ADocuments%2Fnotes.txt`), never a plain
 * filesystem path - Storage Access Framework URIs do not map to normal file
 * paths. The file is not copied into application storage.
 */
class SelectFileAction : Action() {
    var scope: Scope? = null
    var variable: UserVariable? = null
    var mimeType: Formula? = null

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
        val activity = StageActivity.activeStageActivity?.get()
        if (activity == null) {
            Log.w(TAG, "No active stage activity; cannot show file picker")
            finished = true
            return
        }

        val type = try {
            mimeType?.interpretString(scope)?.trim()?.takeIf { it.isNotEmpty() } ?: "*/*"
        } catch (e: Exception) {
            "*/*"
        }

        activity.runOnUiThread {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                this.type = type
            }

            activity.queueIntent(object : StageActivity.IntentListener {
                override fun getTargetIntent(): Intent = intent

                override fun onIntentResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
                    try {
                        val uri = if (resultCode == Activity.RESULT_OK) data?.data else null
                        if (uri != null && SafSelectionManager.isAccessible(uri)) {
                            SafSelectionManager.onFileSelected(uri)
                            variable?.value = uri.toString()
                        } else {
                            // Cancelled, inaccessible or unsupported provider:
                            // keep the previous valid selection untouched.
                            SafSelectionManager.onFileCancelled()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error while handling file selection", e)
                        SafSelectionManager.onFileCancelled()
                    } finally {
                        finished = true
                    }
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
        variable = null
        mimeType = null
    }

    companion object {
        private const val TAG = "SelectFileAction"
    }
}
