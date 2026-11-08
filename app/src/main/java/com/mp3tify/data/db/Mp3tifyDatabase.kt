package com.mp3tify.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mp3tify.data.model.Playlist
import com.mp3tify.data.model.PlaylistSong
import com.mp3tify.data.model.Song

@Database(
    entities = [Song::class, Playlist::class, PlaylistSong::class],
    version = 5,  // CRITICAL (v2.5.24): v4 → v5, agregar columna genre a songs
    exportSchema = false
)
abstract class Mp3tifyDatabase : RoomDatabase() {

    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        private const val DB_NAME = "mp3tify.db"

        @Volatile
        private var instance: Mp3tifyDatabase? = null

        // CRITICAL (v2.5.24): migración v4 → v5 — agregar columna genre a songs.
        // Preserva las canciones y playlists existentes (no borra nada).
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE songs ADD COLUMN genre TEXT")
            }
        }

        /** Never throws: build → delete-and-retry → in-memory fallback. */
        fun get(context: Context): Mp3tifyDatabase {
            val appCtx = context.applicationContext
            return instance ?: synchronized(this) {
                instance ?: build(appCtx).also { instance = it }
            }
        }

        private fun build(context: Context): Mp3tifyDatabase {
            return try {
                Room.databaseBuilder(context, Mp3tifyDatabase::class.java, DB_NAME)
                    .addMigrations(MIGRATION_4_5)  // CRITICAL (v2.5.24): migrar v4 → v5 preservando datos
                    .fallbackToDestructiveMigration()  // safety net: si la migración falla, borra y recrea
                    .build()
            } catch (e: Exception) {
                e.printStackTrace()
                try {
                    // Delete the corrupt DB file and retry once.
                    deleteDbFiles(context)
                    Room.databaseBuilder(context, Mp3tifyDatabase::class.java, DB_NAME)
                        .addMigrations(MIGRATION_4_5)
                        .fallbackToDestructiveMigration()
                        .build()
                } catch (e2: Exception) {
                    e2.printStackTrace()
                    // Last resort: keep the app usable with an in-memory database.
                    Room.inMemoryDatabaseBuilder(context, Mp3tifyDatabase::class.java).build()
                }
            }
        }

        private fun deleteDbFiles(context: Context) {
            try {
                context.getDatabasePath(DB_NAME).delete()
                context.getDatabasePath("$DB_NAME-shm").delete()
                context.getDatabasePath("$DB_NAME-wal").delete()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        /** Wipes the DB files and forgets the cached instance (one-shot reset). */
        fun reset(context: Context) {
            synchronized(this) {
                try {
                    instance?.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                instance = null
                deleteDbFiles(context.applicationContext)
            }
        }
    }
}
