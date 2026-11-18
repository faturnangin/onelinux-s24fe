package dev.omalauncher.storage

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * The phone's shared storage (Download, Documents, Pictures, …) for Linux, bound at [GUEST], with no permission:
 * Android shows the launcher, and so Linux, only the files it made there itself, and lets it make them only where
 * they belong (any file in Download and Documents, pictures in Pictures and DCIM, …). With [hasAllFilesAccess]
 * (`MANAGE_EXTERNAL_STORAGE`), Linux sees and edits all shared storage files seamlessly like DroidDeck.
 */
object PhoneStorage {
    /** Where Linux sees it, as Termux and Android's own shell do: a guest path, not Android's. */
    @SuppressLint("SdCardPath")
    const val GUEST = "/sdcard"

    /** Convenient home-directory paths bound to the phone's shared storage. */
    const val GUEST_ANDROID = "/root/Android"
    const val GUEST_DOWNLOADS = "/root/Downloads"
    const val GUEST_DOCUMENTS = "/root/Documents"

    fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** Whether the user granted `MANAGE_EXTERNAL_STORAGE` (All Files Access) on Android 11+. */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    /** Opens Android's All Files Access toggle for this app, falling back to the general list. */
    fun manageAllFilesIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** The storage itself, /storage/emulated/0 for the phone's owner. */
    val root: File get() = Environment.getExternalStorageDirectory()
}
