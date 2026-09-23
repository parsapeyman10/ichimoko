package com.aurum.edge

import android.app.Application
import com.aurum.edge.core.AppContainer
import com.aurum.edge.notify.Notifier

class AurumApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifier.ensureChannels(this)
    }
}
