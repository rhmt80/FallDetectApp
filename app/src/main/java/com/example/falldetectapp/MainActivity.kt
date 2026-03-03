package com.example.falldetectapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.net.Uri
import android.provider.Settings
import android.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.os.CountDownTimer

class MainActivity : AppCompatActivity() {

    private val NOTIFICATION_PERMISSION_CODE = 101
    private val SMS_PERMISSION_CODE = 102
    private val LOCATION_PERMISSION_CODE = 103
    private val ALL_PERMISSIONS_REQUEST_CODE = 200

    private var alertCountDownTimer: CountDownTimer? = null
    private var isMonitoring: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If user has not completed setup, redirect to SetupActivity
        val prefs = getSharedPreferences("user_prefs", MODE_PRIVATE)
        val hasUserName = !prefs.getString("user_name", "").isNullOrEmpty()
        val hasUserPhone = !prefs.getString("user_phone", "").isNullOrEmpty()
        val hasCaretakerName = !prefs.getString("caretaker_name", "").isNullOrEmpty()
        val hasCaretakerPhone = !prefs.getString("caretaker_phone", "").isNullOrEmpty()

        if (!(hasUserName && hasUserPhone && hasCaretakerName && hasCaretakerPhone)) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        // Ask for all important permissions as soon as main screen opens
        requestAllPermissionsIfNeeded()

        val startBtn = findViewById<Button>(R.id.btnStart)
        val stopBtn = findViewById<Button>(R.id.btnStop)
        val statusText = findViewById<TextView>(R.id.statusText)
        val adaptiveSwitch = findViewById<Switch>(R.id.switchAdaptive)
        val profileSummary = findViewById<TextView>(R.id.profileSummary)
        val btnEditSetup = findViewById<Button>(R.id.btnEditSetup)
        val alertBanner = findViewById<LinearLayout>(R.id.alertBanner)
        val alertCountdownText = findViewById<TextView>(R.id.alertCountdownText)
        val btnCancelAlert = findViewById<Button>(R.id.btnCancelAlert)
        val btnTestAlert = findViewById<Button>(R.id.btnTestAlert)

        // Populate profile summary
        val userName = prefs.getString("user_name", "") ?: ""
        val caretakerName = prefs.getString("caretaker_name", "") ?: ""
        val caretakerPhone = prefs.getString("caretaker_phone", "") ?: ""
        profileSummary.text = "Monitoring for $userName\nCaretaker: $caretakerName ($caretakerPhone)"

        // Refresh status notification to reflect current state
        AppStatusNotifier.refreshStatus(this)

        startBtn.setOnClickListener {

            // Ensure all critical permissions are granted before starting monitoring
            if (!hasAllMonitoringPermissions()) {
                requestNotificationPermissionIfNeeded()
                requestSmsPermissionIfNeeded()
                requestLocationPermissionIfNeeded()

                Toast.makeText(
                    this,
                    "Please grant all permissions, then tap Start again.",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            val intent = Intent(this, ForegroundSensorService::class.java).apply {
                action = ForegroundSensorService.ACTION_START
                putExtra(
                    ForegroundSensorService.EXTRA_ADAPTIVE_MODE,
                    adaptiveSwitch.isChecked
                )
            }

            ContextCompat.startForegroundService(this, intent)

            statusText.text = if (adaptiveSwitch.isChecked)
                "Adaptive Mode Running"
            else
                "Constant Mode Running"
            isMonitoring = true
        }

        stopBtn.setOnClickListener {

            val intent = Intent(this, ForegroundSensorService::class.java).apply {
                action = ForegroundSensorService.ACTION_STOP
            }

            ContextCompat.startForegroundService(this, intent)
            statusText.text = "Service stopped"
            isMonitoring = false
        }

        btnTestAlert.setOnClickListener {
            if (!hasAllMonitoringPermissions()) {
                Toast.makeText(
                    this,
                    "Grant all permissions before sending a test alert.",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            val intent = Intent(this, ForegroundSensorService::class.java).apply {
                action = ForegroundSensorService.ACTION_TEST_ALERT
            }
            ContextCompat.startForegroundService(this, intent)
        }

        btnEditSetup.setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }

        btnCancelAlert.setOnClickListener {
            cancelPendingAlertOnService()
            alertBanner.visibility = View.GONE
            alertCountDownTimer?.cancel()
        }

        adaptiveSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isMonitoring) {
                Toast.makeText(
                    this,
                    "Stop monitoring, change mode, then start again for it to apply.",
                    Toast.LENGTH_LONG
                ).show()
                // Revert the change while monitoring is active
                adaptiveSwitch.isChecked = !isChecked
            }
        }

        // If launched due to pending alert, show banner with countdown
        if (intent?.getBooleanExtra("EXTRA_PENDING_ALERT", false) == true) {
            showAlertBannerWithCountdown(alertBanner, alertCountdownText)
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasNotificationPermission()
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_CODE
            )
        }
    }

    private fun hasSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestSmsPermissionIfNeeded() {
        if (!hasSmsPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.SEND_SMS),
                SMS_PERMISSION_CODE
            )
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun requestLocationPermissionIfNeeded() {
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                LOCATION_PERMISSION_CODE
            )
        }
    }

    private fun requestAllPermissionsIfNeeded() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!hasSmsPermission()) {
            permissionsToRequest.add(Manifest.permission.SEND_SMS)
        }
        if (!hasLocationPermission()) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                ALL_PERMISSIONS_REQUEST_CODE
            )
        }
    }

    private fun hasAllMonitoringPermissions(): Boolean {
        val notificationsOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasNotificationPermission()
        } else {
            true
        }
        val smsOk = hasSmsPermission()
        val locationOk = hasLocationPermission()
        return notificationsOk && smsOk && locationOk
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == ALL_PERMISSIONS_REQUEST_CODE) {
            if (!hasAllMonitoringPermissions()) {
                showPermissionsSettingsDialog()
            }
        }
    }

    private fun showPermissionsSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permissions required")
            .setMessage(
                "This app needs SMS, location, and notification permissions to monitor falls " +
                        "and send alerts. Please enable them in Settings."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun cancelPendingAlertOnService() {
        val intent = Intent(this, ForegroundSensorService::class.java).apply {
            action = ForegroundSensorService.ACTION_CANCEL_ALERT
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun showAlertBannerWithCountdown(
        alertBanner: LinearLayout,
        alertCountdownText: TextView
    ) {
        alertBanner.visibility = View.VISIBLE

        alertCountDownTimer?.cancel()
        alertCountDownTimer = object : CountDownTimer(15_000L, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = millisUntilFinished / 1000
                alertCountdownText.text = "Sending alert in ${seconds}s..."
            }

            override fun onFinish() {
                alertBanner.visibility = View.GONE
            }
        }.start()
    }
}