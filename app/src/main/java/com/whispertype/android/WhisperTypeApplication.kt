package com.whispertype.android

import android.app.Application
import android.content.Context

/**
 * Application root. Wiring is established incrementally by the integration
 * workstream; see the integration commit for the full AppContainer.
 */
class WhisperTypeApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        @Volatile
        lateinit var instance: WhisperTypeApplication

        fun appContext(): Context = instance.applicationContext
    }
}