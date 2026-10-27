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
 * http://www.catrobat.org/license_additional_term
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.catrobat.catroid.pcgame

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Online catalogue of PC layer components.
 *
 * Instead of asking the user to find a Turnip zip, a DXVK tarball or a rootfs by
 * themselves, the PC blocks can list what a GitHub repository publishes as
 * release assets. Tapping an entry downloads it, imports it as an ordinary pack
 * (see [PcPackManager]) and selects it - the result is the same as picking a
 * local file, only without the file hunting.
 *
 * Nothing is bundled and nothing is redistributed by NewCatroid itself: the app
 * only asks the GitHub REST API what a public repository offers and then streams
 * the archive the user tapped, exactly like a browser download would.
 */

/** A public GitHub repository that publishes pack archives as release assets. */
data class PcOnlineRepo(val owner: String, val repository: String, val label: String) {
    val slug: String get() = "$owner/$repository"
}

/** One downloadable archive of a release. */
data class PcOnlineAsset(
    val name: String,
    val size: Long,
    val downloadUrl: String,
    val releaseTag: String,
    val repoSlug: String
) {
    /** One line for the list: `Turnip_v26.0.0_R8.zip · 17.2 MB · v26.0.0-rc08`. */
    fun describe(): String {
        val builder = StringBuilder(name)
        if (size > 0) builder.append("  ·  ").append(PcScriptBuilder.humanSize(size))
        if (releaseTag.isNotEmpty()) builder.append("  ·  ").append(releaseTag)
        return builder.toString()
    }

    /** Directory/id friendly name used when the pack is imported. */
    fun suggestedPackId(): String {
        val base = name
            .removeSuffix(".tar.gz").removeSuffix(".tar.xz").removeSuffix(".tar.zst")
            .removeSuffix(".tgz").removeSuffix(".txz").removeSuffix(".tzst").removeSuffix(".zst")
            .removeSuffix(".tar").removeSuffix(".zip")
        return base.ifEmpty { name }
    }
}

/** A network/API problem with a message that can be shown to the user as is. */
class PcOnlineException(message: String) : IOException(message)

/**
 * The repositories the pickers list by default.
 *
 * Every entry was checked against the GitHub API, so the lists are populated
 * out of the box. Users can add their own `owner/repo` on top (stored in
 * `files/pcgame/online_repos.json`), which is how builds that live in a fork or
 * a private mirror stay reachable.
 */
object PcOnlineCatalog {

    private const val TAG = "PcOnlineCatalog"
    private const val FILE_NAME = "online_repos.json"
    private val SLUG = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")

    fun defaults(type: PcPackType): List<PcOnlineRepo> = when (type) {
        PcPackType.GRAPHICS_DRIVER -> listOf(
            PcOnlineRepo(
                "K11MCH1",
                "AdrenoToolsDrivers",
                "K11MCH1/AdrenoToolsDrivers (Turnip + Qualcomm)"
            )
        )
        PcPackType.D3D_LAYER -> listOf(
            PcOnlineRepo("doitsujin", "dxvk", "doitsujin/dxvk (Direct3D 9/10/11)"),
            PcOnlineRepo("HansKristian-Work", "vkd3d-proton", "vkd3d-proton (Direct3D 12)"),
            PcOnlineRepo("AlpyneDreams", "d8vk", "AlpyneDreams/d8vk (Direct3D 8)")
        )
        PcPackType.RUNTIME -> listOf(
            PcOnlineRepo("ptitSeb", "box64", "ptitSeb/box64 (x86_64 emulator + x86 libs)")
        )
        PcPackType.ROOTFS -> listOf(
            PcOnlineRepo("olegos2", "termux-box", "olegos2/termux-box (Debian rootfs)")
        )
    }

