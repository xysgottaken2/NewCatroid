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

import android.content.Context
import android.util.Log
import org.catrobat.catroid.ProjectManager
import org.catrobat.catroid.formulaeditor.UserVariable
import org.catrobat.catroid.stage.StageActivity
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs PC (Windows) games inside a proot container with an aarch64 Wine +
 * Box64 runtime, a user supplied Vulkan driver and a user supplied D3D
 * translation layer (DXVK / VKD3D). This is the same construction Winlator,
 * GameHub and Mobox use; NewCatroid only orchestrates it and renders the
 * resulting framebuffer through the existing VNC path of the stage.
 *
 * Nothing here ships third party binaries: the user imports them as zip packs
 * (see [PcPackManager]).
 */
object PcGameManager {

    private const val TAG = "PcGameManager"
    private const val MAX_LOG_LINES = 400
    private const val DISPLAY_ATTACH_ATTEMPTS = 90

    enum class State { IDLE, PREPARING, RUNNING, STOPPING, FAILED }

    @Volatile
    var state: State = State.IDLE
        private set

    @Volatile
    var lastError: String = ""
        private set

    @Volatile
    var lastExitCode: Int = Int.MIN_VALUE
        private set

    @Volatile
    var activeExecMode: String = ""
        private set

    var config: PcGameConfig = PcGameConfig()

    var touchProfile: PcTouchProfile? = null
        private set

    private var loadedProjectKey: String? = null
    private var process: Process? = null
    private var processThread: Thread? = null
    private val log = ArrayDeque<String>()
    private val outputVariables = ConcurrentHashMap<String, UserVariable>()
    private var scriptFile: File? = null
    private var lastPaths: PcLaunchPaths? = null

    // ---------------------------------------------------------------- config

    /** Loads `config.json` from the current project once per project. */
    fun ensureProjectConfig() {
        val project = try {
            ProjectManager.getInstance().currentProject
        } catch (e: Exception) {
            null
        }
        val key = project?.name ?: "none"
        if (key == loadedProjectKey) return
        loadedProjectKey = key
        val folder = PcGamePaths.projectFolder(project)
        val stored = PcGameConfig.load(folder)
        config = stored ?: PcGameConfig()
        touchProfile = PcTouchProfile.load(folder, config.touchProfile)
        appendLog("[newcatroid] project '${key}' PC settings loaded")
    }

    fun saveConfig() {
        val folder = PcGamePaths.projectFolder()
        if (PcGameConfig.save(folder, config)) {
            appendLog("[newcatroid] settings saved to project")
        }
    }

    fun setGameDir(path: String) {
        ensureProjectConfig()
        config.gameDir = path.trim()
        val resolved = PcGamePaths.resolve(PcGamePaths.appContext(), config.gameDir)
        if (resolved != null && resolved.isDirectory) {
            val detected = detectExecutables(resolved)
            if (config.executable.isBlank() && detected.isNotEmpty()) {
                config.executable = detected.first()
                appendLog("[newcatroid] detected executable: ${config.executable}")
            }
            appendLog("[newcatroid] game folder: ${resolved.absolutePath} (${detected.size} exe found)")
        } else {
            appendLog("[newcatroid] game folder not found: ${config.gameDir}")
        }
    }

    fun setGraphicsDriver(reference: String) {
        ensureProjectConfig()
        config.graphicsDriver = reference.trim().ifEmpty { "system" }
        val pack = PcPackManager.resolve(
            PcGamePaths.appContext(),
            PcPackType.GRAPHICS_DRIVER,
            config.graphicsDriver
        )
        appendLog(
            if (pack != null) {
                "[newcatroid] graphics driver: ${pack.summary()}"
            } else {
                "[newcatroid] graphics driver: ${config.graphicsDriver} (no matching pack)"
            }
        )
    }

    fun setD3dLayer(reference: String) {
        ensureProjectConfig()
        config.d3dLayer = reference.trim()
        val pack = PcPackManager.resolve(PcGamePaths.appContext(), PcPackType.D3D_LAYER, config.d3dLayer)
        appendLog(
            if (pack != null) {
                "[newcatroid] D3D layer: ${pack.summary()}"
            } else {
                "[newcatroid] D3D layer: ${config.d3dLayer.ifEmpty { "none" }}"
            }
        )
    }

