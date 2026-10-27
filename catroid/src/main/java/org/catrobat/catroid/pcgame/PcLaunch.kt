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

import java.io.File
import kotlin.math.max

/**
 * Host paths of everything the container needs, plus the reserved guest mount
 * points. All strings, so this class is free of Android dependencies and can be
 * inspected (and unit tested) without a device.
 */
data class PcLaunchPaths(
    val containerRoot: String,
    val prefixDir: String,
    val gameDir: String,
    val pcGameDir: String,
    val runtimeDir: String? = null,
    val driverDir: String? = null,
    val driverIcdFiles: List<String> = emptyList(),
    val d3dDir: String? = null,
    /**
     * `true` when the runtime pack is self contained and the game is started
     * without proot (no Linux user space available on the device). In that case
     * the generated script uses the real host paths instead of the guest mount
     * points of a container.
     */
    val hostMode: Boolean = false
) {
    /** Path of the project container folder as seen by the game process. */
    val pcgameMount: String get() = if (hostMode) pcGameDir else GUEST_PCGAME

    /** Path of the game folder as seen by the game process. */
    val gameMount: String get() = if (hostMode) gameDir else GUEST_GAME

    /** Path of the Wine prefix as seen by the game process. */
    val prefixMount: String get() = if (hostMode) prefixDir else GUEST_PREFIX

    /** Path of the Wine/Box64 runtime (empty when there is no runtime). */
    val runtimeMount: String get() = if (hostMode) (runtimeDir ?: "") else GUEST_RUNTIME

    /** Path of the graphics driver pack (empty when the device driver is used). */
    val driverMount: String get() = if (hostMode) (driverDir ?: "") else GUEST_DRIVERS

    /** Path of the D3D layer pack (empty when no layer is selected). */
    val d3dMount: String get() = if (hostMode) (d3dDir ?: "") else GUEST_D3D

    /** Path of the generated start script as seen by the game process. */
    val scriptMount: String get() = pcgameMount + "/scripts/launch.sh"

    companion object {
        const val GUEST_PCGAME = "/pcgame"
        const val GUEST_GAME = "/game"
        const val GUEST_PREFIX = "/prefix"
        const val GUEST_RUNTIME = "/runtime"
        const val GUEST_DRIVERS = "/drivers"
        const val GUEST_D3D = "/d3d"
        const val GUEST_SCRIPT = "$GUEST_PCGAME/scripts/launch.sh"
    }
}

/**
 * Builds the environment for the guest process.
 *
 * The values are deliberately explicit: a user can copy the generated
 * `launch.sh` and see every tuning knob that was applied. Device specific
 * overrides always win, they are applied last.
 */
object PcEnvironment {

