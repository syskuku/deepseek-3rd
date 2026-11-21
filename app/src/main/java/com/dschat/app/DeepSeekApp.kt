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
        com.dschat.app.util.AppLog.init(this)
        // 记录未捕获异常（崩溃现场），再交还系统默认处理
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                com.dschat.app.util.AppLog.log("crash", "UNCAUGHT ${t.name}: ${e.javaClass.name}: ${e.message}\n" + android.util.Log.getStackTraceString(e))
            }
            default?.uncaughtException(t, e)
        }
    }

    companion object {
        lateinit var instance: DeepSeekApp
            private set
    }
}