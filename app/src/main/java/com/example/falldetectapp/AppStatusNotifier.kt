package com.example.falldetectapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

object AppStatusNotifier {

    private const val STATUS_CHANNEL_ID = "FallDetectionStatusChannelV2"
    private const val STATUS_NOTIFICATION_ID = 2
    private const val PREFS_NAME = "user_prefs"
    private const val KEY_MONITORING_ACTIVE = "monitoring_active"

    fun setMonitoringActive(context: Context, active: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_MONITORING_ACTIVE, active).apply()
        refreshStatus(context)
    }

    fun refreshStatus(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val hasUserName = !prefs.getString("user_name", "").isNullOrEmpty()
        val hasUserPhone = !prefs.getString("user_phone", "").isNullOrEmpty()
        val hasCaretakerName = !prefs.getString("caretaker_name", "").isNullOrEmpty()
        val hasCaretakerPhone = !prefs.getString("caretaker_phone", "").isNullOrEmpty()
        val setupComplete = hasUserName && hasUserPhone && hasCaretakerName && hasCaretakerPhone

        val monitoringActive = prefs.getBoolean(KEY_MONITORING_ACTIVE, false)
        val permissionsOk = hasAllMonitoringPermissions(context)

        updateStatusNotification(context, setupComplete, permissionsOk, monitoringActive)
    }

    private fun updateStatusNotification(
        context: Context,
        setupComplete: Boolean,
        permissionsOk: Boolean,
        monitoringActive: Boolean
    ) {
        val manager = context.getSystemService(NotificationManager::class.java)
        createStatusChannelIfNeeded(manager)

        // If everything is fine, remove the status notification
        if (setupComplete && permissionsOk && monitoringActive) {
            manager.cancel(STATUS_NOTIFICATION_ID)
            return
        }

        val statusText = when {
            !setupComplete -> "Emergency contact setup incomplete. Tap to finish."
            !permissionsOk -> "Permissions missing for fall monitoring. Tap to fix."
            !monitoringActive -> "Fall monitoring is OFF. Tap to start."
            else -> "Fall detection needs your attention."
        }

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            1,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, STATUS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Fall Detection status")
            .setContentText(statusText)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setAutoCancel(false)
            .build()

        manager.notify(STATUS_NOTIFICATION_ID, notification)
    }

    private fun createStatusChannelIfNeeded(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = manager.getNotificationChannel(STATUS_CHANNEL_ID)
            if (existing != null) return

            val channel = NotificationChannel(
                STATUS_CHANNEL_ID,
                "Fall Detection Status",
                NotificationManager.IMPORTANCE_HIGH
            )
            manager.createNotificationChannel(channel)
        }
    }

    private fun hasAllMonitoringPermissions(context: Context): Boolean {
        val smsOk = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.SEND_SMS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        val fine = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val locationOk = fine || coarse

        val notificationsOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return smsOk && locationOk && notificationsOk
    }
}

