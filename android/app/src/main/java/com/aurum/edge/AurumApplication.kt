package com.aurum.edge

import android.app.Application
import com.aurum.edge.app.AppContainer
import com.aurum.edge.core.CrashReporter

class AurumApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // پیش از ساخت AppContainer نصب می‌شود تا اگر خودِ مقداردهی اولیه هم خطا داد، ثبت شود.
        CrashReporter.install(this)
        CrashReporter.breadcrumb("app:init-container")
        container = AppContainer(this)
        CrashReporter.breadcrumb("app:ready")
    }
}
