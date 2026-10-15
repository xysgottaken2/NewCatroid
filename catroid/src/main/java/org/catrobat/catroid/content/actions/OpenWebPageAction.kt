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

import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import com.badlogic.gdx.scenes.scene2d.actions.TemporalAction
import org.catrobat.catroid.content.Scope
import org.catrobat.catroid.content.localweb.EmbeddedWebViewManager
import org.catrobat.catroid.content.localweb.LocalHttpServer
import org.catrobat.catroid.formulaeditor.Formula
import org.catrobat.catroid.stage.StageActivity

/**
 * Opens a URL in an in-app WebView.
 *
 * Without a target, the URL opens in the full-screen embedded WebView
 * ([EmbeddedWebViewManager] / `EmbeddedWebViewActivity`), which restricts
 * navigation to approved (loopback) origins. When a target name is given, the
 * URL is loaded into the named in-stage WebView created by the existing
 * "Create WebView" blocks (or a new full-size one is created for that name).
 */
class OpenWebPageAction : TemporalAction() {
    var scope: Scope? = null
    var url: Formula? = null
    var navTarget: Formula? = null

    override fun update(percent: Float) {
        val activity = StageActivity.activeStageActivity?.get()
        if (activity == null) {
            Log.w(TAG, "No active stage activity; cannot open web page")
            return
        }

        val evaluatedUrl = try {
            url?.interpretString(scope)?.trim().orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to interpret URL formula", e)
            return
        }
        // An empty URL opens the URL of the running local HTTP server, so the
        // page always follows the server that is actually running (e.g.
        // http://localhost:PORT/ for the localhost block) instead of a
        // hardcoded address.
        val urlValue = resolvePageUrl(evaluatedUrl)
        if (urlValue.isEmpty()) {
            val message = "No URL given and no local HTTP server is running (start the server first)"
            EmbeddedWebViewManager.reportError(message)
            activity.runOnUiThread {
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            }
            return
        }

        val targetValue = try {
            navTarget?.interpretString(scope)?.trim().orEmpty()
        } catch (e: Exception) {
            ""
        }

        if (targetValue.isNullOrEmpty() || targetValue.equals(DEFAULT_TARGET, ignoreCase = true)) {
            // Full-screen embedded WebView with origin restrictions.
            if (!EmbeddedWebViewManager.open(activity, urlValue)) {
                activity.runOnUiThread {
                    Toast.makeText(
                        activity,
                        EmbeddedWebViewManager.lastError ?: "Unable to open web page",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        } else {
            // Named in-stage WebView from the existing native view architecture.
            activity.runOnUiThread {
                val existing = activity.getViewFromStage(targetValue)
                if (existing is WebView) {
                    if (EmbeddedWebViewManager.isAllowedOrigin(urlValue)) {
                        existing.loadUrl(urlValue)
                    } else {
                        Toast.makeText(
                            activity,
                            "Navigation to external origin blocked: $urlValue",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    if (EmbeddedWebViewManager.isAllowedOrigin(urlValue)) {
                        activity.createWebViewWithUrl(
                            targetValue, urlValue, 0, 0,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    } else {
                        Toast.makeText(
                            activity,
                            "Navigation to external origin blocked: $urlValue",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    /**
     * Resolves the URL to open: an explicitly given URL is used unchanged; an
     * empty URL resolves to the base URL of the running local HTTP server
     * (empty when no server is running).
     */
    internal fun resolvePageUrl(evaluatedUrl: String): String {
        val trimmed = evaluatedUrl.trim()
        if (trimmed.isNotEmpty()) return trimmed
        return LocalHttpServer.baseUrl
    }

    companion object {
        private const val TAG = "OpenWebPageAction"
        const val DEFAULT_TARGET = "default"
    }
}