    /** Repositories the user added for [type]. */
    fun custom(context: Context, type: PcPackType): List<PcOnlineRepo> {
        val array = read(context).optJSONArray(key(type)) ?: return emptyList()
        val result = ArrayList<PcOnlineRepo>()
        for (index in 0 until array.length()) {
            val slug = array.optString(index, "").trim()
            val parts = slug.split("/")
            if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
                result.add(PcOnlineRepo(parts[0], parts[1], slug))
            }
        }
        return result
    }

    /** Defaults plus the repositories the user added, without duplicates. */
    fun all(context: Context, type: PcPackType): List<PcOnlineRepo> {
        val result = ArrayList<PcOnlineRepo>()
        val seen = HashSet<String>()
        for (repo in defaults(type) + custom(context, type)) {
            if (seen.add(repo.slug.lowercase())) result.add(repo)
        }
        return result
    }

    /**
     * Adds an `owner/repo` (a full GitHub URL is accepted as well).
     * Returns `null` when the value is not a repository reference or is already
     * in the list.
     */
    fun addCustom(context: Context, type: PcPackType, value: String?): PcOnlineRepo? {
        val slug = normalizeSlug(value) ?: return null
        if (all(context, type).any { it.slug.equals(slug, ignoreCase = true) }) return null
        val json = read(context)
        val array = json.optJSONArray(key(type)) ?: JSONArray()
        array.put(slug)
        json.put(key(type), array)
        write(context, json)
        val parts = slug.split("/")
        Log.i(TAG, "Added online repository $slug for ${type.folderName}")
        return PcOnlineRepo(parts[0], parts[1], slug)
    }

    /** Removes a repository the user added. Defaults cannot be removed. */
    fun removeCustom(context: Context, type: PcPackType, slug: String): Boolean {
        val json = read(context)
        val array = json.optJSONArray(key(type)) ?: return false
        val updated = JSONArray()
        var removed = false
        for (index in 0 until array.length()) {
            val current = array.optString(index, "")
            if (current.equals(slug, ignoreCase = true)) {
                removed = true
            } else {
                updated.put(current)
            }
        }
        if (!removed) return false
        json.put(key(type), updated)
        write(context, json)
        return true
    }

    /** Accepts `owner/repo`, `github.com/owner/repo` and full release URLs. */
    fun normalizeSlug(value: String?): String? {
        val raw = value?.trim() ?: return null
        if (raw.isEmpty()) return null
        var cleaned = raw
            .removePrefix("https://").removePrefix("http://")
            .removePrefix("www.")
        if (cleaned.startsWith("github.com/")) cleaned = cleaned.removePrefix("github.com/")
        cleaned = cleaned.substringBefore("/releases")
            .substringBefore("?").substringBefore("#")
            .trimEnd('/')
            .removeSuffix(".git")
        return if (SLUG.matches(cleaned)) cleaned else null
    }

    private fun key(type: PcPackType) = type.folderName

    private fun file(context: Context) = File(PcGamePaths.root(context), FILE_NAME)

    private fun read(context: Context): JSONObject {
        val source = file(context)
        if (!source.isFile) return JSONObject()
        return try {
            JSONObject(source.readText())
        } catch (e: Exception) {
            Log.w(TAG, "Cannot read ${source.absolutePath}: ${e.message}")
            JSONObject()
        }
    }

    private fun write(context: Context, json: JSONObject) {
        try {
            file(context).writeText(json.toString(2))
        } catch (e: Exception) {
            Log.w(TAG, "Cannot save the repository list: ${e.message}")
        }
    }
}

/**
 * Minimal GitHub REST client (no extra dependency, no token).
 *
 * Only the public release list is read. Unauthenticated requests are limited to
 * 60 per hour per IP, which is plenty for browsing but is reported in a readable
 * way when it is hit - the fallback is always "download the archive in a browser
 * and import it as a pack".
 */
object PcGithubClient {

    private const val TAG = "PcGithubClient"
    private const val API = "https://api.github.com"

    /** Archive suffixes [PcPackManager] can import. */
    private val ARCHIVE_SUFFIXES = listOf(
        ".zip", ".tar", ".tar.gz", ".tgz", ".tar.xz", ".txz", ".tar.zst", ".tzst", ".zst"
    )

    /** Things a release page offers that are useless for a Wine/Box64 layer. */
    private val REJECTED_SUFFIXES = listOf(
        ".apk", ".apks", ".exe", ".msi", ".dmg", ".deb", ".rpm", ".7z", ".rar", ".xz.sig",
        ".asc", ".sig", ".sha256", ".sha512", ".md5", ".txt", ".json", ".yml", ".yaml"
    )

