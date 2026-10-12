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

package org.catrobat.catroid.pcruntime

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "PcProcess"
private const val MAX_CAPTURED_LINES = 500

data class PcProcessResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean,
    val cancelled: Boolean,
    val error: PcRuntimeError?,
    val durationMs: Long
) {
    val isSuccess: Boolean
        get() = error == null && !timedOut && !cancelled && exitCode == 0
}

/**
 * Owns the lifecycle of one running native process. Native PC software (Wine,
 * the CPU translator, games) is long running, so the handle exposes explicit
 * cancellation instead of relying on the caller's thread being interrupted.
 */
class PcProcessHandle internal constructor(
    val label: String,
    val plan: PcExecPlan,
    private val process: Process,
    private val log: PcRuntimeLog
) {
    private val outputLines = CopyOnWriteArrayList<String>()
    private val finished = AtomicBoolean(false)
    private val exitValue = AtomicInteger(Int.MIN_VALUE)

    val pid: Long
        get() = try {
            val field = process.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            (field.get(process) as? Int)?.toLong() ?: -1L
        } catch (e: Exception) {
            -1L
        }

    val isAlive: Boolean
        get() = !finished.get()

    internal fun appendOutput(line: String) {
        if (outputLines.size >= MAX_CAPTURED_LINES) {
            outputLines.removeAt(0)
        }
        outputLines.add(line)
        log.debug(TAG, "[$label] $line")
    }

    internal fun markFinished(code: Int) {
        exitValue.set(code)
        finished.set(true)
    }

    /**
     * Drains the child's stdout (stderr is redirected into it) on a daemon
     * thread, so a chatty process can never deadlock on a full pipe. Reaching
     * EOF only means the child closed its streams, which is not the same as the
     * process having exited; completion is recorded by await()/destroy().
     */
    internal fun startOutputPump() {
        val pump = Thread {
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        appendOutput(line)
                        line = reader.readLine()
                    }
                }
            } catch (e: IOException) {
                log.debug(TAG, "output pump for '$label' ended: ${e.message}")
            }
        }
        pump.isDaemon = true
        pump.name = "pc-process-$label"
        pump.start()
    }

    fun capturedOutput(): String = outputLines.joinToString("\n")

    /**
     * Waits for completion. Must never be called from the Android main thread;
     * the blocks dispatch every launch onto a background executor.
     */
    fun await(timeoutMs: Long): PcProcessResult {
        val started = System.currentTimeMillis()
        var timedOut = false
        try {
            val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!completed) {
                timedOut = true
                destroy()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            destroy()
            return PcProcessResult(
                -1,
                capturedOutput(),
                false,
                true,
                PcErrorCatalog.describe(PcErrorCode.PROCESS_CANCELLED, "interrupted while waiting"),
                System.currentTimeMillis() - started
            )
        }
        val code = try {
            process.exitValue()
        } catch (e: IllegalThreadStateException) {
            -1
        }
        markFinished(code)
        return PcProcessResult(
            exitCode = code,
            output = capturedOutput(),
            timedOut = timedOut,
            cancelled = false,
            error = if (timedOut) {
                PcErrorCatalog.describe(PcErrorCode.PROCESS_TIMEOUT, "timeout after ${timeoutMs}ms")
            } else {
                null
            },
            durationMs = System.currentTimeMillis() - started
        )
    }

    fun destroy() {
        try {
            process.destroy()
            if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(1500, TimeUnit.MILLISECONDS)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to destroy process $label", e)
        }
        if (!finished.get()) {
            val code = try {
                process.exitValue()
            } catch (e: IllegalThreadStateException) {
                -1
            }
            markFinished(code)
        }
    }
}

/**
 * Launches native processes using [PcExecPlanner] and collects their output.
 * Output is drained on a daemon thread so that a chatty child process can never
 * deadlock on a full pipe.
 */
class PcProcessExecutor(private val log: PcRuntimeLog) {

    fun launch(label: String, plan: PcExecPlan, workingDir: File? = null): Result<PcProcessHandle> {
        if (plan.mode == PcExecMode.BLOCKED) {
            val error = PcErrorCatalog.describe(
                PcErrorCode.EXECUTION_BLOCKED_BY_ANDROID,
                plan.blockedReason ?: "unknown"
            )
            log.error(TAG, "Refusing to launch '$label': $error")
            return Result.failure(IllegalStateException(error.hint))
        }
        if (plan.command.isEmpty()) {
            return Result.failure(IllegalStateException("empty command line"))
        }

        log.info(TAG, "Launching [$label]: ${plan.command.joinToString(" ")} (${plan.mode.displayName})")

        return try {
            val builder = ProcessBuilder(plan.command)
            builder.environment().putAll(plan.environment)
            builder.redirectErrorStream(true)
            if (workingDir != null && workingDir.isDirectory) {
                builder.directory(workingDir)
            }
            val process = builder.start()
            val handle = PcProcessHandle(label, plan, process, log)
            handle.startOutputPump()
            Result.success(handle)
        } catch (e: IOException) {
            log.error(TAG, "Launch of '$label' failed: ${e.message}")
            Result.failure(e)
        } catch (e: SecurityException) {
            log.error(TAG, "Launch of '$label' denied by the platform: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Convenience helper for short lived helper binaries (the native probe, a
     * version query). Always runs on the calling thread, so callers must be on
     * a background executor.
     */
    fun runBlocking(label: String, plan: PcExecPlan, timeoutMs: Long, workingDir: File? = null): PcProcessResult {
        val launched = launch(label, plan, workingDir)
        val handle = launched.getOrElse {
            return PcProcessResult(
                exitCode = -1,
                output = "",
                timedOut = false,
                cancelled = false,
                error = PcErrorCatalog.describe(PcErrorCode.PROCESS_LAUNCH_FAILED, it.message ?: "unknown"),
                durationMs = 0
            )
        }
        return handle.await(timeoutMs)
    }
}
