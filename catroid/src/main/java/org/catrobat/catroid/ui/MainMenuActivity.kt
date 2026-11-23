/*
 * Catroid: An on-device visual programming system for Android devices
 * Copyright (C) 2010-2021 The Catrobat Team
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
package org.catrobat.catroid.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.preference.PreferenceManager
import android.provider.Settings
import android.text.Html
import android.text.method.LinkMovementMethod
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.text.HtmlCompat
import androidx.lifecycle.lifecycleScope
import com.danvexteam.lunoscript_annotations.LunoClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.catrobat.catroid.BuildConfig
import org.catrobat.catroid.CatroidApplication
import org.catrobat.catroid.ProjectManager
import org.catrobat.catroid.R
import org.catrobat.catroid.cast.CastManager
import org.catrobat.catroid.common.Constants
import org.catrobat.catroid.common.FlavoredConstants
import org.catrobat.catroid.common.SharedPreferenceKeys
import org.catrobat.catroid.common.Survey
import org.catrobat.catroid.databinding.ActivityLoadingBinding
import org.catrobat.catroid.databinding.ActivityMainMenuBinding
import org.catrobat.catroid.databinding.ActivityMainMenuSplashscreenBinding
import org.catrobat.catroid.databinding.DeclinedTermsOfUseAndServiceAlertViewBinding
import org.catrobat.catroid.databinding.DialogTermsOfUseBinding
import org.catrobat.catroid.databinding.PrivacyPolicyViewBinding
import org.catrobat.catroid.databinding.ProgressBarBinding
import org.catrobat.catroid.io.ZipArchiver
import org.catrobat.catroid.io.asynctask.ProjectLoader
import org.catrobat.catroid.io.asynctask.ProjectLoader.ProjectLoadListener
import org.catrobat.catroid.io.asynctask.ProjectSaver
import org.catrobat.catroid.ml.MLBridge
import org.catrobat.catroid.plugins.PluginEventBus
import org.catrobat.catroid.python.PythonEngine
import org.catrobat.catroid.stage.StageActivity
import org.catrobat.catroid.ui.dialogs.NewProjectDialogFragment
import org.catrobat.catroid.ui.recyclerview.dialog.AboutDialogFragment
import org.catrobat.catroid.ui.recyclerview.fragment.MainMenuFragment
import org.catrobat.catroid.ui.recyclerview.fragment.MainMenuPcFragment
import org.catrobat.catroid.ui.settingsfragments.SettingsFragment
import org.catrobat.catroid.utils.FileMetaDataExtractor
import org.catrobat.catroid.utils.NativeLibraryManager
import org.catrobat.catroid.utils.ScreenValueHandler
import org.catrobat.catroid.utils.ToastUtil
import org.catrobat.catroid.utils.Utils
import org.catrobat.catroid.utils.setVisibleOrGone
import org.koin.android.ext.android.inject
import java.io.File
import java.io.IOException
import kotlin.random.Random


private const val SDK_VERSION = 24

@LunoClass
class MainMenuActivity : BaseCastActivity(), ProjectLoadListener {

    private val OVERLAY_PERMISSION_REQ_CODE: Int = 1234

    private lateinit var privacyPolicyBinding: PrivacyPolicyViewBinding
    private lateinit var declinedTermsOfUseViewBinding: DeclinedTermsOfUseAndServiceAlertViewBinding
    private lateinit var mainMenuBinding: ActivityMainMenuBinding
    private val projectManager: ProjectManager by inject()
    private var oldPrivacyPolicy = 0
    private lateinit var loadingBinding: ActivityLoadingBinding

    private var safeModeTapCounter = 0
    private var lastTapTime: Long = 0

    @Volatile
    private var currentStartupStage = "MainMenuActivity.onCreate"
    private var startupFailureReported = false
    private var startupCompleted = false

    /**
     * Set when the project of an exported app has been handed to the loader.
     *
     * An exported app can reach the project from two places: the terms-of-use
     * dialog (first start) and `loadFinalContent()` (every later start). Both go
     * through `startStandaloneProjectIfAllowed()`, and without this flag a start
     * would unpack and load the project a second time.
     */
    private var standaloneStartHandled = false
    private var startupMenuFirstLayout = false
    private var startupLoadingOverlayRemoved = false
    private var startupWatchdogJob: kotlinx.coroutines.Job? = null

    private fun startStartupWatchdog() {
        val startedAt = SystemClock.elapsedRealtime()
        startupWatchdogJob?.cancel()
        startupWatchdogJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(45_000)
            if (startupCompleted || startupFailureReported || isFinishing || isDestroyed) return@launch
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            Log.e(STARTUP_TAG, "startup.still-pending stage=$currentStartupStage elapsedMs=$elapsed")
            try {
                AlertDialog.Builder(this@MainMenuActivity)
                    .setTitle("A inicialização ainda está em andamento")
                    .setMessage("O aplicativo continua aguardando a etapa '$currentStartupStage' há ${elapsed / 1000}s. Consulte o Logcat (tag $STARTUP_TAG) para o último estágio registrado.")
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            } catch (dialogError: Throwable) {
                Log.e(STARTUP_TAG, "startup.pending-dialog.failed", dialogError)
            }
        }
    }

    private fun markStartupCompleted(result: String) {
        if (startupCompleted) return
        startupCompleted = true
        startupWatchdogJob?.cancel()
        Log.i(STARTUP_TAG, "startup.ready=$result stage=$currentStartupStage")
    }

    internal fun onStartupMenuFirstLayout(fragmentName: String) {
        startupMenuFirstLayout = true
        Log.i(STARTUP_TAG, "startup.menu-first-layout=$fragmentName")
        markMainMenuHierarchyReadyIfPossible()
    }

    private fun markLoadingOverlayRemoved() {
        startupLoadingOverlayRemoved = true
        Log.i(STARTUP_TAG, "startup.loading-overlay-removed")
        markMainMenuHierarchyReadyIfPossible()
    }

    private fun markMainMenuHierarchyReadyIfPossible() {
        if (startupMenuFirstLayout && startupLoadingOverlayRemoved) {
            markStartupCompleted("main-menu-layout-and-overlay-removed")
        }
    }

    private fun <T> runStartupStage(stage: String, action: () -> T): T {
        currentStartupStage = stage
        val startedAt = SystemClock.elapsedRealtime()
        Log.i(STARTUP_TAG, "stage.begin=$stage")
        updateSplashStatus(stage)
        return try {
            action().also {
                Log.i(STARTUP_TAG, "stage.complete=$stage durationMs=${SystemClock.elapsedRealtime() - startedAt}")
            }
        } catch (error: Throwable) {
            Log.e(STARTUP_TAG, "stage.failed=$stage durationMs=${SystemClock.elapsedRealtime() - startedAt}", error)
            throw error
        }
    }

    private suspend fun <T> runStartupStageSuspend(stage: String, action: suspend () -> T): T {
        currentStartupStage = stage
        val startedAt = SystemClock.elapsedRealtime()
        Log.i(STARTUP_TAG, "stage.begin=$stage")
        updateSplashStatus(stage)
        return try {
            action().also {
                Log.i(STARTUP_TAG, "stage.complete=$stage durationMs=${SystemClock.elapsedRealtime() - startedAt}")
            }
        } catch (cancelled: CancellationException) {
            Log.i(STARTUP_TAG, "stage.cancelled=$stage")
            throw cancelled
        } catch (error: Throwable) {
            Log.e(STARTUP_TAG, "stage.failed=$stage durationMs=${SystemClock.elapsedRealtime() - startedAt}", error)
            throw error
        }
    }

    private fun reportStartupFailure(error: Throwable) {
        if (error is CancellationException) {
            Log.i(STARTUP_TAG, "startup.cancelled stage=$currentStartupStage")
            return
        }
        if (error is VirtualMachineError || error is ThreadDeath) throw error
        if (startupFailureReported) return
        startupFailureReported = true
        startupWatchdogJob?.cancel()

        val detail = describeStartupFailure(error)
        val message = "A inicialização parou na etapa '$currentStartupStage'.\n\n$detail\n\n" +
            "Copie o texto com \"Copiar\" e envie ao desenvolvedor " +
            "(no Logcat a tag é $STARTUP_TAG)."
        Log.e(STARTUP_TAG, "startup.failed stage=$currentStartupStage detail=$detail", error)
        if (isFinishing || isDestroyed) return

        // The failure has to be visible: the splash screen is an empty black
        // layout, so logging only would look like a crash without a reason.
        showStartupProblem(
            "Falha ao iniciar o NewCatroid",
            message
        )
    }

    /**
     * Turns the exception chain into one readable line. The top level message of
     * the classes that matter here (`LoadingProjectException` for a project that
     * cannot be parsed and `UnsatisfiedLinkError` for a missing native library)
     * says nothing useful on its own.
     */
    private fun describeStartupFailure(error: Throwable): String {
        val chain = ArrayList<Throwable>()
        var current: Throwable? = error
        while (current != null && chain.size < 5 && !chain.contains(current)) {
            chain.add(current)
            current = current.cause
        }
        val text = chain.joinToString(" <- ") { throwable ->
            val message = throwable.message?.trim()?.replace('\n', ' ')?.take(300)
            if (message.isNullOrEmpty()) {
                throwable.javaClass.simpleName
            } else {
                "${throwable.javaClass.simpleName}: $message"
            }
        }
        val hint = startupFailureHint(error)
        return if (hint == null) text else "$text\n\n$hint"
    }

    /** Short, actionable hint for the failures an exported app usually hits. */
    private fun startupFailureHint(error: Throwable): String? {
        var current: Throwable? = error
        while (current != null) {
            val message = current.message ?: ""
            if (current is IOException && message.contains("project.zip")) {
                return "O APK exportado não contém assets/project.zip " +
                    "(ou o arquivo está corrompido). Exporte o APK novamente."
            }
            if (current is UnsatisfiedLinkError) {
                return "Uma biblioteca nativa do APK não foi encontrada " +
                    "(${message.ifEmpty { "UnsatisfiedLinkError" }}). " +
                    "Isso acontece quando a arquitetura do processador " +
                    "(${Build.SUPPORTED_ABIS.firstOrNull() ?: "desconhecida"}) não foi incluída na exportação."
            }
            if (message.contains("success=false")) {
                return "O projeto empacotado no APK usa blocos que não existem " +
                    "nesta versão do NewCatroid. Use o diálogo \"Build APK\" desta " +
                    "versão para gerar o APK e um template marcado com \"PC blocks\"."
            }
            current = current.cause
        }
        return null
    }

    /**
     * Shows [message] in a dialog, with the usual buttons to hand the text to the
     * developer, and as a toast if even that fails. A dialog can only be shown
     * while the activity is in a real window, so a notification is the last
     * fallback - that is the only way to surface a failure of a background stage.
     */
    private fun showStartupProblem(title: String, message: String) {
        if (isFinishing || isDestroyed) return
        try {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton("Detalhes / Details") { _, _ -> showStartupDetailsDialog(title, message) }
                .setNegativeButton("Copiar / Copy") { _, _ -> copyStartupProblemToClipboard(message) }
                .show()
            return
        } catch (dialogError: Throwable) {
            Log.e(STARTUP_TAG, "startup.problem-dialog.failed", dialogError)
        }
        if (showToastOrFalse(message)) return
        try {
            val notification = android.app.Notification.Builder(this, STARTUP_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(android.app.Notification.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .build()
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager?.createNotificationChannel(
                android.app.NotificationChannel(
                    STARTUP_CHANNEL_ID,
                    "Startup problems",
                    android.app.NotificationManager.IMPORTANCE_HIGH
                )
            )
            manager?.notify(STARTUP_NOTIFICATION_ID, notification)
        } catch (notificationError: Throwable) {
            Log.e(STARTUP_TAG, "startup.problem-notification.failed", notificationError)
        }
    }

    /**
     * Full text of a startup failure in a scrollable dialog. The normal dialog
     * truncates long messages, and the message is what the user has to send.
     */
    private fun showStartupDetailsDialog(title: String, message: String) {
        try {
            val textView = TextView(this).apply {
                text = message
                setPadding(48, 32, 48, 32)
                textSize = 13f
                setTextIsSelectable(true)
                movementMethod = ScrollingMovementMethod()
            }
            val dialog = Dialog(this)
            dialog.setTitle("$title - detalhes")
            dialog.setContentView(textView)
            dialog.show()
        } catch (error: Throwable) {
            Log.e(STARTUP_TAG, "startup.details-dialog.failed", error)
            showStartupProblemToast(message)
        }
    }

    private fun copyStartupProblemToClipboard(message: String) {
        try {
            val clipboard = getSystemService(android.content.ClipboardManager::class.java)
            clipboard?.setPrimaryClip(
                android.content.ClipData.newPlainText("NewCatroid startup failure", message)
            )
            Toast.makeText(this, "Copiado / Copied", Toast.LENGTH_SHORT).show()
        } catch (error: Throwable) {
            Log.e(STARTUP_TAG, "startup.copy-to-clipboard.failed", error)
        }
    }

    private fun showStartupProblemToast(message: String) {
        showToastOrFalse(message)
    }

    private fun showToastOrFalse(message: String): Boolean {
        return try {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            true
        } catch (error: Throwable) {
            Log.e(STARTUP_TAG, "startup.problem-toast.failed", error)
            false
        }
    }

    /**
     * Mirrors the current [runStartupStage] name into the splash screen.
     *
     * An exported app can spend a long time unpacking a multi hundred megabyte
     * `project.zip`; showing the stage makes a slow start readable instead of a
     * black screen, and it makes a screenshot of a hang useful in a bug report.
     */
    private fun updateSplashStatus(stage: String) {
        if (!BuildConfig.FEATURE_APK_GENERATOR_ENABLED) return
        val label = splashStageLabel(stage)
        // The heavy initialization stages run on Dispatchers.IO, and a view may
        // only be touched from the main thread.
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            runOnUiThread { setSplashStatus(label) }
        } else {
            setSplashStatus(label)
        }
    }

    private fun setSplashStatus(label: String) {
        try {
            findViewById<TextView>(R.id.standalone_splash_status)?.text = label
        } catch (error: Throwable) {
            Log.w(STARTUP_TAG, "startup.splash-status.failed", error)
        }
    }

    /** Turns `standalone.unpack-project` into something a user can read. */
    private fun splashStageLabel(stage: String): String {
        return when {
            stage.startsWith("standalone.unpack") || stage.startsWith("standalone.asset") ->
                getString(R.string.standalone_splash_unpacking)
            stage.startsWith("standalone.") -> getString(R.string.standalone_splash_loading)
            else -> getString(R.string.standalone_splash_preparing)
        }
    }

    private fun logStartupLayout(root: android.view.View, label: String) {
        root.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: android.view.View) {
                Log.i(STARTUP_TAG, "view.attached=$label class=${view.javaClass.simpleName}")
            }

            override fun onViewDetachedFromWindow(view: android.view.View) {
                Log.i(STARTUP_TAG, "view.detached=$label")
            }
        })
        root.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (root.width <= 0 || root.height <= 0) return
                Log.i(STARTUP_TAG, "view.first-layout=$label size=${root.width}x${root.height} visibility=${root.visibility} shown=${root.isShown}")
                try {
                    root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                } catch (error: Exception) {
                    Log.w(STARTUP_TAG, "view.layout-listener-remove-failed=$label", error)
                }
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(STARTUP_TAG, "stage.begin=MainMenuActivity.onCreate.super")
        super.onCreate(savedInstanceState)
        Log.i(STARTUP_TAG, "stage.complete=MainMenuActivity.onCreate.super")

        try {
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                Log.e(STARTUP_TAG, "uncaught.exception stage=$currentStartupStage thread=${thread.name}", throwable)
                try {
                    val sw = java.io.StringWriter()
                    val pw = java.io.PrintWriter(sw)
                    throwable.printStackTrace(pw)
                    val crashLog = sw.toString()

                    val crashFile = File(applicationContext.cacheDir, "last_crash_log.txt")
                    crashFile.writeText(crashLog)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                defaultHandler?.uncaughtException(thread, throwable)
            }

            runStartupStage("activity.set-language") { SettingsFragment.setToChosenLanguage(this) }

            runStartupStage("activity.schedule-moderation-worker") {
                org.catrobat.catroid.utils.community.ModerationCheckWorker.schedulePeriodicCheck(this)
            }

            val crashFile = runStartupStage("activity.prepare-crash-log") {
                File(applicationContext.cacheDir, "last_crash_log.txt")
            }
            if (crashFile.exists()) {
                val errorText = try { crashFile.readText() } catch (e: Exception) { "" }
                crashFile.delete()

                if (errorText.isNotEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle("Аварийное завершение / Crash Log")
                        .setMessage("В прошлый раз приложение закрылось с ошибкой. Пожалуйста, отправьте этот текст разработчику:\n\n$errorText")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }

            Log.i(STARTUP_TAG, "startup.variant=${if (BuildConfig.FEATURE_APK_GENERATOR_ENABLED) "standalone" else "main-menu"}")
            if (BuildConfig.FEATURE_APK_GENERATOR_ENABLED) {
                val splashBinding = runStartupStage("layout.inflate-standalone-splash") {
                    ActivityMainMenuSplashscreenBinding.inflate(layoutInflater)
                }
                runStartupStage("layout.set-standalone-splash") { setContentView(splashBinding.root) }
                logStartupLayout(splashBinding.root, "standalone-splash")
                startStartupWatchdog()

                lifecycleScope.launch {
                    try {
                        runStartupStageSuspend("activity.heavy-initialization") {
                            withContext(Dispatchers.IO) { heavyInitialization() }
                        }
                        runStartupStage("activity.load-standalone-project") { loadFinalContent() }
                    } catch (error: Throwable) {
                        reportStartupFailure(error)
                    }
                }
            } else {
                val startTime = System.currentTimeMillis()

                mainMenuBinding = runStartupStage("layout.inflate-main-menu") {
                    ActivityMainMenuBinding.inflate(layoutInflater)
                }
                runStartupStage("layout.set-main-menu") { setContentView(mainMenuBinding.root) }
                logStartupLayout(mainMenuBinding.root, "main-menu-root")

                loadingBinding = runStartupStage("layout.inflate-loading-overlay") {
                    ActivityLoadingBinding.inflate(layoutInflater)
                }
                val windowContent = findViewById<android.view.ViewGroup>(android.R.id.content)

                val density = resources.displayMetrics.density
                loadingBinding.root.elevation = 100f * density
                loadingBinding.root.translationZ = 200f

                runStartupStage("layout.attach-loading-overlay") { windowContent.addView(loadingBinding.root) }
                logStartupLayout(loadingBinding.root, "loading-overlay")
                startStartupWatchdog()

                loadingBinding.loadingLogo.alpha = 0f
                loadingBinding.loadingLogo.scaleX = 0.6f
                loadingBinding.loadingLogo.scaleY = 0.6f
                loadingBinding.loadingLogo.translationY = 80f * density

                loadingBinding.loadingProgressBar.scaleX = 0f
                loadingBinding.loadingProgressBar.alpha = 0f

                loadingBinding.factTextView.alpha = 0f
                loadingBinding.factTextView.translationY = 40f * density

                val waveView = loadingBinding.loadingProgressBar as? WaveLoadingView

                loadingBinding.loadingLogo.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationY(0f)
                    .setDuration(800)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.4f))
                    .withEndAction {
                        val waveLocation = IntArray(2)
                        waveView?.getLocationOnScreen(waveLocation)

                        val logoLocation = IntArray(2)
                        loadingBinding.loadingLogo.getLocationOnScreen(logoLocation)

                        val logoX = logoLocation[0] + loadingBinding.loadingLogo.width / 2f - waveLocation[0]
                        val logoY = logoLocation[1] + loadingBinding.loadingLogo.height / 2f - waveLocation[1]

                        waveView?.explode(logoX, logoY, 45)
                    }
                    .start()

                loadingBinding.loadingProgressBar.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .setDuration(900)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()

                loadingBinding.factTextView.animate()
                    .alpha(0.8f)
                    .translationY(0f)
                    .setDuration(800)
                    .setStartDelay(300)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()

                loadingBinding.root.setOnClickListener {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastTapTime > 500) {
                        safeModeTapCounter = 0
                    }
                    lastTapTime = currentTime
                    safeModeTapCounter++

                    if (safeModeTapCounter >= 5) {
                        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
                        prefs.edit().putBoolean("force_safe_mode", true).apply()
                        Toast.makeText(this, "Safe Mode for next launch", Toast.LENGTH_LONG).show()
                        safeModeTapCounter = 0
                    }
                }

                lifecycleScope.launch {
                    try {
                        runStartupStage("loading.show-fact") { showRandomFacts() }
                        runStartupStageSuspend("activity.heavy-initialization") {
                            withContext(Dispatchers.IO) { heavyInitialization() }
                        }

                        val elapsedTime = System.currentTimeMillis() - startTime
                        val remainingTime = 1400 - elapsedTime
                        if (remainingTime > 0) kotlinx.coroutines.delay(remainingTime)

                        // The terms dialog of an exported app replaces the
                        // content view, so the overlay has to be looked up again
                        // here instead of using the reference from onCreate.
                        val currentWindowContent = findViewById<android.view.ViewGroup>(android.R.id.content)
                        val overlayAttached = try {
                            currentWindowContent != null && loadingBinding.root.parent === currentWindowContent
                        } catch (error: Throwable) {
                            false
                        }
                        val performExitTransition: () -> Unit = if (!overlayAttached) {
                            {
                                // Nothing to animate: the overlay is not part of
                                // the current content view any more.
                                markLoadingOverlayRemoved()
                            }
                        } else {
                            {
                                runStartupStage("loading.start-exit-transition") {
                                    loadingBinding.root.pivotX = loadingBinding.root.width / 2f
                                    loadingBinding.root.pivotY = loadingBinding.root.height / 2f
                                    loadingBinding.root.animate()
                                        .scaleX(3.5f)
                                        .scaleY(3.5f)
                                        .alpha(0f)
                                        .setDuration(600)
                                        .setInterpolator(android.view.animation.AccelerateInterpolator())
                                        .withEndAction {
                                            try {
                                                runStartupStage("loading.remove-overlay") {
                                                    currentWindowContent.removeView(loadingBinding.root)
                                                }
                                                markLoadingOverlayRemoved()
                                            } catch (error: Throwable) {
                                                reportStartupFailure(error)
                                            }
                                        }
                                        .start()
                                }
                                Unit
                            }
                        }

                        val loadMenuAndExit = {
                            try {
                                runStartupStage("activity.load-final-content") { loadFinalContent() }
                                Log.i(STARTUP_TAG, "startup.result=main-menu-content-requested")
                                if (waveView != null) {
                                    lifecycleScope.launch {
                                        try {
                                            kotlinx.coroutines.delay(150)
                                            performExitTransition()
                                        } catch (error: Throwable) {
                                            reportStartupFailure(error)
                                        }
                                    }
                                } else {
                                    performExitTransition()
                                }
                            } catch (error: Throwable) {
                                reportStartupFailure(error)
                            }
                        }

                        if (waveView != null) {
                            runStartupStage("loading.flatline") {
                                waveView.flatline(350) { loadMenuAndExit() }
                            }
                        } else {
                            loadMenuAndExit()
                        }
                    } catch (error: CancellationException) {
                        reportStartupFailure(error)
                        throw error
                    } catch (error: Throwable) {
                        reportStartupFailure(error)
                    }
                }
            }
        } catch (error: Throwable) {
            reportStartupFailure(error)
        }
    }

    private fun showRandomFacts() {
        val facts = resources.getStringArray(R.array.loading_facts)
        val randomFact = facts[Random.nextInt(facts.size)]
        loadingBinding.factTextView.text = randomFact
    }

    private fun heavyInitialization() {
        // A missing native library must not stop an exported app: the export
        // dialog can leave Python, QEMU and the ML engines out on purpose (and a
        // 32 bit only build has no Python at all), and the project may not use
        // them. `NativeLibraryManager` already records what it could not load.
        runStartupStage("native.initialize-libraries") {
            try {
                NativeLibraryManager.initialize()
            } catch (error: Throwable) {
                if (error is VirtualMachineError || error is ThreadDeath) throw error
                Log.e(STARTUP_TAG, "native.initialize-libraries.failed", error)
            }
        }
        Log.i(
            STARTUP_TAG,
            "native.features python=${NativeLibraryManager.isLoaded(NativeLibraryManager.Feature.PYTHON)} " +
                "core=${NativeLibraryManager.isLoaded(NativeLibraryManager.Feature.CORE)} " +
                "torch=${NativeLibraryManager.isLoaded(NativeLibraryManager.Feature.TORCH)} " +
                "vnc=${NativeLibraryManager.isLoaded(NativeLibraryManager.Feature.VNC)}"
        )
        runStartupStage("preferences.initialize-defaults") {
            PreferenceManager.setDefaultValues(this, R.xml.preferences, true)
            PreferenceManager.setDefaultValues(this, R.xml.nxt_preferences, true)
            PreferenceManager.setDefaultValues(this, R.xml.ev3_preferences, true)
        }
        runStartupStage("display.update-screen-metrics") { ScreenValueHandler.updateScreenWidthAndHeight(this) }
        runStartupStage("python.construct-engine") { pythonEngine = PythonEngine(applicationContext) }
        runStartupStage("ai.initialize-if-enabled") {
            org.catrobat.catroid.ai.KoveManager.initIfEnabled(applicationContext)
        }
    }

    private fun loadFinalContent() {
        // An exported app reaches the project through
        // `startStandaloneProjectIfAllowed()` below, after the terms of use.
        if (!BuildConfig.FEATURE_APK_GENERATOR_ENABLED) {
            runStartupStage("menu.configure-toolbar") {
                setSupportActionBar(findViewById(R.id.toolbar))
                supportActionBar?.setIcon(R.drawable.pc_toolbar_icon)
                supportActionBar?.setTitle(R.string.app_name)
            }

            if (SettingsFragment.isCastSharedPreferenceEnabled(this)) {
                runStartupStage("menu.initialize-cast") { CastManager.getInstance().initializeCast(this) }
            }
            runStartupStage("menu.commit-home-fragment") { loadFragment() }

            val application = checkNotNull(CatroidApplication.current) {
                "CatroidApplication.current was not set before MainMenuActivity loaded"
            }
            runStartupStage("plugins.load") { application.loadPluginsIfNotLoaded() }
        }

        runStartupStage("menu.read-privacy-preference") {
            oldPrivacyPolicy = PreferenceManager.getDefaultSharedPreferences(this)
                .getInt(SharedPreferenceKeys.AGREED_TO_PRIVACY_POLICY_VERSION, 0)
        }
        if (BuildConfig.FEATURE_APK_GENERATOR_ENABLED) {
            // An exported app has one screen and one project: no terms answer
            // means no project yet, and the dialog above starts it.
            if (oldPrivacyPolicy != Constants.CATROBAT_TERMS_OF_USE_ACCEPTED) {
                runStartupStage("standalone.pending-terms-of-use")
            } else {
                runStartupStage("standalone.continue-start") { startStandaloneProjectIfAllowed() }
            }
        } else if (oldPrivacyPolicy != Constants.CATROBAT_TERMS_OF_USE_ACCEPTED) {
            runStartupStage("menu.show-terms-of-use") { showTermsOfUseDialog() }
        } else {
            // No terms dialog on this start, nothing else to do here.
        }

        // An exported app has no server-side survey, and the survey can touch the
        // network before anything is on screen - keep it out of that path.
        if (!BuildConfig.FEATURE_APK_GENERATOR_ENABLED) {
            runStartupStage("menu.initialize-survey") {
                surveyCampaign = Survey(this)
                surveyCampaign?.showSurvey(this)
            }
        }
        runStartupStage("menu.dispatch-activity-on-show") {
            PluginEventBus.getInstance().dispatch("Activity.onShow", "MainMenuActivity")
        }
    }

    /**
     * Asks for the terms of use before anything else runs.
     *
     * The answer is stored, and the stored value is what gates the project of an
     * exported app: `loadContent()` only opens the bundled project once it is
     * [Constants.CATROBAT_TERMS_OF_USE_ACCEPTED]. An empty implementation here
     * therefore left every exported APK on a black splash screen - the dialog was
     * never shown, so the value was never written.
     */
    private fun showTermsOfUseDialog() {
        try {
            val binding = DialogTermsOfUseBinding.inflate(layoutInflater)
            binding.dialogTermsOfUseTextViewInfo.setText(R.string.dialog_terms_of_use_info)

            val urlTextView = binding.dialogTermsOfUseTextViewUrl
            urlTextView.movementMethod = LinkMovementMethod.getInstance()
            val urlText = getString(
                R.string.terms_of_use_link_template,
                Constants.CATROBAT_TERMS_OF_USE_URL,
                getString(R.string.dialog_terms_of_use_link_text)
            )
            urlTextView.text = if (Build.VERSION.SDK_INT >= SDK_VERSION) {
                Html.fromHtml(urlText, HtmlCompat.FROM_HTML_MODE_LEGACY)
            } else {
                Html.fromHtml(urlText)
            }

            AlertDialog.Builder(this)
                .setView(binding.root)
                .setTitle(R.string.dialog_terms_of_use_title)
                .setCancelable(false)
                .setPositiveButton(R.string.ok) { _, _ -> handleAgreedToPrivacyPolicyButton() }
                .setNegativeButton(R.string.decline) { _, _ ->
                    handleDeclinedPrivacyPolicyButton()
                }
                .show()
        } catch (error: Throwable) {
            // An exported app has no other way to reach its project, so a dialog
            // that cannot be inflated must not leave it stuck: log the problem,
            // record the acceptance and continue to the project.
            Log.e(STARTUP_TAG, "standalone.terms-of-use.failed", error)
            handleAgreedToPrivacyPolicyButton()
        }
    }

    fun handleAgreedToPrivacyPolicyButton() {
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit()
            .putInt(
                SharedPreferenceKeys.AGREED_TO_PRIVACY_POLICY_VERSION,
                Constants.CATROBAT_TERMS_OF_USE_ACCEPTED
            )
            .apply()
        if (BuildConfig.FEATURE_APK_GENERATOR_ENABLED) {
            runStartupStage("standalone.prepare-project") { startStandaloneProjectIfAllowed() }
        }
    }

    fun handleDeclinedPrivacyPolicyButton() {
        declinedTermsOfUseViewBinding =
            DeclinedTermsOfUseAndServiceAlertViewBinding.inflate(layoutInflater)
        val dialogView = declinedTermsOfUseViewBinding.root

        val linkString = getString(
            R.string.about_link_template,
            Constants.BASE_APP_URL_HTTPS,
            getString(R.string.share_website_text)
        )

        val linkTextView = declinedTermsOfUseViewBinding.shareWebsiteView
        linkTextView.movementMethod = LinkMovementMethod.getInstance()
        linkTextView.text = if (Build.VERSION.SDK_INT >= SDK_VERSION) {
            Html.fromHtml(linkString, HtmlCompat.FROM_HTML_MODE_LEGACY)
        } else {
            Html.fromHtml(linkString)
        }

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton(R.string.ok) { _, _ -> showTermsOfUseDialog() }
            .setCancelable(false)
            .setOnKeyListener { dialog: DialogInterface, keyCode: Int, _ ->
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    dialog.cancel()
                    showTermsOfUseDialog()
                    return@setOnKeyListener true
                }
                false
            }
            .show()
    }

    private fun loadContent() {
        if (BuildConfig.FEATURE_APK_GENERATOR_ENABLED) {
            // The splash screen is already the content view (see onCreate), and
            // the project is started by `loadFinalContent()` once the terms of
            // use are answered. Replacing the view here would also drop the
            // overlay the loading animation needs.
            return
        }
        mainMenuBinding = ActivityMainMenuBinding.inflate(layoutInflater)
        setContentView(mainMenuBinding.root)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setIcon(R.drawable.pc_toolbar_icon)
        supportActionBar?.setTitle(R.string.app_name)

        setShowProgressBar(true)

        if (SettingsFragment.isCastSharedPreferenceEnabled(this)) {
            CastManager.getInstance().initializeCast(this)
        }
        loadFragment()
    }

    override fun onResume() {
        super.onResume()
        Log.i(STARTUP_TAG, "lifecycle.onResume")
        if (SettingsFragment.isCastSharedPreferenceEnabled(this)) {
            try {
                CastManager.getInstance().addCallback()
            } catch (error: Throwable) {
                Log.e(STARTUP_TAG, "lifecycle.resume-cast.failed", error)
                throw error
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.i(STARTUP_TAG, "lifecycle.window-focus=$hasFocus")
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {

            // Ctrl + N : Create new project
            if (event.isCtrlPressed && event.keyCode == KeyEvent.KEYCODE_N) {
                val dialog = NewProjectDialogFragment()
                dialog.show(supportFragmentManager, NewProjectDialogFragment.TAG)
                return true
            }

            // F5 : Run project
            if (event.keyCode == KeyEvent.KEYCODE_F5) {
                val currentProject = projectManager.currentProject
                if (currentProject != null) {
                    StageActivity.handlePlayButton(projectManager, this)
                } else {
                    Toast.makeText(this, "Нет активного проекта для запуска", Toast.LENGTH_SHORT).show()
                }
                return true
            }

            // Ctrl + O : Open Project Menu
            if (event.isCtrlPressed && event.keyCode == KeyEvent.KEYCODE_O) {
                startActivity(Intent(this, ProjectListActivity::class.java))
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun loadFragment() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val isPcMode = prefs.getBoolean("pref_pc_mode_enabled", false)

        val fragment = if (isPcMode) {
            MainMenuPcFragment()
        } else {
            MainMenuFragment()
        }
        Log.i(STARTUP_TAG, "fragment.selected=${fragment.javaClass.simpleName} pcMode=$isPcMode container=${mainMenuBinding.fragmentContainer.id}")

        runStartupStage("menu.fragment-transaction-commit") {
            supportFragmentManager.beginTransaction()
                .replace(
                    mainMenuBinding.fragmentContainer.id,
                    fragment,
                    "MainMenuFragment"
                )
                .commitAllowingStateLoss()
        }

        runStartupStage("menu.show-fragment-container") { setShowProgressBar(false) }

        val intent = intent
        if (intent.action != null && intent.action == "android.intent.action.VIEW" && intent.data != null) {
            val shareUri = intent.data
            val webIntent = Intent(this, WebViewActivity::class.java)
            webIntent.putExtra(WebViewActivity.INTENT_PARAMETER_URL, shareUri.toString())
            startActivity(webIntent)
        }
    }

    private fun setShowProgressBar(show: Boolean) {
        val progressBarBinding = ProgressBarBinding.inflate(layoutInflater)
        progressBarBinding.root.setVisibleOrGone(show)
        mainMenuBinding.fragmentContainer.setVisibleOrGone(!show)
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {

                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, OVERLAY_PERMISSION_REQ_CODE)
            }
        }
    }

    public override fun onPause() {
        Log.i(STARTUP_TAG, "lifecycle.onPause")
        //PluginEventBus.getInstance().dispatch("MainMenu.onHide");

        super.onPause()
        if (SettingsFragment.isCastSharedPreferenceEnabled(this)) {
            CastManager.getInstance().removeCallback();
        }
        val currentProject = projectManager.currentProject
        if (currentProject != null) {
            ProjectSaver(currentProject, applicationContext).saveProjectAsync()
            Utils.setLastUsedProjectName(applicationContext, currentProject.name)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main_menu, menu)
        //val scratchConverter = getString(R.string.main_menu_scratch_converter)
        //val scratchConverterBeta = SpannableString(
        //    scratchConverter + " " + getString(R.string.beta)
        //)

        //scratchConverterBeta.setSpan(
       //     ForegroundColorSpan(resources.getColor(R.color.beta_label_color, theme)),
        //    scratchConverter.length, scratchConverterBeta.length,
        //    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        //)
        //menu.findItem(R.id.menu_scratch_converter).title = scratchConverterBeta
        return super.onCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        //menu.findItem(R.id.menu_login).isVisible =
        //    !Utils.isUserLoggedIn(this)
        //menu.findItem(R.id.menu_logout).isVisible =
        //    Utils.isUserLoggedIn(this)
        if (!BuildConfig.FEATURE_SCRATCH_CONVERTER_ENABLED) {
            //menu.removeItem(R.id.menu_scratch_converter)
        }
        return true
    }

    private fun copyAssets(assetPath: String, destDir: File) {
        try {
            val assetManager = this.assets
            val assets = assetManager.list(assetPath)
            if (assets.isNullOrEmpty()) {




                if (!destDir.exists()) {
                    destDir.mkdirs()
                }
                return
            }


            if (!destDir.exists()) {
                destDir.mkdirs()
            }

            for (assetName in assets) {
                val sourcePath = if (assetPath.isEmpty()) assetName else "$assetPath/$assetName"
                val destFile = File(destDir, assetName)



                val isDir = assetManager.list(sourcePath)?.isNotEmpty() == true

                if (isDir) {

                    destFile.mkdirs()
                    copyAssets(sourcePath, destFile)
                } else {

                    assetManager.open(sourcePath).use { inputStream ->
                        java.io.FileOutputStream(destFile).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                }
            }
        } catch (e: Exception) {

            Log.e("PythonEngine", "FATAL ERROR in copyAssets for path: $assetPath", e)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_rate_app -> if (Utils.checkIsNetworkAvailableAndShowErrorMessage(this)) {
                try {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://www.rustore.ru/catalog/app/org.DanVexTeam.NewCatroid")
                        )
                    )
                } catch (e: ActivityNotFoundException) {
                    Log.e(TAG, "onOptionsItemSelected: ", e)
                    ToastUtil.showError(this, R.string.main_menu_play_store_not_installed)
                }
            }
            /*R.id.menu_terms_of_use -> TermsOfUseDialogFragment().show(
                supportFragmentManager,
                TermsOfUseDialogFragment.TAG
            )*/
            R.id.menu_privacy_policy -> {
                val browserIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(FlavoredConstants.PRIVACY_POLICY_URL)
                )
                startActivity(browserIntent)
                /*Toast.makeText(this, "Starting Python script...", Toast.LENGTH_SHORT).show()
                Thread {


                    try {

                        val projectDir = File(applicationContext.filesDir, "projects/MyNumpyProject")
                        projectDir.mkdirs()


                        copyAssets("numpy_test_pylibs", File(projectDir, "pylibs"))
                        copyAssets("numpy_test_pylibs_native", File(projectDir, "pylibs_native"))


                        pythonEngine.initialize(projectDir)


                        val output = testPython()
                        Log.d("PythonThread", "--- NUMPY OUTPUT ---")
                        Log.d("PythonThread", output)
                        Log.d("PythonThread", "--------------------")

                    } catch (e: Exception) {
                        Log.e("PythonThread", "Error in python thread", e)
                    }
                }.start()*/
            }
            R.id.menu_about -> AboutDialogFragment().show(
                supportFragmentManager,
                AboutDialogFragment.TAG
            )
            //R.id.menu_scratch_converter -> if (Utils.checkIsNetworkAvailableAndShowErrorMessage(this)) {
            //    startActivity(Intent(this, ScratchConverterActivity::class.java))
            //}
            R.id.settings -> startActivity(Intent(this, SettingsActivity::class.java))
            //R.id.menu_login -> startActivity(Intent(this, SignInActivity::class.java))
            //R.id.menu_logout -> {
            //    Utils.logoutUser(this)
             //   ToastUtil.showSuccess(this, R.string.logout_successful)
            //}
            //R.id.menu_help -> startActivity(
            //    Intent(
            //        Intent.ACTION_VIEW,
            //        Uri.parse(CATROBAT_HELP_URL)
            //    )
            //)
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    /**
     * Starts the bundled project exactly once, and only when the terms of use
     * have been accepted: the answer is what gates the project of an exported
     * app, and starting it twice would unpack and load it twice.
     */
    private fun startStandaloneProjectIfAllowed() {
        if (!BuildConfig.FEATURE_APK_GENERATOR_ENABLED) return
        if (standaloneStartHandled) return
        val accepted = PreferenceManager.getDefaultSharedPreferences(this)
            .getInt(SharedPreferenceKeys.AGREED_TO_PRIVACY_POLICY_VERSION, 0) ==
            Constants.CATROBAT_TERMS_OF_USE_ACCEPTED
        if (!accepted) {
            Log.i(STARTUP_TAG, "standalone.project.waiting-for-terms")
            return
        }
        standaloneStartHandled = true
        oldPrivacyPolicy = Constants.CATROBAT_TERMS_OF_USE_ACCEPTED
        runStartupStage("standalone.prepare-project") { prepareStandaloneProject() }
    }

    private fun prepareStandaloneProject() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val projectDir = File(
            FlavoredConstants.DEFAULT_ROOT_DIRECTORY,
            FileMetaDataExtractor.encodeSpecialCharsForFileSystem(BuildConfig.PROJECT_NAME)
        )
        // Both of these are reset once the export (or the very first start) is
        // done: `last_unpacked_apk_time` is the fingerprint of the APK the project
        // directory was unpacked from, `standalone_project_version` the template
        // generation that project was written by. The export token is written by
        // `ApkToolboxManager` on every build and identifies the exact export, so
        // the project inside a new APK is always unpacked even when the file time
        // looks unchanged.
        val forceRefresh = prefs.getBoolean(FORCE_PROJECT_REFRESH_KEY, false)
        val lastUnpackedTemplate = prefs.getLong(LAST_UNPACKED_TEMPLATE_KEY, -1L)
        val templateChanged = lastUnpackedTemplate != standaloneTemplateVersion
        val exportToken = readExportToken()
        val lastUnpackedToken = prefs.getString(LAST_UNPACKED_TOKEN_KEY, null)
        val exportChanged = exportToken != null && exportToken != lastUnpackedToken

        if (forceRefresh) {
            Log.i(STARTUP_TAG, "standalone.project.refresh-forced")
        }
        if (templateChanged) {
            Log.i(
                STARTUP_TAG,
                "standalone.project.template-changed was=$lastUnpackedTemplate now=$standaloneTemplateVersion"
            )
        }
        if (exportChanged) {
            Log.i(STARTUP_TAG, "standalone.project.export-changed token=$exportToken was=$lastUnpackedToken")
        }

        val apkLastModified = try {
            File(packageCodePath).lastModified()
        } catch (e: Exception) {
            0L
        }

        if (!forceRefresh && !templateChanged && !exportChanged) {
            val lastUnpackedApkTime = prefs.getLong("last_unpacked_apk_time", -1L)
            if (apkLastModified <= lastUnpackedApkTime && projectDir.exists()) {
                Log.d("STANDALONE", "Project is up to date (APK unchanged). Loading from storage.")
                ProjectLoader(projectDir, this).setListener(this).loadProjectAsync()
                return
            }
        }

        Log.d(
            "STANDALONE",
            "Unpacking the bundled project " +
                "(forceRefresh=$forceRefresh templateChanged=$templateChanged exportChanged=$exportChanged)"
        )

        var unpackDir: File? = null
        try {
            val tempDir = File(cacheDir, "standalone_temp_${System.currentTimeMillis()}")
            unpackDir = tempDir
            val assetName = BuildConfig.START_PROJECT + ".zip"
            Log.i(STARTUP_TAG, "standalone.asset.open=$assetName")
            assets.open(assetName).use { inputStream ->
                ZipArchiver().unzip(inputStream, tempDir)
            }
            Log.d("STANDALONE", "Unpacked new template assets to temporary directory: ${tempDir.path}")
            if (!File(tempDir, CODE_XML_FILE_NAME).exists()) {
                // A half written asset would look like a project that cannot be
                // parsed; say what is actually wrong instead.
                throw IOException("$assetName does not contain $CODE_XML_FILE_NAME")
            }

            if (projectDir.exists()) {
                Log.d("STANDALONE", "Old project found. Updating assets and preserving user data...")

                projectDir.listFiles()?.forEach { file ->
                    val name = file.name
                    if (name == "DeviceVariables.json" || name == "DeviceLists.json" || name == "files") {
                        return@forEach
                    }
                    file.deleteRecursively()
                }

                tempDir.listFiles()?.forEach { file ->
                    val destFile = File(projectDir, file.name)
                    if (file.name == "files") {
                        mergeDirectoriesWithoutOverwrite(file, destFile)
                    } else {
                        file.copyRecursively(destFile, overwrite = true)
                    }
                }
                tempDir.deleteRecursively()
            } else {
                Log.d("STANDALONE", "First install. Creating project directory...")
                if (!tempDir.renameTo(projectDir)) {
                    tempDir.copyRecursively(projectDir, overwrite = true)
                    tempDir.deleteRecursively()
                }
            }

            // The flags are cleared before the load starts: the load callback
            // cannot fail for a stale-directory reason any more, and a crash
            // during the load would otherwise repeat the migration for ever.
            prefs.edit()
                .putLong("last_unpacked_apk_time", apkLastModified)
                .putLong(LAST_UNPACKED_TEMPLATE_KEY, standaloneTemplateVersion)
                .putString(LAST_UNPACKED_TOKEN_KEY, exportToken)
                .putBoolean(FORCE_PROJECT_REFRESH_KEY, false)
                .apply()
            Log.d("STANDALONE", "Update complete. Saved APK timestamp: $apkLastModified")

            ProjectLoader(projectDir, this).setListener(this).loadProjectAsync()

        } catch (e: IOException) {
            Log.e(STARTUP_TAG, "stage.failed=standalone.unpack-project asset=${BuildConfig.START_PROJECT}.zip", e)
            // Leave the temporary directory behind: the project directory is
            // only replaced after a complete unpack, so the previous project is
            // still loadable below.
            // Do not retry the same broken unpack on every start; a new APK, a
            // new export token or a different template generation clears the
            // markers again.
            prefs.edit()
                .putBoolean(FORCE_PROJECT_REFRESH_KEY, false)
                .putLong(LAST_UNPACKED_TEMPLATE_KEY, standaloneTemplateVersion)
                .putString(LAST_UNPACKED_TOKEN_KEY, exportToken)
                .apply()
            // A partially unpacked copy would only waste space: the project
            // directory is replaced as a whole, after a complete unpack.
            unpackDir?.deleteRecursively()
            if (projectDir.exists()) {
                Log.w(STARTUP_TAG, "standalone.unpack.fallback-to-existing-project dir=$projectDir")
                runStartupStage("standalone.fallback-load-existing-project") {
                    ProjectLoader(projectDir, this).setListener(this).loadProjectAsync()
                }
            } else {
                reportStartupFailure(e)
            }
        }
    }

    private fun mergeDirectoriesWithoutOverwrite(source: File, destination: File) {
        if (!source.exists()) return
        if (!destination.exists()) {
            destination.mkdirs()
        }
        source.listFiles()?.forEach { file ->
            val targetFile = File(destination, file.name)
            if (file.isDirectory) {
                mergeDirectoriesWithoutOverwrite(file, targetFile)
            } else {
                if (!targetFile.exists()) {
                    file.copyTo(targetFile, overwrite = false)
                    Log.d("STANDALONE_MIGRATE", "Merged new default asset: ${file.name}")
                } else {
                    Log.d("STANDALONE_MIGRATE", "Preserved user-modified file: ${file.name}")
                }
            }
        }
    }


    override fun onLoadFinished(success: Boolean) {
        if (!BuildConfig.FEATURE_APK_GENERATOR_ENABLED) return
        Log.i(STARTUP_TAG, "stage.result=standalone.project-load success=$success")
        if (success) {
            try {
                runStartupStage("standalone.launch-stage-activity") {
                    startActivityForResult(
                        Intent(this, StageActivity::class.java), StageActivity.REQUEST_START_STAGE
                    )
                    markStartupCompleted("standalone-project-loaded")
                }
            } catch (error: Throwable) {
                reportStartupFailure(error)
            }
        } else {
            // The project's `code.xml` could not be turned into a Project. The
            // usual cause in an exported APK is a brick the template does not
            // have; the loader logs the XStream message under its own tag.
            val hint = standaloneProjectLoadHint()
            reportStartupFailure(
                IllegalStateException(
                    "ProjectLoader reported success=false for the bundled standalone project" +
                        (if (hint == null) "" else " ($hint)")
                )
            )
        }
    }

    /**
     * Token that `ApkToolboxManager` writes into every APK it exports, or `null`
     * for an APK that was not produced by the export dialog.
     */
    private fun readExportToken(): String? {
        return try {
            assets.open(EXPORT_TOKEN_ASSET).use { stream ->
                stream.readBytes().toString(Charsets.UTF_8).trim().ifEmpty { null }
            }
        } catch (e: IOException) {
            Log.d(STARTUP_TAG, "standalone.export-token.missing (${e.javaClass.simpleName})")
            null
        }
    }

    /**
     * Something useful to show when the bundled project cannot be loaded.
     *
     * The loader swallows the parser message, but the brick names in `code.xml`
     * are readable, and a missing `code.xml` (or a project directory that lost
     * its media) is a different, equally common problem.
     */
    private fun standaloneProjectLoadHint(): String? {
        return try {
            val projectDir = File(
                FlavoredConstants.DEFAULT_ROOT_DIRECTORY,
                FileMetaDataExtractor.encodeSpecialCharsForFileSystem(BuildConfig.PROJECT_NAME)
            )
            val codeXml = File(projectDir, CODE_XML_FILE_NAME)
            when {
                !projectDir.isDirectory -> "project directory $projectDir is missing"
                !codeXml.exists() -> "$CODE_XML_FILE_NAME is missing in $projectDir"
                else -> "the template of this APK cannot parse $CODE_XML_FILE_NAME"
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (BuildConfig.FEATURE_APK_GENERATOR_ENABLED) {
            if (requestCode == StageActivity.REQUEST_START_STAGE) {
                finish()
            }
        } else if (requestCode == OVERLAY_PERMISSION_REQ_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {

                } else {

                }
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    companion object {
        val TAG = MainMenuActivity::class.java.simpleName
        private const val STARTUP_TAG = "NewCatroidStartup"
        private const val CODE_XML_FILE_NAME = "code.xml"

        /**
         * Bumped whenever the layout of the project a template carries changes.
         * An APK unpacked from an older template unpacks `assets/project.zip`
         * again on the next start instead of reusing the stale directory.
         */
        private const val STANDALONE_TEMPLATE_VERSION = 2L

        /** Set by the export, so the first start of a fresh APK rescans everything. */
        const val FORCE_PROJECT_REFRESH_KEY = "standalone_force_project_refresh"
        private const val LAST_UNPACKED_TEMPLATE_KEY = "standalone_project_template"
        private const val LAST_UNPACKED_TOKEN_KEY = "standalone_project_export_token"

        /** Written into every exported APK by [org.catrobat.catroid.apkbuild.ApkToolboxManager]. */
        private const val EXPORT_TOKEN_ASSET = "standalone_export_token.txt"
        private const val STARTUP_CHANNEL_ID = "newcatroid_startup"
        private const val STARTUP_NOTIFICATION_ID = 4242

        private const val standaloneTemplateVersion = STANDALONE_TEMPLATE_VERSION

        @JvmField
        @SuppressLint("StaticFieldLeak")
        var pythonEngine: PythonEngine? = null
        @JvmField
        var surveyCampaign: Survey? = null

        fun toast(text: String, duration: Int) {
            Toast.makeText(CatroidApplication.getAppContext(), text, duration).show()
        }

        fun getCpuArchitecture(): String {


            val abis = Build.SUPPORTED_ABIS

            return if (abis != null && abis.isNotEmpty()) {
                abis[0]
            } else {


                @Suppress("DEPRECATION")
                Build.CPU_ABI
            }
        }
    }
}
