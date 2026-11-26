/*
 * Catroid: An on-device visual programming system for Android devices
 * Copyright (C) 2010-2022 The Catrobat Team
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
 * along with this program.  If not, see <http:
 */
package org.catrobat.catroid;

import static org.catrobat.catroid.common.FlavoredConstants.DEFAULT_ROOT_DIRECTORY;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.StrictMode;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.danvexteam.lunoscript_annotations.LunoClass;
import com.google.android.gms.analytics.GoogleAnalytics;
import com.google.android.gms.analytics.Tracker;
import com.huawei.agconnect.AGConnectInstance;
import com.huawei.agconnect.config.AGConnectServicesConfig;
import com.huawei.hms.mlsdk.common.MLApplication;

import org.catrobat.catroid.formulaeditor.CustomFormulaManager;
import org.catrobat.catroid.koin.CatroidKoinHelperKt;
import org.catrobat.catroid.plugins.PluginEventBus;
import org.catrobat.catroid.plugins.PluginExecutor;
import org.catrobat.catroid.plugins.PluginOverlayManager;
import org.catrobat.catroid.utils.FileMetaDataExtractor;
import org.catrobat.catroid.utils.ThemeEngine;
import org.catrobat.catroid.utils.Utils;

import java.io.File;
import java.util.Locale;

@LunoClass
public class CatroidApplication extends Application {

	private static final String TAG = CatroidApplication.class.getSimpleName();

	private static Context context;
	public static String defaultSystemLanguage;

	private static GoogleAnalytics googleAnalytics;
	private static Tracker googleTracker;

	public static boolean IS_SAFE_MODE = false;
	private boolean pluginsLoaded = false;

	public static CatroidApplication current;

    public static PluginCompositeClassLoader globalPluginClassLoader;

	@TargetApi(30)
	@Override
	public void onCreate() {
        Log.i(TAG, "startup.stage.begin=application.onCreate");
        Log.i(TAG, "startup.stage.begin=application.read-safe-mode-preference");
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		boolean forceSafeMode = prefs.getBoolean("force_safe_mode", false);

		if (forceSafeMode) {
			IS_SAFE_MODE = true;
			prefs.edit()
					.remove("force_safe_mode")
					.apply();
		}
        Log.i(TAG, "startup.stage.complete=application.read-safe-mode-preference forceSafeMode=" + forceSafeMode);

		super.onCreate();
        Log.i(TAG, "startup.stage.complete=application.super.onCreate");

        Log.i(TAG, "startup.stage.begin=application.install-plugin-classloader");
        try {
            ClassLoader originalClassLoader = getClassLoader();
            globalPluginClassLoader = new PluginCompositeClassLoader(originalClassLoader);

            Context baseContext = getBaseContext();
            Class<?> contextImplClass = Class.forName("android.app.ContextImpl");
            java.lang.reflect.Field mClassLoaderField = contextImplClass.getDeclaredField("mClassLoader");
            mClassLoaderField.setAccessible(true);

            mClassLoaderField.set(baseContext, globalPluginClassLoader);
            Log.d(TAG, "ClassLoader: Глобальный PluginCompositeClassLoader успешно внедрен в ContextImpl.");
            Log.i(TAG, "startup.stage.complete=application.install-plugin-classloader");
        } catch (Exception e) {
            Log.e(TAG, "startup.stage.failed=application.install-plugin-classloader", e);
        }

		Log.d(TAG, "CatroidApplication onCreate");
		Log.d(TAG, "git commit info: " + BuildConfig.GIT_COMMIT_INFO);

		if (BuildConfig.DEBUG && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Log.i(TAG, "startup.stage.begin=application.configure-strict-mode");
			StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
					.detectNonSdkApiUsage()
					.penaltyLog()
					.build());
            Log.i(TAG, "startup.stage.complete=application.configure-strict-mode");
		}

