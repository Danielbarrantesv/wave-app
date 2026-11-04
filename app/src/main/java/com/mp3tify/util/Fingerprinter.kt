package com.mp3tify.util

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * SHA-1 fingerprint computed from the first and last 64KB of an audio file.
 * Fast (reads at most 128KB) and precise enough for dedup purposes.
 */
object Fingerprinter {

    private const val CHUNK = 64 * 1024

    fun fromStream(stream: InputStream, size: Long): String? {
        return try {
            val md = MessageDigest.getInstance("SHA-1")

            // Head chunk
            val head = ByteArray(CHUNK)
            var headRead = 0
            while (headRead < CHUNK) {
                val r = stream.read(head, headRead, CHUNK - headRead)
                if (r < 0) break
                headRead += r
            }
            md.update(head, 0, headRead)

            // Skip to the tail if the file is larger than two chunks
            if (size > CHUNK * 2L) {
                val toSkip = size - CHUNK
                var skipped = 0L
                var guard = 0
                while (skipped < toSkip && guard < 4096) {
                    val s = stream.skip(toSkip - skipped)
                    if (s <= 0) {
                        if (stream.read() < 0) break
                        skipped += 1
                    } else {
                        skipped += s
                    }
                    guard++
                }
            }

            // Tail chunk
            val tail = ByteArray(CHUNK)
            var tailRead = 0
            while (tailRead < CHUNK) {
                val r = stream.read(tail, tailRead, CHUNK - tailRead)
                if (r < 0) break
                tailRead += r
            }
            if (tailRead > 0) {
                md.update(tail, 0, tailRead)
            }

            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    fun fromFile(path: String): String? {
        return try {
            val file = File(path)
            file.inputStream().use { fromStream(it, file.length()) }
        } catch (e: Exception) {
            null
        }
    }
}
