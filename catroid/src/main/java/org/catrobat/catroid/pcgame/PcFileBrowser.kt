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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.File

/**
 * A small in-app file browser for the PC layer.
 *
 * The Storage Access Framework is not usable here: a folder chosen through
 * `ACTION_OPEN_DOCUMENT_TREE` cannot be bind mounted into the container and a
 * `content://` URI is not a path the runtime could start. The game folder and
 * the pack archives therefore have to be real files, which means browsing the
 * shared storage directly - possible once "All files access" is granted.
 *
 * The browser is intentionally dependency free (plain ListView + buttons) so
 * that it works inside the stage, where project layouts must not be trusted.
 */
object PcFileBrowser {

    enum class Mode { FOLDER, ARCHIVE }

    /** Archive containers the pack importer understands. */
    private val ARCHIVE_EXTENSIONS = listOf(
        ".zip", ".tar", ".tar.gz", ".tgz", ".tar.xz", ".txz", ".tzst", ".tar.zst", ".zst"
    )

    fun show(activity: Activity, mode: Mode, startPath: String?, onPicked: (File) -> Unit) {
        if (!PcPermissions.hasFileAccess(activity)) {
            AlertDialog.Builder(activity)
                .setTitle("File access needed")
                .setMessage(PcPermissions.fileAccessHint(activity))
                .setPositiveButton("Open settings") { _, _ -> PcPermissions.requestFileAccess(activity) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val start = resolveStartDirectory(startPath)
        var current: File = start
        // The dialog is created after the listeners, so they refer to it through
        // this holder (a local val would be "used before declaration").
        var dialogRef: Dialog? = null

        val pathField = EditText(activity).apply {
            setText(current.absolutePath)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        val listView = ListView(activity)
        val hintView = TextView(activity).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#90A4AE"))
            setText("")
        }

        var entries: List<File> = emptyList()

        fun refresh() {
            val children = current.listFiles()
            val folders = ArrayList<File>()
            val files = ArrayList<File>()
            if (children != null) {
                for (child in children) {
                    if (child.name.startsWith(".")) continue
                    if (child.isDirectory) {
                        folders.add(child)
                    } else if (mode == Mode.ARCHIVE && isArchive(child.name)) {
                        files.add(child)
                    }
                }
            }
            folders.sortBy { it.name.lowercase() }
            files.sortBy { it.name.lowercase() }
            entries = folders + files
            val labels = ArrayList<String>()
            if (mode == Mode.ARCHIVE) {
                labels.add(".. (up)")
            } else {
                labels.add(".. (up)")
            }
            for (entry in entries) {
                labels.add(if (entry.isDirectory) entry.name + "/" else entry.name + "   " + PcScriptBuilder.humanSize(entry.length()))
            }
            listView.adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, labels)
            pathField.setText(current.absolutePath)
            hintView.text = if (children == null) {
                "This folder cannot be read. Grant \"All files access\" for NewCatroid."
            } else {
                val foldersCount = entries.count { it.isDirectory }
                "$foldersCount folder(s), ${entries.size - foldersCount} file(s)"
            }
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            if (position == 0) {
                val parent = current.parentFile
                if (parent != null && parent.canRead()) {
                    current = parent
                    refresh()
                }
                return@setOnItemClickListener
            }
            val entry = entries.getOrNull(position - 1) ?: return@setOnItemClickListener
            if (entry.isDirectory) {
                current = entry
                refresh()
            } else if (mode == Mode.ARCHIVE) {
                onPicked(entry)
                dialogRef?.dismiss()
            }
        }

        val pathRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pathField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        pathRow.addView(Button(activity).apply {
            text = "Go"
            setOnClickListener {
                val typed = File(pathField.text.toString().trim())
                if (typed.isDirectory && typed.canRead()) {
                    current = typed
                    refresh()
                } else {
                    hintView.text = "Not a readable folder: ${pathField.text}"
                }
            }
        })

        val buttons = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(activity).apply {
            text = "Up"
            setOnClickListener {
                val parent = current.parentFile
                if (parent != null && parent.canRead()) {
                    current = parent
                    refresh()
                }
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val dialog = Dialog(activity)
        dialogRef = dialog
        var picked: File? = null

        buttons.addView(Button(activity).apply {
            text = "Cancel"
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (mode == Mode.FOLDER) {
            buttons.addView(Button(activity).apply {
                text = "Use this folder"
                setOnClickListener {
                    picked = current
                    dialog.dismiss()
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(pathRow)
            addView(hintView)
            addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(buttons)
        }

        dialog.setTitle(if (mode == Mode.FOLDER) "Choose the game folder" else "Choose a pack archive")
        dialog.setContentView(root)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        dialog.setOnDismissListener {
            val result = picked
            if (result != null) onPicked(result)
        }
        refresh()
        dialog.show()
    }

    fun isArchive(name: String): Boolean {
        val lower = name.lowercase()
        return ARCHIVE_EXTENSIONS.any { lower.endsWith(it) }
    }

    /**
     * Picks a sensible starting directory: the folder itself (or the parent of a
     * file), then the app folder, then the usual storage roots.
     */
    private fun resolveStartDirectory(startPath: String?): File {
        val candidates = ArrayList<File>()
        val raw = startPath?.trim().orEmpty()
        if (raw.isNotEmpty()) {
            val file = File(raw)
            if (file.isDirectory && file.canRead()) {
                candidates.add(file)
            } else if (file.parentFile != null && file.parentFile.isDirectory) {
                candidates.add(file.parentFile)
            }
        }
        candidates.add(File("/storage/emulated/0"))
        candidates.add(File("/sdcard"))
        candidates.add(File("/storage"))
        candidates.add(File("/"))
        for (candidate in candidates) {
            if (candidate.isDirectory && candidate.canRead()) return candidate
        }
        return File("/")
    }
}
