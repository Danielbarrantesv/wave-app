package com.mp3tify.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mp3tify.data.model.Song
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {

    @Query("SELECT * FROM songs ORDER BY dateAddedSec DESC")
    fun observeAll(): Flow<List<Song>>

    @Query("SELECT * FROM songs")
    suspend fun listAll(): List<Song>

    @Query("SELECT * FROM songs WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Song?

    @Query("SELECT * FROM songs WHERE filePath = :path LIMIT 1")
    suspend fun getByPath(path: String): Song?

    @Query(
        "SELECT * FROM songs WHERE title LIKE '%' || :q || '%' " +
            "OR artist LIKE '%' || :q || '%' " +
            "OR album LIKE '%' || :q || '%' " +
            "OR fileName LIKE '%' || :q || '%' " +
            "ORDER BY title COLLATE NOCASE ASC"
    )
    fun search(q: String): Flow<List<Song>>

    @Query("SELECT * FROM songs ORDER BY dateAddedSec DESC")
    fun observeByRecentlyAdded(): Flow<List<Song>>

    @Query(
        "SELECT * FROM songs ORDER BY CASE WHEN lastPlayedAt > 0 THEN lastPlayedAt ELSE 0 END DESC, dateAddedSec DESC"
    )
    fun observeByRecentlyPlayed(): Flow<List<Song>>

    @Query("SELECT * FROM songs ORDER BY title COLLATE NOCASE ASC")
    fun observeByTitle(): Flow<List<Song>>

    @Query("SELECT * FROM songs ORDER BY artist COLLATE NOCASE ASC, title COLLATE NOCASE ASC")
    fun observeByArtist(): Flow<List<Song>>

    @Query("SELECT * FROM songs ORDER BY durationMs DESC")
    fun observeByDuration(): Flow<List<Song>>

    @Query("SELECT * FROM songs WHERE isFavorite = 1 ORDER BY lastPlayedAt DESC, dateAddedSec DESC")
    fun observeFavorites(): Flow<List<Song>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(song: Song): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(songs: List<Song>): List<Long>

    @Update
    suspend fun update(song: Song)

    @Query("DELETE FROM songs WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM songs")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM songs")
    suspend fun count(): Int

    @Query("SELECT * FROM songs ORDER BY RANDOM() LIMIT :limit")
    suspend fun random(limit: Int): List<Song>

    @Query("UPDATE songs SET isFavorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean)

    @Query("UPDATE songs SET lastPlayedAt = :ts WHERE id = :id")
    suspend fun setLastPlayedAt(id: Long, ts: Long)

    @Query("UPDATE songs SET title = :title, artist = :artist, album = :album WHERE id = :id")
    suspend fun updateInfo(id: Long, title: String?, artist: String?, album: String?)

    @Query("UPDATE songs SET filePath = :filePath, artUri = :artUri WHERE id = :id")
    suspend fun updateSongPaths(id: Long, filePath: String, artUri: String?)
}
