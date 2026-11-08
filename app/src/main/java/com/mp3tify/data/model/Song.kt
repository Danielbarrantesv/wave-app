package com.mp3tify.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Room entity — DB schema v4. */
@Entity(
    tableName = "songs",
    indices = [
        Index(value = ["filePath"], unique = true),
        Index(value = ["fingerprint"]),
        Index(value = ["title", "artist"]),
        Index(value = ["isFavorite"]),
        Index(value = ["lastPlayedAt"])
    ]
)
data class Song(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filePath: String,
    val fileName: String,
    val fileSize: Long = 0,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0,
    val trackNo: Int? = null,
    val year: Int? = null,
    val mimeType: String? = null,
    val dateAddedSec: Long = 0,
    val fingerprint: String? = null,
    val artUri: String? = null,
    val lastModified: Long = 0,
    val isFavorite: Boolean = false,
    val lastPlayedAt: Long = 0,
    // CRITICAL (v2.5.24): género extraído de los metadatos del archivo
    // (MediaMetadataRetriever.METADATA_KEY_GENRE). Se usa para crear playlists
    // automáticas por género en Repository.createGenrePlaylists().
    val genre: String? = null
)

/** Fallbacks so the UI never renders blank rows. */
val Song.displayTitle: String
    get() = title?.takeIf { it.isNotBlank() } ?: fileName

val Song.displayArtist: String
    get() = artist?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Artista desconocido"

val Song.displayAlbum: String
    get() = album?.takeIf { it.isNotBlank() } ?: "Álbum desconocido"
