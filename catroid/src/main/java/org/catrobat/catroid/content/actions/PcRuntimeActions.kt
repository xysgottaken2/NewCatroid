/*
 * Catroid: An on-device visual programming system for Android devices
 * Copyright (C) 2010-2024 The Catrobat Team
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
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.catrobat.catroid.content.actions

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.badlogic.gdx.scenes.scene2d.actions.TemporalAction
import org.catrobat.catroid.CatroidApplication
import org.catrobat.catroid.content.Scope
import org.catrobat.catroid.formulaeditor.Formula
import org.catrobat.catroid.formulaeditor.UserVariable
import org.catrobat.catroid.pcruntime.BinaryInspector
import org.catrobat.catroid.pcruntime.GraphicsBackend
import org.catrobat.catroid.pcruntime.PcExecPlanner
import org.catrobat.catroid.pcruntime.PcQuery
import org.catrobat.catroid.pcruntime.PcRuntimeLog
import org.catrobat.catroid.pcruntime.PcRuntimeManager
import java.io.File
import java.util.concurrent.Executors

private const val PC_ACTION_TAG = "PcAction"

/**
 * Shared plumbing for every PC runtime block.
 *
 * Two rules are enforced here rather than in each action:
 *
 *  1. Nothing that can block runs on the Android main thread. The runtime
 *     initializes native components, starts processes and waits for them; doing
 *     that on the main thread would freeze the stage. Every action therefore
 *     dispatches to a small shared executor and writes the result back on the
 *     main thread.
 *  2. A block that cannot do its job writes a description of the blocker into
 *     the user's variable. Nothing silently "succeeds".
 */
abstract class PcBaseAction : TemporalAction() {

    var scope: Scope? = null

    override fun update(percent: Float) {
        val userVariable = targetVariable()
        val project = scope?.project
        if (project == null) {
            writeResult(userVariable, "error: no project context")
            return
        }
        if (userVariable == null && requiresVariable()) {
            // Still perform the work: the side effect (initialising, preparing a
            // prefix, launching the probe) is meaningful on its own, the user
            // simply will not see a report.
            Log.d(PC_ACTION_TAG, "${this.javaClass.simpleName} runs without a target variable")
        }
        val work: () -> String = {
            try {
                perform()
            } catch (e: Exception) {
                Log.e(PC_ACTION_TAG, "PC runtime block failed", e)
                "error: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
        EXECUTOR.execute {
            val result = work()
            MAIN_HANDLER.post { writeResult(userVariable, result) }
        }
    }

    /** Performs the block's work and returns the text to expose to the user. */
    abstract fun perform(): String

    /** The variable this block writes into, or null for pure side-effect blocks. */
    open fun targetVariable(): UserVariable? = null

    /** True when the block is useless without a variable to write into. */
    open fun requiresVariable(): Boolean = false

    private fun writeResult(variable: UserVariable?, text: String) {
        if (variable != null) {
            variable.value = text
        }
    }

    protected fun appContext() = CatroidApplication.getAppContext()

    protected fun runtimeLog(): PcRuntimeLog = PcRuntimeManager.runtimeLog()

    companion object {
        /**
         * Small shared pool: PC runtime operations funnel through native and
         * process resources that must not be hammered in parallel.
         */
        private val EXECUTOR = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "pc-runtime-block").apply { isDaemon = true }
        }
        private val MAIN_HANDLER = Handler(Looper.getMainLooper())
    }
}

/**
 * Initializes the runtime: device detection, Vulkan capability probe and
 * component verification.
 */
class PcInitRuntimeAction : PcBaseAction() {

    override fun perform(): String {
        val context = appContext()
            ?: return "error: application context unavailable"
        val snapshot = PcRuntimeManager.initialize(context)
        val vulkan = snapshot.vulkan
        return buildString {
            append("state=").append(snapshot.state.name)
            append("; ").append(snapshot.device?.summary() ?: "device unknown")
            append("; vulkan=")
            append(
                when {
                    vulkan == null -> "not probed"
                    !vulkan.available -> "unavailable"
                    else -> vulkan.preferredDevice()?.let { "${it.name} ${it.apiVersion}" } ?: "no device"
                }
            )
            append("; windowsLayer=").append(if (snapshot.windowsLayerAvailable) "present" else "absent")
        }
    }
}

/** Shuts the runtime down and reports the final state. */
class PcShutdownRuntimeAction : PcBaseAction() {

    override fun perform(): String {
        val snapshot = PcRuntimeManager.shutdown()
        return "state=${snapshot.state.name}; runtime log retained in memory"
    }
}

/**
 * Reports one aspect of the runtime into a user variable. The set of
 * reportable aspects is [PcQuery]; the block's spinner index maps onto it.
 */
class PcQueryAction : PcBaseAction() {

    var querySelection: Int = 0
    var userVariable: UserVariable? = null

    override fun targetVariable(): UserVariable? = userVariable

    override fun requiresVariable(): Boolean = true

