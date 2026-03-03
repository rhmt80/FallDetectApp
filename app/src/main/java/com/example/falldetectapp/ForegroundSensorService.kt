package com.example.falldetectapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.telephony.SmsManager
import kotlin.math.sqrt

class ForegroundSensorService : Service(), SensorEventListener {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_CANCEL_ALERT = "ACTION_CANCEL_ALERT"
        const val ACTION_TEST_ALERT = "ACTION_TEST_ALERT"
        const val EXTRA_ADAPTIVE_MODE = "EXTRA_ADAPTIVE_MODE"

        private const val CHANNEL_ID = "FallDetectionChannelV2"
        private const val NOTIFICATION_ID = 1

        private const val WINDOW_SIZE = 100
        private const val HIGH_RATE = SensorManager.SENSOR_DELAY_GAME
        private const val LOW_RATE = SensorManager.SENSOR_DELAY_NORMAL

        private const val FALL_CONFIDENCE_THRESHOLD = 0.8f
        private const val ALERT_DELAY_MS = 15_000L

        private const val STATE_LOW = 0
        private const val STATE_HIGH = 1
    }

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null

    private lateinit var sensorBuffer: SensorBuffer
    private lateinit var tfliteRunner: TFLiteRunner
    private lateinit var wakeDetector: WakeDetector
    private lateinit var metricsLogger: MetricsLogger

    private lateinit var locationManager: LocationManager

    private val handler = Handler(Looper.getMainLooper())
    private var alertPending = false
    private var alertRunnable: Runnable? = null

    private var adaptiveMode = false
    private var currentSamplingRate = HIGH_RATE

    // Adaptive state machine
    private var currentState = STATE_LOW
    private var highStateStartTime = 0L
    private var calmStartTime = 0L

    private val MIN_HIGH_DURATION = 3000L
    private val CALM_DURATION_REQUIRED = 2000L

    override fun onCreate() {
        super.onCreate()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        sensorBuffer = SensorBuffer(WINDOW_SIZE)
        tfliteRunner = TFLiteRunner(this)
        wakeDetector = WakeDetector()
        metricsLogger = MetricsLogger(this)

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        when (intent?.action) {

            ACTION_START -> {
                adaptiveMode = intent.getBooleanExtra(EXTRA_ADAPTIVE_MODE, false)
                startMonitoring()
            }

            ACTION_STOP -> stopMonitoring()

            ACTION_CANCEL_ALERT -> cancelPendingAlert()

            ACTION_TEST_ALERT -> onPossibleFallDetected(1.0f)
        }

        return START_STICKY
    }

    private fun startMonitoring() {

        startForegroundServiceInternal()

        currentState = STATE_LOW
        calmStartTime = 0L

        currentSamplingRate = if (adaptiveMode) LOW_RATE else HIGH_RATE

        registerSensors(currentSamplingRate)
        metricsLogger.startSession(adaptiveMode)

        Log.d("MODE", if (adaptiveMode) "ADAPTIVE" else "CONSTANT")

        AppStatusNotifier.setMonitoringActive(this, true)
    }

    private fun stopMonitoring() {
        unregisterSensors()
        metricsLogger.stopSession()
        stopForeground(true)
        stopSelf()

        AppStatusNotifier.setMonitoringActive(this, false)
    }

    private fun registerSensors(rate: Int) {
        accelerometer?.let {
            sensorManager.registerListener(this, it, rate)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, rate)
        }
    }

    private fun unregisterSensors() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {

        when (event.sensor.type) {

            Sensor.TYPE_ACCELEROMETER -> {

                sensorBuffer.addAccelerometer(event.values.clone())
                metricsLogger.logAccelerometer()

                if (adaptiveMode) {
                    handleAdaptiveLogic(event.values)
                }
            }

            Sensor.TYPE_GYROSCOPE -> {
                sensorBuffer.addGyroscope(event.values.clone())
                metricsLogger.logGyroscope()
            }
        }

        if (sensorBuffer.isFull()) {

            val input: FloatArray = sensorBuffer.getFlattenedWindow()
            val confidence = tfliteRunner.runInference(input)

            metricsLogger.logInference()

            Log.d("INFERENCE", "Confidence: $confidence")

            if (confidence >= FALL_CONFIDENCE_THRESHOLD) {
                onPossibleFallDetected(confidence)
            }

            sensorBuffer.clear()
        }
    }

    private fun onPossibleFallDetected(confidence: Float) {
        if (alertPending) {
            // Already counting down for an alert, avoid stacking
            return
        }

        alertPending = true

        // Explicit buzz + beep when alert window starts
        try {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(
                        600,
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(600)
            }

            val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(applicationContext, notificationUri)
            ringtone?.play()
        } catch (e: Exception) {
            Log.w("ALERT", "Failed to play alert sound/vibration", e)
        }

        // Launch main activity so the user can cancel within 15 seconds
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("EXTRA_PENDING_ALERT", true)
        }
        startActivity(activityIntent)

        // Update notification to reflect pending alert
        showPendingAlertNotification()

        alertRunnable = Runnable {
            sendAlertSms()
            alertPending = false
            // After sending, go back to normal monitoring notification
            startForegroundServiceInternal()
        }

        handler.postDelayed(alertRunnable!!, ALERT_DELAY_MS)
    }

    private fun cancelPendingAlert() {
        if (!alertPending) return

        alertRunnable?.let { handler.removeCallbacks(it) }
        alertRunnable = null
        alertPending = false

        // Restore normal monitoring notification
        startForegroundServiceInternal()
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun getLastKnownLocation(): Location? {
        if (!hasLocationPermission()) return null

        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )

        for (provider in providers) {
            try {
                val loc = locationManager.getLastKnownLocation(provider)
                if (loc != null) return loc
            } catch (_: SecurityException) {
                // Permission check already done
            }
        }

        return null
    }

    private fun hasSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun sendAlertSms() {
        if (!hasSmsPermission()) {
            Log.w("ALERT", "SMS permission not granted, cannot send alert")
            return
        }

        val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val userName = prefs.getString("user_name", "User") ?: "User"
        val userPhone = prefs.getString("user_phone", "") ?: ""
        val caretakerName = prefs.getString("caretaker_name", "Caretaker") ?: "Caretaker"
        val caretakerPhone = prefs.getString("caretaker_phone", "") ?: ""

        if (caretakerPhone.isEmpty()) {
            Log.w("ALERT", "Caretaker phone not configured, cannot send alert")
            return
        }

        val location = getLastKnownLocation()

        val locationPart = if (location != null) {
            val lat = location.latitude
            val lon = location.longitude
            "Location: https://maps.google.com/?q=$lat,$lon"
        } else {
            "Location: unavailable"
        }

        val userPhonePart = if (userPhone.isNotEmpty()) {
            "User phone: $userPhone"
        } else {
            ""
        }

        val message = buildString {
            append("Fall detected for $userName. ")
            append("Please check on them immediately.\n")
            append("Caretaker: $caretakerName.\n")
            if (userPhonePart.isNotEmpty()) {
                append("$userPhonePart\n")
            }
            append(locationPart)
        }

        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                applicationContext.getSystemService(SmsManager::class.java)
            } else {
                SmsManager.getDefault()
            }
            smsManager.sendTextMessage(caretakerPhone, null, message, null, null)
            Log.d("ALERT", "Alert SMS sent to $caretakerPhone")
        } catch (e: Exception) {
            Log.e("ALERT", "Failed to send SMS", e)
        }
    }

    private fun handleAdaptiveLogic(values: FloatArray) {

        val magnitude = sqrt(
            values[0] * values[0] +
                    values[1] * values[1] +
                    values[2] * values[2]
        )

        val now = System.currentTimeMillis()

        when (currentState) {

            STATE_LOW -> {

                if (wakeDetector.isMovementDetected(magnitude)) {

                    currentState = STATE_HIGH
                    highStateStartTime = now

                    unregisterSensors()
                    registerSensors(HIGH_RATE)

                    currentSamplingRate = HIGH_RATE

                    Log.d("ADAPTIVE", "Switched to HIGH")
                }
            }

            STATE_HIGH -> {

                val timeInHigh = now - highStateStartTime

                if (timeInHigh >= MIN_HIGH_DURATION) {

                    if (wakeDetector.isCalm(magnitude)) {

                        if (calmStartTime == 0L) {
                            calmStartTime = now
                        }

                        val calmDuration = now - calmStartTime

                        if (calmDuration >= CALM_DURATION_REQUIRED) {

                            currentState = STATE_LOW
                            calmStartTime = 0L

                            unregisterSensors()
                            registerSensors(LOW_RATE)

                            currentSamplingRate = LOW_RATE

                            Log.d("ADAPTIVE", "Switched to LOW")
                        }

                    } else {
                        calmStartTime = 0L
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundServiceInternal() {

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Fall Detection Running")
            .setContentText("Monitoring sensors...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun showPendingAlertNotification() {

        val cancelIntent = Intent(this, ForegroundSensorService::class.java).apply {
            action = ACTION_CANCEL_ALERT
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            0,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Possible fall detected")
            .setContentText("Sending alert in 15 seconds unless cancelled")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .addAction(0, "Cancel Alert", cancelPendingIntent)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Fall Detection",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                enableLights(true)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}