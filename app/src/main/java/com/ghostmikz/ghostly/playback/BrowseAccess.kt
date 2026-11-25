@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.ghostmikz.ghostly.playback

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.media3.session.MediaSession

/**
 * Who may browse the library (songs, playlists, recent and most played) through
 * the player service. Any app may connect for play, pause and skip, as every
 * music player allows; only Ghostly itself, the system (lock screen, Bluetooth,
 * the media card that resumes playback) and Android Auto may look through what
 * is on the phone. Otherwise an app without the music permission could read the
 * library through Ghostly.
 */
internal object BrowseAccess {
    /** Car apps that browse the library, known by name; see [isGenuine] for why the name alone isn't enough. */
    private val CAR_APPS = setOf("com.google.android.projection.gearhead")

    private const val PLAY_STORE = "com.android.vending"

    fun mayBrowse(context: Context, controller: MediaSession.ControllerInfo): Boolean = decide(
        ownPackage = context.packageName,
        packageName = controller.packageName,
        trusted = controller.isTrusted,
        // Older Android doesn't always say who a controller is: the app's user id
        // ([MediaSession.ControllerInfo.getUid]) can still confirm the name.
        nameVerified = controller.isPackageNameVerified || ownsUid(context, controller.packageName, controller.uid),
        systemApp = { isSystemApp(context, it) },
        genuine = { isGenuine(context, it) },
    )

    /**
     * [mayBrowse] without Android, for the tests: the app itself, anything Android
     * trusts with media, apps that came with the phone (Bluetooth, a car's own
     * system), and Android Auto from the Play Store.
     */
    internal fun decide(
        ownPackage: String,
        packageName: String,
        trusted: Boolean,
        nameVerified: Boolean,
        systemApp: (String) -> Boolean,
        genuine: (String) -> Boolean,
    ): Boolean = when {
        trusted -> true
        !nameVerified -> false
        packageName == ownPackage -> true
        systemApp(packageName) -> true
        packageName in CAR_APPS -> genuine(packageName)
        else -> false
    }

    private fun ownsUid(context: Context, packageName: String, uid: Int): Boolean =
        uid >= 0 && runCatching { context.packageManager.getPackagesForUid(uid)?.contains(packageName) == true }.getOrDefault(false)

    private fun isSystemApp(context: Context, packageName: String): Boolean = runCatching {
        val flags = context.packageManager.getApplicationInfo(packageName, 0).flags
        flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }.getOrDefault(false)

    /**
     * A package name can be taken by any app that is installed first, so a car app
     * that didn't come with the phone counts only when it came from the Play Store.
     */
    private fun isGenuine(context: Context, packageName: String): Boolean = runCatching {
        val pm = context.packageManager
        val installer = if (Build.VERSION.SDK_INT >= 30) {
            pm.getInstallSourceInfo(packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(packageName)
        }
        installer == PLAY_STORE
    }.getOrDefault(false)
}