		runStartupStep("application.store-context", () -> context = getApplicationContext());
		runStartupStep("application.start-koin", () -> CatroidKoinHelperKt.start(this, CatroidKoinHelperKt.getMyModules()));
		runStartupStep("application.fetch-speech-languages", () -> Utils.fetchSpeechRecognitionSupportedLanguages(this));
		runStartupStep("application.read-system-language", () -> defaultSystemLanguage = Locale.getDefault().toLanguageTag());
		runStartupStep("application.initialize-google-analytics", () -> {
			googleAnalytics = GoogleAnalytics.getInstance(this);
			googleAnalytics.setDryRun(BuildConfig.DEBUG);
		});
		runStartupStep("application.initialize-huawei-services", this::setupHuaweiMobileServices);
		runStartupStep("application.initialize-custom-formulas", () -> CustomFormulaManager.INSTANCE.initialize());
		runStartupStep("application.prepare-plugin-environment", () -> PluginExecutor.getInstance(this).prepareEnvironment());
		runStartupStep("application.register-lifecycle-callbacks", () -> registerActivityLifecycleCallbacks(new OverlayLifecycleCallbacks()));
		runStartupStep("application.publish-current-instance", () -> current = this);
		Log.i(TAG, "startup.result=application-ready");
	}

	private void runStartupStep(String stage, Runnable action) {
		long startedAt = SystemClock.elapsedRealtime();
		Log.i(TAG, "startup.stage.begin=" + stage);
		try {
			action.run();
			Log.i(TAG, "startup.stage.complete=" + stage + " durationMs=" + (SystemClock.elapsedRealtime() - startedAt));
		} catch (RuntimeException | Error error) {
			Log.e(TAG, "startup.stage.failed=" + stage + " durationMs=" + (SystemClock.elapsedRealtime() - startedAt), error);
			throw error;
		}
	}

	public void loadPluginsIfNotLoaded() {
		if (pluginsLoaded) {
			return;
		}
		pluginsLoaded = true;


		if (IS_SAFE_MODE) {
			Log.w(TAG, "SAFE MODE is active. Skipping plugin loading.");

			new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
				Toast.makeText(this, "Safe Mode. Plugins disabled", Toast.LENGTH_LONG).show();
			});
			return;
		}

		Log.d(TAG, "Loading all enabled plugins now.");
		PluginExecutor.getInstance(this).loadAndRunAllEnabledPlugins();
	}

	private void setupHuaweiMobileServices() {
		if (AGConnectInstance.getInstance() == null) {
			AGConnectInstance.initialize(this);
		}

		String apiKey = AGConnectServicesConfig.fromContext(this).getString("client/api_key");
		MLApplication.getInstance().setApiKey(apiKey);
	}

	public synchronized Tracker getDefaultTracker() {
		if (googleTracker == null) {
			googleTracker = googleAnalytics.newTracker(R.xml.global_tracker);
		}

		return googleTracker;
	}

	public static Context getAppContext() {
		return CatroidApplication.context;
	}

	private void resetThemeSettings() {

		String prefsName = "live_theme_settings";


		String toolbarKey = "theme_toolbar_background";
		String backgroundKey = "theme_primary_background";

		Log.d("ThemeReset", "Сброс настроек темы...");

		SharedPreferences themePrefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE);
		SharedPreferences.Editor editor = themePrefs.edit();


		editor.remove(toolbarKey);
		editor.remove(backgroundKey);


		editor.apply();

		Log.d("ThemeReset", "Настройки темы успешно сброшены.");
	}

    public static class PluginCompositeClassLoader extends ClassLoader {
        private final java.util.List<ClassLoader> pluginLoaders = new java.util.concurrent.CopyOnWriteArrayList<>();

        // Потокобезопасный маркер для отслеживания текущих загружаемых классов
        private final ThreadLocal<java.util.Set<String>> loadingClasses = ThreadLocal.withInitial(java.util.HashSet::new);

        public PluginCompositeClassLoader(ClassLoader parent) {
            super(parent);
        }

        public void addPluginLoader(ClassLoader cl) {
            if (cl != null && !pluginLoaders.contains(cl)) {
                pluginLoaders.add(cl);
            }
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            java.util.Set<String> loading = loadingClasses.get();

            if (loading.contains(name)) {
                throw new ClassNotFoundException(name);
            }

            loading.add(name);
            try {
                for (ClassLoader cl : pluginLoaders) {
                    try {
                        return cl.loadClass(name);
                    } catch (ClassNotFoundException ignored) {}
                }
            } finally {
                loading.remove(name);
            }
            throw new ClassNotFoundException(name);
        }

        @Override
        public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            try {
                return getParent().loadClass(name);
            } catch (ClassNotFoundException e) {
                return findClass(name);
            }
        }
    }

	private static class OverlayLifecycleCallbacks implements ActivityLifecycleCallbacks {

		@Override
		public void onActivityResumed(@NonNull Activity activity) {
			PluginOverlayManager.getInstance().attach(activity);
			String activityName = activity.getClass().getSimpleName();
			PluginEventBus.getInstance().dispatch("Activity.onShow", activityName);

			if (!IS_SAFE_MODE) {
				ThemeEngine.applyTheme(activity);
			}
		}

		@Override
		public void onActivityPaused(@NonNull Activity activity) {
			String activityName = activity.getClass().getSimpleName();
			PluginEventBus.getInstance().dispatch("Activity.onHide", activityName);

			PluginOverlayManager.getInstance().detach(activity);
		}



		@Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {}
		@Override public void onActivityStarted(@NonNull Activity activity) {}
		@Override public void onActivityStopped(@NonNull Activity activity) {}
		@Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {}
		@Override public void onActivityDestroyed(@NonNull Activity activity) {}
	}
}
