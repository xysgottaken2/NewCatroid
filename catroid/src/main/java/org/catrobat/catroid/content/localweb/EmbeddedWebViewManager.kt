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

package org.catrobat.catroid.content.localweb

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.catrobat.catroid.ui.EmbeddedWebViewActivity

/**
 * Navigation policy and entry point for the in-app embedded WebView used by
 * the "Open Web Page" block.
 *
 * Security defaults:
 *  - Only loopback origins (`http://127.0.0.1`, `http://localhost`,
 *    `http://[::1]`, any port) are reachable by default - exactly the origins
 *    the embedded local HTTP server is bound to.
 *  - Navigation to any other origin is blocked and reported through
 *    [lastError]. External pages are never opened automatically.
 *  - The WebView exposes no JavaScript bridge to page content.
 */
object EmbeddedWebViewManager {

    private const val TAG = "EmbeddedWebViewManager"

    private val allowedHosts = setOf("127.0.0.1", "localhost", "::1", "[::1]", "0.0.0.0")

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    private var activeActivity: EmbeddedWebViewActivity? = null

    val isOpen: Boolean
        get() = activeActivity != null

    /** True when [url] points to an origin the embedded WebView may load. */
    fun isAllowedOrigin(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            val uri = Uri.parse(url.trim())
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return false
            val host = uri.host?.lowercase()?.trim() ?: return false
            host in allowedHosts
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Opens [url] in the embedded WebView. When a WebView is already open it
     * is navigated to the new URL instead of stacking another activity, so
     * repeated block execution cannot leak activities or WebViews.
     *
     * @return true when the URL was accepted (and loading started).
     */
    fun open(context: Context, url: String?): Boolean {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            lastError = "Empty URL"
            return false
        }
        if (!isAllowedOrigin(trimmed)) {
            lastError = "Navigation to external origin blocked: $trimmed (only local server URLs are allowed by default)"
            Log.w(TAG, lastError!!)
            return false
        }
        lastError = null

        val active = activeActivity
        if (active != null && !active.isFinishing && !active.isDestroyed) {
            active.navigateTo(trimmed)
            return true
        }

        val intent = Intent(context, EmbeddedWebViewActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EmbeddedWebViewActivity.EXTRA_URL, trimmed)
        }
        context.startActivity(intent)
        return true
    }

    /** Closes the embedded WebView when one is open. Safe to call repeatedly. */
    fun close() {
        activeActivity?.let { activity ->
            if (!activity.isFinishing && !activity.isDestroyed) {
                activity.close()
            }
        }
        activeActivity = null
    }

    internal fun register(activity: EmbeddedWebViewActivity) {
        activeActivity = activity
    }

    internal fun unregister(activity: EmbeddedWebViewActivity) {
        if (activeActivity === activity) {
            activeActivity = null
        }
    }
}
