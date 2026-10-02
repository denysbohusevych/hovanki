package app.hovanki.android

import android.app.Application
import app.hovanki.client.di.initKoin
import org.koin.android.ext.koin.androidContext

class HovankiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Crash reports of the field test build (the preview build type with a DSN); nothing in debug and release.
        // First, so that a crash while the app starts is reported too.
        installCrashReporting(this)
        initKoin { androidContext(this@HovankiApplication) }
    }
}