    fun build(
        config: PcGameConfig,
        paths: PcLaunchPaths,
        driverExtraEnv: Map<String, String> = emptyMap()
    ): LinkedHashMap<String, String> {
        val env = LinkedHashMap<String, String>()
        val width = config.resolutionWidth.coerceIn(320, 7680)
        val height = config.resolutionHeight.coerceIn(240, 4320)

        // --- session ------------------------------------------------------
        env["HOME"] = if (paths.hostMode) paths.prefixDir else "/home/pcuser"
        env["TMPDIR"] = "/tmp"
        env["LANG"] = "C.UTF-8"
        env["DISPLAY"] = ":0"
        env["PATH"] = runtimeBin(paths) + "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

        // --- wine ---------------------------------------------------------
        env["WINEPREFIX"] = paths.prefixMount
        env["WINEARCH"] = "win64"
        env["WINEDEBUG"] = if (config.wineDebug.isBlank()) "-all" else config.wineDebug
        env["WINEESYNC"] = "1"
        env["WINEFSYNC"] = "1"
        env["WINE_LARGE_ADDRESS_AWARE"] = "1"
        env["WINEDLLOVERRIDES"] = dllOverrides(config)
        env["WINE_MONO_TRACE"] = "off"

        // --- box64 (x86_64 -> aarch64) ------------------------------------
        env["BOX64_NOBANNER"] = "1"
        env["BOX64_LOG"] = "0"
        env["BOX64_DYNAREC"] = "1"
        env["BOX64_DYNAREC_BIGBLOCK"] = "3"
        env["BOX64_DYNAREC_STRONGMEM"] = "2"
        env["BOX64_DYNAREC_FASTNAN"] = "1"
        env["BOX64_DYNAREC_SAFEFLAGS"] = "2"
        env["BOX64_DYNAREC_X87DOUBLE"] = "0"
        env["BOX64_DYNAREC_FASTROUND"] = "1"
        env["BOX64_MMAP32"] = "1"
        env["BOX64_LD_LIBRARY_PATH"] = libraryPath(paths)

        // --- mesa / vulkan ------------------------------------------------
        env["MESA_NO_ERROR"] = "1"
        env["VK_LOADER_DEBUG"] = "none"
        env["TU_DEBUG"] = "noconform"
        env["MESA_VK_WSI_PRESENT_MODE"] = "fifo"

        if (paths.driverDir != null && paths.driverIcdFiles.isNotEmpty()) {
            val icdList = paths.driverIcdFiles.joinToString(":")
            env["VK_ICD_FILENAMES"] = icdList
            env["VK_DRIVER_FILES"] = icdList
            env["ADRENOTOOLS_DRIVER_PATH"] = paths.driverDir
            env["ADRENOTOOLS_HOOKS_PATH"] = paths.driverDir
            env["GALLIUM_DRIVER"] = "freedreno"
        } else {
            env["GALLIUM_DRIVER"] = "zink"
        }

        // --- DXVK / VKD3D -------------------------------------------------
        if (hasD3dLayer(config)) {
            env["DXVK_STATE_CACHE"] = "1"
            env["DXVK_STATE_CACHE_PATH"] = paths.prefixMount + "/.dxvk-cache"
            env["DXVK_LOG_PATH"] = paths.pcgameMount + "/logs"
            env["DXVK_LOG_LEVEL"] = "none"
            env["DXVK_HUD"] = if (config.dxvkHud) "fps,frametimes,gpuload,devinfo" else "0"
            env["DXVK_ASYNC"] = "1"
            env["VKD3D_SHADER_CACHE_PATH"] = paths.prefixMount + "/.vkd3d-cache"
            env["VKD3D_FEATURE_LEVEL"] = "12_1"
            env["VKD3D_DEBUG"] = "none"
            env["VKD3D_CONFIG"] = "dxr,dxr11"
        } else {
            env["DXVK_HUD"] = if (config.dxvkHud) "fps,frametimes" else "0"
        }

        // --- audio --------------------------------------------------------
        if (config.audioDriver.equals("pulse", ignoreCase = true)) {
            env["PULSE_LATENCY_MSEC"] = "60"
        } else if (config.audioDriver.equals("alsa", ignoreCase = true)) {
            env["SDL_AUDIODRIVER"] = "alsa"
        }

        // --- container hints used by the generated script -----------------
        env["NC_DISPLAY_SIZE"] = "${width}x$height"
        env["NC_DISPLAY_DPI"] = config.dpi.coerceIn(72, 480).toString()
        env["NC_VNC_PORT"] = config.displayPort.toString()
        env["NC_START_XSERVER"] = if (config.startXServer) "1" else "0"
        env["NC_START_VNC"] = if (config.startVncServer) "1" else "0"
        env["NC_XSERVER_CMD"] = config.xServerCommand
            .replace("%RES%", "${width}x$height")
        env["NC_VNC_CMD"] = config.vncCommand.replace("%PORT%", config.displayPort.toString())
        env["NC_WINE_CMD"] = config.wineCommand.ifBlank { "wine" }
        val box64 = config.box64Command.ifBlank { "box64" }
        env["NC_BOX64_CMD"] = if (paths.hostMode && !box64.contains("/system/bin/")) {
            // Android 10+ refuses to execve files inside the app data directory,
            // but /system/bin/linker64 may map them.
            val binary = if (box64.startsWith("/")) box64 else paths.runtimeMount + "/bin/" + box64
            "/system/bin/linker64 " + binary
        } else {
            box64
        }
        env["NC_SET_DPI"] = "1"

        // driver packs may ship their own environment (e.g. AdrenoTools hooks)
        for ((key, value) in driverExtraEnv) {
            env[key] = value
        }

        // user overrides from the blocks always win
        for ((key, value) in config.extraEnv) {
            if (key.isNotBlank()) env[key] = value
        }
        return env
    }

    fun runtimeBin(paths: PcLaunchPaths): String {
        val runtime = paths.runtimeMount
        return if (runtime.isEmpty()) "/bin:" else runtime + "/bin:"
    }

