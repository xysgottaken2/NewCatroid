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

import com.badlogic.gdx.scenes.scene2d.actions.TemporalAction
import org.catrobat.catroid.content.Scope
import org.catrobat.catroid.formulaeditor.Formula
import org.catrobat.catroid.pcgame.PcGameManager

/**
 * Configuration blocks of the PC compatibility layer. They only touch the
 * launcher settings; nothing is started before a "launch game" block runs.
 */

class PcSetGameFolderAction : TemporalAction() {
    var scope: Scope? = null
    var folder: Formula? = null

    override fun update(percent: Float) {
        val value = folder?.interpretString(scope) ?: ""
        PcGameManager.setGameDir(value)
    }
}

class PcSetGraphicsDriverAction : TemporalAction() {
    var scope: Scope? = null
    var driver: Formula? = null

    override fun update(percent: Float) {
        val value = driver?.interpretString(scope) ?: "system"
        PcGameManager.setGraphicsDriver(value)
    }
}

class PcSetD3dLayerAction : TemporalAction() {
    var scope: Scope? = null
    var layer: Formula? = null

    override fun update(percent: Float) {
        val value = layer?.interpretString(scope) ?: ""
        PcGameManager.setD3dLayer(value)
    }
}

class PcSetRuntimeAction : TemporalAction() {
    var scope: Scope? = null
    var runtime: Formula? = null

    override fun update(percent: Float) {
        val value = runtime?.interpretString(scope) ?: "auto"
        PcGameManager.setRuntime(value)
    }
}

class PcSetContainerAction : TemporalAction() {
    var scope: Scope? = null
    var container: Formula? = null

    override fun update(percent: Float) {
        val value = container?.interpretString(scope) ?: ""
        PcGameManager.setContainer(value)
    }
}

class PcSetEnvAction : TemporalAction() {
    var scope: Scope? = null
    var name: Formula? = null
    var value: Formula? = null

    override fun update(percent: Float) {
        val variableName = name?.interpretString(scope) ?: ""
        val variableValue = value?.interpretString(scope) ?: ""
        PcGameManager.setEnvironmentVariable(variableName, variableValue)
    }
}

class PcClearEnvAction : TemporalAction() {
    override fun update(percent: Float) {
        PcGameManager.clearEnvironment()
    }
}

class PcSetResolutionAction : TemporalAction() {
    var scope: Scope? = null
    var width: Formula? = null
    var height: Formula? = null

    override fun update(percent: Float) {
        val targetWidth = try {
            width?.interpretInteger(scope) ?: 1280
        } catch (e: Exception) {
            1280
        }
        val targetHeight = try {
            height?.interpretInteger(scope) ?: 720
        } catch (e: Exception) {
            720
        }
        PcGameManager.setResolution(targetWidth, targetHeight)
    }
}

class PcSetTouchProfileAction : TemporalAction() {
    var scope: Scope? = null
    var profile: Formula? = null

    override fun update(percent: Float) {
        val value = profile?.interpretString(scope) ?: "gamepad"
        PcGameManager.setTouchProfileName(value)
    }
}
