package com.dschat.app

import android.app.Application
import com.dschat.app.data.SettingsStore

class DeepSeekApp : Application() {
    lateinit var settings: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsStore(this)
    }

    companion object {
        lateinit var instance: DeepSeekApp
            private set
    }
}