package com.mp3tify.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "playlists",
    indices = [Index(value = ["name"], unique = true)]
)
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val coverUri: String? = null,
    val folderUri: String? = null
)