    fun setRuntime(reference: String) {
        ensureProjectConfig()
        config.runtime = reference.trim().ifEmpty { "auto" }
        val pack = PcPackManager.resolve(PcGamePaths.appContext(), PcPackType.RUNTIME, config.runtime)
        appendLog(
            if (pack != null) {
                "[newcatroid] runtime: ${pack.summary()}"
            } else {
                "[newcatroid] runtime: ${config.runtime} (no matching pack)"
            }
        )
    }

    fun setContainer(path: String) {
        ensureProjectConfig()
        config.containerDir = path.trim()
        appendLog("[newcatroid] container: ${config.containerDir.ifEmpty { "imported rootfs pack" }}")
    }

    fun setEnvironmentVariable(name: String, value: String) {
        ensureProjectConfig()
        val key = name.trim()
        if (key.isEmpty()) return
        if (!PcScriptBuilder.isValidVariableName(key)) {
            appendLog("[newcatroid] invalid environment variable name: '$key'")
            return
        }
        config.extraEnv[key] = value
        appendLog("[newcatroid] env $key=$value")
    }

    fun clearEnvironment() {
        ensureProjectConfig()
        config.extraEnv.clear()
        appendLog("[newcatroid] environment overrides cleared")
    }

    fun setResolution(width: Int, height: Int) {
        ensureProjectConfig()
        config.resolutionWidth = width.coerceIn(320, 7680)
        config.resolutionHeight = height.coerceIn(240, 4320)
    }

    fun setTouchProfileName(name: String) {
        ensureProjectConfig()
        config.touchProfile = name.trim().ifEmpty { "gamepad" }
        touchProfile = PcTouchProfile.load(PcGamePaths.projectFolder(), config.touchProfile)
        appendLog("[newcatroid] touch profile: ${config.touchProfile} (${touchProfile?.controls?.size ?: 0} controls)")
    }

    // ---------------------------------------------------------------- launch

    /**
     * Launches [executableInGame] (relative to the game folder when empty the
     * configured executable is used).
     *
     * Returns a human readable status line; the detailed log is available via
     * [logText] and the "bind PC log" block.
     */
    fun launch(
        context: Context,
        executableInGame: String? = null,
        arguments: String? = null,
        withTouchControls: Boolean = false
    ): String {
        ensureProjectConfig()
        if (isRunning()) {
            val message = "A PC game is already running"
            appendLog("[newcatroid] $message")
            return message
        }

        val executable = (executableInGame ?: "").trim().ifEmpty { config.executable.trim() }
        if (arguments != null) config.arguments = arguments
        if (executable.isEmpty()) {
            val message = "No executable selected. Set the game folder or the exe path first."
            lastError = message
            state = State.FAILED
            appendLog("[newcatroid] $message")
            return message
        }

        state = State.PREPARING
        lastError = ""
        lastExitCode = Int.MIN_VALUE

        try {
            if (executable != config.executable) config.executable = executable
            val prepared = prepare(context, executable) ?: run {
                val message = lastError.ifEmpty { "Cannot prepare the container" }
                state = State.FAILED
                return message
            }

            val started = startProcess(prepared) ?: run {
                state = State.FAILED
                return lastError.ifEmpty { "Cannot start the container" }
            }
            process = started
            state = State.RUNNING
            lastPaths = prepared.paths

            appendLog("[newcatroid] started (pid ${pidOf(started)}, mode $activeExecMode)")
            attachDisplay(prepared.port, config.showDisplay)
            if (withTouchControls) showTouchControls()
            saveConfig()
            return "Launched $executable"
        } catch (e: Exception) {
            Log.e(TAG, "Launch failed", e)
            lastError = "Launch failed: ${e.message}"
            state = State.FAILED
            appendLog("[newcatroid] $lastError")
            return lastError
        }
    }

    private data class PreparedLaunch(
        val command: List<String>,
        val workingDirectory: File,
        val environment: Map<String, String>,
        val script: File,
        val paths: PcLaunchPaths,
        val port: Int
    )

