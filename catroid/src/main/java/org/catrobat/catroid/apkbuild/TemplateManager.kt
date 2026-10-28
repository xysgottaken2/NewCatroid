package org.catrobat.catroid.apkbuild

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * Fetches the prebuilt APK *templates* the "Build APK" dialog patches.
 *
 * A template is a complete NewCatroid APK; the builder only replaces the package
 * name, the icon, the permissions and `assets/project.zip`. That means a template
 * can only run the bricks of the source tree it was built from - an old template
 * silently drops bricks it does not know.
 *
 * There are therefore two sources:
 *
 *  1. the releases of **this** repository (`NewCatroid-Template` release), built
 *     from this branch by `.github/workflows/apk-template.yml`, so they contain
 *     the PC game blocks and the RAM sensors;
 *  2. the legacy `releases.json` list of the upstream template repository, kept as
 *     a fallback for forks that do not publish a template of their own.
 *
 * Whether a template really supports the PC blocks is not guessed from its name:
 * [templateSupportsPc] looks for the brick classes inside the downloaded APK.
 */
object TemplateManager {

    private const val TAG = "TemplateManager"

    /** Release tag used by `.github/workflows/apk-template.yml` in this repo. */
    const val PC_TEMPLATE_RELEASE_TAG = "NewCatroid-Template"

