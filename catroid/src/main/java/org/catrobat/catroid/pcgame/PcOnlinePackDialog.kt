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
import android.util.Log
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Downloads one archive with a progress dialog, imports it as a pack and selects
 * it. Used by the online list and by the "download from a link" block.
 */
object PcOnlineInstaller {

    private const val TAG = "PcOnlineInstaller"

    /**
     * @param type `null` means "detect the kind from the archive content".
     * @param onFinished called on the UI thread with the imported pack (or
     *        `null`) and a message that can be shown to the user.
     */
    fun downloadAndInstall(
        activity: Activity,
        type: PcPackType?,
        url: String,
        fileName: String?,
        preferredId: String? = null,
        onFinished: (PcPack?, String) -> Unit
    ) {
        var cancelled = false
        val dialog = Dialog(activity)

        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
        }
        val message = TextView(activity).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#E0E0E0"))
            text = "Preparing the download…"
            setPadding(0, 12, 0, 12)
        }
        val cancelButton = Button(activity).apply {
            text = "Cancel"
            setOnClickListener {
                cancelled = true
                text = "Cancelling…"
                isEnabled = false
            }
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
            addView(message)
            addView(bar)
            addView(
                cancelButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        dialog.setTitle(if (type == null) "Downloading PC component" else "Downloading ${type.label}")
        dialog.setContentView(root)
        dialog.setCancelable(false)
        dialog.show()

        fun finish(pack: PcPack?, text: String) {
            if (dialog.isShowing) dialog.dismiss()
            PcGameManager.appendLog("[newcatroid] $text")
            Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
            onFinished(pack, text)
        }

        Thread {
            var downloaded: File? = null
            try {
                val file = PcDownloader.download(
                    activity,
                    url,
                    fileName,
                    onProgress = { copied, total ->
                        activity.runOnUiThread {
                            if (total > 0) {
                                bar.progress = ((copied * 1000) / total).toInt().coerceIn(0, 1000)
                                message.text = "Downloading… ${copied * 100 / total}%  (" +
                                    PcScriptBuilder.humanSize(copied) + " / " +
                                    PcScriptBuilder.humanSize(total) + ")"
                            } else {
                                message.text = "Downloading… " + PcScriptBuilder.humanSize(copied)
                            }
                        }
                    },
                    isCancelled = { cancelled }
                )

                activity.runOnUiThread {
                    bar.isIndeterminate = true
                    message.text = "Installing the archive… (a rootfs can take a few minutes)"
                }

                downloaded = file
                val result = PcPackManager.importFromPath(
                    activity,
                    type,
                    file.absolutePath,
                    preferredId?.takeIf { it.isNotBlank() }
                )
                file.delete()

                val pack = result.pack
                if (!result.success || pack == null) {
                    activity.runOnUiThread { finish(null, "Install failed: ${result.message}") }
                    return@Thread
                }

                val selected = PcPackSelection.select(pack.type, pack)
                PcGameManager.saveConfig()
                activity.runOnUiThread {
                    finish(pack, "${pack.summary()} installed and selected as $selected")
                }
            } catch (e: PcDownloader.CancelledException) {
                downloaded?.delete()
                activity.runOnUiThread {
                    if (dialog.isShowing) dialog.dismiss()
                    Toast.makeText(activity, "Download cancelled", Toast.LENGTH_SHORT).show()
                    onFinished(null, "Download cancelled")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Download of $url failed", e)
                downloaded?.delete()
                activity.runOnUiThread { finish(null, "Download failed: ${e.message}") }
            }
        }.start()
    }
}

/**
 * "PC components from the internet" - the clickable catalogue.
 *
 * The user sees what the configured repositories publish (the Turnip drivers of
 * K11MCH1/AdrenoToolsDrivers, the DXVK / VKD3D / D8VK releases, Box64 and a
 * Debian rootfs), taps an entry and it is downloaded, imported and selected - no
 * file has to be on the phone beforehand. Already installed packs are listed on
 * top: tap to select, long press to delete.
 */
object PcOnlinePackDialog {

    private const val TAG = "PcOnlinePackDialog"

    fun show(activity: Activity, initialType: PcPackType = PcPackType.GRAPHICS_DRIVER) {
        PcGameManager.ensureProjectConfig()

        var type = initialType
        var loading = false
        var assets: List<PcOnlineAsset> = emptyList()

        val dialog = Dialog(activity)
        val labels = ArrayList<String>()
        val adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, labels)

        val intro = TextView(activity).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#90A4AE"))
            text = "Everything listed here is fetched from a public GitHub repository when you " +
                "tap it and stored as a normal pack, so the next launch uses it without asking " +
                "again. Nothing is bundled with NewCatroid and you need no account."
            setPadding(0, 0, 0, 12)
        }

        val categoryRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val categoryButtons = LinkedHashMap<PcPackType, Button>()
        for (candidate in PcPackType.all()) {
            val button = Button(activity).apply {
                text = shortName(candidate)
            }
            categoryButtons[candidate] = button
            categoryRow.addView(
                button,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }

        val repoInfo = TextView(activity).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#78909C"))
            setPadding(0, 8, 0, 8)
            text = ""
        }

        val repoButtons = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val reloadButton = Button(activity).apply { text = "⟳ Reload" }
        val addRepoButton = Button(activity).apply { text = "+ Add repository" }

        val installedTitle = TextView(activity).apply {
            text = "Installed"
            textSize = 13f
            setTextColor(Color.parseColor("#CFD8DC"))
            setPadding(0, 8, 0, 4)
        }
        val installedBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 8)
        }
        val installedScroll = ScrollView(activity).apply { addView(installedBox) }

        val status = TextView(activity).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#B0BEC5"))
            setPadding(0, 4, 0, 6)
            text = ""
        }

        val listView = ListView(activity)
        listView.adapter = adapter

        val closeButton = Button(activity).apply { text = "Close" }

        fun toast(text: String) {
            Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
        }

        fun isSelected(pack: PcPack): Boolean {
            val config = PcGameManager.config
            return when (type) {
                PcPackType.GRAPHICS_DRIVER -> config.graphicsDriver.equals(pack.id, true)
                PcPackType.D3D_LAYER -> config.d3dLayer.equals(pack.id, true)
                PcPackType.RUNTIME -> config.runtime.equals(pack.id, true)
                PcPackType.ROOTFS -> config.containerDir.trimEnd('/')
                    .equals(pack.directory.absolutePath.trimEnd('/'), true)
            }
        }

        fun refreshInstalled() {
            installedBox.removeAllViews()
            val packs = PcPackManager.packs(activity, type)
            if (packs.isEmpty()) {
                installedBox.addView(TextView(activity).apply {
                    text = "Nothing installed yet - pick an entry below."
                    textSize = 12f
                    setTextColor(Color.parseColor("#78909C"))
                })
                return
            }
            for (pack in packs) {
                val row = Button(activity).apply {
                    text = (if (isSelected(pack)) "✓ " else "") + pack.summary()
                    setOnClickListener {
                        PcPackSelection.select(type, pack)
                        PcGameManager.saveConfig()
                        toast("${pack.summary()} selected as ${type.label}")
                        refreshInstalled()
                    }
                    setOnLongClickListener {
                        AlertDialog.Builder(activity)
                            .setTitle("Delete ${pack.displayName}?")
                            .setMessage(
                                "The pack is removed from the device. A launcher that still " +
                                    "points at it falls back to the default afterwards."
                            )
                            .setPositiveButton("Delete") { _, _ ->
                                toast(
                                    if (PcPackManager.delete(activity, pack)) {
                                        "${pack.displayName} deleted"
                                    } else {
                                        "Cannot delete ${pack.displayName}"
                                    }
                                )
                                refreshInstalled()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                        true
                    }
                }
                installedBox.addView(
                    row,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        }

        fun updateCategoryButtons() {
            for (item in categoryButtons) {
                val active = item.key == type
                item.value.alpha = if (active) 1f else 0.55f
                item.value.text = (if (active) "▸ " else "") + shortName(item.key)
            }
            val repos = PcOnlineCatalog.all(activity, type)
            repoInfo.text = "Sources (hold to remove a custom one): " +
                repos.joinToString(" · ") { it.slug }
        }

        fun renderAssets() {
            labels.clear()
            val multipleRepos = assets.map { it.repoSlug }.distinct().size > 1
            for (asset in assets) {
                labels.add(
                    if (multipleRepos) {
                        "[" + asset.repoSlug.substringAfter('/') + "] " + asset.describe()
                    } else {
                        asset.describe()
                    }
                )
            }
            adapter.notifyDataSetChanged()
        }

        fun loadAssets() {
            if (loading) return
            loading = true
            assets = emptyList()
            renderAssets()
            status.text = "Contacting GitHub…"
            val snapshot = type
            val repos = PcOnlineCatalog.all(activity, snapshot)
            Thread {
                val found = ArrayList<PcOnlineAsset>()
                val problems = ArrayList<String>()
                for (repo in repos) {
                    try {
                        found.addAll(PcGithubClient.assets(repo, snapshot))
                    } catch (e: Exception) {
                        Log.w(TAG, "Cannot list ${repo.slug}: ${e.message}")
                        problems.add("${repo.slug}: ${e.message}")
                    }
                }
                activity.runOnUiThread {
                    loading = false
                    if (type != snapshot) return@runOnUiThread
                    assets = found
                    renderAssets()
                    status.text = when {
                        found.isEmpty() && problems.isNotEmpty() -> problems.joinToString("\n")
                        found.isEmpty() ->
                            "No compatible archive in the listed repositories. Add your own " +
                                "repository, or download the archive in a browser and use " +
                                "\"Choose a … zip\"."
                        problems.isNotEmpty() ->
                            "${found.size} archive(s) found, ${problems.size} repository " +
                                "error(s): ${problems.first()}"
                        else -> "${found.size} archive(s) - tap one to download and install it"
                    }
                }
            }.start()
        }

        fun addRepository() {
            val input = EditText(activity).apply {
                hint = "owner/repository"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setSingleLine()
            }
            AlertDialog.Builder(activity)
                .setTitle("Add a GitHub repository")
                .setMessage(
                    "Enter a public repository that publishes pack archives as release assets, " +
                        "for example K11MCH1/AdrenoToolsDrivers. It is added to the " +
                        "\"${type.label}\" list."
                )
                .setView(input)
                .setPositiveButton("Add") { _, _ ->
                    val added = PcOnlineCatalog.addCustom(activity, type, input.text.toString())
                    if (added == null) {
                        toast("Not an owner/repository value, or already in the list")
                    } else {
                        toast("Repository ${added.slug} added")
                        updateCategoryButtons()
                        loadAssets()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        fun download(asset: PcOnlineAsset) {
            val extra = if (asset.releaseTag.isNotEmpty()) " (${asset.releaseTag})" else ""
            AlertDialog.Builder(activity)
                .setTitle(asset.name)
                .setMessage(
                    "Download ${PcScriptBuilder.humanSize(asset.size)} from ${asset.repoSlug}" +
                        "$extra and install it as a ${type.label}?"
                )
                .setPositiveButton("Download") { _, _ ->
                    PcOnlineInstaller.downloadAndInstall(
                        activity,
                        type,
                        asset.downloadUrl,
                        asset.name,
                        preferredId = asset.suggestedPackId()
                    ) { _, _ -> refreshInstalled() }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        for (item in categoryButtons) {
            item.value.setOnClickListener {
                if (type == item.key) return@setOnClickListener
                type = item.key
                updateCategoryButtons()
                refreshInstalled()
                loadAssets()
            }
        }
        reloadButton.setOnClickListener { loadAssets() }
        addRepoButton.setOnClickListener { addRepository() }
        repoInfo.setOnLongClickListener {
            val custom = PcOnlineCatalog.custom(activity, type)
            if (custom.isEmpty()) {
                toast("Only the built-in repositories are listed")
            } else {
                AlertDialog.Builder(activity)
                    .setTitle("Remove a repository")
                    .setItems(custom.map { it.slug }.toTypedArray()) { _, which ->
                        val repo = custom[which]
                        if (PcOnlineCatalog.removeCustom(activity, type, repo.slug)) {
                            toast("${repo.slug} removed")
                            updateCategoryButtons()
                            loadAssets()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            true
        }
        closeButton.setOnClickListener { dialog.dismiss() }
        listView.setOnItemClickListener { _, _, position, _ ->
            val asset = assets.getOrNull(position) ?: return@setOnItemClickListener
            download(asset)
        }

        repoButtons.addView(
            reloadButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        repoButtons.addView(
            addRepoButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
            addView(categoryRow)
            addView(intro)
            addView(repoInfo)
            addView(installedTitle)
            addView(
                installedScroll,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            )
            addView(repoButtons)
            addView(status)
            addView(
                listView,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f)
            )
            addView(closeButton)
        }

        dialog.setTitle("PC components from the internet")
        dialog.setContentView(root)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        dialog.show()

        updateCategoryButtons()
        refreshInstalled()
        loadAssets()
    }

    private fun shortName(type: PcPackType): String = when (type) {
        PcPackType.GRAPHICS_DRIVER -> "Drivers"
        PcPackType.D3D_LAYER -> "DXVK"
        PcPackType.RUNTIME -> "Runtime"
        PcPackType.ROOTFS -> "Rootfs"
    }
}
