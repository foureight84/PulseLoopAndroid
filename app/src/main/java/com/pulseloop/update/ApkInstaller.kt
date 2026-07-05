package com.pulseloop.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a downloaded APK to the system package installer.
 *
 * Uses an ACTION_VIEW install intent with a FileProvider content URI, which reliably shows
 * the standard "install update?" confirmation. Requires REQUEST_INSTALL_PACKAGES plus the
 * user having granted "install unknown apps" for PulseLoop — gate the call on [canInstall]
 * and send the user to settings via [installPermissionIntent] when needed.
 *
 * The APK must be signed with the same key as the installed app, or the system rejects it
 * with INSTALL_FAILED_UPDATE_INCOMPATIBLE (so this only works for the signed release build).
 */
object ApkInstaller {
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** Intent to the per-app "install unknown apps" screen. */
    fun installPermissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
