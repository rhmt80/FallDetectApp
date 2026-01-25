package com.example.falldetectapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val NOTIFICATION_PERMISSION_CODE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestNotificationPermissionIfNeeded()

        val startBtn = findViewById<Button>(R.id.btnStart)
        val stopBtn = findViewById<Button>(R.id.btnStop)
        val statusText = findViewById<TextView>(R.id.statusText)

        startBtn.setOnClickListener {
            if (hasNotificationPermission()) {
                val intent = Intent(this, ForegroundSensorService::class.java).apply {
                    action = ForegroundSensorService.ACTION_START
                }
                ContextCompat.startForegroundService(this, intent)

                // ✅ UPDATE UI
                statusText.text = "Service running"
            } else {
                requestNotificationPermissionIfNeeded()
            }
        }

        stopBtn.setOnClickListener {
            val intent = Intent(this, ForegroundSensorService::class.java).apply {
                action = ForegroundSensorService.ACTION_STOP
            }
            ContextCompat.startForegroundService(this, intent)

            // ✅ UPDATE UI
            statusText.text = "Service stopped"
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
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
}