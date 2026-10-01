package app.hovanki.client

/**
 * The running build. Shown small on the start screen, so feedback from testers names the exact build.
 * [version] and [buildNumber] come from the platform (Android versionName and versionCode, iOS
 * CFBundleShortVersionString and CFBundleVersion), [commit] from the build (`BuildConstants`). [isEmulator]: the Android
 * emulator or the iOS simulator rather than a real phone. [isPreview]: the field test build
 * (docs/adr/0018-field-test-build.md §1): `BuildConstants.CHANNEL` is `preview` or, on Android, the application id ends
 * with `.preview`.
 */
data class BuildInfo(
    val version: String,
    val buildNumber: String,
    val commit: String,
    val isDebug: Boolean,
    val isEmulator: Boolean = false,
    val isPreview: Boolean = false,
) {
    /** `debug`, `preview` (the field test build on its own server) or `release`; a debug build is `debug` always. */
    val channel: String
        get() = when {
            isDebug -> CHANNEL_DEBUG
            isPreview -> CHANNEL_PREVIEW
            else -> CHANNEL_RELEASE
        }

    /** The field test build: the field log, the consent, «Something is wrong» and the staff's lab exist only here. */
    val isFieldBuild: Boolean get() = channel == CHANNEL_PREVIEW

    /** E.g. "0.1.0 (42) · 1a2b3c4"; debug builds say so, field test builds name the staging server. */
    val label: String
        get() = buildString {
            append("$version ($buildNumber) · $commit")
            if (isDebug) {
                append(" · debug")
            } else if (isPreview) {
                append(" · β staging")
            }
        }

    companion object {
        const val CHANNEL_DEBUG = "debug"
        const val CHANNEL_PREVIEW = "preview"
        const val CHANNEL_RELEASE = "release"
    }
}
