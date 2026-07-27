package com.minsu.guardapp.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands a downloaded APK to the system package installer.
 *
 * The app never installs anything itself. It asks, and Android shows its own confirmation — twice
 * on a fresh device, because installing from outside a store is off by default and has to be
 * turned on for this app first.
 */
@Singleton
class ApkInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Whether Android will let the app even raise an install prompt.
     *
     * Always true below API 26, where sideloading was a single device-wide setting rather than a
     * per-app grant — there is nothing for the app to request there.
     */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Opens the system screen where the guard grants this app permission to install. */
    fun requestInstallPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * Launches the install.
     *
     * The APK lives in the app's own cache, which the installer cannot read, so it goes across as
     * a FileProvider URI with a read grant attached — the same mechanism the exported report PDFs
     * use. Handing over a `file://` path instead throws FileUriExposedException on anything
     * modern.
     */
    fun install(apk: File): Boolean {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