    fun libraryPath(paths: PcLaunchPaths): String {
        val parts = ArrayList<String>()
        val runtime = paths.runtimeMount
        if (runtime.isNotEmpty()) {
            parts.add(runtime + "/lib")
            parts.add(runtime + "/lib/wine")
            parts.add(runtime + "/lib/x86_64-linux-gnu")
            parts.add(runtime)
        }
        if (paths.driverDir != null) parts.add(paths.driverMount)
        if (paths.d3dDir != null) parts.add(paths.d3dMount)
        parts.add("/usr/lib/x86_64-linux-gnu")
        parts.add("/usr/lib")
        parts.add("/lib/x86_64-linux-gnu")
        parts.add("/lib")
        return parts.joinToString(":")
    }

    /**
     * `mscoree,mshtml=` disables Wine's Mono/Gecko prompts (they block the first
     * start on a phone). With a D3D layer the builtin d3d/ dxgi DLLs are
     * replaced by the native ones that were copied into the prefix.
     */
    fun dllOverrides(config: PcGameConfig): String {
        val base = "mscoree,mshtml="
        return if (hasD3dLayer(config)) {
            base + ";d3d9,d3d10core,d3d11,dxgi,d3d12,d3d12core=n,b"
        } else {
            base
        }
    }

    fun hasD3dLayer(config: PcGameConfig): Boolean {
        val value = config.d3dLayer.trim().lowercase()
        return value.isNotEmpty() && value != "none" && value != "system" && value != "off"
    }

    fun hasGraphicsDriver(config: PcGameConfig): Boolean {
        val value = config.graphicsDriver.trim().lowercase()
        return value.isNotEmpty() && value != "none" && value != "system" && value != "off"
    }
}

/**
 * Generates the `launch.sh` that runs *inside* the container, and the proot
 * command line that starts it on Android.
 */
object PcScriptBuilder {

    const val GENERATED_HEADER = "# Generated by NewCatroid - PC compatibility layer"