    private fun prepare(context: Context, executable: String): PreparedLaunch? {
        val cfg = config.copyConfig()

        val gameDir = PcGamePaths.resolveExisting(context, cfg.gameDir)
        if (gameDir == null || !gameDir.isDirectory) {
            lastError = "Game folder not found: '${cfg.gameDir}'. " +
                "Use an absolute path such as /sdcard/Games/MyGame."
            appendLog("[newcatroid] $lastError")
            return null
        }

        val executableRelative = relativeExecutable(gameDir, executable)
        if (executableRelative == null) {
            lastError = "Cannot find '$executable' inside ${gameDir.absolutePath}"
            appendLog("[newcatroid] $lastError")
            return null
        }

        val containerRoot = resolveContainer(context)
        val runtimePack = PcPackManager.resolve(context, PcPackType.RUNTIME, cfg.runtime)
        val runtimeDir = runtimePack?.directory
        if (runtimeDir == null) {
            appendLog("[newcatroid] warning: no Wine/Box64 runtime pack imported")
        } else if (!hasRuntimeBinary(runtimeDir)) {
            appendLog("[newcatroid] warning: runtime '${runtimePack.id}' has no box64/wine binary in bin/")
        }

        val hostMode = containerRoot == null
        if (hostMode) {
            if (runtimeDir == null) {
                lastError = "Nothing to run: import a Wine/Box64 runtime pack, " +
                    "or a container rootfs if your runtime needs a Linux user space."
                appendLog("[newcatroid] $lastError")
                return null
            }
            appendLog(
                "[newcatroid] no container rootfs: running in host mode, " +
                    "the runtime pack must be self contained (box64, wine, X server, x11vnc)"
            )
        } else {
            appendLog("[newcatroid] container: ${containerRoot.absolutePath}")
        }

        val driverPack = if (PcEnvironment.hasGraphicsDriver(cfg)) {
            PcPackManager.resolve(context, PcPackType.GRAPHICS_DRIVER, cfg.graphicsDriver)
        } else {
            null
        }
        if (PcEnvironment.hasGraphicsDriver(cfg) && driverPack == null) {
            appendLog("[newcatroid] warning: graphics driver '${cfg.graphicsDriver}' not imported, " +
                "falling back to the device driver")
        } else if (driverPack != null) {
            appendLog("[newcatroid] graphics driver: ${driverPack.summary()}")
        }

        val d3dPack = if (PcEnvironment.hasD3dLayer(cfg)) {
            PcPackManager.resolve(context, PcPackType.D3D_LAYER, cfg.d3dLayer)
        } else {
            null
        }
        if (PcEnvironment.hasD3dLayer(cfg) && d3dPack == null) {
            appendLog("[newcatroid] warning: D3D layer '${cfg.d3dLayer}' not imported")
        }

        val prefixDir = PcGamePaths.prefixFor(projectKey())
        if (!prefixDir.exists() && !prefixDir.mkdirs()) {
            lastError = "Cannot create the Wine prefix at ${prefixDir.absolutePath}"
            appendLog("[newcatroid] $lastError")
            return null
        }

        // Driver mount + ICD list (guest paths)
        val driverMount = if (hostMode) (driverPack?.directory?.absolutePath ?: "") else PcLaunchPaths.GUEST_DRIVERS
        var driverIcdGuest: List<String> = emptyList()
        if (driverPack != null) {
            driverIcdGuest = driverPack.icdFiles().map { file ->
                val relative = file.absolutePath.removePrefix(driverPack.directory.absolutePath)
                    .trimStart('/')
                driverMount + "/" + relative
            }
            if (driverIcdGuest.isEmpty()) {
                appendLog("[newcatroid] warning: driver pack has no *icd*.json metadata, " +
                    "Android can only load drivers through an ICD file")
            }
        }

        val driverExtraEnv = readPackEnvironment(driverPack?.directory)

        val paths = PcLaunchPaths(
            containerRoot = containerRoot?.absolutePath ?: "",
            prefixDir = prefixDir.absolutePath,
            gameDir = gameDir.absolutePath,
            pcGameDir = PcGamePaths.root(context).absolutePath,
            runtimeDir = runtimeDir?.absolutePath,
            driverDir = driverPack?.directory?.absolutePath,
            driverIcdFiles = driverIcdGuest,
            d3dDir = d3dPack?.directory?.absolutePath,
            hostMode = hostMode
        )

        val environment = PcEnvironment.build(cfg, paths, driverExtraEnv)
        environment["NC_INSTALL_D3D"] = if (d3dPack != null) "1" else "0"
        environment["NC_D3D_PACK"] = d3dPack?.id ?: ""

        val executableGuest = paths.gameMount + "/" + executableRelative
        val script = writeLaunchScript(context, cfg, paths, environment, executableGuest)

        val command = ArrayList<String>()
        if (hostMode) {
            command.add("/system/bin/sh")
            command.add(script.absolutePath)
        } else {
            val prootArgs = PcScriptBuilder.buildProotArguments(paths)
            val prootBinary = findProot(runtimeDir, containerRoot)
            command.add(prootBinary)
            command.addAll(prootArgs)
        }

        appendLog("[newcatroid] command: ${command.joinToString(" ")}")
        appendLog("[newcatroid] script: ${script.absolutePath}")

        val processEnv = HashMap<String, String>()
        processEnv["PROOT_TMP_DIR"] = PcGamePaths.tmp(context).absolutePath
        processEnv["PROOT_NO_SECCOMP"] = "1"
        processEnv["HOME"] = prefixDir.absolutePath
        if (runtimeDir != null) {
            processEnv["LD_LIBRARY_PATH"] = runtimeDir.absolutePath + "/lib:" + runtimeDir.absolutePath
            processEnv["PROOT_LOADER"] = File(runtimeDir, "lib/proot/loader").absolutePath
        }

        return PreparedLaunch(
            command = command,
            workingDirectory = PcGamePaths.root(context),
            environment = processEnv,
            script = script,
            paths = paths,
            port = cfg.displayPort
        )
    }

