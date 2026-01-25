package com.example.falldetectapp

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity

class SetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        val etUserName = findViewById<EditText>(R.id.etUserName)
        val etCaretakerName = findViewById<EditText>(R.id.etCaretakerName)
        val etCaretakerPhone = findViewById<EditText>(R.id.etCaretakerPhone)
        val btnSave = findViewById<Button>(R.id.btnSave)

        val prefs = getSharedPreferences("user_prefs", MODE_PRIVATE)

        // ✅ Pre-fill if user is editing details
        etUserName.setText(prefs.getString("user_name", ""))
        etCaretakerName.setText(prefs.getString("caretaker_name", ""))
        etCaretakerPhone.setText(prefs.getString("caretaker_phone", ""))

        btnSave.setOnClickListener {
            val userName = etUserName.text.toString().trim()
            val caretakerName = etCaretakerName.text.toString().trim()
            val caretakerPhone = etCaretakerPhone.text.toString().trim()

            if (userName.isEmpty()) {
                etUserName.error = "Required"
                return@setOnClickListener
            }

            if (caretakerName.isEmpty()) {
                etCaretakerName.error = "Required"
                return@setOnClickListener
            }

            if (caretakerPhone.length < 10) {
                etCaretakerPhone.error = "Enter valid phone number"
                return@setOnClickListener
            }

            prefs.edit()
                .putString("user_name", userName)
                .putString("caretaker_name", caretakerName)
                .putString("caretaker_phone", caretakerPhone)
                .apply()

            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}