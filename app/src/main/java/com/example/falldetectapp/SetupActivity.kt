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
        val etUserPhone = findViewById<EditText>(R.id.etUserPhone)
        val etCaretakerName = findViewById<EditText>(R.id.etCaretakerName)
        val etCaretakerPhone = findViewById<EditText>(R.id.etCaretakerPhone)
        val btnSave = findViewById<Button>(R.id.btnSave)

        val prefs = getSharedPreferences("user_prefs", MODE_PRIVATE)

        // Pre-fill if user is editing details
        etUserName.setText(prefs.getString("user_name", ""))
        etUserPhone.setText(
            stripCountryPrefix(
                prefs.getString("user_phone", "") ?: ""
            )
        )
        etCaretakerName.setText(prefs.getString("caretaker_name", ""))
        etCaretakerPhone.setText(
            stripCountryPrefix(
                prefs.getString("caretaker_phone", "") ?: ""
            )
        )

        btnSave.setOnClickListener {
            val userName = etUserName.text.toString().trim()
            val userPhoneRaw = etUserPhone.text.toString().trim()
            val caretakerName = etCaretakerName.text.toString().trim()
            val caretakerPhoneRaw = etCaretakerPhone.text.toString().trim()

            if (userName.isEmpty()) {
                etUserName.error = "Required"
                return@setOnClickListener
            }

            val normalizedUserPhone = normalizeIndianNumber(userPhoneRaw)
            if (normalizedUserPhone == null) {
                etUserPhone.error = "Enter valid 10-digit number"
                return@setOnClickListener
            }

            if (caretakerName.isEmpty()) {
                etCaretakerName.error = "Required"
                return@setOnClickListener
            }

            val normalizedCaretakerPhone = normalizeIndianNumber(caretakerPhoneRaw)
            if (normalizedCaretakerPhone == null) {
                etCaretakerPhone.error = "Enter valid 10-digit number"
                return@setOnClickListener
            }

            prefs.edit()
                .putString("user_name", userName)
                .putString("user_phone", normalizedUserPhone)
                .putString("caretaker_name", caretakerName)
                .putString("caretaker_phone", normalizedCaretakerPhone)
                .apply()

            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    private fun normalizeIndianNumber(raw: String): String? {
        val digitsOnly = raw.filter { it.isDigit() }
        val core = when {
            digitsOnly.length == 10 -> digitsOnly
            digitsOnly.length == 12 && digitsOnly.startsWith("91") -> digitsOnly.substring(2)
            else -> return null
        }
        return "+91$core"
    }

    private fun stripCountryPrefix(number: String): String {
        return when {
            number.startsWith("+91") && number.length > 3 -> number.substring(3)
            number.startsWith("91") && number.length > 2 -> number.substring(2)
            else -> number
        }
    }
}