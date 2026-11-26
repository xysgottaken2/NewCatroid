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
import org.json.JSONObject
import java.io.File

/**
 * Everything a launcher project can configure with blocks.
 *
 * The object is mutable on purpose: blocks write into the shared instance that
 * [PcGameManager] keeps, and the same instance is serialised to
 * `<project>/files/pcgame/config.json` so a launcher remembers its settings
 * between sessions.
 */
data class PcGameConfig(
    /** Folder that contains the game, usually `/sdcard/Games/GTAV`. */
    var gameDir: String = "",
    /** Executable relative to [gameDir] (or an absolute path inside it). */
    var executable: String = "",
    /** Extra command line arguments for the executable. */
    var arguments: String = "",
    /** Graphics driver pack reference, `system` to use the device driver. */
    var graphicsDriver: String = "system",
    /** D3D translation layer pack reference (`dxvk`, `vkd3d`, `none`). */
    var d3dLayer: String = "",
    /** Wine + Box64 runtime pack reference. */
    var runtime: String = "auto",
    /** Optional explicit rootfs directory (otherwise the imported rootfs pack). */
    var containerDir: String = "",
    var resolutionWidth: Int = 1280,
    var resolutionHeight: Int = 720,
    var dpi: Int = 96,
    var windowsVersion: String = "win10",
    /** Run the game inside a Wine virtual desktop of the configured size. */
    var virtualDesktop: Boolean = true,
    /** VNC port the embedded X server is reachable on (inside the container). */
    var displayPort: Int = 5901,
    var startXServer: Boolean = true,
    var startVncServer: Boolean = true,
    var xServerCommand: String = "Xvfb :0 -screen 0 %RES%x24 -nolisten tcp",
    var vncCommand: String = "x11vnc -display :0 -rfbport %PORT% -nopw -forever -shared -noxdamage -repeat -quiet",
    var wineCommand: String = "wine",
    var box64Command: String = "box64",
    var extraEnv: MutableMap<String, String> = LinkedHashMap(),
    var touchProfile: String = "gamepad",
    var showDisplay: Boolean = true,
    /** `auto`, `linker`, `direct` or `sh` — how the proot binary is executed. */
    var execMode: String = "auto",
    var wineDebug: String = "-all",
    var audioDriver: String = "pulse",
    /** 0 keeps the default of the runtime. */
    var cpuCores: Int = 0,
    var dxvkHud: Boolean = false,
    /** Free form extra commands appended to the generated start script. */
    var extraStartupCommands: String = ""
) {

    fun copyConfig(): PcGameConfig = copy(extraEnv = LinkedHashMap(extraEnv))

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("gameDir", gameDir)
        json.put("executable", executable)
        json.put("arguments", arguments)
        json.put("graphicsDriver", graphicsDriver)
        json.put("d3dLayer", d3dLayer)
        json.put("runtime", runtime)
        json.put("containerDir", containerDir)
        json.put("resolutionWidth", resolutionWidth)
        json.put("resolutionHeight", resolutionHeight)
        json.put("dpi", dpi)
        json.put("windowsVersion", windowsVersion)
        json.put("virtualDesktop", virtualDesktop)
        json.put("displayPort", displayPort)
        json.put("startXServer", startXServer)
        json.put("startVncServer", startVncServer)
        json.put("xServerCommand", xServerCommand)
        json.put("vncCommand", vncCommand)
        json.put("wineCommand", wineCommand)
        json.put("box64Command", box64Command)
        json.put("touchProfile", touchProfile)
        json.put("showDisplay", showDisplay)
        json.put("execMode", execMode)
        json.put("wineDebug", wineDebug)
        json.put("audioDriver", audioDriver)
        json.put("cpuCores", cpuCores)
        json.put("dxvkHud", dxvkHud)
        json.put("extraStartupCommands", extraStartupCommands)
        val envJson = JSONObject()
        for ((key, value) in extraEnv) {
            envJson.put(key, value)
        }
        json.put("extraEnv", envJson)
        return json
    }

    fun describe(): String {
        val builder = StringBuilder()
        builder.append("game: ").append(if (gameDir.isEmpty()) "<not set>" else gameDir)
        builder.append("\nexe: ").append(if (executable.isEmpty()) "<not set>" else executable)
        builder.append("\ndriver: ").append(graphicsDriver)
        builder.append("\nd3d: ").append(if (d3dLayer.isEmpty()) "none" else d3dLayer)
        builder.append("\nruntime: ").append(runtime)
        builder.append("\nresolution: ").append(resolutionWidth).append("x").append(resolutionHeight)
        builder.append("\nenv overrides: ").append(extraEnv.size)
        return builder.toString()
    }

    companion object {
        private const val TAG = "PcGameConfig"
        const val FILE_NAME = "config.json"

        fun fromJson(json: JSONObject): PcGameConfig {
            val config = PcGameConfig()
            config.gameDir = json.optString("gameDir", config.gameDir)
            config.executable = json.optString("executable", config.executable)
            config.arguments = json.optString("arguments", config.arguments)
            config.graphicsDriver = json.optString("graphicsDriver", config.graphicsDriver)
            config.d3dLayer = json.optString("d3dLayer", config.d3dLayer)
            config.runtime = json.optString("runtime", config.runtime)
            config.containerDir = json.optString("containerDir", config.containerDir)
            config.resolutionWidth = json.optInt("resolutionWidth", config.resolutionWidth).coerceIn(320, 7680)
            config.resolutionHeight = json.optInt("resolutionHeight", config.resolutionHeight).coerceIn(240, 4320)
            config.dpi = json.optInt("dpi", config.dpi).coerceIn(72, 480)
            config.windowsVersion = json.optString("windowsVersion", config.windowsVersion)
            config.virtualDesktop = json.optBoolean("virtualDesktop", config.virtualDesktop)
            config.displayPort = json.optInt("displayPort", config.displayPort)
            config.startXServer = json.optBoolean("startXServer", config.startXServer)
            config.startVncServer = json.optBoolean("startVncServer", config.startVncServer)
            config.xServerCommand = json.optString("xServerCommand", config.xServerCommand)
            config.vncCommand = json.optString("vncCommand", config.vncCommand)
            config.wineCommand = json.optString("wineCommand", config.wineCommand)
            config.box64Command = json.optString("box64Command", config.box64Command)
            config.touchProfile = json.optString("touchProfile", config.touchProfile)
            config.showDisplay = json.optBoolean("showDisplay", config.showDisplay)
            config.execMode = json.optString("execMode", config.execMode)
            config.wineDebug = json.optString("wineDebug", config.wineDebug)
            config.audioDriver = json.optString("audioDriver", config.audioDriver)
            config.cpuCores = json.optInt("cpuCores", config.cpuCores)
            config.dxvkHud = json.optBoolean("dxvkHud", config.dxvkHud)
            config.extraStartupCommands = json.optString("extraStartupCommands", config.extraStartupCommands)

            val envJson = json.optJSONObject("extraEnv")
            if (envJson != null) {
                val env = LinkedHashMap<String, String>()
                val keys = envJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    env[key] = envJson.optString(key, "")
                }
                config.extraEnv = env
            }
            return config
        }

        fun load(projectFolder: File?): PcGameConfig? {
            val dir = projectFolder ?: return null
            val file = File(dir, FILE_NAME)
            if (!file.isFile) return null
            return try {
                fromJson(JSONObject(file.readText()))
            } catch (e: Exception) {
                Log.w(TAG, "Cannot read ${file.absolutePath}: ${e.message}")
                null
            }
        }

        fun save(projectFolder: File?, config: PcGameConfig): Boolean {
            val dir = projectFolder ?: return false
            val file = File(dir, FILE_NAME)
            return try {
                file.writeText(config.toJson().toString(2))
                true
            } catch (e: Exception) {
                Log.w(TAG, "Cannot write ${file.absolutePath}: ${e.message}")
                false
            }
        }
    }
}
