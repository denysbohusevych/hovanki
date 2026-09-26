package app.hovanki.client

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat

/** 10.0.2.2 is the host machine as seen from the Android emulator. */
actual fun developmentServerUrl(): String = "http://10.0.2.2:8080"

/** Version name and code of the installed app (set by :androidApp, CI passes the run number). */
internal fun androidBuildInfo(context: Context): BuildInfo {
    val packageInfo = context.packageManager.packageInfo(context.packageName)
    return BuildInfo(
        version = packageInfo.versionName.orEmpty(),
        buildNumber = PackageInfoCompat.getLongVersionCode(packageInfo).toString(),
        commit = BuildConstants.COMMIT,
        isDebug = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
    )
}

private fun PackageManager.packageInfo(packageName: String): PackageInfo =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        getPackageInfo(packageName, 0)
    }
