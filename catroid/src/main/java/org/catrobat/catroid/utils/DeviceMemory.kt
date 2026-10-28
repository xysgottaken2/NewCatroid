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

package org.catrobat.catroid.utils

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import org.catrobat.catroid.CatroidApplication

/**
 * Device memory (`RAM`) in one place, for the `device RAM` sensors and the
 * "does this phone have enough RAM" blocks.
 *
 * `ActivityManager.MemoryInfo` is a binder call, and a sensor that is read inside
 * a "forever" loop would ask for it every frame. The values are therefore cached
 * for [CACHE_MILLIS]: at most two binder calls per second, which is accurate
 * enough for a requirement check and cheap enough for 60 fps.
 */
object DeviceMemory {

    private const val TAG = "DeviceMemory"
    private const val CACHE_MILLIS = 500L
    private const val MB = 1024.0 * 1024.0

    /**
     * One reading of the memory state.
     *
     * @param totalMb physical RAM of the device.
     * @param availableMb RAM the system can hand out right now (cached pages that
     *        can be reclaimed are already counted as available).
     * @param usedMb `totalMb - availableMb`.
     * @param usedPercent 0..100, rounded.
     * @param appLimitMb how much the Java heap of NewCatroid itself may grow to.
     * @param lowMemory the system's own "we are running out of memory" flag.
     */
    data class Snapshot(
        val totalMb: Int,
        val availableMb: Int,
        val usedMb: Int,
        val usedPercent: Int,
        val appLimitMb: Int,
        val lowMemory: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        /** RAM the device has left for a native process such as Wine. */
        val freeForGameMb: Int get() = availableMb

        /** `8 GB / 5.1 GB free (36% used)` */
        fun describe(): String =
            "${formatGb(totalMb)} total, ${formatGb(availableMb)} free " +
                "($usedPercent% used)"
    }

    @Volatile
    private var cached: Snapshot? = null

    /** Drops the cache, so the next read is fresh (used by the self test). */
    fun invalidate() {
        cached = null
    }

    /** Reads the memory state, from the cache when it is younger than 500 ms. */
    fun snapshot(): Snapshot {
        val previous = cached
        if (previous != null && System.currentTimeMillis() - previous.timestamp < CACHE_MILLIS) {
            return previous
        }
        val fresh = read()
        cached = fresh
        return fresh
    }

    private fun read(): Snapshot {
        val totalMb: Int
        val availableMb: Int
        var lowMemory = false
        try {
            val context = CatroidApplication.getAppContext()
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val info = ActivityManager.MemoryInfo()
            manager?.getMemoryInfo(info)
            totalMb = (info.totalMem / MB).toInt()
            availableMb = (info.availMem / MB).toInt()
            lowMemory = info.lowMemory
        } catch (e: Exception) {
            Log.w(TAG, "Cannot read the memory info: ${e.message}")
            return Snapshot(0, 0, 0, 0, appHeapLimitMb(), false)
        }
        if (totalMb <= 0) {
            return Snapshot(0, 0, 0, 0, appHeapLimitMb(), lowMemory)
        }
        val usedMb = (totalMb - availableMb).coerceAtLeast(0)
        val usedPercent = ((usedMb * 100L) / totalMb).toInt().coerceIn(0, 100)
        return Snapshot(totalMb, availableMb, usedMb, usedPercent, appHeapLimitMb(), lowMemory)
    }

    private fun appHeapLimitMb(): Int {
        return try {
            (Runtime.getRuntime().maxMemory() / MB).toInt()
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Is [requiredMb] of RAM available?
     *
     * [mode] decides what is compared:
     *  * `total` - only the physical RAM of the device (a hardware check),
     *  * `free`  - only the RAM that is free right now,
     *  * `both`  - both of the above (the default, the safest answer).
     *
     * The comparison also fails when the system reports [Snapshot.lowMemory],
     * because the killer would end the game anyway.
     */
    fun isEnough(requiredMb: Int, mode: String = "both"): Boolean {
        val snapshot = snapshot()
        if (snapshot.totalMb <= 0) return false
        if (snapshot.lowMemory) return false
        if (requiredMb <= 0) return true
        val normalized = normalizeMode(mode)
        val totalOk = requiredMb <= snapshot.totalMb
        val freeOk = requiredMb <= snapshot.freeForGameMb
        return when (normalized) {
            "total" -> totalOk
            "free" -> freeOk
            else -> totalOk && freeOk
        }
    }

    /** Accepts the values the block field may contain. */
    fun normalizeMode(mode: String?): String {
        val value = mode?.trim()?.lowercase() ?: ""
        return when {
            value.isEmpty() -> "both"
            value.startsWith("tot") || value.startsWith("dev") || value.startsWith("hardware") -> "total"
            value.startsWith("free") || value.startsWith("avail") || value.startsWith("now") -> "free"
            value.startsWith("both") || value.startsWith("all") -> "both"
            else -> "both"
        }
    }

    /**
     * Accepts the values the "device RAM" block field may contain.
     *
     * @return one of `total`, `available`, `used`, `percent`, `app`, `low`.
     */
    fun normalizeKind(kind: String?): String {
        val value = kind?.trim()?.lowercase()?.replace("_", " ")?.replace("-", " ") ?: ""
        return when {
            value.isEmpty() -> "total"
            value.startsWith("tot") -> "total"
            value.startsWith("avail") || value.startsWith("free") -> "available"
            value.startsWith("used") || value == "usage" -> "used"
            value.startsWith("percent") || value.contains("%") || value.startsWith("load") -> "percent"
            value.startsWith("app") || value.startsWith("heap") || value.startsWith("java") -> "app"
            value.startsWith("low") -> "low"
            else -> "total"
        }
    }

    /**
     * Value of [kind] from [snapshot], as it is stored in a variable.
     *
     * Always a [Double]: `Conversions.convertArgumentToDouble` casts a non
     * [String] formula value to `Double`, so storing an `Int` would make the
     * variable blow up as soon as it is used in a formula.
     */
    fun valueOf(kind: String?, snapshot: Snapshot = snapshot()): Double {
        return when (normalizeKind(kind)) {
            "total" -> snapshot.totalMb.toDouble()
            "available" -> snapshot.availableMb.toDouble()
            "used" -> snapshot.usedMb.toDouble()
            "percent" -> snapshot.usedPercent.toDouble()
            "app" -> snapshot.appLimitMb.toDouble()
            "low" -> if (snapshot.lowMemory) 1.0 else 0.0
            else -> snapshot.totalMb.toDouble()
        }
    }

    /** `8192` -> `8.0 GB`, for messages and docs. */
    fun formatGb(megabytes: Int): String {
        if (megabytes <= 0) return "unknown"
        val gb = megabytes / 1024.0
        return if (gb >= 1.0) String.format("%.1f GB", gb) else "$megabytes MB"
    }

    /**
     * One log line with everything a requirement check looks at, used by the
     * "RAM check" block and the PC self test.
     */
    fun describeForLog(): String {
        val snapshot = snapshot()
        return "RAM: ${formatGb(snapshot.totalMb)} total, ${formatGb(snapshot.availableMb)} free, " +
            "${snapshot.usedPercent}% used, app limit ${formatGb(snapshot.appLimitMb)}" +
            (if (snapshot.lowMemory) ", system reports low memory" else "")
    }
}
