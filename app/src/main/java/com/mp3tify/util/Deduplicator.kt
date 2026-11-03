package com.mp3tify.util

import com.mp3tify.data.model.Song

/**
 * 3-level audio deduplication:
 *  1. fileName + fileSize
 *  2. title + artist + durationBucket (2s buckets)
 *  3. content fingerprint (SHA-1 head+tail via Fingerprinter)
 */
object Deduplicator {

    data class Key(
        val nameSize: String,
        val meta: String,
        val fingerprint: String?
    )

    fun keyOf(song: Song): Key = Key(
        nameSize = "${song.fileName.lowercase()}|${song.fileSize}",
        meta = "${(song.title ?: "").lowercase()}|${(song.artist ?: "").lowercase()}|${song.durationMs / 2000}",
        fingerprint = song.fingerprint
    )

    /** Returns true if [song] duplicates anything already present in [known]. */
    fun isDuplicate(song: Song, known: Collection<Key>): Boolean {
        val k = keyOf(song)
        // Level 1: fileName + size
        if (known.any { it.nameSize == k.nameSize }) {
            return true
        }
        // Level 2: title + artist + durationBucket(2s) — skip empty metadata triples
        if (k.meta != "||" && known.any { it.meta == k.meta }) {
            return true
        }
        // Level 3: fingerprint
        val fp = k.fingerprint
        if (fp != null && known.any { it.fingerprint == fp }) {
            return true
        }
        return false
    }

    fun keysOf(songs: List<Song>): List<Key> = songs.map { keyOf(it) }
}
