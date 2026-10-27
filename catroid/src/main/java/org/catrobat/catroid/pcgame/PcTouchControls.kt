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

package org.catrobat.catroid.pcgame

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One on screen control. Positions and sizes are normalised (0..1 of the
 * virtual screen) so a layout looks the same on every device, the same scheme
 * the WebView touch controls of the local server use.
 *
 * Kinds:
 *  * `joystick` — round base with a knob, emits up to four keys
 *    (`code` = "w,a,s,d" for up, left, down, right),
 *  * `look` — drag area for mouse look, optional tap to click,
 *  * `button` — one button, `code` may combine keys with `+` ("ctrl+shift"),
 *    `type` = "key" or "mouse" (`button` = 0 left, 1 middle, 2 right).
 */
data class PcTouchControl(
    var id: String = "control",
    var kind: String = "button",
    var x: Float = 0.5f,
    var y: Float = 0.5f,
    var size: Float = 0.16f,
    var label: String = "BTN",
    var type: String = "key",
    var code: String = "space",
    var button: Int = 0,
    var opacity: Float = 0.5f
) {
    fun keyList(): List<String> {
        if (type.equals("mouse", ignoreCase = true)) return emptyList()
        return code.split('+')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    fun sanitized(): PcTouchControl {
        val copy = this.copy()
        copy.x = copy.x.coerceIn(0f, 1f)
        copy.y = copy.y.coerceIn(0f, 1f)
        val (minimum, maximum) = sizeBounds(copy.kind)
        copy.size = copy.size.coerceIn(minimum, maximum)
        copy.opacity = copy.opacity.coerceIn(0.15f, 1f)
        copy.button = copy.button.coerceIn(0, 4)
        copy.label = copy.label.take(16)
        copy.code = copy.code.take(64)
        if (!copy.kind.equals("joystick", true) && !copy.kind.equals("look", true)) {
            copy.kind = "button"
        }
        if (!copy.type.equals("mouse", true)) copy.type = "key"
        return copy
    }

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("kind", kind)
        json.put("x", x.toDouble())
        json.put("y", y.toDouble())
        json.put("size", size.toDouble())
        json.put("label", label)
        json.put("type", type)
        json.put("code", code)
        json.put("button", button)
        json.put("opacity", opacity.toDouble())
        return json
    }

    companion object {
        fun sizeBounds(kind: String): Pair<Float, Float> {
            return when {
                kind.equals("joystick", true) -> 0.18f to 0.44f
                kind.equals("look", true) -> 0.32f to 0.76f
                else -> 0.11f to 0.30f
            }
        }

        fun fromJson(json: JSONObject): PcTouchControl {
            return PcTouchControl(
                id = json.optString("id", "control"),
                kind = json.optString("kind", "button"),
                x = json.optDouble("x", 0.5).toFloat(),
                y = json.optDouble("y", 0.5).toFloat(),
                size = json.optDouble("size", 0.16).toFloat(),
                label = json.optString("label", "BTN"),
                type = json.optString("type", "key"),
                code = json.optString("code", "space"),
                button = json.optInt("button", 0),
                opacity = json.optDouble("opacity", 0.5).toFloat()
            ).sanitized()
        }
    }
}

/**
 * A complete overlay layout, stored per project in
 * `<project>/files/pcgame/touch_<profile>.json` so it travels with the project
 * and can be edited with blocks or by hand.
 */
