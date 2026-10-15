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

import android.net.Uri
import android.util.Log
import org.catrobat.catroid.CatroidApplication
import java.io.File
import java.util.concurrent.Executors

/**
 * Application-wide manager for the embedded local HTTP server used by the
 * NewCatroid "local HTTP server" blocks.
 *
 * Responsibilities:
 *  - Holds the selected document root (a SAF directory URI from the directory
 *    picker, or a plain filesystem directory) and the optional URL prefix.
 *  - Creates, starts and stops the [HttpServerEngine] off the UI thread.
 *  - Exposes observable state for the reporter functions: [isRunning],
 *    [baseUrl] and [lastError].
 *
 * The server binds to the loopback interface by default. Binding to
 * `0.0.0.0` (LAN exposure) is possible but must be requested explicitly
 * through the "Start Local HTTP Server" block's bind address parameter.
 */
object LocalHttpServer {

    private const val TAG = "LocalHttpServer"
    private const val HTTP_LOG_TAG = "NewCatroidHttp"

    /** Lazily read (and cached) local opt-in scripts from the app resources. */
    private var cachedTouchControlsScript: String? = null
    private var cachedGameDiagnosticsScript: String? = null
    private var cachedWorkerDiagnosticsBootstrap: String? = null

    private fun touchControlsScript(): String? = readLocalScript(
        cached = cachedTouchControlsScript,
        resourceId = org.catrobat.catroid.R.raw.newcatroid_touch_controls,
        description = "touch controls overlay"
    ) { cachedTouchControlsScript = it }

    private fun gameDiagnosticsScript(): String? = readLocalScript(
        cached = cachedGameDiagnosticsScript,
        resourceId = org.catrobat.catroid.R.raw.newcatroid_game_diagnostics,
        description = "game diagnostics bootstrap"
    ) { cachedGameDiagnosticsScript = it }

    private fun workerDiagnosticsBootstrap(): String? = readLocalScript(
        cached = cachedWorkerDiagnosticsBootstrap,
        resourceId = org.catrobat.catroid.R.raw.newcatroid_worker_diagnostics,
        description = "worker diagnostics bootstrap"
    ) { cachedWorkerDiagnosticsBootstrap = it }

    private fun readLocalScript(cached: String?, resourceId: Int, description: String, cache: (String) -> Unit): String? {
        cached?.let { return it }
        return try {
            val context = CatroidApplication.getAppContext()
            context.resources.openRawResource(resourceId)
                .use { it.readBytes().toString(Charsets.UTF_8) }
                .also(cache)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read $description script", e)
            null
        }
    }

    /** Default bind address: the loopback interface (never exposed on the LAN unless requested). */
    const val DEFAULT_BIND = HttpServerEngine.DEFAULT_BIND_ADDRESS

