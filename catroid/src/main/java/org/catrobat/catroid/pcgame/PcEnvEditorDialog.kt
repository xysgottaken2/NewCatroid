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

package org.catrobat.catroid.pcgame

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Editor for the environment variables of the PC layer.
 *
 * This is the "click and change things" surface the launcher needs: the user can
 * add, edit and delete variables without touching blocks, and the well known
 * tuning sets of the Wine/Box64/DXVK stack are one tap away (they are the values
 * Winlator, Mobox and the DXVK/Box64 documentation recommend, see
 * `docs/PC_GAMES.md`).
 */
object PcEnvEditorDialog {

    private const val TAG = "PcEnvEditor"

    /** A named group of variables that is applied as a whole. */
    private data class Preset(val label: String, val values: Map<String, String>)

    private val presets = listOf(
        Preset(
            "DXVK HUD (fps + frametimes)",
            linkedMapOf("DXVK_HUD" to "fps,frametimes,gpuload,devinfo")
        ),
        Preset(
            "Frame limit 60 fps",
            linkedMapOf("DXVK_FRAME_RATE" to "60")
        ),
        Preset(
            "Box64 - performance",
            linkedMapOf(
                "BOX64_DYNAREC_BIGBLOCK" to "3",
                "BOX64_DYNAREC_STRONGMEM" to "1",
                "BOX64_DYNAREC_SAFEFLAGS" to "1",
                "BOX64_DYNAREC_FASTROUND" to "1"
            )
        ),
        Preset(
            "Box64 - stability",
            linkedMapOf(
                "BOX64_DYNAREC_BIGBLOCK" to "0",
                "BOX64_DYNAREC_STRONGMEM" to "2",
                "BOX64_DYNAREC_SAFEFLAGS" to "2",
                "BOX64_DYNAREC_FASTROUND" to "0"
            )
        ),
        Preset(
            "Old games (2003 GL extensions)",
            linkedMapOf("MESA_EXTENSION_MAX_YEAR" to "2003")
        ),
        Preset(
            "Zink (OpenGL over Vulkan)",
            linkedMapOf(
                "GALLIUM_DRIVER" to "zink",
                "ZINK_DESCRIPTORS" to "lazy",
                "MESA_GLTHREAD" to "true"
            )
        ),
        Preset(
            "Wine debug off / audio",
            linkedMapOf(
                "WINEDEBUG" to "-all",
                "PULSE_LATENCY_MSEC" to "60"
            )
        )
    )

    fun show(activity: Activity) {
        PcGameManager.ensureProjectConfig()
        val working = LinkedHashMap(PcGameManager.config.extraEnv)

        val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val dialog = Dialog(activity)

        fun addRow(name: String = "", value: String = "") {
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            val nameField = EditText(activity).apply {
                setText(name)
                hint = "NAME"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setSingleLine()
            }
            val valueField = EditText(activity).apply {
                setText(value)
                hint = "value"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setSingleLine()
            }
            row.addView(nameField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(valueField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
            val remove = Button(activity).apply {
                text = "×"
                setOnClickListener {
                    rows.removeView(row)
                }
            }
            row.addView(remove, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // keep the typed content reachable when saving
            row.tag = nameField to valueField
            rows.addView(row)
        }

        for ((key, value) in working) addRow(key, value)

        val addButton = Button(activity).apply {
            text = "+ Add variable"
            setOnClickListener { addRow() }
        }
        val presetButton = Button(activity).apply {
            text = "Presets…"
            setOnClickListener {
                val labels = presets.map { it.label }.toTypedArray()
                AlertDialog.Builder(activity)
                    .setTitle("Add a tuning preset")
                    .setItems(labels) { _, which ->
                        val preset = presets[which]
                        for ((key, value) in preset.values) {
                            working[key] = value
                            addRow(key, value)
                        }
                        Toast.makeText(
                            activity,
                            "${preset.label}: ${preset.values.size} variable(s) added",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        val info = TextView(activity).apply {
            text = "These variables are exported in the generated launch.sh and win over " +
                "the defaults of the layer. NAME must look like DXVK_HUD, BOX64_DYNAREC_BIGBLOCK or " +
                "MESA_EXTENSION_MAX_YEAR."
            textSize = 12f
            setTextColor(Color.parseColor("#90A4AE"))
            setPadding(0, 8, 0, 8)
        }

        val buttons = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(activity).apply {
            text = "Save"
            setOnClickListener {
                val updated = LinkedHashMap<String, String>()
                for (index in 0 until rows.childCount) {
                    val row = rows.getChildAt(index) as? LinearLayout ?: continue
                    @Suppress("UNCHECKED_CAST")
                    val pair = row.tag as? Pair<EditText, EditText> ?: continue
                    val name = pair.first.text.toString().trim()
                    val value = pair.second.text.toString()
                    if (name.isEmpty()) continue
                    if (!PcScriptBuilder.isValidVariableName(name)) {
                        Toast.makeText(
                            activity,
                            "Invalid variable name: $name",
                            Toast.LENGTH_LONG
                        ).show()
                        return@setOnClickListener
                    }
                    updated[name] = value
                }
                PcGameManager.replaceEnvironment(updated)
                PcGameManager.saveConfig()
                Toast.makeText(
                    activity,
                    "${updated.size} environment variable(s) saved",
                    Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(Button(activity).apply {
            text = "Cancel"
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(info)
            addView(ScrollView(activity).apply {
                addView(rows)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(addButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(presetButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
            addView(buttons)
        }

        dialog.setTitle("PC environment variables")
        dialog.setContentView(root)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        dialog.show()
    }
}
