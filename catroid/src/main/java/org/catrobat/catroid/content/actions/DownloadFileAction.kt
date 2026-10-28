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
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.catrobat.catroid.content.actions

import android.util.Log
import android.widget.Toast
import com.badlogic.gdx.scenes.scene2d.Action
import org.catrobat.catroid.common.NewCatroidHttpManager
import org.catrobat.catroid.common.NewCatroidHttpManager.DownloadState
import org.catrobat.catroid.common.NewCatroidHttpManager.DownloadStatus
import org.catrobat.catroid.content.Scope
import org.catrobat.catroid.formulaeditor.Formula
import org.catrobat.catroid.stage.StageActivity
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Base action for the URL-to-file blocks. It remains active until the streamed
 * transfer completes or fails, and reset/restart cancels only this action's
 * transfer state. Concrete actions choose a destination appropriate to their
 * storage contract.
 */
abstract class DownloadFileAction : Action() {
    var scope: Scope? = null
    var url: Formula? = null
    var name: Formula? = null

    private var started = false
    private var terminalReported = false
    private var lastProgressCheckpoint = -1
    private var downloadState: DownloadState? = null

    internal val currentDownloadState: DownloadState?
        get() = downloadState

    protected abstract fun resolveDestination(fileName: String): File

    protected open fun defaultFileName(): String = "download.bin"

    override fun act(delta: Float): Boolean {
        if (!started) {
            started = true
            startDownload()
        }

        val state = downloadState ?: return true
        when (state.status) {
            DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> {
                reportProgress(state)
                return false
            }
            DownloadStatus.COMPLETED -> {
                if (!terminalReported) {
                    terminalReported = true
                    val size = state.bytesDownloaded
                    Log.i(TAG, "Download complete: ${state.destinationFile.name} ($size bytes)")
                    showToast("Download complete: ${state.destinationFile.name}")
                }
                return true
            }
            DownloadStatus.FAILED -> {
                if (!terminalReported) {
                    terminalReported = true
                    val reason = state.error ?: "Unknown transfer error"
                    Log.e(TAG, "Download failed: $reason")
                    showToast("Download failed: $reason")
                }
                return true
            }
            DownloadStatus.CANCELLED -> return true
        }
    }

    private fun startDownload() {
        try {
            val requestUrl = url?.interpretString(scope)?.trim().orEmpty()
            if (requestUrl.isEmpty()) throw IllegalArgumentException("A download URL is required")
            val suppliedName = name?.interpretString(scope)?.trim().orEmpty()
            val fileName = suppliedName.ifBlank { fileNameFromUrl(requestUrl) ?: defaultFileName() }
            val destination = resolveDestination(fileName)
            downloadState = NewCatroidHttpManager.downloadToFile(requestUrl, destination)
            Log.i(TAG, "Queued download to ${destination.absolutePath}")
        } catch (exception: Exception) {
            val reason = exception.message?.takeIf { it.isNotBlank() } ?: exception.javaClass.simpleName
            Log.e(TAG, "Unable to start file download: $reason", exception)
            showToast("Download failed: $reason")
        }
    }

    private fun fileNameFromUrl(requestUrl: String): String? =
        requestUrl.toHttpUrlOrNull()?.pathSegments?.lastOrNull()?.takeIf { it.isNotBlank() }

    private fun reportProgress(state: DownloadState) {
        val total = state.totalBytes
        if (total > 0L) {
            val percent = state.progressPercent ?: return
            val checkpoint = percent / 5
            if (checkpoint > lastProgressCheckpoint && percent < 100) {
                lastProgressCheckpoint = checkpoint
                Log.i(
                    TAG,
                    "Download progress: ${state.destinationFile.name} $percent% " +
                        "(${state.bytesDownloaded}/$total bytes)"
                )
            }
        } else {
            val checkpoint = (state.bytesDownloaded / PROGRESS_LOG_INTERVAL_BYTES).toInt()
            if (checkpoint > lastProgressCheckpoint) {
                lastProgressCheckpoint = checkpoint
                Log.i(
                    TAG,
                    "Download progress: ${state.destinationFile.name} " +
                        "${state.bytesDownloaded} bytes (server length unknown)"
                )
            }
        }
    }

    private fun showToast(message: String) {
        val activity = StageActivity.activeStageActivity.get() ?: return
        activity.runOnUiThread {
            Toast.makeText(activity, message.take(MAX_TOAST_LENGTH), Toast.LENGTH_LONG).show()
        }
    }

    override fun restart() {
        downloadState?.cancel()
        downloadState = null
        started = false
        terminalReported = false
        lastProgressCheckpoint = -1
        super.restart()
    }

    override fun reset() {
        downloadState?.cancel()
        downloadState = null
        started = false
        terminalReported = false
        lastProgressCheckpoint = -1
        scope = null
        url = null
        name = null
        super.reset()
    }

    companion object {
        private const val TAG = "DownloadFileAction"
        private const val PROGRESS_LOG_INTERVAL_BYTES = 4L * 1024L * 1024L
        private const val MAX_TOAST_LENGTH = 300
    }
}
