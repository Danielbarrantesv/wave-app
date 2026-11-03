package com.mp3tify.data.scanner

import android.content.Context
import android.provider.MediaStore
import com.mp3tify.data.model.Song
import com.mp3tify.util.Fingerprinter
import java.io.File

/** MediaStore.Audio.Media scan with IS_MUSIC = 1. */
object AudioScanner {

    fun scan(context: Context): List<Song> {
        val songs = mutableListOf<Song>()
        try {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.YEAR,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DATE_MODIFIED,
                MediaStore.Audio.Media.ALBUM_ID
            )
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val iId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iTitle = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val iArtist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val iAlbum = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val iDuration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val iTrack = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                val iYear = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                val iData = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val iName = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val iSize = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val iMime = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val iAdded = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val iModified = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                val iAlbumId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                while (cursor.moveToNext()) {
                    try {
                        val duration = cursor.getLong(iDuration)
                        if (duration <= 0) continue
                        val data = cursor.getString(iData) ?: continue
                        val song = Song(
                            filePath = data,
                            fileName = cursor.getString(iName) ?: data.substringAfterLast('/'),
                            fileSize = cursor.getLong(iSize),
                            title = cursor.getString(iTitle),
                            artist = cursor.getString(iArtist),
                            album = cursor.getString(iAlbum),
                            durationMs = duration,
                            trackNo = cursor.getInt(iTrack),
                            year = if (cursor.isNull(iYear)) null else cursor.getInt(iYear),
                            mimeType = cursor.getString(iMime),
                            dateAddedSec = cursor.getLong(iAdded),
                            // Album art via MediaStore album art URI.
                            artUri = "content://media/external/audio/albumart/${cursor.getLong(iAlbumId)}",
                            lastModified = cursor.getLong(iModified) * 1000L
                        )
                        songs.add(song)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return songs
    }

    /** SHA-1 fingerprint of a scanned song file (head+tail 64KB via Fingerprinter). */
    fun fingerprint(song: Song): String? {
        return try {
            val file = File(song.filePath)
            file.inputStream().use { Fingerprinter.fromStream(it, file.length()) }
        } catch (e: Exception) {
            null
        }
    }
}
