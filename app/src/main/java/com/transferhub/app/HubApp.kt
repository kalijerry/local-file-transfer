package com.transferhub.app

import android.app.Application
import com.transferhub.app.data.HubRepository
import com.transferhub.app.data.Settings

class HubApp : Application() {
    override fun onCreate() {
        super.onCreate()
        HubRepository.init(this)
        Settings.init(this)
    }
}
