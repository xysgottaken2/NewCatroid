package org.catrobat.catroid.apkbuild

import java.io.File

object ProjectScanner {

    data class ScanResult(
        val hasQemu: Boolean,
        val hasPython: Boolean,
        val hasCompiler: Boolean,
        val hasMkit: Boolean,
        val hasOnnx: Boolean,
        val hasMnn: Boolean,
        val needsInternet: Boolean,
        val needsCamera: Boolean,
        val needsLocation: Boolean,
        val needsBluetooth: Boolean,
        val needsNfc: Boolean,
        val needsAudioRecord: Boolean,
        val needsVibrate: Boolean,
        val needsOverlay: Boolean,
        val needsStorage: Boolean,
        val needsBackground: Boolean,
        /**
         * The project uses one of the PC game bricks or one of the RAM sensors,
         * so the APK template must be built from a tree that has them - otherwise
         * the exported APK loads the project without those bricks.
         */
        val hasPcGames: Boolean = false
    )

    fun scanProject(projectDir: File): ScanResult {
        val codeXml = File(projectDir, "code.xml")
        if (!codeXml.exists()) {
            return ScanResult(false, false, false, false, false, false,
                false, false, false, false, false, false, false, false, false, false, false)
        }

        var hasQemu = false
        var hasPython = false
        var hasCompiler = false
        var hasMkit = false
        var hasOnnx = false
        var hasMnn = false

        var needsInternet = false
        var needsCamera = false
        var needsLocation = false
        var needsBluetooth = false
        var needsNfc = false
        var needsAudioRecord = false
        var needsVibrate = false
        var needsOverlay = false
        var needsStorage = false
        var needsBackground = false
        var hasPcGames = false

        // `Pc...Brick` in code.xml plus the RAM sensors that were added with them.
        val pcBrickPattern = Regex("Pc[A-Za-z0-9]*Brick")
        val ramSensors = listOf("RAM_TOTAL", "RAM_AVAILABLE", "RAM_USED_PERCENT", "RAM_APP_LIMIT", "RAM_LOW")

        try {
            codeXml.bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    if (line.contains("RunVMBrick") || line.contains("RunVM2Brick") ||
                        line.contains("StopVMBrick") || line.contains("CreateDiskBrick")) {
                        hasQemu = true
                    }
                    if (line.contains("RunPythonScriptBrick") || line.contains("LoadNativeModuleBrick")) {
                        hasPython = true
                    }
                    if (line.contains("JavaCompileBrick") || line.contains("JavaLoadAndRunBrick")) {
                        hasCompiler = true
                    }
                    if (line.contains("LoadNNBrick") || line.contains("PredictNNBrick")) {
                        hasOnnx = true
                    }
                    if (line.contains("PtSetTrainingBrick") || line.contains("PtCreateTensorBrick")) {
                        hasMnn = true
                    }

                    if (line.contains("Multiplayer", ignoreCase = true) ||
                        line.contains("Http", ignoreCase = true) || line.contains("Download", ignoreCase = true) || line.contains("Network", ignoreCase = true) ||
                        line.contains("Firebase", ignoreCase = true) || line.contains("Mqtt", ignoreCase = true)) {
                        needsInternet = true
                    }

                    if (line.contains("CameraBrick") || line.contains("ChooseCameraBrick")) {
                        needsCamera = true
                        val isMlKitFace = line.contains("FACE_DETECTED", ignoreCase = true) || line.contains("FACE_SIZE", ignoreCase = true) ||
                                line.contains("TEXT_BLOCK", ignoreCase = true) || line.contains("OBJECT_ID")
                        if (isMlKitFace) hasMkit = true
                    }

                    if (line.contains("Location", ignoreCase = true) || line.contains("GPS", ignoreCase = true) || line.contains("Latitude", ignoreCase = true) || line.contains("Longitude", ignoreCase = true)) {
                        needsLocation = true
                    }

                    if (line.contains("Bluetooth", ignoreCase = true) || line.contains("Nxt", ignoreCase = true) || line.contains("Ev3", ignoreCase = true) || line.contains("Lego", ignoreCase = true)) {
                        needsBluetooth = true
                    }

                    if (line.contains("Nfc") || line.contains("NFC")) {
                        needsNfc = true
                    }

                    if (line.contains("StartRecordingBrick") || line.contains("Audio", ignoreCase = true) || line.contains("Speech", ignoreCase = true)) {
                        needsAudioRecord = true
                    }

                    if (line.contains("VibrationBrick")) {
                        needsVibrate = true
                    }

                    if (line.contains("File") || line.contains("Save") || line.contains("Export") || line.contains("Write")) {
                        needsStorage = true
                    }

                    if (line.contains("Background") || line.contains("Service") || line.contains("Foreground")) {
                        needsBackground = true
                    }

                    if (pcBrickPattern.containsMatchIn(line) || ramSensors.any { line.contains(it) }) {
                        hasPcGames = true
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return ScanResult(
            hasQemu, hasPython, hasCompiler, hasMkit, hasOnnx, hasMnn,
            needsInternet, needsCamera, needsLocation, needsBluetooth, needsNfc,
            needsAudioRecord, needsVibrate, needsOverlay, needsStorage, needsBackground,
            hasPcGames
        )
    }
}
