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

package org.catrobat.catroid.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.catrobat.catroid.content.localweb.LocalDiagnostics
import org.catrobat.catroid.content.localweb.LocalHttpServer

/**
 * In-app diagnostics screen for the local web hosting feature: shows the
 * server state, the recent HTTP requests the server handled (method, path,
 * status, MIME type, bytes sent, duration, redirect/error/abort details) and
 * the captured WebView events (console messages, resource load failures, HTTP
 * error responses, blocked navigations). The whole report can be copied to the
 * clipboard or shared (exported) as plain text.
 *
 * Opened from the "Show HTTP diagnostics" block or from the "Diag" button in
 * the embedded WebView. Contains no file contents - only paths, statuses,
 * sizes and failure reasons.
 */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var requestsText: TextView
    private lateinit var webViewText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val title = TextView(this).apply {
            text = "Local web diagnostics"
            textSize = 18f
            setPadding(16, 16, 16, 8)
        }

        statusText = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(16, 0, 16, 8)
        }

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, action: () -> Unit): Button = Button(this).apply {
            text = label
            setOnClickListener { action() }
        }
        buttons.addView(button("Copy") { copyToClipboard() })
        buttons.addView(button("Share") { share() })
        buttons.addView(button("Clear") {
            LocalDiagnostics.clear()
            refresh()
        })
        buttons.addView(button("Refresh") { refresh() })

        fun section(label: String): TextView = TextView(this).apply {
            text = label
            textSize = 14f
            setPadding(16, 12, 16, 4)
        }

        requestsText = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(16, 0, 16, 8)
        }
        webViewText = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(16, 0, 16, 16)
        }

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(section("Server"))
        content.addView(statusText)
        content.addView(section("HTTP requests (newest last)"))
        content.addView(requestsText)
        content.addView(section("WebView events (newest last)"))
        content.addView(webViewText)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            addView(content)
        }

        root.addView(title)
        root.addView(buttons)
        root.addView(scroll)
        setContentView(root)

        refresh()
    }

    private fun refresh() {
        statusText.text = buildString {
            append("running: ").append(LocalHttpServer.isRunning).append('\n')
            append("url: ").append(LocalHttpServer.baseUrl.ifEmpty { "(none)" }).append('\n')
            append("port: ").append(LocalHttpServer.port).append('\n')
            append("document root: ").append(LocalHttpServer.documentRoot.ifEmpty { "(none)" }).append('\n')
            append("last error: ").append(LocalHttpServer.lastError ?: "(none)").append('\n')
            val webViewPackage = try {
                WebView.getCurrentWebViewPackage()
            } catch (e: Exception) {
                null
            }
            append("webview package: ")
                .append(if (webViewPackage != null) webViewPackage.packageName + " " + (webViewPackage.versionName ?: "?") else "(unknown)")
        }
        requestsText.text = LocalDiagnostics.requestLog.formatForCopy()
        webViewText.text = LocalDiagnostics.webViewLog.formatForCopy()
    }

    private fun copyToClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText("NewCatroid diagnostics", LocalDiagnostics.formatForCopy())
        )
        Toast.makeText(this, "Diagnostics copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, LocalDiagnostics.formatForCopy())
        }
        startActivity(Intent.createChooser(send, "Share diagnostics"))
    }
}