    override fun perform(): String {
        val queries = PcQuery.entries
        val query = queries.getOrElse(querySelection) { PcQuery.STATUS }
        if (PcRuntimeManager.state() == org.catrobat.catroid.pcruntime.PcRuntimeState.NOT_INITIALIZED) {
            // Cheap queries still work before init, but they must say so rather
            // than pretend the runtime is up.
            return "runtime not initialized (query ${query.name})"
        }
        return PcRuntimeManager.query(query)
    }
}

/**
 * Runs the native ARM64 smoke-test executable as a real child process and
 * reports its output and exit status.
 */
class PcNativeProbeAction : PcBaseAction() {

    var userVariable: UserVariable? = null

    override fun targetVariable(): UserVariable? = userVariable

    override fun requiresVariable(): Boolean = true

    override fun perform(): String {
        val context = appContext() ?: return "error: application context unavailable"
        if (PcRuntimeManager.state() == org.catrobat.catroid.pcruntime.PcRuntimeState.NOT_INITIALIZED) {
            PcRuntimeManager.initialize(context)
        }
        val result = PcRuntimeManager.runNativeProbe()
        if (!result.available) {
            return "probe unavailable: ${result.error?.format() ?: "unknown"}"
        }
        return buildString {
            append("exit=").append(result.exitCode)
            append(" mode=").append(result.execMode?.displayName ?: "?")
            append('\n').append(result.output.trim())
        }
    }
}

/**
 * Creates the on-disk skeleton of a Wine prefix. It intentionally does not
 * claim to produce a *working* Windows prefix: only `wineboot` can do that.
 */
class PcPreparePrefixAction : PcBaseAction() {

    var prefixName: Formula? = null
    var userVariable: UserVariable? = null

    override fun targetVariable(): UserVariable? = userVariable

    override fun perform(): String {
        val context = appContext() ?: return "error: application context unavailable"
        if (PcRuntimeManager.state() == org.catrobat.catroid.pcruntime.PcRuntimeState.NOT_INITIALIZED) {
            PcRuntimeManager.initialize(context)
        }
        val name = prefixName?.interpretString(scope)?.trim().orEmpty()
        if (name.isEmpty()) {
            return "error: prefix name is empty"
        }
        val result = PcRuntimeManager.preparePrefix(name)
        return result.summary()
    }
}

/**
 * Selects the graphics backend for the environment and reports whether the
 * device actually meets the upstream driver requirements. Selecting a backend
 * never enables it: a backend that fails its checks is reported as unusable.
 */
class PcGraphicsBackendAction : PcBaseAction() {

    var backendSelection: Int = 0
    var userVariable: UserVariable? = null

    override fun targetVariable(): UserVariable? = userVariable

    override fun perform(): String {
        val context = appContext() ?: return "error: application context unavailable"
        if (PcRuntimeManager.state() == org.catrobat.catroid.pcruntime.PcRuntimeState.NOT_INITIALIZED) {
            PcRuntimeManager.initialize(context)
        }
        val backend = GraphicsBackend.entries.getOrElse(backendSelection) { GraphicsBackend.NONE }
        val readiness = PcRuntimeManager.setGraphicsBackend(backend)
        if (readiness.checks.isEmpty()) {
            return "selected ${backend.displayName}; no Vulkan device, so no requirements could be " +
                "evaluated - the backend cannot be used"
        }
        return "selected ${backend.displayName}\n${readiness.summary()}"
    }
}

/**
 * Describes a native binary the user placed in the component area (or any
 * accessible path) without executing it. This is the discovery/validation
 * counterpart of the launch blocks: it answers "what is this file and can this
 * device run it?".
 */
class PcInspectBinaryAction : PcBaseAction() {

    var pathFormula: Formula? = null
    var userVariable: UserVariable? = null

    override fun targetVariable(): UserVariable? = userVariable

    override fun perform(): String {
        val path = pathFormula?.interpretString(scope)?.trim().orEmpty()
        if (path.isEmpty()) {
            return "error: no path given"
        }
        val file = File(path)
        if (!file.exists()) {
            return "error: ${file.absolutePath} does not exist"
        }
        if (!file.isFile) {
            return "error: ${file.absolutePath} is not a regular file"
        }
        if (!file.canRead()) {
            // Reading a file on shared storage needs a granted runtime
            // permission (or SAF). Saying "unknown format" here would send the
            // user looking for the wrong problem.
            return "error: ${file.absolutePath} exists but cannot be read - " +
                "check the storage permission of the app"
        }
        val context = appContext() ?: return "error: application context unavailable"
        val plan = PcExecPlanner.plan(
            executable = file,
            nativeLibraryDir = context.applicationInfo.nativeLibraryDir
        )
        return buildString {
            append(file.name).append(": ")
            append(BinaryInspector.describe(file))
            append('\n').append(plan.describe())
            if (plan.blockedReason != null) {
                append('\n').append("reason: ").append(plan.blockedReason)
            }
        }
    }
}
