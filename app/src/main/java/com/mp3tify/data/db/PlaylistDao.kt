package com.mp3tify.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.mp3tify.data.model.Playlist
import com.mp3tify.data.model.PlaylistSong
import com.mp3tify.data.model.Song
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<Playlist>>

    @Query("SELECT * FROM playlists")
    suspend fun getAll(): List<Playlist>

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Playlist?

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    fun observeById(id: Long): Flow<Playlist?>

    @Query("SELECT * FROM playlists WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): Playlist?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(playlist: Playlist): Long

    @Update
    suspend fun update(playlist: Playlist)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Transaction
    @Query(
        "SELECT songs.* FROM playlist_songs " +
            "INNER JOIN songs ON songs.id = playlist_songs.songId " +
            "WHERE playlist_songs.playlistId = :playlistId " +
            "ORDER BY playlist_songs.position ASC"
    )
    fun observeSongsInPlaylist(playlistId: Long): Flow<List<Song>>

    @Query("SELECT * FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun getEntries(playlistId: Long): List<PlaylistSong>

    @Transaction
    suspend fun addSongsToPlaylist(playlistId: Long, songIds: List<Long>) {
        val existing = getEntries(playlistId)
        val existingIds = existing.map { it.songId }.toHashSet()
        var maxPos = existing.maxOfOrNull { it.position } ?: -1
        songIds.forEach { songId ->
            if (!existingIds.contains(songId)) {
                maxPos += 1
                insertEntry(PlaylistSong(playlistId = playlistId, songId = songId, position = maxPos))
            }
        }
        touch(playlistId, System.currentTimeMillis())
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntry(entry: PlaylistSong)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSong(playlistId: Long, songId: Long)

    @Query("UPDATE playlists SET updatedAt = :ts WHERE id = :id")
    suspend fun touch(id: Long, ts: Long)

    @Query("DELETE FROM playlist_songs")
    suspend fun deleteAllEntries()

    @Query("DELETE FROM playlists WHERE name != :keepName")
    suspend fun deleteAllExcept(keepName: String)

    // CRITICAL (v2.5.13 UI): retorna los IDs de playlists que contienen la canción
    // dada. Usado por LibraryScreen para marcar visualmente (borde + ▷) las
    // playlists que están sonando actualmente.
    @Query("SELECT DISTINCT playlistId FROM playlist_songs WHERE songId = :songId")
    suspend fun playlistIdsForSong(songId: Long): List<Long>
}
