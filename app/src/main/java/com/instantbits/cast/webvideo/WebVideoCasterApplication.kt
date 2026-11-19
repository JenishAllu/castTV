package com.instantbits.cast.webvideo

import android.app.Application
import com.instantbits.cast.webvideo.cast.CastOptionsProvider
import timber.log.Timber

class WebVideoCasterApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize Timber for logging (no crash reporters, no analytics)
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }
}