    private val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LocalHttpServerIO").apply { isDaemon = true }
    }

    private val lock = Any()

    private var engine: HttpServerEngine? = null

    @Volatile
    private var documentRootUri: Uri? = null

    @Volatile
    private var documentRootFile: File? = null

    @Volatile
    private var cachedStore: DocumentStore? = null

    @Volatile
    private var urlPrefix: String = "/"

    /** Readable description of the latest server related error, or null when there is none. */
    @Volatile
    var lastError: String? = null
        private set

    val isRunning: Boolean
        get() = engine?.isRunning == true

    /** Base URL of the running server (e.g. `http://127.0.0.1:8080/`), or an empty string when stopped. */
    val baseUrl: String
        get() = engine?.baseUrl ?: ""

    /** Port the server is bound to, or -1 when not running. */
    val port: Int
        get() = engine?.boundPort ?: -1

    /** The currently hosted document root as a string (URI or path), or an empty string. */
    val documentRoot: String
        get() = documentRootUri?.toString() ?: documentRootFile?.absolutePath ?: ""

    val currentUrlPrefix: String
        get() = urlPrefix

    /**
     * Sets the directory that is served over HTTP. Accepts a content URI
     * (`content://...`, as stored by the directory selection block), a
     * `file://` URI, or a plain absolute filesystem path. A null or blank
     * value clears the root (the server then answers 404 for every request).
     */
    fun setDocumentRoot(root: String?) {
        synchronized(lock) {
            if (root.isNullOrBlank()) {
                // Explicit clear.
                documentRootUri = null
                documentRootFile = null
                cachedStore = null
                return
            }

            try {
                val trimmed = root.trim()
                val uri = Uri.parse(trimmed)
                when (uri.scheme?.lowercase()) {
                    "content" -> {
                        // Construct first and commit only on success: when the
                        // URI is not a servable document tree the previous root
                        // stays untouched and lastError explains the problem.
                        val store = SafDocumentStore(CatroidApplication.getAppContext(), uri)
                        documentRootUri = uri
                        documentRootFile = null
                        cachedStore = store
                    }
                    "file" -> {
                        val file = File(uri.path ?: "")
                        documentRootUri = null
                        documentRootFile = file
                        cachedStore = FileDocumentStore(file)
                    }
                    null -> {
                        // Plain filesystem path (no scheme).
                        val file = File(trimmed)
                        documentRootUri = null
                        documentRootFile = file
                        cachedStore = FileDocumentStore(file)
                    }
                    else -> {
                        lastError = "Unsupported document root scheme: ${uri.scheme}"
                        Log.w(TAG, lastError!!)
                    }
                }
            } catch (e: Exception) {
                // Invalid root: keep the previous configuration, report why.
                lastError = "Invalid document root: ${e.message}"
                Log.w(TAG, "Invalid document root", e)
            }
        }
    }

    /** Sets the URL prefix the hosted directory is served under (default "/"). */
    fun setUrlPrefix(prefix: String?) {
        urlPrefix = HttpPaths.normalizePrefix(prefix)
    }

    /**
     * Starts the server. Must not be called on the UI thread for the blocking
     * bind; use [startAsync] from script actions. Repeated calls replace the
     * running instance instead of creating conflicting servers.
     *
     * @return true when the server is now running.
     */
    fun start(
        port: Int,
        bindAddress: String = HttpServerEngine.DEFAULT_BIND_ADDRESS,
        addCrossOriginIsolationHeaders: Boolean = true
    ): Boolean {
        if (port < 1 || port > 65535) {
            lastError = "Invalid port: $port (expected 1-65535)"
            Log.w(TAG, lastError!!)
            return false
        }
        val address = bindAddress.ifBlank { HttpServerEngine.DEFAULT_BIND_ADDRESS }

        synchronized(lock) {
            val current = engine
            if (current != null && current.isRunning && current.boundPort == port && current.bindAddress == address) {
                // Already running on the requested endpoint: idempotent success,
                // never create a duplicate instance.
                lastError = null
                return true
            }
            current?.stop()
        }

        val newEngine = HttpServerEngine(
            storeProvider = { currentStore() },
            urlPrefixProvider = { urlPrefix },
            addCrossOriginIsolationHeaders = addCrossOriginIsolationHeaders,
            requestLog = LocalDiagnostics.requestLog,
            touchControlsScriptProvider = { touchControlsScript() },
            gameDiagnosticsScriptProvider = { gameDiagnosticsScript() },
            workerDiagnosticsBootstrapProvider = { workerDiagnosticsBootstrap() },
            logger = { level, message, error ->
                when (level) {
                    LogLevel.DEBUG -> Log.d(HTTP_LOG_TAG, message)
                    LogLevel.INFO -> Log.i(HTTP_LOG_TAG, message)
                    LogLevel.WARN ->
                        if (error != null) Log.w(HTTP_LOG_TAG, message, error) else Log.w(HTTP_LOG_TAG, message)
                    LogLevel.ERROR ->
                        if (error != null) Log.e(HTTP_LOG_TAG, message, error) else Log.e(HTTP_LOG_TAG, message)
                }
            }
        )
        when (val result = newEngine.start(port, address)) {
            is HttpServerEngine.Result.Success -> {
                synchronized(lock) {
                    engine?.stop()
                    engine = newEngine
                }
                lastError = null
                Log.i(TAG, "Local HTTP server started on ${newEngine.baseUrl}")
                return true
            }
            is HttpServerEngine.Result.Error -> {
                synchronized(lock) {
                    if (engine === null || engine?.isRunning == false) engine = null
                }
                lastError = result.message
                Log.w(TAG, "Failed to start local HTTP server: ${result.message}")
                return false
            }
        }
    }

    /**
     * Starts the server on a background thread. [onComplete] is invoked (on
     * the background thread) exactly once with the outcome, so scripts can
     * wait for startup to finish before continuing.
     */
    fun startAsync(port: Int, bindAddress: String = HttpServerEngine.DEFAULT_BIND_ADDRESS, onComplete: (Boolean) -> Unit) {
        ioExecutor.execute {
            val success = start(port, bindAddress)
            try {
                onComplete(success)
            } catch (e: Exception) {
                Log.e(TAG, "Error in startAsync callback", e)
            }
        }
    }

    /** Stops the server. Safe to call repeatedly; does not touch unrelated networking. */
    fun stop() {
        synchronized(lock) {
            engine?.stop()
            engine = null
        }
        Log.i(TAG, "Local HTTP server stopped")
    }

    /** Clears the last error (e.g. when a new operation is attempted). */
    fun clearError() {
        lastError = null
    }

    /**
     * Records a user-facing error (readable through the "local HTTP server
     * error" reporter). Used by blocks that configure the server so failures
     * are never silently swallowed.
     */
    fun reportError(message: String) {
        lastError = message
        Log.w(TAG, message)
    }

    private fun currentStore(): DocumentStore? {
        synchronized(lock) {
            return cachedStore
        }
    }
}
