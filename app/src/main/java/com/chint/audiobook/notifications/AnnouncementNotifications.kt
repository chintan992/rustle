package com.chint.audiobook.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.chint.audiobook.R
import com.google.firebase.messaging.FirebaseMessaging
import com.google.android.gms.tasks.Task

object AnnouncementNotifications {
  const val TOPIC = "general-announcements"

  fun subscribe(): Task<Void> {
    val messaging = FirebaseMessaging.getInstance()
    messaging.isAutoInitEnabled = true
    return messaging.subscribeToTopic(TOPIC).addOnFailureListener {
      messaging.isAutoInitEnabled = false
    }
  }

  fun unsubscribe(): Task<Void> {
    val messaging = FirebaseMessaging.getInstance()
    return messaging.unsubscribeFromTopic(TOPIC).addOnSuccessListener {
      messaging.isAutoInitEnabled = false
    }
  }

  fun createChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

    val channel = NotificationChannel(
      context.getString(R.string.announcement_notification_channel_id),
      context.getString(R.string.announcement_notification_channel_name),
      NotificationManager.IMPORTANCE_DEFAULT
    ).apply {
      description = context.getString(R.string.announcement_notification_channel_description)
    }
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(channel)
  }
}
