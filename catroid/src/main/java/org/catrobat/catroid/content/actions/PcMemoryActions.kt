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
import com.badlogic.gdx.scenes.scene2d.actions.TemporalAction
import org.catrobat.catroid.content.Scope
import org.catrobat.catroid.formulaeditor.Formula
import org.catrobat.catroid.formulaeditor.UserVariable
import org.catrobat.catroid.pcgame.PcGameManager
import org.catrobat.catroid.utils.DeviceMemory

/**
 * Blocks that report the RAM of the device into a variable.
 *
 * They exist because the PC layer needs a cheap "can this phone run that game"
 * question before a launch: Wine + Box64 + DXVK need a lot of memory, and a
 * launcher project can refuse to start instead of dying in the middle of a load
 * screen. The same numbers are also available as formula sensors
 * (`device RAM total (MB)`, `device RAM free (MB)`, ...), so a project can either
 * use these blocks or build its own check.
 */

/**
 * "Set [variable] to device memory [kind]".
 *
 * [kind] accepts `total`, `available` (free right now), `used`, `percent`,
 * `app` (the heap limit of NewCatroid itself) and `low` (1 when Android says the
 * device is running out of memory, otherwise 0).
 */
class PcDeviceMemoryAction : TemporalAction() {

    var scope: Scope? = null
    var kind: Formula? = null
    var userVariable: UserVariable? = null

    override fun update(percent: Float) {
        val requested = interpret(kind, "total")
        val snapshot = DeviceMemory.snapshot()
        val value = DeviceMemory.valueOf(requested, snapshot)
        userVariable?.value = value
        val message = "[newcatroid] memory ${DeviceMemory.normalizeKind(requested)} = " +
            "%s (%s)".format(value, snapshot.describe())
        PcGameManager.appendLog(message)
        Log.i(TAG, message)
    }

    private fun interpret(formula: Formula?, fallback: String): String {
        return try {
            formula?.interpretString(scope)?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
        } catch (e: Exception) {
            fallback
        }
    }

    override fun reset() {
        super.reset()
        scope = null
        kind = null
        userVariable = null
    }

    companion object {
        private const val TAG = "PcDeviceMemoryAction"
    }
}

/**
 * "Set [variable] to YES if the device has at least [MB] MB of RAM".
 *
 * [mode] decides what is compared - `total` (only the hardware), `free` (only
 * what is free right now) or `both` (default). The variable ends up holding the
 * text `YES` or `NO`, so it can be compared in an ordinary `if` brick, and the
 * numbers behind the answer are written to the PC log.
 */
class PcRamCheckAction : TemporalAction() {

    var scope: Scope? = null
    var requiredMb: Formula? = null
    var mode: Formula? = null
    var userVariable: UserVariable? = null

    override fun update(percent: Float) {
        val required = try {
            requiredMb?.interpretDouble(scope) ?: 0.0
        } catch (e: Exception) {
            0.0
        }
        val modeValue = try {
            mode?.interpretString(scope)?.trim() ?: "both"
        } catch (e: Exception) {
            "both"
        }
        val snapshot = DeviceMemory.snapshot()
        val needed = required.toInt()
        val enough = DeviceMemory.isEnough(needed, modeValue)
        val answer = if (enough) "YES" else "NO"
        userVariable?.value = answer
        val message = "[newcatroid] RAM check: needs $needed MB " +
            "(${DeviceMemory.normalizeMode(modeValue)}) -> $answer; ${snapshot.describe()}"
        PcGameManager.appendLog(message)
        Log.i(TAG, message)
    }

    override fun reset() {
        super.reset()
        scope = null
        requiredMb = null
        mode = null
        userVariable = null
    }

    companion object {
        private const val TAG = "PcRamCheckAction"
    }
}
