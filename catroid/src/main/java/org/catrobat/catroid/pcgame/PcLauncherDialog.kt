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

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.catrobat.catroid.stage.StageActivity

/**
 * The ready made launcher: game folder, executable, graphics driver, D3D layer,
 * runtime, resolution and the launch / stop / import buttons, all without
 * writing a single block.
 *
 * It is created from code on purpose: the launcher must also work in a project
 * that was built into an APK, and it does not depend on layout resources that a
 * project could override.
 */
object PcLauncherDialog {

    private const val TAG = "PcLauncherDialog"
    private var current: Dialog? = null

    private val resolutions = listOf(
        "800 x 600", "1024 x 768", "1280 x 720", "1366 x 768",
        "1600 x 900", "1920 x 1080", "2560 x 1440"
    )

    fun show(activity: Activity) {
        if (current?.isShowing == true) return
        PcGameManager.ensureProjectConfig()
        val config = PcGameManager.config

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 16), dp(activity, 16))
        }

        val gameFolder = EditText(activity).apply {
            setText(config.gameDir)
            hint = "/sdcard/Games/MyGame"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        root.addView(label(activity, "Game folder"))
        root.addView(gameFolder)
        root.addView(
            button(activity, "Browse folder…") {
                if (!PcPermissions.hasFileAccess(activity)) {
                    AlertDialog.Builder(activity)
                        .setTitle("File access needed")
                        .setMessage(PcPermissions.fileAccessHint(activity))
                        .setPositiveButton("Open settings") { _, _ ->
                            PcPermissions.requestFileAccess(activity)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    return@button
                }
                PcFileBrowser.show(activity, PcFileBrowser.Mode.FOLDER, gameFolder.text.toString()) { folder ->
                    gameFolder.setText(folder.absolutePath)
                    val found = PcGameManager.detectExecutables(folder)
                    if (found.isNotEmpty() && executable.text.isBlank()) {
                        executable.setText(found.first())
                    }
                    toast(
                        activity,
                        "Folder: ${folder.absolutePath} (${found.size} exe found)"
                    )
                }
            }
        )

        val executable = EditText(activity).apply {
            setText(config.executable)
            hint = "Game.exe"
            setSingleLine()
        }
        root.addView(label(activity, "Executable (inside the game folder)"))
        root.addView(executable)
        root.addView(
            button(activity, "Find executables") {
                val folder = PcGamePaths.resolveExisting(activity, gameFolder.text.toString())
                if (folder == null || !folder.isDirectory) {
                    toast(activity, "Set a readable game folder first")
                    return@button
                }
                val found = PcGameManager.detectExecutables(folder)
                if (found.isEmpty()) {
                    toast(activity, "No .exe found in that folder")
                } else {
                    executable.setText(found.first())
                    toast(activity, found.size.toString() + " executable(s) found, first one selected")
                }
            }
        )

        val driverSpinner = Spinner(activity)
        val driverItems = ArrayList<String>()
        val driverValues = ArrayList<String>()
        driverItems.add("System driver (no pack)")
        driverValues.add("system")
        for (pack in PcPackManager.packs(activity, PcPackType.GRAPHICS_DRIVER)) {
            driverItems.add(pack.summary())
            driverValues.add(pack.id)
        }
        setupSpinner(activity, driverSpinner, driverItems, driverValues, config.graphicsDriver)
        root.addView(label(activity, "Graphics driver (Turnip / Adreno / Zink pack)"))
        root.addView(driverSpinner)

        val d3dSpinner = Spinner(activity)
        val d3dItems = ArrayList<String>()
        val d3dValues = ArrayList<String>()
        d3dItems.add("None (Wine builtin Direct3D)")
        d3dValues.add("")
        for (pack in PcPackManager.packs(activity, PcPackType.D3D_LAYER)) {
            d3dItems.add(pack.summary())
            d3dValues.add(pack.id)
        }
        setupSpinner(activity, d3dSpinner, d3dItems, d3dValues, config.d3dLayer)
        root.addView(label(activity, "D3D translation layer (DXVK / VKD3D / D8VK)"))
        root.addView(d3dSpinner)

        val runtimeSpinner = Spinner(activity)
        val runtimeItems = ArrayList<String>()
        val runtimeValues = ArrayList<String>()
        for (pack in PcPackManager.packs(activity, PcPackType.RUNTIME)) {
            runtimeItems.add(pack.summary())
            runtimeValues.add(pack.id)
        }
        if (runtimeItems.isEmpty()) {
            runtimeItems.add("No runtime imported")
            runtimeValues.add("auto")
        }
        setupSpinner(activity, runtimeSpinner, runtimeItems, runtimeValues, config.runtime)
        root.addView(label(activity, "Wine + Box64 runtime"))
        root.addView(runtimeSpinner)

        val resolutionSpinner = Spinner(activity)
        setupSpinner(
            activity,
            resolutionSpinner,
            resolutions,
            resolutions,
            config.resolutionWidth.toString() + " x " + config.resolutionHeight
        )
        root.addView(label(activity, "Resolution"))
        root.addView(resolutionSpinner)

        val dpiField = EditText(activity).apply {
            setText(config.dpi.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
        }
        root.addView(label(activity, "DPI"))
        root.addView(dpiField)

        val showDisplay = CheckBox(activity).apply {
            text = "Show the game inside the stage"
            isChecked = config.showDisplay
        }
        root.addView(showDisplay)

        val touchControls = CheckBox(activity).apply {
            text = "Enable touch controls"
            isChecked = true
        }
        root.addView(touchControls)

        val touchEditor = CheckBox(activity).apply {
            text = "Edit touch layout (drag the buttons)"
            isChecked = false
        }
        touchEditor.setOnCheckedChangeListener { _, checked ->
            (activity as? StageActivity)?.setPcTouchEditMode(checked)
            if (checked) {
                PcGameManager.showTouchControls()
            }
        }
        root.addView(touchEditor)

        val hud = CheckBox(activity).apply {
            text = "DXVK performance HUD"
            isChecked = config.dxvkHud
        }
        root.addView(hud)

        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        actions.addView(
            button(activity, "Play") {
                applySettings(
                    activity, config, gameFolder, executable, driverValues, driverSpinner,
                    d3dValues, d3dSpinner, runtimeValues, runtimeSpinner, resolutions,
                    resolutionSpinner, dpiField, showDisplay, hud
                )
                val result = PcGameManager.launch(
                    activity,
                    executable.text.toString(),
                    null,
                    touchControls.isChecked
                )
                toast(activity, result)
            },
            weight(1f)
        )
        actions.addView(
            button(activity, "Stop") {
                PcGameManager.stop()
            },
            weight(1f)
        )
        root.addView(actions)

        val secondRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        secondRow.addView(
            button(activity, "Import pack…") { choosePackType(activity) },
            weight(1f)
        )
        secondRow.addView(
            button(activity, "Environment…") { PcEnvEditorDialog.show(activity) },
            weight(1f)
        )
        secondRow.addView(
            button(activity, "Self test") {
                val report = PcGameManager.selfTest(activity)
                showText(activity, "Environment check", report)
            },
            weight(1f)
        )
        root.addView(secondRow)

        root.addView(
            button(activity, "Save settings") {
                applySettings(
                    activity, config, gameFolder, executable, driverValues, driverSpinner,
                    d3dValues, d3dSpinner, runtimeValues, runtimeSpinner, resolutions,
                    resolutionSpinner, dpiField, showDisplay, hud
                )
                PcGameManager.saveConfig()
                toast(activity, "Settings saved to the project")
            }
        )

        val logView = TextView(activity).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#B0BEC5"))
            setPadding(0, dp(activity, 8), 0, 0)
            text = PcGameManager.logTail(20)
        }
        root.addView(label(activity, "Log"))
        root.addView(logView)

        val scroll = ScrollView(activity).apply { addView(root) }

        val dialog = Dialog(activity)
        dialog.setTitle("PC Game Launcher")
        dialog.setContentView(scroll)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.setOnDismissListener {
            current = null
            (activity as? StageActivity)?.setPcTouchEditMode(false)
        }
        current = dialog
        dialog.show()

        val handler = Handler(Looper.getMainLooper())
        val updater = object : Runnable {
            override fun run() {
                if (current?.isShowing != true) return
                logView.text = PcGameManager.logTail(20)
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(updater, 1000)
    }

    fun setTouchEditMode(enabled: Boolean) {
        val activity = StageActivity.activeStageActivity.get() ?: return
        activity.setPcTouchEditMode(enabled)
    }

    // ------------------------------------------------------------- internals

    private fun applySettings(
        activity: Activity,
        config: PcGameConfig,
        folder: EditText,
        executable: EditText,
        driverValues: List<String>,
        driverSpinner: Spinner,
        d3dValues: List<String>,
        d3dSpinner: Spinner,
        runtimeValues: List<String>,
        runtimeSpinner: Spinner,
        resolutions: List<String>,
        resolutionSpinner: Spinner,
        dpiField: EditText,
        showDisplay: CheckBox,
        hud: CheckBox
    ) {
        config.gameDir = folder.text.toString().trim()
        config.executable = executable.text.toString().trim()
        config.graphicsDriver = valueAt(driverValues, driverSpinner.selectedItemPosition, "system")
        config.d3dLayer = valueAt(d3dValues, d3dSpinner.selectedItemPosition, "")
        config.runtime = valueAt(runtimeValues, runtimeSpinner.selectedItemPosition, "auto")
        val resolution = resolutions.getOrNull(resolutionSpinner.selectedItemPosition) ?: "1280 x 720"
        val parts = resolution.split("x").map { it.trim() }
        if (parts.size == 2) {
            config.resolutionWidth = parts[0].toIntOrNull() ?: config.resolutionWidth
            config.resolutionHeight = parts[1].toIntOrNull() ?: config.resolutionHeight
        }
        config.dpi = dpiField.text.toString().toIntOrNull() ?: config.dpi
        config.showDisplay = showDisplay.isChecked
        config.dxvkHud = hud.isChecked
        PcGameManager.config = config
        Log.i(TAG, "Launcher settings applied from ${activity.javaClass.simpleName}")
    }

    private fun valueAt(values: List<String>, index: Int, fallback: String): String {
        if (index < 0 || index >= values.size) return fallback
        return values[index]
    }

    private fun setupSpinner(
        activity: Activity,
        spinner: Spinner,
        items: List<String>,
        values: List<String>,
        selectedValue: String
    ) {
        val adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_item,
            items
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        val index = values.indexOfFirst { it.equals(selectedValue, ignoreCase = true) }
        if (index >= 0) spinner.setSelection(index)
    }

    private fun choosePackType(activity: Activity) {
        val types = arrayOf(
            "Detect automatically", "Graphics driver", "D3D layer (DXVK/VKD3D)",
            "Wine/Box64 runtime", "Container rootfs"
        )
        AlertDialog.Builder(activity)
            .setTitle("Import pack")
            .setItems(types) { _, which ->
                val typeName = when (which) {
                    1 -> "driver"
                    2 -> "d3d"
                    3 -> "runtime"
                    4 -> "rootfs"
                    else -> ""
                }
                importPack(activity, typeName)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importPack(activity: Activity, typeName: String) {
        if (!PcPermissions.hasFileAccess(activity)) {
            AlertDialog.Builder(activity)
                .setTitle("File access needed")
                .setMessage(PcPermissions.fileAccessHint(activity))
                .setPositiveButton("Open settings") { _, _ ->
                    PcPermissions.requestFileAccess(activity)
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        PcFileBrowser.show(activity, PcFileBrowser.Mode.ARCHIVE, null) { archive ->
            Thread {
                val type = if (typeName.isBlank()) null else PcPackType.fromName(typeName)
                val result = try {
                    PcPackManager.importFromPath(activity, type, archive.absolutePath)
                } catch (e: Exception) {
                    Log.e(TAG, "Import failed", e)
                    null
                }
                activity.runOnUiThread {
                    val message = result?.message ?: "Import failed"
                    PcGameManager.appendLog("[newcatroid] $message")
                    toast(activity, message)
                    if (chartShowsLauncher()) {
                        current?.dismiss()
                        show(activity)
                    }
                }
            }.start()
        }
    }

    private fun chartShowsLauncher(): Boolean = current?.isShowing == true

    private fun showText(activity: Activity, title: String, text: String) {
        val view = TextView(activity).apply {
            this.text = text
            textSize = 12f
            setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 16), dp(activity, 8))
        }
        val scroll = ScrollView(activity).apply { addView(view) }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun label(activity: Activity, text: String): TextView {
        return TextView(activity).apply {
            this.text = text
            textSize = 13f
            setPadding(0, dp(activity, 10), 0, dp(activity, 2))
        }
    }

    private fun button(activity: Activity, text: String, onClick: () -> Unit): Button {
        return Button(activity).apply {
            this.text = text
            setOnClickListener { onClick() }
        }
    }

    private fun LinearLayout.addView(view: View, weight: Float) {
        val layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        addView(view, layoutParams)
    }

    private fun weight(value: Float): Float = value

    private fun dp(activity: Activity, value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            activity.resources.displayMetrics
        ).toInt()
    }

    private fun toast(activity: Activity, message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }
}
