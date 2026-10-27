package com.aurum.edge

import android.app.Application
import com.aurum.edge.app.AppContainer

class AurumApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
