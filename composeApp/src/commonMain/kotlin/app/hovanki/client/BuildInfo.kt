package app.hovanki.client

/**
 * The running build. Shown small on the start screen, so feedback from testers names the exact build.
 * [version] and [buildNumber] come from the platform (Android versionName and versionCode, iOS
 * CFBundleShortVersionString and CFBundleVersion), [commit] from the build (`BuildConstants`).
 */
data class BuildInfo(val version: String, val buildNumber: String, val commit: String, val isDebug: Boolean) {
    /** E.g. "0.1.0 (42) · 1a2b3c4"; debug builds say so. */
    val label: String
        get() = buildString {
            append("$version ($buildNumber) · $commit")
            if (isDebug) append(" · debug")
        }
}
