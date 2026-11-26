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
 * http://developer.catrobat.org/license_additional_term
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.catrobat.catroid.content.actions

import java.io.File

/** Downloads a URL into the current project's files directory. */
class FilesUrlAction : DownloadFileAction() {
    override fun resolveDestination(fileName: String): File {
        val projectFiles = scope?.project?.filesDir?.canonicalFile
            ?: throw IllegalStateException("No current project is available for the download")
        val normalizedName = fileName.replace('\\', '/').trim()
        if (normalizedName.isEmpty() || normalizedName.startsWith('/')) {
            throw IllegalArgumentException("The destination must be a relative project file path")
        }
        val destination = File(projectFiles, normalizedName).canonicalFile
        val projectPrefix = projectFiles.path.trimEnd(File.separatorChar) + File.separator
        if (!destination.path.startsWith(projectPrefix)) {
            throw IllegalArgumentException("The destination must stay inside the project's files directory")
        }
        return destination
    }

    override fun defaultFileName(): String = "fileFromUrl.jpg"
}
