package com.antidetect.browser

import android.app.Application
import com.antidetect.browser.data.AppDatabase
import com.antidetect.browser.data.ProfileRepository

class AntiDetectApp : Application() {

    val database by lazy { AppDatabase.getInstance(this) }
    val repository by lazy { ProfileRepository(database.profileDao()) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Fingerprint config for content-scripts (early-spoof.js fetches 127.0.0.1:17351/cfg)
        try {
            com.antidetect.browser.gecko.ConfigServer.start()
        } catch (_: Exception) {}
    }

    companion object {
        lateinit var instance: AntiDetectApp
            private set
    }
}
