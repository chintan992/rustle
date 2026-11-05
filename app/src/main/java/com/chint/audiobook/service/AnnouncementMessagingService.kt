package com.chint.audiobook.service

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.chint.audiobook.MainActivity
import com.chint.audiobook.R
import com.chint.audiobook.notifications.AnnouncementNotifications
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class AnnouncementMessagingService : FirebaseMessagingService() {
  override fun onMessageReceived(message: RemoteMessage) {
    val title = message.notification?.title
      ?: message.data["title"]
      ?: getString(R.string.app_name)
    val body = message.notification?.body ?: message.data["body"]

    if (body.isNullOrBlank()) {
      Log.w(TAG, "Ignoring announcement without a message body")
      return
    }
    if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
      Log.w(TAG, "Announcement received while app notifications are disabled")
      return
    }

    AnnouncementNotifications.createChannel(this)
    val openAppIntent = Intent(this, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    val pendingIntent = PendingIntent.getActivity(
      this,
      0,
      openAppIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val notification = NotificationCompat.Builder(
      this,
      getString(R.string.announcement_notification_channel_id)
    )
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle(title)
      .setContentText(body)
      .setStyle(NotificationCompat.BigTextStyle().bigText(body))
      .setContentIntent(pendingIntent)
      .setAutoCancel(true)
      .setPriority(NotificationCompat.PRIORITY_DEFAULT)
      .build()

    NotificationManagerCompat.from(this).notify(
      message.messageId?.hashCode() ?: System.currentTimeMillis().toInt(),
      notification
    )
  }

  private companion object {
    const val TAG = "AnnouncementFCM"
  }
}