    private val REJECTED_WORDS = listOf(
        "sha256", "sha512", "checksum", "source", "symbols", "debug", "native"
    )

    /**
     * Lists the archives of the newest [maxReleases] releases of [repo].
     *
     * @throws PcOnlineException with a user readable message.
     */
    fun assets(
        repo: PcOnlineRepo,
        type: PcPackType,
        maxReleases: Int = 6,
        maxAssets: Int = 80
    ): List<PcOnlineAsset> {
        val url = "$API/repos/${repo.owner}/${repo.repository}/releases?per_page=$maxReleases"
        val body = getText(url)
        val releases = try {
            JSONArray(body)
        } catch (e: Exception) {
            throw PcOnlineException("Unexpected answer from GitHub for ${repo.slug}")
        }
        val result = ArrayList<PcOnlineAsset>()
        for (index in 0 until releases.length()) {
            val release = releases.optJSONObject(index) ?: continue
            if (release.optBoolean("draft", false)) continue
            val tag = release.optString("tag_name", "")
            val assets = release.optJSONArray("assets") ?: continue
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.optJSONObject(assetIndex) ?: continue
                val name = asset.optString("name", "")
                if (!isUsableAsset(name)) continue
                val downloadUrl = asset.optString("browser_download_url", "")
                if (downloadUrl.isEmpty()) continue
                result.add(
                    PcOnlineAsset(
                        name = name,
                        size = asset.optLong("size", 0L),
                        downloadUrl = downloadUrl,
                        releaseTag = tag,
                        repoSlug = repo.slug
                    )
                )
                if (result.size >= maxAssets) return order(result, type)
            }
        }
        return order(result, type)
    }

    /** Newest first, but a rootfs archive is moved in front of helper archives. */
    private fun order(assets: List<PcOnlineAsset>, type: PcPackType): List<PcOnlineAsset> {
        if (type != PcPackType.ROOTFS) return assets
        return assets.sortedByDescending { asset ->
            val name = asset.name.lowercase()
            when {
                name.contains("rootfs") -> 3
                name.contains("ubuntu") || name.contains("debian") || name.contains("alpine") -> 2
                name.contains("image") || name.contains("container") -> 1
                else -> 0
            }
        }
    }

    /** True for a release asset that [PcPackManager] can actually import. */
    fun isUsableAsset(name: String): Boolean {
        if (name.isEmpty()) return false
        val lower = name.lowercase()
        if (REJECTED_SUFFIXES.any { lower.endsWith(it) }) return false
        if (REJECTED_WORDS.any { lower.contains(it) }) return false
        return ARCHIVE_SUFFIXES.any { lower.endsWith(it) }
    }

    private fun getText(url: String): String {
        val connection = try {
            PcHttp.open(url, "application/vnd.github+json")
        } catch (e: Exception) {
            throw PcOnlineException("Cannot reach GitHub: ${e.message}")
        }
        try {
            val code = connection.responseCode
            when {
                code == 403 || code == 429 -> {
                    val remaining = connection.getHeaderField("X-RateLimit-Remaining")
                    throw PcOnlineException(
                        if (remaining == "0") {
                            "GitHub request limit reached (60 per hour). Try again later, " +
                                "or download the archive in a browser and use \"Import PC pack\"."
                        } else {
                            "GitHub refused the request (HTTP $code)."
                        }
                    )
                }
                code == 404 -> throw PcOnlineException("Repository not found: $url")
                code !in 200..299 -> throw PcOnlineException("GitHub answered with HTTP $code")
            }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            if (text.isBlank()) throw PcOnlineException("GitHub sent an empty answer")
            return text
        } catch (e: PcOnlineException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Request failed: ${e.message}")
            throw PcOnlineException("No internet connection or GitHub unreachable")
        } finally {
            connection.disconnect()
        }
    }
}

/** Shared HTTP plumbing: redirects, timeouts and a proper user agent. */
internal object PcHttp {

    const val USER_AGENT = "NewCatroid-PC-layer (+https://github.com/xysgottaken2/NewCatroid)"

