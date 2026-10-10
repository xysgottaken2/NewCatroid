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

package org.catrobat.catroid.content.actions

import android.util.Log
import com.badlogic.gdx.scenes.scene2d.actions.TemporalAction
import org.catrobat.catroid.content.Scope
import org.catrobat.catroid.content.localweb.LocalHttpServer
import org.catrobat.catroid.formulaeditor.Formula

/**
 * Configures which directory the embedded HTTP server serves. Accepts the
 * value stored by the "Select Directory" block (a `content://` document tree
 * URI) or a plain filesystem path, plus an optional URL prefix.
 *
 * This only *configures* the server; it does not start it, so scripts control
 * the order: host directory first, then start the server.
 */
class HostDirectoryAction : TemporalAction() {
    var scope: Scope? = null
    var directory: Formula? = null
    var urlPrefix: Formula? = null

    override fun update(percent: Float) {
        val directoryValue = try {
            directory?.interpretString(scope)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to interpret directory formula", e)
            null
        }
        LocalHttpServer.setDocumentRoot(directoryValue)

        val prefixValue = try {
            urlPrefix?.interpretString(scope)
        } catch (e: Exception) {
            null
        }
        if (prefixValue != null) {
            LocalHttpServer.setUrlPrefix(prefixValue)
        }
    }

    companion object {
        private const val TAG = "HostDirectoryAction"
    }
}
