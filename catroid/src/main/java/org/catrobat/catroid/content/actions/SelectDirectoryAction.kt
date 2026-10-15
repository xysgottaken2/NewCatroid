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
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import com.badlogic.gdx.scenes.scene2d.Action
import org.catrobat.catroid.content.Scope
import org.catrobat.catroid.content.localweb.SafSelectionManager
import org.catrobat.catroid.formulaeditor.Formula
import org.catrobat.catroid.formulaeditor.UserVariable
import org.catrobat.catroid.stage.StageActivity

/**
 * Launches the system directory picker (`ACTION_OPEN_DOCUMENT_TREE`) and
 * stores the selected directory URI in a user variable.
 *
 * The block waits until the picker returns: on success the URI string is
 * written to the variable and the persisted URI permission is taken; on
 * cancellation the variable keeps its previous value and the cancellation is
 * recorded in [SafSelectionManager].
 */
class SelectDirectoryAction : Action() {
    var scope: Scope? = null
    var variable: UserVariable? = null
    var initialDirectory: Formula? = null

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
            Log.w(TAG, "No active stage activity; cannot show directory picker")
            finished = true
            return
        }

        activity.runOnUiThread {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                // Optional: pre-select a directory when the block provides one.
                try {
                    val initial = initialDirectory?.interpretString(scope)?.trim().orEmpty()
                    if (initial.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(initial))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to apply EXTRA_INITIAL_URI", e)
                }
            }

            activity.queueIntent(object : StageActivity.IntentListener {
                override fun getTargetIntent(): Intent = intent

                override fun onIntentResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
                    try {
                        val uri = if (resultCode == Activity.RESULT_OK) data?.data else null
                        if (uri != null && SafSelectionManager.isAccessible(uri)) {
                            SafSelectionManager.onDirectorySelected(uri)
                            variable?.value = uri.toString()
                        } else {
                            // Cancelled, inaccessible or unsupported provider:
                            // keep the previous valid selection untouched.
                            SafSelectionManager.onDirectoryCancelled()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error while handling directory selection", e)
                        SafSelectionManager.onDirectoryCancelled()
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
        initialDirectory = null
    }

    companion object {
        private const val TAG = "SelectDirectoryAction"
    }
}
