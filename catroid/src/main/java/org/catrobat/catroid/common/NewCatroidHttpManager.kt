package org.catrobat.catroid.common

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Properties
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object NewCatroidHttpManager {

    private val baseClient = OkHttpClient()

    private val cookieStore = HashMap<String, List<Cookie>>()
    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookieStore[url.host] = cookies
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return cookieStore[url.host] ?: emptyList()
        }
    }

    class RequestState(val id: String, val method: String, var url: String) {
        val headers = mutableMapOf<String, String>()
        val queryParams = mutableMapOf<String, String>()
        var bodyText: String? = null
        var bodyContentType: String = "application/json"

        var uploadFile: File? = null
        var uploadFilePartName: String = "file"
        var uploadFileMimeType: String = "application/octet-stream"

        var connectTimeoutMs: Long = 10_000
        var readTimeoutMs: Long = 10_000
        var writeTimeoutMs: Long = 10_000
        var followRedirects: Boolean = true
        var trustAllCerts: Boolean = false
        var useCookies: Boolean = true

        var proxyHost: String? = null
        var proxyPort: Int? = null
    }

    class ResponseState(
        val code: Int,
        val headers: Map<String, String>,
        val bodyBytes: ByteArray?
    ) {
        val bodyString: String by lazy {
            bodyBytes?.decodeToString() ?: ""
        }
    }

    enum class DownloadStatus { QUEUED, DOWNLOADING, COMPLETED, FAILED, CANCELLED }

    /**
     * Observable state for a streamed file download. Progress fields are
     * volatile because the transfer runs away from the caller's thread.
     */
    class DownloadState(
        val url: String,
        val destinationFile: File
    ) {
        private val stateLock = Any()

        @Volatile var status: DownloadStatus = DownloadStatus.QUEUED
            private set
        @Volatile var bytesDownloaded: Long = 0L
            internal set
        @Volatile var totalBytes: Long = -1L
            internal set
        @Volatile var responseCode: Int? = null
            internal set
        @Volatile var resumedFromBytes: Long = 0L
            internal set
        @Volatile var error: String? = null
            internal set
        @Volatile var progressCallbackError: String? = null
            internal set
        @Volatile internal var activeCall: Call? = null

        val isFinished: Boolean
            get() = status == DownloadStatus.COMPLETED ||
                status == DownloadStatus.FAILED || status == DownloadStatus.CANCELLED

        val progressPercent: Int?
            get() = if (totalBytes <= 0L) null else
                ((bytesDownloaded.coerceAtMost(totalBytes).toDouble() / totalBytes.toDouble()) * 100.0).toInt()

        fun cancel() {
            synchronized(stateLock) {
                if (status == DownloadStatus.COMPLETED || status == DownloadStatus.FAILED ||
                    status == DownloadStatus.CANCELLED) return
                status = DownloadStatus.CANCELLED
                activeCall?.cancel()
            }
        }

        internal fun markDownloading(): Boolean = synchronized(stateLock) {
            if (status == DownloadStatus.CANCELLED) false else {
                status = DownloadStatus.DOWNLOADING
                true
            }
        }

        internal fun completeWith(finalize: () -> Unit): Boolean = synchronized(stateLock) {
            if (status == DownloadStatus.CANCELLED) return@synchronized false
            finalize()
            status = DownloadStatus.COMPLETED
            true
        }

        internal fun fail(message: String) {
            synchronized(stateLock) {
                if (status != DownloadStatus.CANCELLED && status != DownloadStatus.COMPLETED) {
                    error = message
                    status = DownloadStatus.FAILED
                }
            }
        }
    }

    private data class ResumeMetadata(
        val urlFingerprint: String,
        val validatorHeader: String,
        val validatorValue: String,
        val totalBytes: Long
    )

    private data class ContentRange(val start: Long, val end: Long, val total: Long?)

    private val downloadClient = baseClient.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val downloadThreadIds = AtomicInteger()
    private val downloadExecutor = ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(16),
        { runnable -> Thread(runnable, "NewCatroid-file-download-${downloadThreadIds.incrementAndGet()}").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )
    private val destinationLocks = ConcurrentHashMap<String, ReentrantLock>()
    private val progressBufferSize = 64 * 1024
    private val resumeMetadataSuffix = ".newcatroid.part.meta"
    private val partialFileSuffix = ".newcatroid.part"
    private val contentRangePattern = Regex("^bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)$", RegexOption.IGNORE_CASE)

    private val activeRequests = ConcurrentHashMap<String, RequestState>()
    private val completedResponses = ConcurrentHashMap<String, ResponseState>()

    /**
     * Starts a bounded-background, streamed GET to [destinationFile]. The
     * response body is never accumulated in memory. A partial file is resumed
     * only when the server supplied a strong ETag or Last-Modified validator;
     * otherwise an interrupted partial is discarded on the next attempt.
     *
     * [onProgress] is called from a download worker thread on state changes and
     * at most every 250 ms while bytes are flowing. Poll [DownloadState] for
     * immediate volatile progress updates from another thread.
     */
    fun downloadToFile(
        url: String,
        destinationFile: File,
        state: DownloadState? = null,
        onProgress: ((DownloadState) -> Unit)? = null
    ): DownloadState {
        // Keep the resolved value distinct from the optional parameter: this
        // avoids shadowing ambiguity and makes the reused-state contract clear.
        val resolvedState = state ?: DownloadState(url, destinationFile)
        val sameRequest = resolvedState.url == url &&
            runCatching { resolvedState.destinationFile.canonicalFile == destinationFile.canonicalFile }
                .getOrDefault(false)
        if (!sameRequest) {
            resolvedState.fail("Download state does not match the requested URL and destination")
            notifyDownloadState(resolvedState, onProgress)
            return resolvedState
        }
        if (resolvedState.status != DownloadStatus.QUEUED) {
            resolvedState.fail("Download state has already been used (${resolvedState.status})")
            notifyDownloadState(resolvedState, onProgress)
            return resolvedState
        }

        try {
            downloadExecutor.execute { runDownload(resolvedState, onProgress) }
        } catch (rejected: RejectedExecutionException) {
            resolvedState.fail("Download queue is full; retry after an active download finishes")
            notifyDownloadState(resolvedState, onProgress)
        }
        return resolvedState
    }

    private fun runDownload(state: DownloadState, onProgress: ((DownloadState) -> Unit)?) {
        val destination = state.destinationFile.absoluteFile
        val lockKey = try {
            destination.canonicalPath
        } catch (e: Exception) {
            state.fail("Cannot resolve destination path: ${e.message ?: e.javaClass.simpleName}")
            notifyDownloadState(state, onProgress)
            return
        }
        val pathLock = destinationLocks.computeIfAbsent(lockKey) { ReentrantLock() }
        pathLock.withLock {
            if (state.status == DownloadStatus.CANCELLED) return
            if (!state.markDownloading()) return
            notifyDownloadState(state, onProgress)
            try {
                performDownload(state, destination, onProgress)
            } catch (e: Exception) {
                if (state.status != DownloadStatus.CANCELLED) {
                    val reason = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                    state.fail("Download setup failed: $reason (URL: ${safeUrlForError(state.url)})")
                    notifyDownloadState(state, onProgress)
                }
            }
        }
    }

    private fun performDownload(
        state: DownloadState,
        destination: File,
        onProgress: ((DownloadState) -> Unit)?
    ) {
        val parent = destination.parentFile
        if (parent == null || (!parent.exists() && !parent.mkdirs()) || !parent.isDirectory) {
            state.fail("Destination directory is unavailable: ${parent?.absolutePath ?: destination.path}")
            notifyDownloadState(state, onProgress)
            return
        }
        if (destination.exists() && destination.isDirectory) {
            state.fail("Destination is a directory: ${destination.absolutePath}")
            notifyDownloadState(state, onProgress)
            return
        }

        val partial = File(parent, destination.name + partialFileSuffix)
        val metadataFile = File(parent, destination.name + resumeMetadataSuffix)
        val fingerprint = urlFingerprint(state.url)
        var resume = readResumeMetadata(metadataFile)?.takeIf {
            it.urlFingerprint == fingerprint && partial.isFile && partial.length() > 0L &&
                (it.totalBytes < 0L || partial.length() < it.totalBytes) &&
                (it.validatorHeader == "ETag" || it.validatorHeader == "Last-Modified") &&
                it.validatorValue.isNotBlank()
        }
        if (resume == null) {
            deleteQuietly(partial)
            deleteQuietly(metadataFile)
        }
        var offset = if (resume != null) partial.length() else 0L
        state.bytesDownloaded = offset
        state.totalBytes = resume?.totalBytes ?: -1L
        state.resumedFromBytes = offset
        var canKeepPartial = resume != null
        var responseCode: Int? = null
        var lastProgressCallbackNanos = 0L

        fun notifyProgress(force: Boolean = false) {
            val now = System.nanoTime()
            if (force || now - lastProgressCallbackNanos >= TimeUnit.MILLISECONDS.toNanos(250)) {
                notifyDownloadState(state, onProgress)
                lastProgressCallbackNanos = now
            }
        }

        try {
            if (state.status == DownloadStatus.CANCELLED) return
            val resumeForRequest = resume
            val requestBuilder = Request.Builder()
                .url(state.url)
                .header("Accept-Encoding", "identity")
                .get()
            if (resumeForRequest != null && offset > 0L) {
                requestBuilder.header("Range", "bytes=$offset-")
                    .header("If-Range", resumeForRequest.validatorValue)
            }
            val call = downloadClient.newCall(requestBuilder.build())
            state.activeCall = call
            if (state.status == DownloadStatus.CANCELLED) {
                call.cancel()
                return
            }

            call.execute().use { response ->
                responseCode = response.code
                state.responseCode = response.code
                val responseBody = response.body
                if (response.code != 200 && response.code != 206) {
                    val detail = readErrorBody(responseBody)
                    val description = "HTTP ${response.code} ${response.message}".trim()
                    val safeUrl = safeUrlForError(state.url)
                    throw IOException(
                        "$description while downloading $safeUrl" +
                            if (detail.isEmpty()) "" else ": $detail"
                    )
                }
                if (responseBody == null) throw IOException("HTTP ${response.code} returned no response body")

                var append = false
                var expectedBodyLength = responseBody.contentLength()
                var expectedTotalLength: Long? = null
                var validator: ResumeMetadata? = null

                if (response.code == 206) {
                    val rangeResume = resumeForRequest
                        ?: throw IOException("Server returned HTTP 206 without a validated Range request")
                    if (offset <= 0L) {
                        throw IOException("Server returned HTTP 206 without a positive Range offset")
                    }
                    val range = parseContentRange(response.header("Content-Range"))
                        ?: throw IOException("HTTP 206 has an invalid or missing Content-Range header")
                    if (range.start != offset) {
                        throw IOException("HTTP 206 started at byte ${range.start}; requested byte $offset")
                    }
                    val total = range.total
                        ?: throw IOException("HTTP 206 Content-Range does not declare the complete resource length")
                    if (rangeResume.totalBytes >= 0L && rangeResume.totalBytes != total) {
                        throw IOException("Resource length changed while resuming (${rangeResume.totalBytes} to $total bytes)")
                    }
                    if (range.end < range.start || range.end != total - 1L) {
                        throw IOException("HTTP 206 returned an incomplete range ending at byte ${range.end} of $total")
                    }
                    val receivedValidator = response.header(rangeResume.validatorHeader)
                    if (receivedValidator != null && receivedValidator != rangeResume.validatorValue) {
                        throw IOException("Resource validator changed while resuming")
                    }
                    val rangeLength = range.end - range.start + 1L
                    if (expectedBodyLength >= 0L && expectedBodyLength != rangeLength) {
                        throw IOException("HTTP 206 Content-Length $expectedBodyLength does not match range length $rangeLength")
                    }
                    expectedBodyLength = rangeLength
                    expectedTotalLength = total
                    append = true
                    validator = rangeResume.copy(totalBytes = total)
                    canKeepPartial = true
                } else {
                    // If-Range failed or the server ignored Range. A complete
                    // 200 response is authoritative, so discard the old tail.
                    if (offset > 0L) {
                        partial.outputStream().use { }
                        deleteQuietly(metadataFile)
                        offset = 0L
                        state.bytesDownloaded = 0L
                        state.resumedFromBytes = 0L
                        resume = null
                        canKeepPartial = false
                    }
                    if (expectedBodyLength >= 0L) expectedTotalLength = expectedBodyLength
                    val (header, value) = responseValidator(response)
                    if (header != null && value != null) {
                        validator = ResumeMetadata(fingerprint, header, value, expectedTotalLength ?: -1L)
                        writeResumeMetadata(metadataFile, validator)
                        canKeepPartial = true
                    } else {
                        deleteQuietly(metadataFile)
                        canKeepPartial = false
                    }
                }

                state.totalBytes = expectedTotalLength ?: -1L
                state.bytesDownloaded = offset
                notifyProgress(force = true)
                if (state.status == DownloadStatus.CANCELLED) return

                val input = responseBody.byteStream()
                val output = FileOutputStream(partial, append)
                var copied = 0L
                try {
                    output.use { sink ->
                        val buffer = ByteArray(progressBufferSize)
                        while (true) {
                            if (state.status == DownloadStatus.CANCELLED || call.isCanceled()) {
                                throw IOException("Download cancelled")
                            }
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            sink.write(buffer, 0, read)
                            copied += read
                            state.bytesDownloaded = offset + copied
                            notifyProgress()
                        }
                        sink.fd.sync()
                    }
                } finally {
                    input.close()
                }

                if (expectedBodyLength >= 0L && copied != expectedBodyLength) {
                    throw IOException("Incomplete response body: received $copied of $expectedBodyLength bytes")
                }
                val finalLength = offset + copied
                if (expectedTotalLength != null && finalLength != expectedTotalLength) {
                    throw IOException("Incomplete download: received $finalLength of $expectedTotalLength bytes")
                }
                if (validator != null) {
                    writeResumeMetadata(metadataFile, validator.copy(totalBytes = expectedTotalLength ?: -1L))
                    canKeepPartial = true
                }
                if (state.status == DownloadStatus.CANCELLED || call.isCanceled()) return

                val completed = state.completeWith {
                    moveAtomically(partial, destination)
                    deleteQuietly(metadataFile)
                    deleteQuietly(File(parent, metadataFile.name + ".tmp"))
                    state.bytesDownloaded = finalLength
                    state.totalBytes = expectedTotalLength ?: finalLength
                }
                if (!completed) return
                state.activeCall = null
                notifyProgress(force = true)
            }
        } catch (e: Exception) {
            val cancelled = state.status == DownloadStatus.CANCELLED
            if (!canKeepPartial) {
                deleteQuietly(partial)
                deleteQuietly(metadataFile)
                deleteQuietly(File(parent, metadataFile.name + ".tmp"))
            }
            if (!cancelled) {
                val rawDescription = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                val safeUrl = safeUrlForError(state.url)
                var description = rawDescription
                if (state.url.isNotEmpty()) description = description.replace(state.url, safeUrl)
                val normalizedUrl = state.url.toHttpUrlOrNull()?.toString()
                if (!normalizedUrl.isNullOrEmpty()) description = description.replace(normalizedUrl, safeUrl)
                val statusPrefix = responseCode?.let { code ->
                    if (description.contains("HTTP $code")) "" else "HTTP $code; "
                }.orEmpty()
                state.fail("$statusPrefix$description (URL: $safeUrl)")
            }
            state.activeCall = null
            notifyProgress(force = true)
        } finally {
            state.activeCall = null
        }
    }

    private fun responseValidator(response: Response): Pair<String?, String?> {
        val etag = response.header("ETag")?.trim()
        if (!etag.isNullOrEmpty() && !etag.startsWith("W/", ignoreCase = true)) return "ETag" to etag
        val modified = response.header("Last-Modified")?.trim()
        return if (modified.isNullOrEmpty()) null to null else "Last-Modified" to modified
    }

    private fun parseContentRange(header: String?): ContentRange? {
        val match = header?.trim()?.let(contentRangePattern::matchEntire) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val totalText = match.groupValues[3]
        val total = if (totalText == "*") null else totalText.toLongOrNull() ?: return null
        return ContentRange(start, end, total)
    }

    private fun readResumeMetadata(file: File): ResumeMetadata? {
        return try {
            if (!file.isFile) return null
            val properties = Properties()
            FileInputStream(file).use { properties.load(it) }
            ResumeMetadata(
                urlFingerprint = properties.getProperty("urlFingerprint") ?: return null,
                validatorHeader = properties.getProperty("validatorHeader") ?: return null,
                validatorValue = properties.getProperty("validatorValue") ?: return null,
                totalBytes = properties.getProperty("totalBytes")?.toLongOrNull() ?: -1L
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun writeResumeMetadata(file: File, metadata: ResumeMetadata) {
        val temp = File(file.parentFile, file.name + ".tmp")
        val properties = Properties().apply {
            setProperty("urlFingerprint", metadata.urlFingerprint)
            setProperty("validatorHeader", metadata.validatorHeader)
            setProperty("validatorValue", metadata.validatorValue)
            setProperty("totalBytes", metadata.totalBytes.toString())
        }
        FileOutputStream(temp).use { properties.store(it, "NewCatroid resumable download metadata") }
        moveAtomically(temp, file)
    }

    private fun urlFingerprint(url: String): String = MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun safeUrlForError(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return "(invalid URL)"
        val portPart = if ((parsed.scheme == "http" && parsed.port == 80) ||
            (parsed.scheme == "https" && parsed.port == 443)) "" else ":${parsed.port}"
        return "${parsed.scheme}://${parsed.host}$portPart${parsed.encodedPath}"
    }

    private fun readErrorBody(body: ResponseBody?): String {
        if (body == null) return ""
        return try {
            val bytes = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(1024)
                var remaining = 4096
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (read < 0) break
                    bytes.write(buffer, 0, read)
                    remaining -= read
                }
            }
            bytes.toString(StandardCharsets.UTF_8.name())
                .replace(Regex("[\\r\\n\\t]+"), " ")
                .replace(Regex("\\s{2,}"), " ")
                .trim()
                .take(400)
        } catch (e: Exception) {
            ""
        }
    }

    private fun moveAtomically(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(), destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        } catch (unsupported: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun deleteQuietly(file: File) {
        if (file.exists()) file.delete()
    }

    private fun notifyDownloadState(state: DownloadState, callback: ((DownloadState) -> Unit)?) {
        try {
            callback?.invoke(state)
        } catch (e: Exception) {
            state.progressCallbackError = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        }
    }

    fun createRequest(id: String, method: String, url: String) {
        activeRequests[id] = RequestState(id, method.uppercase(), url)
        completedResponses.remove(id)
    }

    fun setConfig(id: String, type: String, key: String, value: String) {
        val state = activeRequests[id] ?: return
        when (type.lowercase()) {
            "header" -> state.headers[key] = value
            "query" -> state.queryParams[key] = value
            "timeout_connect" -> state.connectTimeoutMs = value.toLongOrNull() ?: 10_000
            "timeout_read" -> state.readTimeoutMs = value.toLongOrNull() ?: 10_000
            "timeout_write" -> state.writeTimeoutMs = value.toLongOrNull() ?: 10_000
            "follow_redirects" -> state.followRedirects = value.toBoolean()
            "trust_all_certs" -> state.trustAllCerts = value.toBoolean()
            "use_cookies" -> state.useCookies = value.toBoolean()
            "proxy_host" -> state.proxyHost = value
            "proxy_port" -> state.proxyPort = value.toIntOrNull()
        }
    }

    private fun sanitizeUrl(urlStr: String): String {
        val withEscapedSpaces = urlStr.replace(" ", "%20")
        val sb = StringBuilder()
        for (char in withEscapedSpaces) {
            if (char.code > 127) {
                sb.append(java.net.URLEncoder.encode(char.toString(), "UTF-8"))
            } else {
                sb.append(char)
            }
        }
        return sb.toString()
    }


    fun setBodyText(id: String, body: String, contentType: String) {
        val state = activeRequests[id] ?: return
        state.bodyText = body
        state.bodyContentType = contentType
    }

    fun attachFile(id: String, file: File, partName: String, mimeType: String) {
        val state = activeRequests[id] ?: return
        state.uploadFile = file
        state.uploadFilePartName = partName
        state.uploadFileMimeType = if (mimeType.trim().isEmpty()) "application/octet-stream" else mimeType
    }

    fun executeRequest(id: String, onComplete: () -> Unit) {
        val state = activeRequests[id]
        if (state == null) {
            completedResponses[id] = ResponseState(
                code = -1,
                headers = emptyMap(),
                bodyBytes = "Request failed: Request ID '$id' was not created. Check for typos!".toByteArray()
            )
            onComplete()
            return
        }

        completedResponses.remove(id)

        if (!state.headers.containsKey("User-Agent")) {
            state.headers["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }

        val clientBuilder = baseClient.newBuilder()
            .connectTimeout(state.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(state.readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(state.writeTimeoutMs, TimeUnit.MILLISECONDS)
            .followRedirects(state.followRedirects)
            .followSslRedirects(state.followRedirects)

        if (state.useCookies) {
            clientBuilder.cookieJar(cookieJar)
        }

        if (state.trustAllCerts) {
            try {
                val trustAllCerts = arrayOf<X509TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                })
                val sslContext = SSLContext.getInstance("SSL")
                sslContext.init(null, trustAllCerts, SecureRandom())
                clientBuilder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0])
                clientBuilder.hostnameVerifier { _, _ -> true }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (!state.proxyHost.isNullOrEmpty() && state.proxyPort != null) {
            val proxyAddr = InetSocketAddress(state.proxyHost, state.proxyPort!!)
            clientBuilder.proxy(Proxy(Proxy.Type.HTTP, proxyAddr))
        }

        val client = clientBuilder.build()

        val sanitizedUrl = sanitizeUrl(state.url)
        val urlBuilder = sanitizedUrl.toHttpUrlOrNull()?.newBuilder()

        if (urlBuilder == null) {
            completedResponses[id] = ResponseState(
                code = -1,
                headers = emptyMap(),
                bodyBytes = "Request failed: Invalid URL '$sanitizedUrl'".toByteArray()
            )
            onComplete()
            return
        }

        state.queryParams.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }
        val finalUrl = urlBuilder.build()

        var requestBody: RequestBody? = null
        if (state.uploadFile != null && state.uploadFile!!.exists()) {
            val mediaType = state.uploadFileMimeType.toMediaTypeOrNull()
            val fileBody = state.uploadFile!!.asRequestBody(mediaType)
            val multipartBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(state.uploadFilePartName, state.uploadFile!!.name, fileBody)

            state.bodyText?.let { multipartBuilder.addFormDataPart("payload", it) }
            requestBody = multipartBuilder.build()
        } else if (state.bodyText != null) {
            requestBody = state.bodyText!!.toRequestBody(state.bodyContentType.toMediaTypeOrNull())
        } else if (state.method != "GET" && state.method != "HEAD") {
            requestBody = "".toRequestBody(null)
        }

        val request = Request.Builder()
            .url(finalUrl)
            .method(state.method, requestBody)
            .apply {
                state.headers.forEach { (k, v) -> addHeader(k, v) }
            }
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val headersMap = mutableMapOf<String, String>()
                for (name in response.headers.names()) {
                    headersMap[name] = response.header(name) ?: ""
                }

                val bodyBytes = response.body?.bytes()

                completedResponses[id] = ResponseState(
                    code = response.code,
                    headers = headersMap,
                    bodyBytes = bodyBytes
                )
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            completedResponses[id] = ResponseState(
                code = -1,
                headers = emptyMap(),
                bodyBytes = "Request failed: ${e.localizedMessage}".toByteArray()
            )
        } finally {
            onComplete()
        }
    }

    fun saveResponseToFile(id: String, destinationFile: File): Boolean {
        val response = completedResponses[id] ?: return false
        val bytes = response.bodyBytes ?: return false
        return try {
            FileOutputStream(destinationFile).use { fos ->
                fos.write(bytes)
            }
            true
        } catch (e: IOException) {
            e.printStackTrace()
            false
        }
    }

    fun getResponseText(id: String): String {
        return completedResponses[id]?.bodyString ?: ""
    }

    fun getResponseCode(id: String): Int {
        return completedResponses[id]?.code ?: -1
    }

    fun getResponseHeader(id: String, headerName: String): String {
        return completedResponses[id]?.headers?.get(headerName) ?: ""
    }
}
