package com.mp3tify.cloud

import android.content.Context
import android.net.Uri
import com.mp3tify.backup.BackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cloud-style backup through SAF documents (CreateDocument / OpenDocument pickers).
 * The "cloud" destination is any document uri the user picks (e.g. Drive, Downloads…).
 */
object CloudBackupManager {

    /** Writes the full backup JSON to the picked document uri. */
    suspend fun uploadTo(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            BackupManager.writeBackupToUri(context, uri)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /** Reads a backup JSON from the picked document uri and restores it. */
    suspend fun downloadAndRestore(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val backup = BackupManager.readFromFile(context, uri.toString())
                ?: return@withContext false
            BackupManager.restore(context, backup)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
