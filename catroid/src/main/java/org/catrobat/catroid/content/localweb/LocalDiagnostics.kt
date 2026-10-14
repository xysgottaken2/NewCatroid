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

package org.catrobat.catroid.content.localweb

import android.content.Context
import android.content.Intent
import org.catrobat.catroid.ui.DiagnosticsActivity

/**
 * Shared, in-app diagnostics for the local web hosting feature.
 *
 * Holds the bounded ring buffers that are fed by the actual running
 * components - [requestLog] by the HTTP engine (one entry per request:
 * method, path, status, MIME type, bytes sent, duration, failure/redirect
 * detail) and [webViewLog] by the embedded WebView (console messages,
 * resource load failures, HTTP error responses, navigation errors) - and
 * opens the [DiagnosticsActivity] where they can be viewed and copied.
 *
 * Nothing here ever contains file contents, only paths, statuses, sizes and
 * failure reasons.
 */
object LocalDiagnostics {

    /** Recent HTTP requests handled by the local server (bounded ring buffer). */
    val requestLog: RequestLog = RequestLog()

    /** Recent WebView console/resource events (bounded ring buffer). */
    val webViewLog: WebViewEventLog = WebViewEventLog()

    /** Opens the diagnostics screen. */
    fun open(context: Context) {
        context.startActivity(
            Intent(context, DiagnosticsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Formats everything for copying/sharing (server state + requests + WebView events). */
    fun formatForCopy(): String {
        val sb = StringBuilder()
        sb.append("NewCatroid local web diagnostics\n")
        sb.append("================================\n")
        sb.append("Server running: ").append(LocalHttpServer.isRunning).append('\n')
        sb.append("Server URL: ").append(LocalHttpServer.baseUrl.ifEmpty { "(none)" }).append('\n')
        sb.append("Document root: ").append(LocalHttpServer.documentRoot.ifEmpty { "(none)" }).append('\n')
        sb.append("Last server error: ")
            .append(LocalHttpServer.lastError ?: "(none)").append('\n')
        sb.append('\n')
        sb.append(requestLog.formatForCopy())
        sb.append('\n')
        sb.append(webViewLog.formatForCopy())
        return sb.toString()
    }

    /** Clears both ring buffers. */
    fun clear() {
        requestLog.clear()
        webViewLog.clear()
    }
}
