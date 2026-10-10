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

/**
 * MIME type resolution for the embedded HTTP server.
 *
 * A self-contained table is used (instead of android.webkit.MimeTypeMap) so
 * that behaviour is identical on every device and unit tests can verify it
 * on the JVM. Covers HTML/CSS/JS/JSON/WebAssembly, images, fonts, audio,
 * video and other common web resources.
 */
object MimeTypes {

    const val DEFAULT = "application/octet-stream"

    private val TABLE: Map<String, String> = mapOf(
        // text & web
        "html" to "text/html",
        "htm" to "text/html",
        "css" to "text/css",
        "js" to "text/javascript",
        "mjs" to "text/javascript",
        "json" to "application/json",
        "map" to "application/json",
        "txt" to "text/plain",
        "md" to "text/markdown",
        "csv" to "text/csv",
        "xml" to "application/xml",
        "xsl" to "application/xml",
        "svg" to "image/svg+xml",
        "wasm" to "application/wasm",
        // images
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "avif" to "image/avif",
        "bmp" to "image/bmp",
        "ico" to "image/x-icon",
        // fonts
        "woff" to "font/woff",
        "woff2" to "font/woff2",
        "ttf" to "font/ttf",
        "otf" to "font/otf",
        "eot" to "application/vnd.ms-fontobject",
        // audio
        "mp3" to "audio/mpeg",
        "wav" to "audio/wav",
        "ogg" to "audio/ogg",
        "oga" to "audio/ogg",
        "flac" to "audio/flac",
        "m4a" to "audio/mp4",
        "aac" to "audio/aac",
        "opus" to "audio/opus",
        // video
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "webm" to "video/webm",
        "mkv" to "video/x-matroska",
        "mov" to "video/quicktime",
        "avi" to "video/x-msvideo",
        // documents & archives
        "pdf" to "application/pdf",
        "zip" to "application/zip",
        "gz" to "application/gzip",
        "tar" to "application/x-tar",
        "rar" to "application/vnd.rar",
        "7z" to "application/x-7z-compressed",
        "epub" to "application/epub+zip",
        "apk" to "application/vnd.android.package-archive",
        // other
        "yaml" to "application/yaml",
        "yml" to "application/yaml",
        "jsonld" to "application/ld+json",
        "bin" to "application/octet-stream",
        "dmg" to "application/x-apple-diskimage",
        "exe" to "application/vnd.microsoft.portable-executable"
    )

    /**
     * Returns the MIME type for a file name or path. Textual types get a
     * `; charset=utf-8` suffix so browsers decode them correctly.
     */
    fun forPath(path: String?): String {
        if (path.isNullOrBlank()) return DEFAULT
        val fileName = path.substringAfterLast('/').substringAfterLast('\\')
        val dotIndex = fileName.lastIndexOf('.')
        if (dotIndex <= 0 || dotIndex == fileName.length - 1) return DEFAULT
        val extension = fileName.substring(dotIndex + 1).lowercase()
        val base = TABLE[extension] ?: return DEFAULT
        return if (base.startsWith("text/") || base == "application/json" ||
            base == "application/javascript" || base == "image/svg+xml"
        ) {
            "$base; charset=utf-8"
        } else {
            base
        }
    }
}