    fun buildScript(
        config: PcGameConfig,
        paths: PcLaunchPaths,
        env: Map<String, String>,
        executableInGuest: String
    ): String {
        val environment = StringBuilder()
        for ((key, value) in env) {
            if (!isValidVariableName(key)) continue
            environment.append("export ").append(key).append('=').append(shellQuote(value)).append('\n')
        }

        val extra = StringBuilder()
        if (config.extraStartupCommands.isNotBlank()) {
            extra.append("# ---- extra startup commands ----\n")
            extra.append(config.extraStartupCommands)
            if (!config.extraStartupCommands.endsWith("\n")) extra.append('\n')
        }

        val desktop = if (config.virtualDesktop) {
            "explorer /desktop=ncgame," + config.resolutionWidth + "x" + config.resolutionHeight + " "
        } else {
            ""
        }
        val arguments = config.arguments.trim()
        val template = StringBuilder()
        template.append("#!/bin/sh\n")
        template.append("# Generated by NewCatroid - PC compatibility layer\n")
        template.append("# game folder: @@GAMEDIR@@\n")
        template.append("# resolution: @@W@@x@@H@@\n")
        template.append("set -u\n")
        template.append("\n")
        template.append("# ---- environment ----\n")
        template.append("@@ENV@@\n")
        template.append("\n")
        template.append("# ---- container preparation ----\n")
        template.append("mkdir -p \"\$HOME\" /tmp @@PCGAME@@/logs 2>/dev/null || true\n")
        template.append("chmod 1777 /tmp 2>/dev/null || true\n")
        template.append("export XDG_RUNTIME_DIR=\$HOME/.runtime\n")
        template.append("mkdir -p \"\$XDG_RUNTIME_DIR\" 2>/dev/null || true\n")
        template.append("chmod 700 \"\$XDG_RUNTIME_DIR\" 2>/dev/null || true\n")
        template.append("\n")
        template.append("# ---- X server (the framebuffer the stage renders) ----\n")
        template.append("if [ \"\$NC_START_XSERVER\" = \"1\" ]; then\n")
        template.append("  echo \"[newcatroid] starting X server: \$NC_XSERVER_CMD\"\n")
        template.append("  \$NC_XSERVER_CMD > @@PCGAME@@/logs/xserver.log 2>&1 &\n")
        template.append("  XSERVER_PID=\$!\n")
        template.append("  sleep 1\n")
        template.append("fi\n")
        template.append("\n")
        template.append("# ---- VNC bridge (rendering and input) ----\n")
        template.append("if [ \"\$NC_START_VNC\" = \"1\" ]; then\n")
        template.append("  echo \"[newcatroid] starting VNC bridge on port \$NC_VNC_PORT\"\n")
        template.append("  \$NC_VNC_CMD > @@PCGAME@@/logs/x11vnc.log 2>&1 &\n")
        template.append("  VNC_PID=\$!\n")
        template.append("  sleep 1\n")
        template.append("fi\n")
        template.append("\n")
        template.append("# ---- optional user hook: <project>/files/pcgame/hook_pre.sh ----\n")
        template.append("if [ -f @@PCGAME@@/scripts/hook_pre.sh ]; then\n")
        template.append("  echo \"[newcatroid] running hook_pre.sh\"\n")
        template.append("  . @@PCGAME@@/scripts/hook_pre.sh\n")
        template.append("fi\n")
        template.append("\n")
        template.append("@@EXTRA@@\n")
        template.append("\n")
        template.append("# ---- sanity checks ----\n")
        template.append("NC_BOX64_BIN=\${NC_BOX64_CMD%% *}\n")
        template.append("if ! command -v \"\$NC_BOX64_BIN\" >/dev/null 2>&1; then\n")
        template.append("  echo \"[newcatroid] '\$NC_BOX64_CMD' is not in the container.\"\n")
        template.append("  echo \"[newcatroid] import a Wine + Box64 runtime pack, then launch again.\"\n")
        template.append("fi\n")
        template.append("if [ \"\$NC_START_VNC\" = \"1\" ] && ! command -v x11vnc >/dev/null 2>&1; then\n")
        template.append("  echo \"[newcatroid] x11vnc is missing in the rootfs: the stage will stay black.\"\n")
        template.append("fi\n")
        template.append("\n")
        template.append("# ---- prefix tuning ----\n")
        template.append("mkdir -p \"\$WINEPREFIX\" 2>/dev/null || true\n")
        template.append("if [ ! -f \"\$WINEPREFIX/.newcatroid_tuned\" ]; then\n")
        template.append("  \$NC_BOX64_CMD \"\$NC_WINE_CMD\" reg add 'HKCU\\Control Panel\\Desktop' /v LogPixels /t REG_DWORD /d @@DPI@@ /f >/dev/null 2>&1 || true\n")
        template.append("  touch \"\$WINEPREFIX/.newcatroid_tuned\" 2>/dev/null || true\n")
        template.append("fi\n")
        template.append("if [ ! -f \"\$WINEPREFIX/system.reg\" ]; then\n")
        template.append("  echo \"[newcatroid] initialising the Wine prefix (first start, this can take a while)\"\n")
        template.append("  \$NC_BOX64_CMD \"\$NC_WINE_CMD\" wineboot -u > @@PCGAME@@/logs/wineboot.log 2>&1 || true\n")
        template.append("fi\n")
        template.append("\n")
        template.append("# ---- D3D layer (DXVK / VKD3D / D8VK) ----\n")
        template.append("mkdir -p \"\$WINEPREFIX/drive_c/windows/system32\" \"\$WINEPREFIX/drive_c/windows/syswow64\" 2>/dev/null || true\n")
        template.append("if [ \"\$NC_INSTALL_D3D\" = \"1\" ] && [ -d @@D3D@@ ]; then\n")
        template.append("  NC_D3D_CURRENT=\$(cat \"\$WINEPREFIX/.newcatroid_d3d\" 2>/dev/null || echo none)\n")
        template.append("  if [ \"\$NC_D3D_CURRENT\" != \"\$NC_D3D_PACK\" ]; then\n")
        template.append("    echo \"[newcatroid] installing the D3D layer \$NC_D3D_PACK\"\n")
        template.append("    find @@D3D@@ -name '*.dll' 2>/dev/null | while read -r nc_dll; do\n")
        template.append("      case \"\$nc_dll\" in\n")
        template.append("        */x86/*|*/i386/*|*/syswow64/*)\n")
        template.append("          cp -f \"\$nc_dll\" \"\$WINEPREFIX/drive_c/windows/syswow64/\" 2>/dev/null || true ;;\n")
        template.append("        *)\n")
        template.append("          cp -f \"\$nc_dll\" \"\$WINEPREFIX/drive_c/windows/system32/\" 2>/dev/null || true ;;\n")
        template.append("      esac\n")
        template.append("    done\n")
        template.append("    echo \"\$NC_D3D_PACK\" > \"\$WINEPREFIX/.newcatroid_d3d\" 2>/dev/null || true\n")
        template.append("  fi\n")
        template.append("fi\n")
        template.append("\n")
        template.append("# ---- run ----\n")
        template.append("cd @@GAMEDIR_GUEST@@ || {\n")
        template.append("  echo \"[newcatroid] the game folder is not mounted inside the container\"\n")
        template.append("  exit 90\n")
        template.append("}\n")
        template.append("echo \"[newcatroid] launching: \$NC_BOX64_CMD \$NC_WINE_CMD @@EXE_NAME@@\"\n")
        template.append("exec \$NC_BOX64_CMD \"\$NC_WINE_CMD\" @@DESKTOP@@@@EXE@@ @@ARGS@@\n")
        template.append("\n")

        return template.toString()
            .replace("@@GAMEDIR@@", shellEscape(config.gameDir))
            .replace("@@GAMEDIR_GUEST@@", paths.gameMount)
            .replace("@@W@@", config.resolutionWidth.toString())
            .replace("@@H@@", config.resolutionHeight.toString())
            .replace("@@DPI@@", config.dpi.coerceIn(72, 480).toString())
            .replace("@@ENV@@", environment.toString())
            .replace("@@EXTRA@@", extra.toString())
            .replace("@@PCGAME@@", paths.pcgameMount)
            .replace("@@D3D@@", paths.d3dMount)
            .replace("@@EXE@@", shellQuote(executableInGuest))
            .replace("@@EXE_NAME@@", shellEscape(executableInGuest))
            .replace("@@DESKTOP@@", desktop)
            .replace("@@ARGS@@", arguments)
    }

