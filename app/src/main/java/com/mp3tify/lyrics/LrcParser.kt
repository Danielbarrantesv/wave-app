package com.mp3tify.lyrics

data class LrcLine(val timeMs: Long, val text: String)

data class ParsedLyrics(
    val lines: List<LrcLine>,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null
)

/** .lrc parser: [mm:ss.xx] timestamps (multi-timestamp per line) + id tags. */
object LrcParser {

    private val timeRegex = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]")

    fun parse(raw: String): ParsedLyrics? {
        return try {
            var title: String? = null
            var artist: String? = null
            var album: String? = null
            val entries = mutableListOf<LrcLine>()

            raw.lines().forEach { lineRaw ->
                val line = lineRaw.trim()
                if (line.isEmpty()) return@forEach
                val lower = line.lowercase()
                if (lower.startsWith("[ti:")) {
                    title = line.substringAfter(':').removeSuffix("]").trim()
                    return@forEach
                }
                if (lower.startsWith("[ar:")) {
                    artist = line.substringAfter(':').removeSuffix("]").trim()
                    return@forEach
                }
                if (lower.startsWith("[al:")) {
                    album = line.substringAfter(':').removeSuffix("]").trim()
                    return@forEach
                }
                val matches = timeRegex.findAll(line).toList()
                if (matches.isEmpty()) return@forEach
                val text = line.substring(matches.last().range.last + 1).trim()
                matches.forEach { m ->
                    val minutes = m.groupValues[1].toLongOrNull() ?: 0L
                    val seconds = m.groupValues[2].toLongOrNull() ?: 0L
                    val fracStr = m.groupValues[3]
                    val fracMs = when (fracStr.length) {
                        0 -> 0L
                        1 -> (fracStr.toLongOrNull() ?: 0L) * 100
                        2 -> (fracStr.toLongOrNull() ?: 0L) * 10
                        else -> fracStr.take(3).toLongOrNull() ?: 0L
                    }
                    entries.add(LrcLine(minutes * 60_000 + seconds * 1000 + fracMs, text))
                }
            }

            if (entries.isEmpty()) {
                null
            } else {
                ParsedLyrics(entries.sortedBy { it.timeMs }, title, artist, album)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Binary search: index of the line active at [positionMs] (-1 if before the first). */
    fun activeLineIndex(lyrics: ParsedLyrics, positionMs: Long): Int {
        var lo = 0
        var hi = lyrics.lines.size - 1
        var result = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lyrics.lines[mid].timeMs <= positionMs) {
                result = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return result
    }
}
