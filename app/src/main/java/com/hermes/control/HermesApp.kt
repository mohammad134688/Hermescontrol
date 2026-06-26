package com.hermes.control

import android.app.Application

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
