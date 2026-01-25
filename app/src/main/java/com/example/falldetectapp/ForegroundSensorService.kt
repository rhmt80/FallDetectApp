// adaptive
package com.example.falldetectapp

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import kotlin.math.sqrt

class ForegroundSensorService : Service(), SensorEventListener {

    companion object {
        const val ACTION_START = "com.example.falldetectapp.ACTION_START"
        const val ACTION_STOP = "com.example.falldetectapp.ACTION_STOP"
        private const val CHANNEL_ID = "fall_detect_channel"
        private const val NOTIF_ID = 101
    }

    // ================= CONFIG =================

    enum class SamplingMode { CONSTANT, ADAPTIVE }

    //--------------------------------------------
    // CHANGE HERE FOR ADAPTIVE AND CONSTANT
    private val SAMPLING_MODE = SamplingMode.CONSTANT
    //--------------------------------------------

    private val WINDOW_SIZE = 100
    private val CHANNELS = 6
    private val FALL_THRESHOLD = 0.75f

    // ================= ANDROID =================

    private lateinit var sensorManager: SensorManager
    private var accel: Sensor? = null
    private var gyro: Sensor? = null

    // ================= DATA =================

    private val buffer = Array(WINDOW_SIZE) { FloatArray(CHANNELS) }
    private var bufferIndex = 0

    private val lastAccel = FloatArray(3)
    private val lastGyro = FloatArray(3)

    // ================= ADAPTIVE =================

    private enum class MotionState { IDLE, ACTIVE }
    private var motionState = MotionState.IDLE
    private val wakeThreshold = 1.2f
    private var wakeCount = 0
    private val WAKE_REQUIRED = 5
    private var lastActiveTs = System.currentTimeMillis()
    private val IDLE_TIMEOUT_MS = 10_000L

    // ================= ML =================

    private lateinit var tfliteRunner: TFLiteRunner

    // ================= METRICS =================

    private var sensorEventCount = 0
    private var inferenceCount = 0
    private var totalInferenceTimeMs = 0L
    private var startTs = System.currentTimeMillis()

    // ======================================================

    override fun onCreate() {
        super.onCreate()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        tfliteRunner = TFLiteRunner(this)

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Fall detection running"))

        if (SAMPLING_MODE == SamplingMode.CONSTANT) {
            registerFastSensors()
            Log.i("MODE", "CONSTANT SAMPLING ENABLED")
        } else {
            registerSlowSensors()
            Log.i("MODE", "ADAPTIVE SAMPLING ENABLED")
        }

        logBattery()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        logFinalMetrics()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ================= SENSOR =================

    override fun onSensorChanged(event: SensorEvent) {
        sensorEventCount++

        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            lastAccel[0] = event.values[0]
            lastAccel[1] = event.values[1]
            lastAccel[2] = event.values[2]

            if (SAMPLING_MODE == SamplingMode.ADAPTIVE) {
                handleWakeDetection()
            }
        }

        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            lastGyro[0] = event.values[0]
            lastGyro[1] = event.values[1]
            lastGyro[2] = event.values[2]
        }