    private fun writeLaunchScript(
        context: Context,
        cfg: PcGameConfig,
        paths: PcLaunchPaths,
        environment: Map<String, String>,
        executableGuest: String
    ): File {
        val scriptsDir = PcGamePaths.scripts(context)
        val script = File(scriptsDir, "launch.sh")
        val content = PcScriptBuilder.buildScript(cfg, paths, environment, executableGuest)
        script.writeText(content)
        PcPermissions.markExecutable(script)

        // optional user hook, copied from the project so it survives reinstall
        val projectFolder = PcGamePaths.projectFolder()
        val hookSource = projectFolder?.let { File(it, "hook_pre.sh") }
        val hookTarget = File(scriptsDir, "hook_pre.sh")
        if (hookSource != null && hookSource.isFile) {
            try {
                hookSource.copyTo(hookTarget, overwrite = true)
                PcPermissions.markExecutable(hookTarget)
                appendLog("[newcatroid] hook_pre.sh copied from the project")
            } catch (e: Exception) {
                Log.w(TAG, "Cannot copy hook_pre.sh", e)
            }
        } else if (hookTarget.exists()) {
            hookTarget.delete()
        }

        scriptFile = script
        return script
    }

    private fun startProcess(prepared: PreparedLaunch): Process? {
        if (prepared.paths.hostMode) {
            return startHostProcess(prepared)
        }
        val modes = when (config.execMode.trim().lowercase()) {
            "linker" -> listOf("linker")
            "direct" -> listOf("direct")
            "sh" -> listOf("sh")
            else -> listOf("linker", "direct", "sh")
        }
        var lastException: Exception? = null
        for (mode in modes) {
            val command = when (mode) {
                "linker" -> listOf("/system/bin/linker64") + prepared.command
                "sh" -> listOf("/system/bin/sh", "-c", "exec \"\$@\"", "sh") + prepared.command
                else -> prepared.command
            }
            try {
                appendLog("[newcatroid] exec mode '$mode': ${command.first()} ...")
                val builder = ProcessBuilder(command)
                builder.directory(prepared.workingDirectory)
                builder.redirectErrorStream(true)
                builder.environment().putAll(prepared.environment)
                val started = builder.start()
                activeExecMode = mode
                consumeOutput(started)
                return started
            } catch (e: Exception) {
                lastException = e
                appendLog("[newcatroid] exec mode '$mode' failed: ${e.message}")
                Log.w(TAG, "exec mode $mode failed", e)
            }
        }
        lastError = "Cannot execute proot: ${lastException?.message ?: "unknown error"}"
        appendLog("[newcatroid] $lastError")
        return null
    }

    /**
     * Host mode: the runtime pack is self contained, so the generated script is
     * started by `/system/bin/sh` directly - no proot, no rootfs. Keeps the same
     * log streaming and exit code handling as the container path.
     */
    private fun startHostProcess(prepared: PreparedLaunch): Process? {
        return try {
            val command = prepared.command
            appendLog("[newcatroid] host mode: ${command.joinToString(" ")}")
            val builder = ProcessBuilder(command)
            builder.directory(prepared.workingDirectory)
            builder.redirectErrorStream(true)
            builder.environment().putAll(prepared.environment)
            val started = builder.start()
            activeExecMode = "host"
            consumeOutput(started)
            started
        } catch (e: Exception) {
            lastError = "Cannot start the runtime: ${e.message}"
            appendLog("[newcatroid] $lastError")
            Log.e(TAG, "Host mode start failed", e)
            null
        }
    }