    fun open(url: String, accept: String? = null): HttpURLConnection {
        var current = url
        var redirects = 0
        while (true) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20000
                readTimeout = 40000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept-Encoding", "identity")
                if (accept != null) setRequestProperty("Accept", accept)
            }
            val code = connection.responseCode
            if (code in 300..399 && redirects < 6) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrEmpty()) {
                    throw PcOnlineException("The server sent a broken redirect")
                }
                current = if (location.startsWith("http")) {
                    location
                } else {
                    URL(URL(current), location).toString()
                }
                redirects++
                continue
            }
            return connection
        }
    }
}

/**
 * Streams an archive into the app cache.
 *
 * Progress is reported with a throttle (about four updates per second) so a
 * 200 MB rootfs does not flood the UI thread, and the partial file is removed
 * when the user cancels or the connection drops.
 */
object PcDownloader {

    private const val TAG = "PcDownloader"

    /** Thrown when [isCancelled] asked to stop the transfer. */
    class CancelledException : IOException("download cancelled")

    fun downloadsDir(context: Context): File {
        val dir = File(PcGamePaths.cache(context), "downloads")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** File name of a URL, used when the caller does not know it. */
    fun fileNameFromUrl(url: String): String {
        val tail = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val cleaned = tail.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (cleaned.isEmpty()) "download_" + System.currentTimeMillis() else cleaned
    }

    fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)
        return cleaned.ifEmpty { "download_" + System.currentTimeMillis() }
    }

    /**
     * Downloads [url] into the cache and returns the finished file.
     *
     * @param onProgress called with (bytes so far, total or -1 when unknown).
     * @throws PcOnlineException on HTTP/network errors, [CancelledException]
     *         when [isCancelled] returns true.
     */
    fun download(
        context: Context,
        url: String,
        fileName: String? = null,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }
    ): File {
        val target = File(downloadsDir(context), sanitizeFileName(fileName ?: fileNameFromUrl(url)))
        val connection = try {
            PcHttp.open(url, "application/octet-stream")
        } catch (e: Exception) {
            throw PcOnlineException("Cannot start the download: ${e.message}")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw PcOnlineException("The server answered with HTTP $code")
            }
            val total = connection.contentLengthLong
            var copied = 0L
            var lastReport = 0L
            val input: InputStream = connection.inputStream
            input.use { source ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        if (isCancelled()) throw CancelledException()
                        val read = source.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 250) {
                            lastReport = now
                            onProgress(copied, total)
                        }
                    }
                    output.flush()
                }
            }
            onProgress(copied, total)
            if (copied < 64) throw PcOnlineException("The download is empty")
            if (total > 0 && copied != total) {
                throw PcOnlineException("The download is incomplete ($copied of $total bytes)")
            }
            Log.i(TAG, "Downloaded $url -> ${target.absolutePath} ($copied bytes)")
            return target
        } catch (e: CancelledException) {
            target.delete()
            throw e
        } catch (e: PcOnlineException) {
            target.delete()
            throw e
        } catch (e: Exception) {
            target.delete()
            Log.w(TAG, "Download failed: ${e.message}")
            throw PcOnlineException("Download failed: ${e.message}")
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Writes a pack into the launcher configuration.
 *
 * Shared by the local file pickers, the online list and the "download from a
 * link" block so all three select the pack in exactly the same way.
 */
object PcPackSelection {

    /** Selects [pack] for the next launch and returns what was set. */
    fun select(type: PcPackType, pack: PcPack?): String {
        return when (type) {
            PcPackType.GRAPHICS_DRIVER -> {
                PcGameManager.setGraphicsDriver(pack?.id ?: "system")
                "graphics driver"
            }
            PcPackType.D3D_LAYER -> {
                PcGameManager.setD3dLayer(pack?.id ?: "")
                "D3D layer"
            }
            PcPackType.RUNTIME -> {
                PcGameManager.setRuntime(pack?.id ?: "auto")
                "runtime"
            }
            PcPackType.ROOTFS -> {
                PcGameManager.setContainer(pack?.directory?.absolutePath ?: "")
                "container"
            }
        }
    }
}
