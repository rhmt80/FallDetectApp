package com.example.falldetectapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("user_prefs", MODE_PRIVATE)

        // 🚨 HARD GUARD: redirect if setup not done
        if (!prefs.contains("caretaker_phone")) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        val startBtn = findViewById<Button>(R.id.btnStart)
        val stopBtn = findViewById<Button>(R.id.btnStop)
        val editBtn = findViewById<Button>(R.id.btnEditSetup)
        val statusText = findViewById<TextView>(R.id.statusText)

        startBtn.setOnClickListener {
            val intent = Intent(this, ForegroundSensorService::class.java).apply {
                action = ForegroundSensorService.ACTION_START
            }
            ContextCompat.startForegroundService(this, intent)
            statusText.text = "Service running"
        }

        stopBtn.setOnClickListener {
            val intent = Intent(this, ForegroundSensorService::class.java).apply {
                action = ForegroundSensorService.ACTION_STOP
            }
            ContextCompat.startForegroundService(this, intent)
            statusText.text = "Service stopped"
        }

        editBtn.setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }
    }
}