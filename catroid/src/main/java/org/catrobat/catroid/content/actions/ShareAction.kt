package org.catrobat.catroid.content.actions

import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.badlogic.gdx.scenes.scene2d.actions.TemporalAction
import org.catrobat.catroid.content.Scope
import org.catrobat.catroid.formulaeditor.Formula
import org.catrobat.catroid.stage.StageActivity
import java.io.File

class ShareAction : TemporalAction() {
    var scope: Scope? = null
    var contentFormula: Formula? = null

    override fun update(percent: Float) {
        val value = contentFormula?.interpretString(scope) ?: ""
        if (value.isEmpty()) return

        val activity = StageActivity.activeStageActivity.get() ?: return

        activity.runOnUiThread {
            try {
                val projectFile = scope?.project?.getFile(value)

                if (projectFile != null && projectFile.exists() && projectFile.isFile) {
                    shareFile(activity, projectFile)
                } else {
                    shareText(activity, value)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun shareText(activity: android.app.Activity, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(intent, "Share")
        activity.startActivity(chooser)
    }

    private fun shareFile(activity: android.app.Activity, file: File) {
        // The manifest declares exactly `${applicationId}.fileProvider`; any
        // other authority fails with IllegalArgumentException. The package id
        // differs in an exported APK, so it has to come from the activity.
        // The file also has to be granted to the receiving app.
        val authority = "${activity.packageName}.fileProvider"
        val uri = FileProvider.getUriForFile(activity, authority, file)

        val extension = MimeTypeMap.getFileExtensionFromUrl(file.path)
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Share file")
        activity.startActivity(chooser)
    }

    override fun reset() {
        super.reset()
        scope = null
        contentFormula = null
    }
}
