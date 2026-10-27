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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.catrobat.catroid.content.actions

import android.widget.Toast
import com.badlogic.gdx.scenes.scene2d.actions.TemporalAction
import org.catrobat.catroid.CatroidApplication
import org.catrobat.catroid.content.Scope
import org.catrobat.catroid.formulaeditor.Formula
import org.catrobat.catroid.formulaeditor.UserVariable
import org.catrobat.catroid.pcgame.PcGameManager
import org.catrobat.catroid.pcgame.PcLauncherDialog
import org.catrobat.catroid.pcgame.PcTouchControl
import org.catrobat.catroid.stage.StageActivity

/**
 * Runtime blocks of the PC compatibility layer: launch, stop, log binding,
 * environment self check, the built-in launcher and the touch layout editor API.
 */

class PcLaunchGameAction : TemporalAction() {
    var scope: Scope? = null
    var executable: Formula? = null
    var arguments: Formula? = null

    /** Set by the "launch with touch controls" brick. */
    var withTouchControls: Boolean = false
    var touchProfile: Formula? = null

    override fun update(percent: Float) {
        val context = CatroidApplication.getAppContext()
        if (withTouchControls) {
            val profileName = touchProfile?.interpretString(scope) ?: ""
            if (profileName.isNotBlank()) {
                PcGameManager.setTouchProfileName(profileName)
            }
        }
        val exe = executable?.interpretString(scope) ?: ""
        val args = arguments?.interpretString(scope)
        val result = PcGameManager.launch(context, exe, args, withTouchControls)
        if (result.startsWith("Cannot") || result.startsWith("No ") || result.startsWith("Launch")) {
            val activity = StageActivity.activeStageActivity.get()
            if (activity != null) {
                activity.runOnUiThread {
                    Toast.makeText(activity, result, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

class PcStopGameAction : TemporalAction() {
    override fun update(percent: Float) {
        PcGameManager.stop()
    }
}

class PcBindGameLogAction : TemporalAction() {
    var userVariable: UserVariable? = null

    override fun update(percent: Float) {
        PcGameManager.bindOutputVariable(userVariable)
    }
}

class PcSelfTestAction : TemporalAction() {
    var userVariable: UserVariable? = null

    override fun update(percent: Float) {
        val report = PcGameManager.selfTest(CatroidApplication.getAppContext())
        userVariable?.value = report
    }
}

class PcGameStatusAction : TemporalAction() {
    var userVariable: UserVariable? = null

    override fun update(percent: Float) {
        userVariable?.value = PcGameManager.statusLine()
    }
}

class PcShowLauncherAction : TemporalAction() {
    override fun update(percent: Float) {
        val activity = StageActivity.activeStageActivity.get() ?: return
        activity.runOnUiThread {
            PcLauncherDialog.show(activity)
        }
    }
}

class PcShowTouchEditorAction : TemporalAction() {
    var scope: Scope? = null
    var enabled: Formula? = null

    override fun update(percent: Float) {
        val value = enabled?.interpretString(scope) ?: "1"
        PcLauncherDialog.setTouchEditMode(value.toBoolean())
    }
}

/**
 * Adds or replaces one touch control.
 *
 * The [keys] formula decides the control kind:
 *  * `look` — drag area for mouse look (tap to click),
 *  * `mouse:0` … `mouse:4` — a mouse button,
 *  * `w,a,s,d` — four direction keys on a round joystick,
 *  * anything else (`space`, `shift`, `ctrl+shift`, `esc`) — a key button.
 */
class PcAddTouchControlAction : TemporalAction() {
    var scope: Scope? = null
    var label: Formula? = null
    var keys: Formula? = null
    var x: Formula? = null
    var y: Formula? = null
    var size: Formula? = null

    override fun update(percent: Float) {
        val controlLabel = label?.interpretString(scope) ?: "BTN"
        val keySpec = keys?.interpretString(scope) ?: "space"
        val positionX = (x?.interpretDouble(scope) ?: 50.0) / 100.0
        val positionY = (y?.interpretDouble(scope) ?: 50.0) / 100.0
        val controlSize = (size?.interpretDouble(scope) ?: 16.0) / 100.0

        val normalized = keySpec.trim()
        val control = PcTouchControl(
            id = controlLabel.lowercase().replace(Regex("[^a-z0-9]"), "_"),
            kind = when {
                normalized.equals("look", true) -> "look"
                normalized.contains(",") -> "joystick"
                else -> "button"
            },
            x = positionX.toFloat(),
            y = positionY.toFloat(),
            size = controlSize.toFloat(),
            label = controlLabel,
            type = if (normalized.startsWith("mouse:", true)) "mouse" else "key",
            code = if (normalized.startsWith("mouse:", true)) {
                ""
            } else {
                normalized.replace(" ", "").lowercase()
            },
            button = if (normalized.startsWith("mouse:", true)) {
                normalized.substringAfter(':').trim().toIntOrNull() ?: 0
            } else {
                0
            }
        )
        PcGameManager.addTouchControl(control)
    }
}

class PcRemoveTouchControlAction : TemporalAction() {
    var scope: Scope? = null
    var label: Formula? = null

    override fun update(percent: Float) {
        val controlLabel = label?.interpretString(scope) ?: ""
        val id = controlLabel.lowercase().replace(Regex("[^a-z0-9]"), "_")
        PcGameManager.removeTouchControl(id)
    }
}