    /**
     * proot invocation. The first argument list element is the binary; the
     * caller decides whether it is started directly, through `linker64` or
     * through `sh`.
     */
    fun buildProotArguments(paths: PcLaunchPaths): List<String> {
        val args = ArrayList<String>()
        args.add("-r")
        args.add(paths.containerRoot)
        args.add("-0")
        args.add("-w")
        args.add(PcLaunchPaths.GUEST_GAME)
        args.add("-b")
        args.add("/dev")
        args.add("-b")
        args.add("/proc")
        args.add("-b")
        args.add("/sys")
        args.add("-b")
        args.add(paths.pcGameDir + ":" + PcLaunchPaths.GUEST_PCGAME)
        args.add("-b")
        args.add(paths.gameDir + ":" + PcLaunchPaths.GUEST_GAME)
        args.add("-b")
        args.add(paths.prefixDir + ":" + PcLaunchPaths.GUEST_PREFIX)
        if (!paths.runtimeDir.isNullOrBlank()) {
            args.add("-b")
            args.add(paths.runtimeDir + ":" + PcLaunchPaths.GUEST_RUNTIME)
        }
        if (!paths.driverDir.isNullOrBlank()) {
            args.add("-b")
            args.add(paths.driverDir + ":" + PcLaunchPaths.GUEST_DRIVERS)
        }
        if (!paths.d3dDir.isNullOrBlank()) {
            args.add("-b")
            args.add(paths.d3dDir + ":" + PcLaunchPaths.GUEST_D3D)
        }
        args.add("/bin/sh")
        args.add(paths.scriptMount)
        return args
    }

    fun buildWindowsCommand(config: PcGameConfig, executableInGuest: String): String {
        val builder = StringBuilder("wine ")
        if (config.virtualDesktop) {
            builder.append("explorer /desktop=ncgame,")
                .append(config.resolutionWidth).append('x').append(config.resolutionHeight).append(' ')
        }
        builder.append(executableInGuest)
        if (config.arguments.isNotBlank()) builder.append(' ').append(config.arguments.trim())
        return builder.toString()
    }

    fun isValidVariableName(name: String): Boolean {
        if (name.isEmpty()) return false
        if (!(name[0].isLetter() || name[0] == '_')) return false
        for (character in name) {
            if (!(character.isLetterOrDigit() || character == '_')) return false
        }
        return true
    }

    /** POSIX single quotes: everything is literal inside them. */
    fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }

    fun shellEscape(value: String): String = value.replace("\n", " ")

    fun humanSize(bytes: Long): String {
        val kilo = 1024.0
        return when {
            bytes < kilo -> "$bytes B"
            bytes < kilo * kilo -> String.format("%.1f KB", bytes / kilo)
            bytes < kilo * kilo * kilo -> String.format("%.1f MB", bytes / (kilo * kilo))
            else -> String.format("%.2f GB", bytes / (kilo * kilo * kilo))
        }
    }

    fun folderSize(directory: File, limitFiles: Int = 20000): Long {
        var total = 0L
        var counted = 0
        val stack = ArrayDeque<File>()
        stack.add(directory)
        while (stack.isNotEmpty() && counted < limitFiles) {
            val current = stack.removeLast()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    stack.add(child)
                } else {
                    total += max(0L, child.length())
                    counted++
                }
            }
        }
        return total
    }
}