    /** Classes that only exist in a template built from this branch. */
    private val PC_MARKERS = listOf(
        "PcLaunchGameBrick",
        "PcShowLauncherBrick",
        "PcDownloadPackBrick",
        "PcRamCheckBrick"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    data class TemplateRelease(
        val tagName: String,
        val fileName: String,
        val downloadUrl: String,
        /** `fork` = built from this repository, `upstream` = the legacy list. */
        val source: String = "upstream",
        /** Hint only - the real check is [templateSupportsPc]. */
        val supportsPc: Boolean = false
    ) {
        /** Label shown in the spinner. */
        fun label(): String {
            val suffix = if (supportsPc) "  ·  PC blocks" else ""
            return "$tagName ($fileName)$suffix"
        }
    }

    suspend fun fetchAvailableTemplates(): List<TemplateRelease> = withContext(Dispatchers.IO) {
        val result = ArrayList<TemplateRelease>()
        val seen = HashSet<String>()

        // 1. our own releases (built from this branch)
        for (template in fetchForkTemplates()) {
            if (seen.add(template.downloadUrl)) result.add(template)
        }

        // 2. the legacy list, only for what we do not already have
        for (template in fetchLegacyTemplates()) {
            if (seen.add(template.downloadUrl)) result.add(template)
        }

        Log.i(TAG, "Templates: ${result.size} (${result.count { it.supportsPc }} with PC blocks)")
        return@withContext result
    }

    /**
     * Release assets of this repository whose name looks like a template APK.
     * Returns an empty list on any problem - the legacy list is tried next.
     */
    private fun fetchForkTemplates(): List<TemplateRelease> {
        val repository = "xysgottaken2/NewCatroid"
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repository/releases?per_page=20")
            .header("User-Agent", "NewCatroid")
            .header("Accept", "application/vnd.github+json")
            .build()
        val list = ArrayList<TemplateRelease>()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Cannot list the releases of $repository: HTTP ${response.code}")
                    return emptyList()
                }
                val body = response.body?.string() ?: return emptyList()
                val releases = JSONArray(body)
                for (index in 0 until releases.length()) {
                    val release = releases.optJSONObject(index) ?: continue
                    if (release.optBoolean("draft", false)) continue
                    val tag = release.optString("tag_name", "")
                    val assets = release.optJSONArray("assets") ?: continue
                    for (assetIndex in 0 until assets.length()) {
                        val asset = assets.optJSONObject(assetIndex) ?: continue
                        val name = asset.optString("name", "")
                        val url = asset.optString("browser_download_url", "")
                        if (!isTemplateAsset(name) || url.isEmpty()) continue
                        val pcBuild = isPcTemplate(name, tag)
                        list.add(
                            TemplateRelease(
                                tagName = tag,
                                fileName = name,
                                downloadUrl = url,
                                source = "fork",
                                supportsPc = pcBuild
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch the templates of $repository: ${e.message}", e)
        }
        // PC capable first, then newest version string.
        return list.sortedWith(
            compareByDescending<TemplateRelease> { it.supportsPc }
                .thenByDescending { versionKey(it.tagName) }
        )
    }

    private fun fetchLegacyTemplates(): List<TemplateRelease> {
        val request = Request.Builder()
            .url("https://raw.githubusercontent.com/Danveyd/NewCatroid-Templates/main/releases.json")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Accept", "application/json")
            .build()

        val list = mutableListOf<TemplateRelease>()
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@use
                    val releasesJson = JSONArray(body)
                    for (i in 0 until releasesJson.length()) {
                        val release = releasesJson.getJSONObject(i)
                        val fileName = release.getString("file_name")
                        list.add(
                            TemplateRelease(
                                tagName = release.getString("tag_name"),
                                fileName = fileName,
                                downloadUrl = release.getString("download_url"),
                                source = "upstream",
                                supportsPc = isPcTemplate(fileName, release.optString("tag_name"))
                            )
                        )
                    }
                } else {
                    throw IOException("HTTP error code: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch templates safely: ${e.message}", e)
        }
        return list
    }

    /** `NewCatroid_APKTemplate_pc.apk`, `NewCatroid_APKTemplate_2-2-0r.apk`, ... */
    private fun isTemplateAsset(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".apk") && lower.contains("templat")
    }

    /**
     * Name/tag hint used to sort and to preselect a template. A template is
     * considered PC capable when its name or tag says so; the authoritative check
     * is [templateSupportsPc] on the downloaded file.
     */
    private fun isPcTemplate(name: String, tag: String): Boolean {
        val lowerName = name.lowercase()
        val lowerTag = tag.lowercase()
        // The release this repository publishes is built by
        // .github/workflows/apk-template.yml, which refuses to upload an APK that
        // does not contain the PC bricks.
        if (tag == PC_TEMPLATE_RELEASE_TAG) return true
        if (lowerName.contains("_nocatroid_pc") || lowerName.contains("_pc.") ||
            lowerName.contains("_pc_") || lowerName.contains("-pc") || lowerName.contains("pcblocks")
        ) {
            return true
        }
        return lowerTag.contains("pc")
    }

    private fun versionKey(tag: String): String {
        val digits = tag.filter { it.isDigit() || it == '.' }
        return digits.padStart(8, '0')
    }

    /**
     * True when the downloaded template contains the PC game bricks.
     *
     * The classes are looked up by name in the string pool of `classes*.dex`,
     * which is how the class references of a dex file are stored - no dex parser
     * needed, and it is what XStream would fail to find when a project uses one of
     * the bricks.
     */
    fun templateSupportsPc(apk: File): Boolean {
        if (!apk.isFile) return false
        return try {
            ZipFile(apk).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.name.matches(Regex("classes[0-9]*\\.dex"))) continue
                    if (zip.getInputStream(entry).use { stream -> containsAnyMarker(stream) }) {
                        return true
                    }
                }
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cannot inspect ${apk.name}: ${e.message}", e)
            false
        }
    }

    /**
     * Streams a dex file and looks for one of the [PC_MARKERS]. The windows
     * overlap by one byte less than the longest marker, so a name that is split
     * between two chunks is still found.
     */
    private fun containsAnyMarker(input: java.io.InputStream): Boolean {
        val longest = PC_MARKERS.maxOf { it.length }
        val overlap = longest - 1
        var carry = ByteArray(0)
        val buffer = ByteArray(256 * 1024)
        val needles = PC_MARKERS.map { it.toByteArray(Charsets.US_ASCII) }
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) return false
            val window = ByteArray(carry.size + read)
            System.arraycopy(carry, 0, window, 0, carry.size)
            System.arraycopy(buffer, 0, window, carry.size, read)
            for (needle in needles) {
                if (indexOf(window, needle) >= 0) return true
            }
            carry = if (window.size > overlap) {
                window.copyOfRange(window.size - overlap, window.size)
            } else {
                window
            }
        }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        val last = haystack.size - needle.size
        outer@ for (i in 0..last) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    suspend fun downloadTemplate(
        context: Context,
        downloadUrl: String,
        fileName: String,
        onProgress: (Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val destFile = File(context.cacheDir, "templates/$fileName")
        if (destFile.exists() && destFile.length() > 0) {
            return@withContext destFile
        }
        destFile.parentFile?.mkdirs()

        val request = Request.Builder()
            .url(downloadUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Accept", "*/*")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null
                val totalBytes = body.contentLength()

                body.byteStream().use { input ->
                    FileOutputStream(destFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            if (totalBytes > 0) {
                                val progress = ((totalRead * 100) / totalBytes).toInt()
                                onProgress(progress)
                            }
                        }
                    }
                }
                return@withContext destFile
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download template", e)
            destFile.delete()
            return@withContext null
        }
    }
}
