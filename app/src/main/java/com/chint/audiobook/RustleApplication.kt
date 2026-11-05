package com.chint.audiobook

import android.app.Application
import com.chint.audiobook.notifications.AnnouncementNotifications
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class RustleApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    AnnouncementNotifications.createChannel(this)
  }
}
