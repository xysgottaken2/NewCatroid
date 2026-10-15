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
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.catrobat.catroid.content.actions

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import com.badlogic.gdx.scenes.scene2d.Action
import org.catrobat.catroid.content.Scope
import org.catrobat.catroid.content.localweb.LocalHttpServer
import org.catrobat.catroid.content.localweb.SafSelectionManager
import org.catrobat.catroid.formulaeditor.UserVariable
import org.catrobat.catroid.stage.StageActivity

/**
 * Combined "Select Folder, Save to Variable and Host" block.
 *
 * Opens the system directory picker (`ACTION_OPEN_DOCUMENT_TREE`), waits for
 * the user, and then - atomically from the user's perspective:
 *
 *  1. saves the selected SAF tree URI string to the block's user variable, and
 *  2. passes the *same* selected URI directly to [LocalHttpServer] as the HTTP
 *     document root, using "/" as the URL prefix.
 *
 * The two operations are independent and their errors are handled separately:
 * a failure of one does not undo the other, and both outcomes are reported
 * through [LocalHttpServer.lastError] (readable with the "local HTTP server
 * error" reporter) and a toast. A cancelled or invalid/inaccessible selection
 * never modifies the previous variable value or the server configuration.
 *
 * Unlike the separate "Select Directory" + "Host Directory" blocks, this block
 * does not depend on a formula field being wired to the variable: the URI
 * object returned by the picker is used directly, so no script-execution or
 * formula-evaluation step can drop it.
 */
class SelectFolderAndHostAction : Action() {
    var scope: Scope? = null
    var variable: UserVariable? = null

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
            Log.w(TAG, "No active stage activity; cannot show folder picker")
            LocalHttpServer.reportError("Cannot select a folder: no stage activity is running")
            finished = true
            return
        }

        activity.runOnUiThread {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }

            activity.queueIntent(object : StageActivity.IntentListener {
                override fun getTargetIntent(): Intent = intent

                override fun onIntentResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
                    try {
                        val uri = if (resultCode == Activity.RESULT_OK) data?.data else null
                        handleFolderSelection(uri)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error while handling folder selection", e)
                        LocalHttpServer.reportError("Error while handling folder selection: ${e.message}")
                    } finally {
                        finished = true
                    }
                    return true
                }
            })
        }
    }

    /**
     * Applies the picker result. Internal so the atomic logic is unit testable
     * without a running stage activity.
     *
     * @param uri the selected document tree URI, or null when the user cancelled.
     */
    internal fun handleFolderSelection(uri: Uri?) {
        if (uri == null) {
            // Cancelled: keep the previous valid selection and the server
            // configuration completely untouched.
            SafSelectionManager.onDirectoryCancelled()
            Log.i(TAG, "Folder selection cancelled; previous selection and hosting kept")
            return
        }

        if (!DocumentsContract.isTreeUri(uri)) {
            // Not a folder (the tree picker only returns tree URIs, but stay
            // defensive): report, modify nothing.
            SafSelectionManager.onDirectoryCancelled()
            val message = "Selected item is not a folder (expected a directory tree URI): $uri"
            LocalHttpServer.reportError(message)
            showToast(message)
            return
        }

        if (!SafSelectionManager.isAccessible(uri)) {
            // Invalid or inaccessible selection: report, modify nothing.
            SafSelectionManager.onDirectoryCancelled()
            val message = "Selected folder is not accessible (permission revoked or unsupported provider): $uri"
            LocalHttpServer.reportError(message)
            showToast(message)
            return
        }

        SafSelectionManager.onDirectorySelected(uri)

        // Operation 1: save the URI string to the user variable.
        val variableError = try {
            if (variable == null) {
                "No variable selected: the folder URI was not saved to a variable"
            } else {
                variable?.value = uri.toString()
                null
            }
        } catch (e: Exception) {
            "Unable to save the selected folder to the variable: ${e.message}"
        }

        // Operation 2: configure the hosting manager with the SAME selected URI.
        val hostError = try {
            LocalHttpServer.setDocumentRoot(uri.toString())
            LocalHttpServer.setUrlPrefix("/")
            if (LocalHttpServer.documentRoot != uri.toString()) {
                LocalHttpServer.lastError
                    ?: "The hosting manager did not accept the selected folder: $uri"
            } else {
                null
            }
        } catch (e: Exception) {
            "Unable to host the selected folder: ${e.message}"
        }

        val errors = listOfNotNull(variableError, hostError)
        if (errors.isEmpty()) {
            LocalHttpServer.clearError()
            Log.i(TAG, "Folder selected, stored in variable and hosted: $uri")
        } else {
            val message = errors.joinToString("; ")
            LocalHttpServer.reportError(message)
            showToast(message)
        }
    }

    private fun showToast(message: String) {
        try {
            StageActivity.activeStageActivity?.get()?.runOnUiThread {
                Toast.makeText(
                    StageActivity.activeStageActivity?.get(),
                    message,
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to show selection result toast", e)
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
    }

    companion object {
        private const val TAG = "SelectFolderAndHostAction"
    }
}