        if (SAMPLING_MODE == SamplingMode.CONSTANT || motionState == MotionState.ACTIVE) {
            addToBuffer()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ================= BUFFER =================

    private fun addToBuffer() {
        buffer[bufferIndex][0] = lastAccel[0]
        buffer[bufferIndex][1] = lastAccel[1]
        buffer[bufferIndex][2] = lastAccel[2]
        buffer[bufferIndex][3] = lastGyro[0]
        buffer[bufferIndex][4] = lastGyro[1]
        buffer[bufferIndex][5] = lastGyro[2]

        bufferIndex++

        if (bufferIndex == WINDOW_SIZE) {
            bufferIndex = 0
            runInference()
        }
    }

    private fun runInference() {
        val start = System.currentTimeMillis()
        val input = Array(1) { buffer }
        val confidence = tfliteRunner.runInference(input)
        val duration = System.currentTimeMillis() - start

        inferenceCount++
        totalInferenceTimeMs += duration

        Log.i("INFERENCE", "conf=$confidence time=${duration}ms")

        if (confidence >= FALL_THRESHOLD) {
            Log.e("FALL", "Fall detected conf=$confidence")
        }

        lastActiveTs = System.currentTimeMillis()
    }

    // ================= ADAPTIVE =================

    private fun handleWakeDetection() {
        val mag = sqrt(
            lastAccel[0] * lastAccel[0] +
                    lastAccel[1] * lastAccel[1] +
                    lastAccel[2] * lastAccel[2]
        )

        if (abs(mag - 9.81f) > wakeThreshold) {
            wakeCount++
        } else {
            wakeCount = 0
        }

        if (motionState == MotionState.IDLE && wakeCount >= WAKE_REQUIRED) {
            motionState = MotionState.ACTIVE
            registerFastSensors()
            wakeCount = 0
            Log.i("ADAPTIVE", "ENTER ACTIVE")
        }

        if (motionState == MotionState.ACTIVE &&
            System.currentTimeMillis() - lastActiveTs > IDLE_TIMEOUT_MS
        ) {
            motionState = MotionState.IDLE
            registerSlowSensors()
            Log.i("ADAPTIVE", "RETURN TO IDLE")
        }
    }

    // ================= SENSORS =================

    private fun registerFastSensors() {
        sensorManager.unregisterListener(this)
        accel?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        gyro?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
    }

    private fun registerSlowSensors() {
        sensorManager.unregisterListener(this)
        accel?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        gyro?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    // ================= METRICS =================

    private fun logBattery() {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        Log.i("METRICS", "Battery=${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%")
    }

    private fun logFinalMetrics() {
        val elapsed = (System.currentTimeMillis() - startTs) / 1000f
        val avgSensorRate = sensorEventCount / elapsed
        val avgInferTime =
            if (inferenceCount > 0) totalInferenceTimeMs / inferenceCount else 0

        Log.i("METRICS", "Elapsed=${elapsed}s")
        Log.i("METRICS", "SensorRate=${"%.2f".format(avgSensorRate)} events/s")
        Log.i("METRICS", "InferenceCount=$inferenceCount")
        Log.i("METRICS", "AvgInferenceTime=${avgInferTime}ms")
        logBattery()
    }

    // ================= NOTIFICATION =================

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Fall Detection")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Fall Detection",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }
}


// old version - no adaptive

//package com.example.falldetectapp
//
//import android.app.Notification
//import android.app.NotificationChannel
//import android.app.NotificationManager
//import android.app.Service
//import android.content.Intent
//import android.hardware.Sensor
//import android.hardware.SensorEvent
//import android.hardware.SensorEventListener
//import android.hardware.SensorManager
//import android.os.Build
//import android.os.IBinder
//import android.util.Log
//import androidx.core.app.NotificationCompat
//
//class ForegroundSensorService : Service(), SensorEventListener {
//
//    companion object {
//        const val ACTION_START = "com.example.falldetectapp.ACTION_START"
//        const val ACTION_STOP = "com.example.falldetectapp.ACTION_STOP"
//
//        private const val CHANNEL_ID = "fall_detect_channel"
//        private const val NOTIF_ID = 1
//    }
//
//    // ---------------- Sensors ----------------
//    private lateinit var sensorManager: SensorManager
//    private var accel: Sensor? = null
//    private var gyro: Sensor? = null
//
//    private var lastAccel = FloatArray(3)
//    private var lastGyro = FloatArray(3)
//    private var hasAccel = false
//    private var hasGyro = false
//
//    // ---------------- Buffer ----------------
//    private val FALL_THRESHOLD = 0.75f
//    private val windowSize = 100
//    private val sensorWindow = ArrayDeque<SensorFrame>(windowSize)
//
//    // ---------------- ML ----------------
//    private lateinit var tfliteRunner: TFLiteRunner
//
//    // ---------------- Lifecycle ----------------
//    override fun onCreate() {
//        super.onCreate()
//
//        createNotificationChannel()
//        startForeground(NOTIF_ID, buildNotification("Monitoring sensors"))
//
//        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
//        accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
//        gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
//
//        tfliteRunner = TFLiteRunner(this)
//
//        Log.d("Service", "Foreground service created")
//    }
//
//    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
//        when (intent?.action) {
//            ACTION_START -> registerSensors()
//            ACTION_STOP -> stopServiceInternal()
//        }
//        return START_STICKY
//    }
//
//    override fun onDestroy() {
//        unregisterSensors()
//        super.onDestroy()
//    }
//
//    override fun onBind(intent: Intent?): IBinder? = null
//
//    // ---------------- Sensor Handling ----------------
//    private fun registerSensors() {
//        accel?.let {
//            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
//        }
//        gyro?.let {
//            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
//        }
//        Log.d("Sensors", "Sensors registered")
//    }
//
//    private fun unregisterSensors() {
//        sensorManager.unregisterListener(this)
//        Log.d("Sensors", "Sensors unregistered")
//    }
//
//    override fun onSensorChanged(event: SensorEvent) {
//
//        when (event.sensor.type) {
//
//            Sensor.TYPE_ACCELEROMETER -> {
//                lastAccel[0] = event.values[0]
//                lastAccel[1] = event.values[1]
//                lastAccel[2] = event.values[2]
//                hasAccel = true
//            }
//
//            Sensor.TYPE_GYROSCOPE -> {
//                lastGyro[0] = event.values[0]
//                lastGyro[1] = event.values[1]
//                lastGyro[2] = event.values[2]
//                hasGyro = true
//            }
//        }
//
//        if (hasAccel && hasGyro) {
//            addFrameToWindow()
//        }
//    }
//
//    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
//
//    // ---------------- Buffer Logic ----------------
//    private fun addFrameToWindow() {
//        val frame = SensorFrame(
//            lastAccel[0], lastAccel[1], lastAccel[2],
//            lastGyro[0], lastGyro[1], lastGyro[2]
//        )
//
//        sensorWindow.addLast(frame)
//
//        if (sensorWindow.size > windowSize) {
//            sensorWindow.removeFirst()
//        }
//
//        if (sensorWindow.size == windowSize) {
//            runInference()
//        }
//    }
//
//    // ---------------- Normalization ----------------
//    private fun normalize(frame: SensorFrame): SensorFrame {
//        return SensorFrame(
//            ax = frame.ax / 9.81f,
//            ay = frame.ay / 9.81f,
//            az = frame.az / 9.81f,
//            gx = frame.gx / 5.0f,
//            gy = frame.gy / 5.0f,
//            gz = frame.gz / 5.0f
//        )
//    }
//
//    // ---------------- Inference ----------------
//    private fun runInference() {
//
//        val input = Array(1) { Array(windowSize) { FloatArray(6) } }
//
//        sensorWindow.forEachIndexed { i, frame ->
//            val f = normalize(frame)
//
//            input[0][i][0] = f.ax
//            input[0][i][1] = f.ay
//            input[0][i][2] = f.az
//            input[0][i][3] = f.gx
//            input[0][i][4] = f.gy
//            input[0][i][5] = f.gz
//        }
//
//        val confidence: Float = tfliteRunner.runInference(input)
//        val isFall: Boolean = confidence >= FALL_THRESHOLD
//
//        Log.d(
//            "Decision",
//            "confidence=$confidence | threshold=$FALL_THRESHOLD | fall=$isFall"
//        )
//    }
//
//    // ---------------- Notification ----------------
//    private fun createNotificationChannel() {
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//            val channel = NotificationChannel(
//                CHANNEL_ID,
//                "Fall Detection",
//                NotificationManager.IMPORTANCE_LOW
//            )
//            val manager = getSystemService(NotificationManager::class.java)
//            manager.createNotificationChannel(channel)
//        }
//    }
//
//    private fun buildNotification(text: String): Notification {
//        return NotificationCompat.Builder(this, CHANNEL_ID)
//            .setContentTitle("Fall Detection")
//            .setContentText(text)
//            .setSmallIcon(R.mipmap.ic_launcher)
//            .build()
//    }
//
//    private fun stopServiceInternal() {
//        unregisterSensors()
//        stopForeground(true)
//        stopSelf()
//        Log.d("Service", "Service stopped")
//    }
//}
//
//// ---------------- Data Class ----------------
//data class SensorFrame(
//    val ax: Float,
//    val ay: Float,
//    val az: Float,
//    val gx: Float,
//    val gy: Float,
//    val gz: Float
//)