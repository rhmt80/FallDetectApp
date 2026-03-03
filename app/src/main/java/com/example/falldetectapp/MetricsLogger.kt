package com.example.falldetectapp

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class MetricsLogger(private val context: Context) {

    private var accelCount = 0
    private var gyroCount = 0
    private var inferenceCount = 0
    private var startTime = 0L
    private var mode = "CONSTANT"

    private var logFile: File? = null

    fun startSession(adaptiveMode: Boolean) {

        mode = if (adaptiveMode) "ADAPTIVE" else "CONSTANT"

        accelCount = 0
        gyroCount = 0
        inferenceCount = 0
        startTime = System.currentTimeMillis()

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            .format(Date())

        logFile = File(
            context.getExternalFilesDir(null),
            "metrics_${mode}_$timeStamp.csv"
        )

        logFile?.writeText(
            "Mode,Duration_ms,AccelEvents,GyroEvents,InferenceCount\n"
        )
    }

    fun stopSession() {

        val duration = System.currentTimeMillis() - startTime

        logFile?.appendText(
            "$mode,$duration,$accelCount,$gyroCount,$inferenceCount\n"
        )
    }

    fun logAccelerometer() {
        accelCount++
    }

    fun logGyroscope() {
        gyroCount++
    }

    fun logInference() {
        inferenceCount++
    }
}