package com.elderphone.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

class ElderPhoneApp : Application() {

    companion object {
        const val CHANNEL_ID_CALLS = "elder_phone_calls_channel"
        lateinit var instance: ElderPhoneApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "สายเรียกเข้า (Incoming Calls)"
            val descriptionText = "การแจ้งเตือนสายโทรเข้าสำหรับผู้สูงอายุ"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID_CALLS, name, importance).apply {
                description = descriptionText
                setShowBadge(true)
                enableVibration(true)
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
