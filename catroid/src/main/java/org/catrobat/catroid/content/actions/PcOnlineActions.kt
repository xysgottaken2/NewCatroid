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

package org.catrobat.catroid.content.actions

import android.util.Log
import android.widget.Toast
import com.badlogic.gdx.scenes.scene2d.actions.TemporalAction
import org.catrobat.catroid.content.Scope
import org.catrobat.catroid.formulaeditor.Formula
import org.catrobat.catroid.pcgame.PcGameManager
import org.catrobat.catroid.pcgame.PcOnlineInstaller
import org.catrobat.catroid.pcgame.PcOnlinePackDialog
import org.catrobat.catroid.pcgame.PcPackType
import org.catrobat.catroid.stage.StageActivity

/**
 * Blocks that fetch the PC layer components from the internet.
 *
 * Both of them end in [PcOnlineInstaller]: the archive is downloaded, imported as
 * a normal pack and selected, exactly like a pack the user copied to the phone
 * himself. Only the download part is new.
 */

/**
 * "Download PC components from the internet" - opens the catalogue with the
 * categories Drivers / DXVK / Runtime / Rootfs.
 *
 * [packType] only preselects a category, so a launcher can jump straight to the
 * Turnip list.
 */
class PcDownloadPacksAction : TemporalAction() {
    var packType: String = ""

    override fun update(percent: Float) {
        val activity = StageActivity.activeStageActivity.get() ?: return
        val type = PcPackType.fromName(packType)
        activity.runOnUiThread { PcOnlinePackDialog.show(activity, type) }
    }
}

/**
 * "Download a PC component from the link" - for anything that is not a GitHub
 * release: a mirror, a direct `dxvk-3.1.1.tar.gz` URL, an Ubuntu base tarball.
 *
 * An empty type is allowed, the archive content is then sniffed by
 * [org.catrobat.catroid.pcgame.PcPackManager.detectType].
 */
class PcDownloadLinkAction : TemporalAction() {
    var scope: Scope? = null
    var urlFormula: Formula? = null
    var typeFormula: Formula? = null

    override fun update(percent: Float) {
        val activity = StageActivity.activeStageActivity.get() ?: return
        val url = interpret(urlFormula).trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            val message = "The download needs a full web address (http:// or https://), got: '$url'"
            PcGameManager.appendLog("[newcatroid] $message")
            activity.runOnUiThread { Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
            return
        }
        val typeName = interpret(typeFormula).trim()
        val type = if (typeName.isEmpty()) null else PcPackType.fromName(typeName)
        Log.i(TAG, "Downloading $url as ${type?.label ?: "detected pack"}")
        activity.runOnUiThread {
            PcOnlineInstaller.downloadAndInstall(activity, type, url, null) { _, _ -> }
        }
    }

    private fun interpret(formula: Formula?): String {
        return try {
            formula?.interpretString(scope)?.trim() ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    companion object {
        private const val TAG = "PcDownloadLinkAction"
    }
}
