package com.hermes.control

import android.app.Application
import dev.rikka.shizuku.ShizukuProvider

class HermesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: HermesApp
            private set
    }
}
