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

package org.catrobat.catroid.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import org.catrobat.catroid.BuildConfig
import org.catrobat.catroid.content.localweb.EmbeddedWebViewManager
import org.catrobat.catroid.content.localweb.LocalDiagnostics
import org.catrobat.catroid.content.localweb.WebViewEvent

/**
 * Reusable full-screen in-app WebView used by the "Open Web Page" block.
 *
 * Features:
 *  - Navigation controls (back / reload / close) and a loading progress bar.
 *  - Readable error state when a page fails to load.
 *  - Navigation is restricted to approved (loopback) origins; see
 *    [EmbeddedWebViewManager].
 *  - JavaScript and DOM storage are enabled so pages served by the embedded
 *    local HTTP server work normally (including WebAssembly and Web Workers
 *    where the platform WebView supports them). No JavaScript bridge is
 *    exposed to page content.
 *  - After each page load a capability report (JavaScript, WebAssembly,
 *    Web Worker, crossOriginIsolated, SharedArrayBuffer, navigator.gpu) is
 *    evaluated and reported through the console log; in debug builds console
 *    messages are logged with the "EmbeddedWebView" tag.
 *  - Pressing back navigates the page history first and closes the activity
 *    (returning to NewCatroid) when there is no history.
 */
class EmbeddedWebViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var urlText: TextView
    private lateinit var backButton: Button

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EmbeddedWebViewManager.register(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        backButton = Button(this).apply { text = "◀"; setOnClickListener { onBackPressed() } }
        val reloadButton = Button(this).apply { text = "⟳"; setOnClickListener { webView.reload() } }
        val diagnosticsButton = Button(this).apply {
            text = "Diag"
            textSize = 11f
            setOnClickListener { LocalDiagnostics.open(this@EmbeddedWebViewActivity) }
        }
        val closeButton = Button(this).apply { text = "✕"; setOnClickListener { close() } }
        urlText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            textSize = 12f
        }
        toolbar.addView(backButton)
        toolbar.addView(reloadButton)
        toolbar.addView(diagnosticsButton)
        toolbar.addView(closeButton)
        toolbar.addView(urlText)

        progressBar = ProgressBar(this).apply {
            isVisible = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        errorText = TextView(this).apply {
            isVisible = false
            textSize = 14f
            setPadding(16, 16, 16, 16)
        }

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = LocalWebViewClient()
            webChromeClient = LocalWebChromeClient()
        }

        root.addView(toolbar)
        root.addView(progressBar)
        root.addView(errorText)
        root.addView(webView)
        setContentView(root)

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        }

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank() || !EmbeddedWebViewManager.isAllowedOrigin(url)) {
            showError("Refusing to open URL: $url")
        } else {
            navigateTo(url)
        }
    }

    /** Navigates the WebView to [url] when the origin is allowed. */
    fun navigateTo(url: String) {
        if (!EmbeddedWebViewManager.isAllowedOrigin(url)) {
            showError("Navigation blocked (origin not allowed): $url")
            return
        }
        errorText.isVisible = false
        urlText.text = url
        webView.loadUrl(url)
    }

    /** Closes the activity, returning the user to the previous NewCatroid screen. */
    fun close() {
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        // Destroy the WebView properly so repeated open/close cycles do not
        // leak renderers or JavaScript engines.
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        EmbeddedWebViewManager.unregister(this)
        super.onDestroy()
    }

    private fun captureConsole(level: String, message: String, source: String?, line: Int) {
        LocalDiagnostics.webViewLog.add(
            WebViewEvent(
                timestampMs = System.currentTimeMillis(),
                kind = WebViewEvent.Kind.CONSOLE,
                level = level,
                message = message,
                url = source,
                line = line
            )
        )
    }

    private fun captureResourceError(url: String?, errorCode: Int, description: String?, isMainFrame: Boolean) {
        LocalDiagnostics.webViewLog.add(
            WebViewEvent(
                timestampMs = System.currentTimeMillis(),
                kind = WebViewEvent.Kind.RESOURCE_ERROR,
                level = if (isMainFrame) "ERROR" else "WARN",
                message = "resource load failed: $description (code $errorCode)" +
                    if (isMainFrame) " [main frame]" else "",
                url = url
            )
        )
    }

    private fun captureHttpError(url: String?, statusCode: Int) {
        LocalDiagnostics.webViewLog.add(
            WebViewEvent(
                timestampMs = System.currentTimeMillis(),
                kind = WebViewEvent.Kind.HTTP_ERROR,
                level = "ERROR",
                message = "HTTP error response: $statusCode",
                url = url
            )
        )
    }

    private fun showError(message: String) {
        Log.w(TAG, message)
        errorText.text = message
        errorText.isVisible = true
        progressBar.isVisible = false
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun reportCapabilities() {
        // Diagnostic capability report. Evaluated as plain JavaScript and
        // surfaced through the console log - no JavaScript bridge involved.
        // Unsupported features are reported as false/absent by the page
        // itself; features the platform has not been tested with are listed
        // in the project documentation.
        val script = """
            (function () {
                var report = {
                    javascript: true,
                    webAssembly: typeof WebAssembly === 'object',
                    webWorker: typeof Worker === 'function',
                    crossOriginIsolated: typeof crossOriginIsolated === 'boolean' ? crossOriginIsolated : false,
                    sharedArrayBuffer: typeof SharedArrayBuffer === 'function',
                    webgpu: typeof navigator !== 'undefined' && navigator.gpu !== undefined && navigator.gpu !== null,
                    webgl: (function () { try { var c = document.createElement('canvas'); return !!(c.getContext('webgl') || c.getContext('experimental-webgl')); } catch (e) { return false; } })(),
                    webgl2: (function () { try { var c = document.createElement('canvas'); return !!c.getContext('webgl2'); } catch (e) { return false; } })(),
                    readyState: document.readyState,
                    resources: (function () {
                        try {
                            var entries = performance.getEntriesByType('resource');
                            var failed = [];
                            for (var i = 0; i < entries.length; i++) {
                                var status = entries[i].responseStatus;
                                if (typeof status === 'number' && status >= 400) {
                                    failed.push(entries[i].name + ' -> ' + status);
                                }
                            }
                            return {
                                count: entries.length,
                                failedCount: failed.length,
                                failed: failed.slice(0, 20),
                                scriptCount: entries.filter(function (e) { return /\.(js|mjs)(\?|#|$)/.test(e.name); }).length
                            };
                        } catch (e) { return { error: String(e) }; }
                    })()
                };
                console.log('NewCatroid WebView capabilities: ' + JSON.stringify(report));
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private inner class LocalWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val url = request?.url?.toString() ?: return false
            if (EmbeddedWebViewManager.isAllowedOrigin(url)) {
                return false // load inside this WebView
            }
            LocalDiagnostics.webViewLog.add(
                WebViewEvent(
                    timestampMs = System.currentTimeMillis(),
                    kind = WebViewEvent.Kind.NAVIGATION_ERROR,
                    level = "WARN",
                    message = "navigation blocked (origin not allowed)",
                    url = url
                )
            )
            showError("Navigation blocked (origin not allowed): $url")
            return true
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            progressBar.isVisible = true
            errorText.isVisible = false
            urlText.text = url
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            progressBar.isVisible = false
            reportCapabilities()
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            super.onReceivedError(view, request, error)
            // Capture every failed resource (not only the main frame) - a
            // single failing script or texture is enough to stall a game.
            captureResourceError(
                request?.url?.toString(),
                error?.errorCode ?: -1,
                error?.description?.toString(),
                request?.isForMainFrame == true
            )
            if (request?.isForMainFrame == true) {
                showError("Failed to load ${request.url}: ${error?.description} (${error?.errorCode})")
            }
        }

        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            errorResponse: WebResourceResponse?
        ) {
            super.onReceivedHttpError(view, request, errorResponse)
            captureHttpError(request?.url?.toString(), errorResponse?.statusCode ?: -1)
        }
    }

    private inner class LocalWebChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            progressBar.progress = newProgress
            if (newProgress >= 100) progressBar.isVisible = false
        }

        override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
            val message = consoleMessage?.message() ?: return false
            val level = consoleMessage.messageLevel()?.name ?: "LOG"
            captureConsole(
                level,
                message,
                consoleMessage.sourceId(),
                consoleMessage.lineNumber()
            )
            val line = "JS console (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}): $message"
            when (consoleMessage.messageLevel()) {
                android.webkit.ConsoleMessage.MessageLevel.ERROR -> Log.e(TAG, line)
                android.webkit.ConsoleMessage.MessageLevel.WARNING -> Log.w(TAG, line)
                else -> if (BuildConfig.DEBUG) Log.d(TAG, line) else Log.i(TAG, line)
            }
            return true
        }
    }

    companion object {
        private const val TAG = "EmbeddedWebView"
        const val EXTRA_URL = "org.catrobat.catroid.extra.EMBEDDED_WEBVIEW_URL"
    }
}