    private fun consumeOutput(started: Process) {
        val thread = Thread {
            try {
                val reader = started.inputStream.bufferedReader()
                var line = reader.readLine()
                while (line != null) {
                    appendLog(line)
                    line = reader.readLine()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Log reader stopped: ${e.message}")
            }
            val exit = try {
                started.waitFor()
            } catch (e: InterruptedException) {
                -1
            }
            lastExitCode = exit
            if (state != State.STOPPING) {
                state = if (exit == 0) State.IDLE else State.FAILED
            } else {
                state = State.IDLE
            }
            appendLog("[newcatroid] game process finished with exit code $exit")
            process = null
        }
        thread.isDaemon = true
        thread.name = "pcgame-log"
        processThread = thread
        thread.start()
    }

    private fun attachDisplay(port: Int, visible: Boolean) {
        if (!visible) return
        try {
            val activity = StageActivity.activeStageActivity.get()
            if (activity == null) {
                appendLog("[newcatroid] no stage activity, display not attached")
                return
            }
            appendLog("[newcatroid] attaching display to VNC port $port")
            activity.showPcDisplay(true, port, DISPLAY_ATTACH_ATTEMPTS)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot attach the display", e)
            appendLog("[newcatroid] display attach failed: ${e.message}")
        }
    }

    fun showTouchControls() {
        try {
            ensureProjectConfig()
            val folder = PcGamePaths.projectFolder()
            val profile = PcTouchProfile.load(folder, config.touchProfile)
            touchProfile = profile
            installTouchOverlay(profile)
            appendLog("[newcatroid] touch controls: ${profile.controls.size} controls")
        } catch (e: Exception) {
            Log.w(TAG, "Cannot show touch controls", e)
            appendLog("[newcatroid] touch controls failed: ${e.message}")
        }
    }

    fun hideTouchControls() {
        try {
            StageActivity.activeStageActivity.get()?.setPcTouchOverlay(null)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot hide touch controls", e)
        }
    }

    private fun installTouchOverlay(profile: PcTouchProfile) {
        val activity = StageActivity.activeStageActivity.get() ?: return
        val overlay = PcTouchControlsActor(
            profile,
            keyAction = { keyName, isDown ->
                val keysym = activity.getKeysymByName(keyName)
                if (keysym != 0) {
                    activity.sendVmKeyEvent(keysym, isDown)
                } else {
                    appendLog("[newcatroid] unknown key '$keyName' in the touch layout")
                }
            },
            pointerAction = { stageX, stageY, mask -> activity.sendVmMouseEvent(stageX, stageY, mask) },
            pointerDeltaAction = { dx, dy, mask -> activity.moveVmMouseRelative(dx, dy, mask) },
            onLayoutChanged = Runnable { saveTouchProfile() }
        )
        activity.setPcTouchOverlay(overlay)
    }

    fun saveTouchProfile() {
        touchProfile?.save(PcGamePaths.projectFolder())
        appendLog("[newcatroid] touch layout saved")
    }

    fun addTouchControl(control: PcTouchControl) {
        ensureProjectConfig()
        val folder = PcGamePaths.projectFolder()
        val profile = touchProfile ?: PcTouchProfile.load(folder, config.touchProfile)
        profile.controls.removeAll { it.id == control.id }
        profile.controls.add(control)
        touchProfile = profile
        profile.save(folder)
        refreshTouchOverlay()
        appendLog("[newcatroid] touch control '${control.label}' (${control.kind}) saved")
    }

    fun removeTouchControl(id: String) {
        ensureProjectConfig()
        val folder = PcGamePaths.projectFolder()
        val profile = touchProfile ?: PcTouchProfile.load(folder, config.touchProfile)
        profile.controls.removeAll { it.id == id }
        touchProfile = profile
        profile.save(folder)
        refreshTouchOverlay()
        appendLog("[newcatroid] touch control '$id' removed")
    }

    fun resetTouchProfile(name: String, preset: String) {
        ensureProjectConfig()
        val folder = PcGamePaths.projectFolder()
        val profile = PcTouchProfile.fromPreset(name.ifEmpty { "custom" }, preset)
        config.touchProfile = profile.name
        touchProfile = profile
        profile.save(folder)
        refreshTouchOverlay()
        appendLog("[newcatroid] touch profile reset to preset '${preset.ifEmpty { "gamepad" }}'")
    }

    private fun refreshTouchOverlay() {
        val profile = touchProfile ?: return
        installTouchOverlay(profile)
    }

    fun stop() {
        val current = process
        if (current == null) {
            state = State.IDLE
            appendLog("[newcatroid] nothing to stop")
            return
        }
        state = State.STOPPING
        appendLog("[newcatroid] stopping game")
        hideTouchControls()
        try {
            current.destroy()
            val deadline = System.currentTimeMillis() + 1500
            while (System.currentTimeMillis() < deadline) {
                if (!current.isAlive) break
                Thread.sleep(50)
            }
            if (current.isAlive) current.destroyForcibly()
        } catch (e: Exception) {
            Log.w(TAG, "Stop failed", e)
        }
        process = null
        try {
            StageActivity.activeStageActivity.get()?.showPcDisplay(false, config.displayPort, 1)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot hide display", e)
        }
        state = State.IDLE
        appendLog("[newcatroid] stopped")
    }

    fun isRunning(): Boolean = process?.isAlive == true

    // ------------------------------------------------------------- utilities

    fun submitText(text: String) {
        // Very small convenience for launchers: writes into the running game by
        // pressing each key of the string through the VNC bridge.
        val activity = StageActivity.activeStageActivity.get() ?: return
        for (character in text) {
            val keysym = activity.getKeysymByName(character.toString())
            if (keysym == 0) continue
            activity.sendVmKeyEvent(keysym, true)
            activity.sendVmKeyEvent(keysym, false)
        }
    }

    fun keyEvent(keyName: String, isDown: Boolean) {
        val activity = StageActivity.activeStageActivity.get() ?: return
        val keysym = activity.getKeysymByName(keyName)
        if (keysym == 0) {
            appendLog("[newcatroid] unknown key '$keyName'")
            return
        }
        activity.sendVmKeyEvent(keysym, isDown)
    }

    fun pointerEvent(x: Float, y: Float, buttonMask: Int) {
        StageActivity.activeStageActivity.get()?.sendVmMouseEvent(x, y, buttonMask)
    }

    fun bindOutputVariable(variable: UserVariable?) {
        if (variable == null) {
            outputVariables.remove(OUTPUT_KEY)
        } else {
            variable.value = logText()
            outputVariables[OUTPUT_KEY] = variable
        }
    }

    fun appendLog(line: String) {
        val text = line.trimEnd()
        if (text.isEmpty()) return
        Log.i(TAG, text)
        synchronized(log) {
            log.add(text)
            while (log.size > MAX_LOG_LINES) log.removeFirst()
        }
        val variable = outputVariables[OUTPUT_KEY] ?: return
        val current = variable.value as? String ?: ""
        val combined = if (current.isEmpty()) text else current + "\n" + text
        val lines = combined.lines()
        variable.value = if (lines.size > MAX_LOG_LINES) {
            lines.takeLast(MAX_LOG_LINES).joinToString("\n")
        } else {
            combined
        }
    }

    fun logText(): String = synchronized(log) { log.joinToString("\n") }

    fun logTail(maxLines: Int): String {
        val lines = synchronized(log) { log.toList() }
        val start = if (lines.size > maxLines) lines.size - maxLines else 0
        return lines.subList(start, lines.size).joinToString("\n")
    }

    fun statusLine(): String {
        val builder = StringBuilder()
        builder.append("state: ").append(state.name.lowercase())
        if (isRunning()) builder.append(" (pid ").append(pidOf(process)).append(")")
        builder.append("\nexe: ").append(config.executable)
        builder.append("\nresolution: ").append(config.resolutionWidth).append("x")
            .append(config.resolutionHeight)
        if (lastExitCode != Int.MIN_VALUE) builder.append("\nlast exit code: ").append(lastExitCode)
        return builder.toString()
    }

    /**
     * Checks every component the runtime needs and returns a report that can be
     * shown on screen (the phone is usually the only debug surface available).
     */
    fun selfTest(context: Context): String {
        ensureProjectConfig()
        val report = StringBuilder()
        report.append("=== NewCatroid PC layer check ===\n")

        val container = resolveContainer(context)
        report.append(if (container != null) {
            "[ok] rootfs: ${container.absolutePath}\n"
        } else {
            "[missing] rootfs — import a rootfs pack (zip) or set the container path\n"
        })

        val runtime = PcPackManager.resolve(context, PcPackType.RUNTIME, config.runtime)
        report.append(if (runtime != null) {
            "[ok] runtime: ${runtime.summary()}\n"
        } else {
            "[missing] Wine/Box64 runtime pack\n"
        })
        if (runtime != null) {
            val proot = findProot(runtime.directory, container)
            report.append("[info] proot: $proot\n")
            report.append(
                if (hasRuntimeBinary(runtime.directory)) {
                    "[ok] box64/wine binary present\n"
                } else {
                    "[warn] no box64/wine binary in the runtime pack (expected bin/box64 and bin/wine)\n"
                }
            )
        }

        val driver = if (PcEnvironment.hasGraphicsDriver(config)) {
            PcPackManager.resolve(context, PcPackType.GRAPHICS_DRIVER, config.graphicsDriver)
        } else {
            null
        }
        if (driver == null) {
            report.append("[info] graphics driver: device driver (no pack selected)\n")
        } else {
            val icds = driver.icdFiles()
            report.append(
                if (icds.isEmpty()) {
                    "[warn] driver '${driver.id}' has no icd json\n"
                } else {
                    "[ok] driver '${driver.id}' with ${icds.size} ICD file(s)\n"
                }
            )
        }

        val d3d = if (PcEnvironment.hasD3dLayer(config)) {
            PcPackManager.resolve(context, PcPackType.D3D_LAYER, config.d3dLayer)
        } else {
            null
        }
        report.append(
            if (d3d != null) "[ok] D3D layer: ${d3d.summary()}\n"
            else "[info] D3D layer: none (Direct3D falls back to Wine's builtin implementation)\n"
        )

        val gameDir = PcGamePaths.resolveExisting(context, config.gameDir)
        if (gameDir == null || !gameDir.isDirectory) {
            report.append("[missing] game folder '${config.gameDir}'\n")
        } else {
            val executables = detectExecutables(gameDir)
            report.append("[ok] game folder with ${executables.size} executable(s)\n")
            if (executables.isNotEmpty()) {
                report.append("       first: ${executables.first()}\n")
            }
        }

        val prefix = PcGamePaths.prefixFor(projectKey())
        report.append(
            if (File(prefix, "system.reg").isFile) {
                "[ok] wine prefix initialized: ${prefix.absolutePath}\n"
            } else {
                "[info] wine prefix will be created on the first launch\n"
            }
        )

        val packs = PcPackManager.allPacks(context)
        report.append("[info] imported packs: ").append(packs.size)
        if (packs.isNotEmpty()) {
            val byType = packs.groupBy { it.type }
            for ((type, list) in byType) {
                report.append("\n       ").append(type.label).append(": ")
                    .append(list.joinToString(", ") { it.id })
            }
        }
        report.append('\n')
        report.append("[info] exec mode preference: ${config.execMode}")
        if (activeExecMode.isNotEmpty()) report.append(" (last used: $activeExecMode)")
        report.append('\n')

        val runtimeForMode = PcPackManager.resolve(context, PcPackType.RUNTIME, config.runtime)
        val containerForMode = resolveContainer(context)
        val mode = when {
            containerForMode != null -> "proot container (${containerForMode.absolutePath})"
            runtimeForMode != null -> "host mode - no rootfs, the runtime pack must contain " +
                "box64, wine, an X server and x11vnc"
            else -> "nothing to run yet"
        }
        report.append("[info] run mode: ").append(mode).append('\n')
        report.append("[info] ABI: ").append(android.os.Build.SUPPORTED_ABIS.joinToString(", ")).append('\n')
        if (android.os.Build.SUPPORTED_ABIS.none { it.startsWith("arm64") }) {
            report.append("[warn] this device is not arm64: Box64 needs an arm64 CPU\n")
        }

        val reportText = report.toString()
        appendLog(reportText)
        return reportText
    }

    fun detectExecutables(gameDir: File): List<String> {
        val result = ArrayList<String>()
        val stack = ArrayDeque<File>()
        stack.add(gameDir)
        var visited = 0
        while (stack.isNotEmpty() && visited < 4000) {
            val current = stack.removeLast()
            visited++
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    if (child.name.equals("bin", true) || child.name.equals("data", true) ||
                        child.name.equals("redist", true) || child.name.equals("_commonredist", true)
                    ) {
                        continue
                    }
                    stack.add(child)
                } else if (child.name.endsWith(".exe", ignoreCase = true)) {
                    val relative = child.absolutePath.removePrefix(gameDir.absolutePath).trimStart('/')
                    result.add(relative)
                }
            }
        }
        return result.sortedBy { it.lowercase() }
    }

    private fun relativeExecutable(gameDir: File, executable: String): String? {
        val cleaned = executable.trim()
        if (cleaned.isEmpty()) return null
        val direct = File(cleaned)
        if (direct.isAbsolute) {
            val canonicalGame = gameDir.absolutePath.trimEnd('/')
            if (cleaned.startsWith(canonicalGame + "/")) {
                return cleaned.removePrefix(canonicalGame + "/")
            }
            return null
        }
        val candidate = File(gameDir, cleaned)
        if (candidate.isFile) return cleaned.replace('\\', '/')
        val detected = detectExecutables(gameDir)
        for (name in detected) {
            if (name.equals(cleaned, ignoreCase = true)) return name
        }
        return detected.firstOrNull()
    }

    private fun resolveContainer(context: Context): File? {
        val configured = PcGamePaths.resolveExisting(context, config.containerDir)
        if (configured != null) {
            return normalizeRootfs(configured)
        }
        val pack = PcPackManager.resolve(context, PcPackType.ROOTFS, "auto")
        if (pack != null) return normalizeRootfs(pack.directory)
        val defaultDir = PcGamePaths.containerRoot(context)
        val candidates = defaultDir.listFiles()
        if (candidates != null) {
            for (candidate in candidates) {
                val normalized = normalizeRootfs(candidate)
                if (normalized != null) return normalized
            }
        }
        return null
    }

    /**
     * Accepts a rootfs folder that either contains `bin/sh` directly or one
     * single nested directory with the real rootfs (as most packs are built).
     */
    private fun normalizeRootfs(dir: File): File? {
        if (!dir.isDirectory) return null
        if (looksLikeRootfs(dir)) return dir
        val children = dir.listFiles() ?: return null
        for (child in children) {
            if (child.isDirectory && looksLikeRootfs(child)) return child
        }
        return null
    }

    private fun looksLikeRootfs(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val hasShell = File(dir, "bin/sh").isFile || File(dir, "usr/bin/sh").isFile
        val hasUsr = File(dir, "usr").isDirectory || File(dir, "etc").isDirectory
        return hasShell && hasUsr
    }

    private fun hasRuntimeBinary(runtimeDir: File): Boolean {
        val candidates = listOf(
            "bin/box64", "box64", "bin/box86", "bin/wine", "wine/bin/wine", "bin/wine64"
        )
        for (relative in candidates) {
            if (File(runtimeDir, relative).isFile) return true
        }
        val children = runtimeDir.listFiles() ?: return false
        for (child in children) {
            if (child.isFile && child.name.contains("box64")) return true
        }
        return false
    }

    private fun findProot(runtimeDir: File?, containerRoot: File?): String {
        val candidates = ArrayList<File>()
        if (runtimeDir != null) {
            candidates.add(File(runtimeDir, "bin/proot"))
            candidates.add(File(runtimeDir, "proot"))
        }
        if (containerRoot != null) {
            candidates.add(File(containerRoot, "usr/bin/proot"))
            candidates.add(File(containerRoot, "bin/proot"))
        }
        val nativeDir = File(android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "aarch64")
        candidates.add(File(nativeDir, "proot"))
        for (candidate in candidates) {
            if (candidate.isFile) {
                PcPermissions.markExecutable(candidate)
                return candidate.absolutePath
            }
        }
        // Nothing found: still return the most likely location so the error log
        // shows a path the user can act on.
        return candidates.first().absolutePath
    }

    private fun readPackEnvironment(packDir: File?): Map<String, String> {
        if (packDir == null) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (name in listOf("pack.json", "meta.json", "driver.json")) {
            val file = File(packDir, name)
            if (!file.isFile) continue
            try {
                val json = org.json.JSONObject(file.readText())
                val env = json.optJSONObject("env") ?: continue
                val keys = env.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    result[key] = env.optString(key, "")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cannot read env from ${file.name}: ${e.message}")
            }
        }
        return result
    }

    private fun projectKey(): String {
        return try {
            ProjectManager.getInstance().currentProject.name ?: "project"
        } catch (e: Exception) {
            "project"
        }
    }

    private fun pidOf(target: Process?): Long {
        if (target == null) return -1
        return try {
            val method = target.javaClass.getMethod("pid")
            method.invoke(target) as Long
        } catch (e: Exception) {
            try {
                val field = target.javaClass.getDeclaredField("pid")
                field.isAccessible = true
                field.getLong(target)
            } catch (e2: Exception) {
                -1
            }
        }
    }

    fun resetForTests() {
        state = State.IDLE
        lastError = ""
        lastExitCode = Int.MIN_VALUE
        process = null
        loadedProjectKey = null
        config = PcGameConfig()
        synchronized(log) { log.clear() }
    }

    private const val OUTPUT_KEY = "pcgame"
}
