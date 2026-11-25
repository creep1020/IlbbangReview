package com.example.ilbbangandroid

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class IlbbangFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "IlbbangFCM"
        const val CHANNEL_ID = "ilbbang_study_notifications"
        const val PREFS_NAME = "ilbbang_native_fcm"
        const val TOKEN_KEY = "fcm_token"
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(TOKEN_KEY, token)
            .apply()

        Log.d(TAG, "새 FCM 토큰 저장 완료")
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val title = message.notification?.title
            ?: message.data["title"]
            ?: "일빵빵 영어"

        val body = message.notification?.body
            ?: message.data["body"]
            ?: message.data["message"]
            ?: "오늘의 영어 학습을 확인해 보세요."

        Log.d(TAG, "알림 수신: $title / $body")

        createNotificationChannel()
        showNotification(title, body)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val soundUri = RingtoneManager.getDefaultUri(
            RingtoneManager.TYPE_NOTIFICATION
        )

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val channel = NotificationChannel(
            CHANNEL_ID,
            "일빵빵 학습 알림",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "일빵빵 영어 학습 및 복습 알림"
            enableVibration(true)
            setSound(soundUri, audioAttributes)
            lockscreenVisibility =
                android.app.Notification.VISIBILITY_PUBLIC
        }

        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun showNotification(title: String, body: String) {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "알림 권한이 없어 표시하지 못했습니다.")
            return
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(body)
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSound(
                RingtoneManager.getDefaultUri(
                    RingtoneManager.TYPE_NOTIFICATION
                )
            )
            .setVibrate(longArrayOf(0, 300, 200, 300))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(this)
                .notify(System.currentTimeMillis().toInt(), notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "알림 표시 권한 오류", e)
        }
    }
}
