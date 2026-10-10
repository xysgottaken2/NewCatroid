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

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A small embedded HTTP server built on [ServerSocket].
 *
 * - Binds to a configurable address (loopback by default; pass `0.0.0.0`
 *   explicitly to expose the server on the LAN - this is an opt-in).
 * - Runs the accept loop on a dedicated thread and serves connections from a
 *   bounded thread pool, so slow clients can neither block the UI thread nor
 *   exhaust device resources. When the pool is saturated the connection is
 *   answered with `503 Service Unavailable`.
 * - Lifecycle is idempotent: [stop] may be called repeatedly, and [start]
 *   always replaces a previously running instance (no duplicate servers).
 *
 * Pure JVM so the full server can be exercised by unit tests on the JVM.
 */
class HttpServerEngine(
    private val storeProvider: () -> DocumentStore?,
    private val urlPrefixProvider: () -> String = { "/" }
) {

    @Volatile
    var isRunning = false
        private set

    @Volatile
    var boundPort = -1
        private set

    @Volatile
    var bindAddress = DEFAULT_BIND_ADDRESS
        private set

    /** Base URL of the running server, e.g. `http://127.0.0.1:8080/`. Empty when stopped. */
    val baseUrl: String
        get() = if (isRunning) "http://$bindAddress:$boundPort/" else ""

    private val lifecycleLock = Any()
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var executor: ThreadPoolExecutor? = null

    /**
     * Binds and starts the server. When the server is already running it is
     * stopped first, so repeated calls never create conflicting instances.
     *
     * @param port port to bind; 0 picks a free ephemeral port (mainly for tests).
     * @param bindAddress address to bind; defaults to the loopback interface.
     * @return a [Result] describing success or the failure reason.
     */
    fun start(port: Int, bindAddress: String = DEFAULT_BIND_ADDRESS): Result {
        synchronized(lifecycleLock) {
            stopInternal()

            if (port < 0 || port > 65535) {
                return Result.Error("Invalid port: $port (expected 0-65535)")
            }
            val address = bindAddress.ifBlank { DEFAULT_BIND_ADDRESS }
            val inetAddress: InetAddress = try {
                InetAddress.getByName(address)
            } catch (e: Exception) {
                return Result.Error("Invalid bind address: $address")
            }

            val engine = HttpEngine(storeProvider, urlPrefixProvider)

            val socket: ServerSocket = try {
                ServerSocket()
            } catch (e: IOException) {
                return Result.Error("Unable to create server socket: ${e.message}")
            }
            try {
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(inetAddress, port), SOCKET_BACKLOG)
            } catch (e: IOException) {
                try {
                    socket.close()
                } catch (ignored: IOException) {
                }
                return Result.Error("Unable to bind $address:$port: ${e.message}")
            }

            val pool = ThreadPoolExecutor(
                0, MAX_WORKER_THREADS,
                30L, TimeUnit.SECONDS,
                ArrayBlockingQueue(MAX_QUEUED_CONNECTIONS),
                DaemonThreadFactory("NewCatroidHttp")
            )
            pool.allowCoreThreadTimeOut(true)

            serverSocket = socket
            executor = pool
            boundPort = socket.localPort
            this.bindAddress = address
            isRunning = true

            val thread = Thread({ acceptLoop(socket, pool, engine) }, "NewCatroidHttpAccept")
            thread.isDaemon = true
            acceptThread = thread
            thread.start()

            return Result.Success(boundPort)
        }
    }

    /** Stops the server, closing the listening socket and the worker pool. Safe to call repeatedly. */
    fun stop() {
        synchronized(lifecycleLock) {
            stopInternal()
        }
    }

    private fun stopInternal() {
        isRunning = false
        val socket = serverSocket
        val thread = acceptThread
        serverSocket = null
        acceptThread = null
        socket?.let { s ->
            try {
                s.close()
            } catch (ignored: IOException) {
            }
        }
        // Closing the socket makes a blocked accept() throw, but the JDK may
        // release the underlying port only once the accept loop has actually
        // exited. Wait for it so stop() deterministically frees the listening
        // port and an immediate restart on the same port cannot race the close.
        thread?.let { t ->
            try {
                t.join(ACCEPT_THREAD_JOIN_TIMEOUT_MS)
            } catch (ignored: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        executor?.let { pool ->
            pool.shutdown()
            try {
                if (!pool.awaitTermination(2, TimeUnit.SECONDS)) {
                    pool.shutdownNow()
                }
            } catch (ignored: InterruptedException) {
                pool.shutdownNow()
                Thread.currentThread().interrupt()
            }
        }
        executor = null
        boundPort = -1
    }

    private fun acceptLoop(socket: ServerSocket, pool: ThreadPoolExecutor, engine: HttpEngine) {
        while (isRunning) {
            val connection: Socket = try {
                socket.accept()
            } catch (e: SocketException) {
                break // socket closed by stop()
            } catch (e: IOException) {
                if (isRunning) continue else break
            }
            try {
                pool.execute { serveConnection(connection, engine) }
            } catch (e: RejectedExecutionException) {
                // Too many concurrent connections: answer 503 and close.
                try {
                    BufferedOutputStream(connection.getOutputStream()).use { out ->
                        val body = "503 Service Unavailable (server busy)\n"
                        val response = ("HTTP/1.1 503 Service Unavailable\r\n" +
                            "Content-Type: text/plain; charset=utf-8\r\n" +
                            "Content-Length: ${body.toByteArray(StandardCharsets.UTF_8).size}\r\n" +
                            "Connection: close\r\n\r\n" + body)
                        out.write(response.toByteArray(StandardCharsets.UTF_8))
                        out.flush()
                    }
                } catch (ignored: IOException) {
                } finally {
                    try {
                        connection.close()
                    } catch (ignored: IOException) {
                    }
                }
            }
        }
    }

    private fun serveConnection(connection: Socket, engine: HttpEngine) {
        try {
            connection.soTimeout = SOCKET_TIMEOUT_MS
            connection.tcpNoDelay = true
            val input = BufferedInputStream(connection.getInputStream())
            val out = BufferedOutputStream(connection.getOutputStream())

            val requestLine = readLine(input, MAX_REQUEST_LINE_BYTES) ?: return
            if (requestLine.isBlank()) return
            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                engine.handle("GET", "/", emptyMap(), out)
                return
            }
            val method = parts[0]
            val target = parts[1]

            val headers = HashMap<String, String>()
            var totalHeaderBytes = requestLine.length
            while (true) {
                val line = readLine(input, MAX_HEADER_LINE_BYTES) ?: break
                if (line.isEmpty()) break
                totalHeaderBytes += line.length + 2
                if (totalHeaderBytes > MAX_HEADER_BYTES || headers.size >= MAX_HEADERS) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase(Locale.US)] =
                        line.substring(colon + 1).trim()
                }
            }

            engine.handle(method, target, headers, out)
        } catch (e: SocketException) {
            // Client went away; nothing to do.
        } catch (e: IOException) {
            // Malformed request or broken pipe; the connection is closed below.
        } finally {
            try {
                connection.close()
            } catch (ignored: IOException) {
            }
        }
    }

    /** Reads a CRLF (or LF) terminated line, returning null on EOF. Enforces [maxBytes]. */
    private fun readLine(input: InputStream, maxBytes: Int): String? {
        val sb = StringBuilder()
        var count = 0
        while (true) {
            val b = input.read()
            if (b == -1) {
                if (sb.isEmpty()) return null
                throw EOFException("Connection closed mid-line")
            }
            count++
            if (count > maxBytes) throw IOException("HTTP line too long")
            if (b == '\n'.code) {
                var result = sb.toString()
                if (result.endsWith("\r")) result = result.dropLast(1)
                return result
            }
            sb.append(b.toChar())
        }
    }

    sealed class Result {
        data class Success(val port: Int) : Result()
        data class Error(val message: String) : Result()

        val isSuccess: Boolean get() = this is Success
    }

    companion object {
        const val DEFAULT_BIND_ADDRESS = "127.0.0.1"
        const val MAX_WORKER_THREADS = 8
        const val MAX_QUEUED_CONNECTIONS = 64
        const val SOCKET_BACKLOG = 64
        const val SOCKET_TIMEOUT_MS = 15000
        const val ACCEPT_THREAD_JOIN_TIMEOUT_MS = 2000L
        const val MAX_REQUEST_LINE_BYTES = 8192
        const val MAX_HEADER_LINE_BYTES = 8192
        const val MAX_HEADER_BYTES = 16384
        const val MAX_HEADERS = 100
    }

    private class DaemonThreadFactory(private val prefix: String) : ThreadFactory {
        private val counter = AtomicInteger(0)
        override fun newThread(r: Runnable): Thread {
            val thread = Thread(r, "$prefix-${counter.incrementAndGet()}")
            thread.isDaemon = true
            return thread
        }
    }
}
