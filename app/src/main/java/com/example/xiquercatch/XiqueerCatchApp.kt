package com.example.xiquercatch

import android.app.Application
import com.example.xiquercatch.data.ServiceLocator
import com.example.xiquercatch.debug.AppLog

class XiqueerCatchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        AppLog.i("XiqueerCatchApp", "Application started")
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            AppLog.e("Crash", "Uncaught exception on thread=${t.name}", e)
            defaultHandler?.uncaughtException(t, e)
        }
    }
}
