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
import com.badlogic.gdx.scenes.scene2d.Action
import org.catrobat.catroid.content.Scope
import org.catrobat.catroid.content.localweb.LocalHttpServer
import org.catrobat.catroid.formulaeditor.Formula

/**
 * Starts the embedded local HTTP server (see [LocalHttpServer]).
 *
 * The blocking bind happens on a background thread; the action only finishes
 * once startup has completed or failed, so scripts never continue before the
 * server state is known. Failures are reported through
 * `LocalHttpServer.lastError` (readable with the "local HTTP server error"
 * reporter) and never crash the app.
 */
class StartHttpServerAction : Action() {
    var scope: Scope? = null
    var port: Formula? = null
    var bindAddress: Formula? = null

    private var started = false

    @Volatile
    private var finished = false

    override fun act(delta: Float): Boolean {
        if (!started) {
            started = true
            startServer()
        }
        return finished
    }

    private fun startServer() {
        val portValue = try {
            port?.interpretInteger(scope) ?: DEFAULT_PORT
        } catch (e: Exception) {
            Log.w(TAG, "Unable to interpret port formula", e)
            DEFAULT_PORT
        }
        val address = try {
            bindAddress?.interpretString(scope)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        } ?: LocalHttpServer.DEFAULT_BIND

        LocalHttpServer.startAsync(portValue, address) { success ->
            if (!success) {
                Log.w(TAG, "Local HTTP server failed to start: ${LocalHttpServer.lastError}")
            }
            finished = true
        }
    }

    override fun restart() {
        super.restart()
        started = false
        finished = false
    }

    override fun reset() {
        super.reset()
        started = false
        finished = false
        scope = null
        port = null
        bindAddress = null
    }

    companion object {
        private const val TAG = "StartHttpServerAction"
        const val DEFAULT_PORT = 8080
    }
}
