package app.hovanki.radar

import android.Manifest
import android.os.Build

/**
 * Android 12+: scanning («never for location»: the manifest says so) and advertising; connecting for the name. Empty
 * before Android 12, where Bluetooth LE needs only the location permission. The radio checks them; the app asks for
 * them (`rememberBluetoothPermissionRequester` in `:composeApp`).
 */
val RADAR_PERMISSIONS: Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
    )
} else {
    emptyArray()
}
