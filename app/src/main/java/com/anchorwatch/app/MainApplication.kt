package com.anchorwatch.app

import android.app.Application
import com.anchorwatch.app.service.AnchorLogger

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AnchorLogger.init(this)
    }
}