class PcTouchProfile(
    var name: String = "gamepad",
    var opacity: Float = 0.55f,
    val controls: MutableList<PcTouchControl> = ArrayList()
) {

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("name", name)
        json.put("version", 1)
        json.put("opacity", opacity.toDouble())
        val array = JSONArray()
        for (control in controls) array.put(control.toJson())
        json.put("controls", array)
        return json
    }

    fun save(folder: File?) {
        val dir = folder ?: return
        try {
            PcTouchProfile.fileFor(dir, name).writeText(toJson().toString(2))
        } catch (e: Exception) {
            Log.w(TAG, "Cannot save touch profile: ${e.message}")
        }
    }

    fun findControl(id: String): PcTouchControl? = controls.firstOrNull { it.id == id }

    fun sanitized(): PcTouchProfile {
        val clean = ArrayList<PcTouchControl>()
        val seen = HashSet<String>()
        for (control in controls) {
            val item = control.sanitized()
            if (item.id.isBlank()) continue
            if (!seen.add(item.id)) continue
            clean.add(item)
            if (clean.size >= MAX_CONTROLS) break
        }
        controls.clear()
        controls.addAll(clean)
        opacity = opacity.coerceIn(0.15f, 1f)
        return this
    }

    companion object {
        private const val TAG = "PcTouchProfile"
        const val MAX_CONTROLS = 24
        const val FILE_PREFIX = "touch_"

        fun fileFor(folder: File, name: String): File {
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "gamepad" }
            return File(folder, FILE_PREFIX + safe + ".json")
        }

        /**
         * Loads a profile. Order of precedence: a saved JSON file, then a built
         * in preset with the same name, then the gamepad preset.
         */
        fun load(folder: File?, name: String): PcTouchProfile {
            val requested = name.trim().ifEmpty { "gamepad" }
            if (folder != null) {
                val file = fileFor(folder, requested)
                if (file.isFile) {
                    try {
                        return fromJson(JSONObject(file.readText())).sanitized()
                    } catch (e: Exception) {
                        Log.w(TAG, "Cannot read ${file.absolutePath}: ${e.message}")
                    }
                }
            }
            return fromPreset(requested, requested).sanitized()
        }

        fun fromJson(json: JSONObject): PcTouchProfile {
            val profile = PcTouchProfile(
                name = json.optString("name", "gamepad"),
                opacity = json.optDouble("opacity", 0.55).toFloat()
            )
            val array = json.optJSONArray("controls")
            if (array != null) {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    profile.controls.add(PcTouchControl.fromJson(item))
                }
            }
            return profile
        }

        fun presets(): List<String> = listOf("gamepad", "fps", "platformer", "empty")

        /** Builds a preset; unknown names fall back to `gamepad`. */
        fun fromPreset(name: String, preset: String): PcTouchProfile {
            val profile = PcTouchProfile(name = name.ifEmpty { "gamepad" }, opacity = 0.55f)
            when (preset.trim().lowercase()) {
                "empty" -> Unit
                "platformer" -> addPlatformer(profile)
                "fps" -> addFps(profile)
                "gamepad", "" -> addGamepad(profile)
                else -> addGamepad(profile)
            }
            return profile
        }

        private fun addGamepad(profile: PcTouchProfile) {
            profile.controls.add(
                PcTouchControl(
                    id = "move", kind = "joystick", x = 0.16f, y = 0.76f, size = 0.32f,
                    label = "WASD", type = "key", code = "w,a,s,d", opacity = 0.45f
                )
            )
            profile.controls.add(
                PcTouchControl(
                    id = "look", kind = "look", x = 0.70f, y = 0.42f, size = 0.56f,
                    label = "LOOK", type = "mouse", code = "", button = 0, opacity = 0.16f
                )
            )
            profile.controls.add(
                PcTouchControl(
                    id = "jump", kind = "button", x = 0.86f, y = 0.46f, size = 0.17f,
                    label = "SPACE", type = "key", code = "space", opacity = 0.5f
                )
            )
            profile.controls.add(
                PcTouchControl(
                    id = "sprint", kind = "button", x = 0.86f, y = 0.62f, size = 0.17f,
                    label = "SHIFT", type = "key", code = "shift", opacity = 0.5f
                )
            )
            profile.controls.add(
                PcTouchControl(
                    id = "crouch", kind = "button", x = 0.86f, y = 0.78f, size = 0.17f,
                    label = "CTRL", type = "key", code = "ctrl", opacity = 0.5f
                )
            )
            profile.controls.add(
                PcTouchControl(
                    id = "fire", kind = "button", x = 0.66f, y = 0.70f, size = 0.17f,
                    label = "LMB", type = "mouse", code = "", button = 0, opacity = 0.5f
                )
            )
            profile.controls.add(
                PcTouchControl(
                    id = "menu", kind = "button", x = 0.06f, y = 0.10f, size = 0.12f,
                    label = "ESC", type = "key", code = "esc", opacity = 0.45f
                )
            )
            profile.controls.add(
                PcTouchControl(
                    id = "confirm", kind = "button", x = 0.20f, y = 0.10f, size = 0.12f,
                    label = "ENTER", type = "key", code = "enter", opacity = 0.45f
                )
            )
            profile.controls.add(
                PcTouchControl(
                    id = "inventory", kind = "button", x = 0.34f, y = 0.10f, size = 0.12f,
                    label = "TAB", type = "key", code = "tab", opacity = 0.45f
                )
            )
        }

        private fun addFps(profile: PcTouchProfile) {
            addGamepad(profile)
            profile.controls.add(
                PcTouchControl(
                    id = "altfire", kind = "button", x = 0.66f, y = 0.86f, size = 0.17f,
                    label = "RMB", type = "mouse", code = "", button = 2, opacity = 0.5f
                )
            )
            profile.controls.add(
                PcTouchControl(
                    id = "reload", kind = "button", x = 0.50f, y = 0.78f, size = 0.14f,
                    label = "R", type = "key", code = "r", opacity = 0.5f
                )
            )
        }

        private fun addPlatformer(profile: PcTouchProfile) {
            profile.controls.add(
                PcTouchControl(
                    id = "left", kind = "button", x = 0.10f, y = 0.84f, size = 0.16f,
                    label = "<", type = "key", code = "left", opacity = 0.5f
                )
            )
            profile.controls.add(
                PcTouchControl(
                    id = "right", kind = "button", x = 0.30f, y = 0.84f, size = 0.16f,
                    label = ">", type = "key", code = "right", opacity = 0.5f
                )
            )
            profile.controls.add(
                PcTouchControl(
                    id = "jump", kind = "button", x = 0.86f, y = 0.84f, size = 0.18f,
                    label = "SPACE", type = "key", code = "space", opacity = 0.5f
                )
            )
            profile.controls.add(
                PcTouchControl(
                    id = "run", kind = "button", x = 0.86f, y = 0.64f, size = 0.16f,
                    label = "SHIFT", type = "key", code = "shift", opacity = 0.5f
                )
            )
            profile.controls.add(
                PcTouchControl(
                    id = "action", kind = "button", x = 0.70f, y = 0.72f, size = 0.15f,
                    label = "ENTER", type = "key", code = "enter", opacity = 0.5f
                )
            )
        }
    }
}
