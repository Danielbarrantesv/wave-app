package com.mp3tify.data.repository

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.mp3tify.backup.BackupUriManager
import com.mp3tify.data.model.Song
import com.mp3tify.playback.PlaybackController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

sealed class DeleteFileResult {
    object Deleted : DeleteFileResult()
    data class NeedsConsent(val intentSender: IntentSender, val song: Song) : DeleteFileResult()
    data class Failed(val reason: String) : DeleteFileResult()
}

sealed class MoveFileResult {
    data class Moved(val newUri: String, val deleteSourceConsent: IntentSender? = null) : MoveFileResult()
    data class Failed(val reason: String) : MoveFileResult()
}

object SongFileOps {
    private fun resolveSafUri(context: Context, path: String): Uri {
        val uri = Uri.parse(path)
        if (uri.authority == "com.android.externalstorage.documents") {
            val p = uri.path ?: ""
            if (p.startsWith("/document/") && !p.contains("/tree/")) {
                val docId = Uri.decode(p.removePrefix("/document/"))
                for (tStr in BackupUriManager.listTreeUris(context)) {
                    val tUri = Uri.parse(tStr)
                    val tp = tUri.path ?: continue
                    if (!tp.startsWith("/tree/")) continue
                    val tDoc = Uri.decode(tp.removePrefix("/tree/"))
                    if (docId == tDoc || docId.startsWith("$tDoc/")) {
                        DocumentsContract.buildDocumentUriUsingTree(tUri, docId)?.let { return it }
                    }
                }
            }
        }
        return uri
    }

    @Suppress("DEPRECATION")
    private fun findMediaStoreUri(context: Context, song: Song): Uri? {
        return try {
            val res = context.contentResolver
            val proj = arrayOf(MediaStore.Audio.Media._ID)
            if (!song.filePath.startsWith("content://")) {
                val raw = song.filePath.removePrefix("file://")
                res.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, "${MediaStore.Audio.Media.DATA}=?", arrayOf(raw), null)?.use { c ->
                    if (c.moveToFirst()) return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                }
            }
            res.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, "${MediaStore.Audio.Media.DISPLAY_NAME}=? AND ${MediaStore.Audio.Media.SIZE}=?", arrayOf(song.fileName, song.fileSize.toString()), null)?.use { c ->
                if (c.moveToFirst()) return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
            }
            null
        } catch (_: Exception) { null }
    }

    private fun deletePhysicalFileOnly(context: Context, song: Song): Pair<Boolean, IntentSender?> {
        if (song.filePath.startsWith("content://")) {
            val uri = resolveSafUri(context, song.filePath)
            try { if (DocumentFile.fromSingleUri(context, uri)?.delete() == true) return true to null } catch (_: Exception) {}
            try { if (DocumentsContract.deleteDocument(context.contentResolver, uri)) return true to null } catch (_: Exception) {}
        } else {
            val f = File(song.filePath.removePrefix("file://"))
            val ok = try { !f.exists() || f.delete() } catch (_: Exception) { false }
            if (ok) { try { findMediaStoreUri(context, song)?.let { context.contentResolver.delete(it, null, null) } } catch (_: Exception) {}; return true to null }
        }
        val mUri = findMediaStoreUri(context, song) ?: return false to null
        try { if (context.contentResolver.delete(mUri, null, null) > 0) return true to null } catch (se: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && se is RecoverableSecurityException) return false to se.userAction.actionIntent.intentSender
        } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try { return false to MediaStore.createDeleteRequest(context.contentResolver, listOf(mUri)).intentSender } catch (_: Exception) {}
        }
        return false to null
    }

    suspend fun finishPermanentDelete(context: Context, song: Song) = withContext(Dispatchers.IO) {
        val repo = Repository.get(context); repo.deleteSongById(song.id)
        val art = song.artUri
        if (!art.isNullOrBlank() && !art.startsWith("content://") && !art.startsWith("file://") && art.contains("/art/")) {
            try { if (repo.allSongs().none { it.id != song.id && it.artUri == art }) File(art).delete() } catch (_: Exception) {}
        }
        withContext(Dispatchers.Main) { PlaybackController.removeSongFromQueue(song.id) }
    }

    suspend fun deleteSongPermanently(context: Context, song: Song): DeleteFileResult = withContext(Dispatchers.IO) {
        val (deleted, consent) = deletePhysicalFileOnly(context, song)
        when {
            deleted -> { finishPermanentDelete(context, song); DeleteFileResult.Deleted }
            consent != null -> DeleteFileResult.NeedsConsent(consent, song)
            else -> DeleteFileResult.Failed("No permission")
        }
    }

    private fun uniqueName(dir: DocumentFile, desired: String): String {
        if (dir.findFile(desired) == null) return desired
        val dot = desired.lastIndexOf('.')
        val base = if (dot > 0) desired.substring(0, dot) else desired
        val ext = if (dot > 0) desired.substring(dot) else ""
        var i = 2
        while (dir.findFile("$base ($i)$ext") != null) i++
        return "$base ($i)$ext"
    }

    suspend fun moveSongToFolder(context: Context, song: Song, destTreeUri: Uri): MoveFileResult = withContext(Dispatchers.IO) {
        try {
            BackupUriManager.saveTreeUri(context, destTreeUri)
            val destTree = DocumentFile.fromTreeUri(context, destTreeUri) ?: return@withContext MoveFileResult.Failed("Invalid folder")
            val srcUri = if (song.filePath.startsWith("content://")) resolveSafUri(context, song.filePath) else null
            if (srcUri != null && destTree.findFile(song.fileName)?.uri == srcUri) return@withContext MoveFileResult.Moved(srcUri.toString())
            val targetName = uniqueName(destTree, song.fileName)
            val parentDoc = DocumentsContract.buildDocumentUriUsingTree(destTreeUri, DocumentsContract.getTreeDocumentId(destTreeUri)) ?: return@withContext MoveFileResult.Failed("Invalid folder")
            val mime = song.mimeType?.takeIf { it.isNotBlank() } ?: "audio/mpeg"
            val created = DocumentsContract.createDocument(context.contentResolver, parentDoc, mime, targetName) ?: return@withContext MoveFileResult.Failed("Create failed")
            var copied = 0L
            try {
                val inp = if (srcUri != null) context.contentResolver.openInputStream(srcUri) else File(song.filePath.removePrefix("file://")).inputStream()
                val out = context.contentResolver.openOutputStream(created)
                if (inp == null || out == null) throw IllegalStateException("Stream null")
                inp.use { i -> out.use { o -> copied = i.copyTo(o, 32768); o.flush() } }
                if (copied <= 0L) throw IllegalStateException("Empty copy")
            } catch (e: Exception) {
                try { DocumentsContract.deleteDocument(context.contentResolver, created) } catch (_: Exception) {}
                return@withContext MoveFileResult.Failed(e.message ?: "Copy failed")
            }
            Repository.get(context).updateMovedSong(song.id, created.toString(), targetName)
            withContext(Dispatchers.Main) { PlaybackController.updateSongInQueue(song.id, created.toString(), targetName) }
            val (delOk, consent) = deletePhysicalFileOnly(context, song)
            MoveFileResult.Moved(created.toString(), if (delOk) null else consent)
        } catch (e: Exception) { e.printStackTrace(); MoveFileResult.Failed(e.message ?: "Move failed") }
    }
}
