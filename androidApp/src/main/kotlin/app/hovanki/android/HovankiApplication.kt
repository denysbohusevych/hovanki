package app.hovanki.android

import android.app.Application
import app.hovanki.client.di.initKoin
import org.koin.android.ext.koin.androidContext

class HovankiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin { androidContext(this@HovankiApplication) }
    }
}
