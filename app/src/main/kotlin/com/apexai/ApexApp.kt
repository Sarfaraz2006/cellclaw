package com.apexai

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import androidx.hilt.work.HiltWorkerFactory

@HiltAndroidApp
class ApexApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        val serviceChannel = NotificationChannel(
            CHANNEL_SERVICE,
            getString(R.string.notification_channel_service),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "APEX AI background service status"
            setShowBadge(false)
        }

        val approvalChannel = NotificationChannel(
            CHANNEL_APPROVALS,
            getString(R.string.notification_channel_approvals),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Tool execution approval requests"
        }

        val alertChannel = NotificationChannel(
            CHANNEL_ALERTS,
            getString(R.string.notification_channel_alerts),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "General alerts and notifications"
        }

        val wakeWordChannel = NotificationChannel(
            CHANNEL_WAKE_WORD,
            getString(R.string.notification_channel_wake_word),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Wake word detection status"
            setShowBadge(false)
        }

        manager.createNotificationChannels(listOf(serviceChannel, approvalChannel, alertChannel, wakeWordChannel))
    }

    companion object {
        const val CHANNEL_SERVICE = "apexai_service"
        const val CHANNEL_APPROVALS = "apexai_approvals"
        const val CHANNEL_ALERTS = "apexai_alerts"
        const val CHANNEL_WAKE_WORD = "apexai_wake_word"
    }
}
