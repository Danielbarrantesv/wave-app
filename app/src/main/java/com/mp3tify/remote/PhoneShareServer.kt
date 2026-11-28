package com.mp3tify.remote

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.OpenableColumns
import com.google.gson.Gson
import com.mp3tify.data.model.Song
import com.mp3tify.data.repository.Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

data class PhoneShareState(
    val active: Boolean = false,
    val code: String = "",
    val port: Int = 45454,
    val localIp: String = "",
    val deviceName: String = Build.MODEL ?: "Android",
    val error: String? = null
)

object PhoneShareServer {
    const val DEFAULT_HTTP_PORT = 45454
    const val DISCOVERY_UDP_PORT = 45455

    private val gson = Gson()
    private val random = SecureRandom()
    private val _state = MutableStateFlow(PhoneShareState())
    val state: StateFlow<PhoneShareState> = _state.asStateFlow()

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var udpSocket: DatagramSocket? = null
    @Volatile private var wifiLock: WifiManager.WifiLock? = null
    private var scope: CoroutineScope? = null
    private var httpJob: Job? = null
    private var udpJob: Job? = null

    @Volatile private var failedAttempts = 0
    @Volatile private var blockedUntilMs = 0L

    fun start(context: Context) {
        val ctx = context.applicationContext
        if (_state.value.active && serverSocket != null && serverSocket?.isClosed == false) {
            val ip = getLocalIpv4Address()
            _state.value = _state.value.copy(localIp = ip, error = null)
            return
        }
        stop()

        val code = generateCode()
        val ip = getLocalIpv4Address()
        val device = listOfNotNull(
            Build.MANUFACTURER?.takeIf { it.isNotBlank() },
            Build.MODEL?.takeIf { it.isNotBlank() }
        ).joinToString(" ").ifBlank { "Wave Android" }

        try {
            val ss = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(DEFAULT_HTTP_PORT))
                }
            } catch (_: Exception) {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(0))
                }
            }
            serverSocket = ss
            val boundPort = ss.localPort

            val ds = try {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(DISCOVERY_UDP_PORT))
                }
            } catch (_: Exception) {
                null
            }
            udpSocket = ds

            try {
                val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Wave:PhoneShare")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (_: Exception) {}

            val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope = newScope
            _state.value = PhoneShareState(
                active = true,
                code = code,
                port = boundPort,
                localIp = ip,
                deviceName = device,
                error = null
            )

            httpJob = newScope.launch {
                while (isActive && serverSocket?.isClosed == false) {
                    try {
                        val client = ss.accept()
                        launch { handleHttpClient(client, ctx) }
                    } catch (_: Exception) {
                        if (serverSocket?.isClosed == true) break
                    }
                }
            }

            if (ds != null) {
                udpJob = newScope.launch {
                    val buf = ByteArray(1024)
                    while (isActive && udpSocket?.isClosed == false) {
                        try {
                            val packet = DatagramPacket(buf, buf.size)
                            ds.receive(packet)
                            val msg = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                            if (msg.startsWith("WAVE_DISCOVER_V1|")) {
                                val candidate = msg.substringAfter("WAVE_DISCOVER_V1|").trim()
                                val current = _state.value
                                if (current.active && isCodeValid(candidate, current.code)) {
                                    val reply = "WAVE_HERE_V1|${current.port}|${current.deviceName}".toByteArray(Charsets.UTF_8)
                                    val out = DatagramPacket(reply, reply.size, packet.address, packet.port)
                                    ds.send(out)
                                }
                            }
                        } catch (_: Exception) {
                            if (udpSocket?.isClosed == true) break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            stop()
            _state.value = PhoneShareState(active = false, error = e.message ?: "Error")
        }
    }

    fun regenerateCode(context: Context) {
        if (!_state.value.active) {
            start(context)
            return
        }
        failedAttempts = 0
        blockedUntilMs = 0L
        _state.value = _state.value.copy(
            code = generateCode(),
            localIp = getLocalIpv4Address(),
            error = null
        )
    }

    fun stop() {
        try { httpJob?.cancel() } catch (_: Exception) {}
        try { udpJob?.cancel() } catch (_: Exception) {}
        httpJob = null
        udpJob = null
        try { serverSocket?.close() } catch (_: Exception) {}
        try { udpSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        udpSocket = null
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
        wifiLock = null
        scope = null
        failedAttempts = 0
        blockedUntilMs = 0L
        _state.value = PhoneShareState(active = false)
    }

    private fun generateCode(): String = (100000 + random.nextInt(900000)).toString()

    private fun isCodeValid(candidate: String?, expected: String): Boolean {
        if (candidate.isNullOrBlank() || expected.isBlank()) return false
        val now = System.currentTimeMillis()
        if (now < blockedUntilMs) return false
        val ok = MessageDigest.isEqual(
            candidate.trim().toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8)
        )
        if (ok) {
            failedAttempts = 0
        } else {
            failedAttempts++
            if (failedAttempts >= 8) {
                blockedUntilMs = now + 10_000L
                failedAttempts = 0
            }
        }
        return ok
    }

    private fun handleHttpClient(socket: Socket, context: Context) {
        socket.use { sock ->
            try {
                sock.soTimeout = 30_000
                val rawIn = BufferedInputStream(sock.getInputStream())
                val out = BufferedOutputStream(sock.getOutputStream())

                val requestLine = readLineAscii(rawIn) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) {
                    sendJson(out, 400, "Bad Request", mapOf("error" to "bad_request"))
                    return
                }
                val method = parts[0].uppercase(Locale.US)
                val target = parts[1]

                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLineAscii(rawIn) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        headers[line.substring(0, idx).trim().lowercase(Locale.US)] = line.substring(idx + 1).trim()
                    }
                }

                val qIdx = target.indexOf('?')
                val path = if (qIdx >= 0) target.substring(0, qIdx) else target
                val query = if (qIdx >= 0) parseQuery(target.substring(qIdx + 1)) else emptyMap()

                if (path == "/ping") {
                    sendJson(out, 200, "OK", mapOf(
                        "service" to "wave-phone",
                        "version" to 1,
                        "device" to _state.value.deviceName
                    ))
                    return
                }

                val current = _state.value
                val providedCode = headers["x-wave-code"] ?: query["code"]
                if (!current.active || !isCodeValid(providedCode, current.code)) {
                    val status = if (System.currentTimeMillis() < blockedUntilMs) 429 else 401
                    sendJson(out, status, if (status == 429) "Too Many Requests" else "Unauthorized", mapOf("error" to "invalid_code"))
                    return
                }

                if (method != "GET" && method != "HEAD") {
                    sendJson(out, 405, "Method Not Allowed", mapOf("error" to "method_not_allowed"))
                    return
                }

                val repo = Repository.get(context)
                when {
                    path == "/pair" -> {
                        val (songCount, plCount) = runBlocking {
                            repo.allSongs().size to repo.allPlaylists().size
                        }
                        sendJson(out, 200, "OK", mapOf(
                            "ok" to true,
                            "device" to current.deviceName,
                            "songCount" to songCount,
                            "playlistCount" to plCount
                        ))
                    }
                    path == "/songs" -> {
                        val songs = runBlocking { repo.allSongs() }
                        val payload = songs.map { s ->
                            val ext = inferExt(s)
                            mapOf(
                                "id" to s.id,
                                "fileName" to s.fileName,
                                "fileSize" to s.fileSize,
                                "title" to s.title,
                                "artist" to s.artist,
                                "album" to s.album,
                                "durationMs" to s.durationMs,
                                "trackNo" to s.trackNo,
                                "year" to s.year,
                                "mimeType" to s.mimeType,
                                "dateAddedSec" to s.dateAddedSec,
                                "isFavorite" to s.isFavorite,
                                "genre" to s.genre,
                                "hasArt" to !s.artUri.isNullOrBlank(),
                                "ext" to ext
                            )
                        }
                        sendJson(out, 200, "OK", payload)
                    }
                    path == "/playlists" -> {
                        val payload = runBlocking {
                            repo.allPlaylists().map { pl ->
                                val entries = repo.playlistEntries(pl.id).sortedBy { it.position }
                                mapOf(
                                    "id" to pl.id,
                                    "name" to pl.name,
                                    "songIds" to entries.map { it.songId }
                                )
                            }
                        }
                        sendJson(out, 200, "OK", payload)
                    }
                    path.startsWith("/art/") -> {
                        val idPart = path.removePrefix("/art/").substringBefore('.')
                        val songId = idPart.toLongOrNull()
                        val song = if (songId != null) runBlocking { repo.songById(songId) } else null
                        val bytes = if (song != null) readArtBytes(context, song.artUri) else null
                        if (bytes == null || bytes.isEmpty()) {
                            sendStatus(out, 404, "Not Found", "text/plain", ByteArray(0), headOnly = method == "HEAD")
                        } else {
                            val mime = if (bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) "image/png" else "image/jpeg"
                            sendStatus(out, 200, "OK", mime, bytes, headOnly = method == "HEAD", extraHeaders = mapOf("Cache-Control" to "max-age=3600"))
                        }
                    }
                    path.startsWith("/stream/") -> {
                        val idPart = path.removePrefix("/stream/").substringBefore('.')
                        val songId = idPart.toLongOrNull()
                        val song = if (songId != null) runBlocking { repo.songById(songId) } else null
                        if (song == null) {
                            sendStatus(out, 404, "Not Found", "text/plain", ByteArray(0), headOnly = method == "HEAD")
                        } else {
                            streamSong(context, song, headers["range"], out, headOnly = method == "HEAD")
                        }
                    }
                    else -> sendJson(out, 404, "Not Found", mapOf("error" to "not_found"))
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun readArtBytes(context: Context, artUri: String?): ByteArray? {
        if (artUri.isNullOrBlank()) return null
        return try {
            if (artUri.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(artUri))?.use { it.readBytes() }
            } else {
                val f = File(artUri.removePrefix("file://"))
                if (f.exists() && f.isFile) f.readBytes() else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun streamSong(
        context: Context,
        song: Song,
        rangeHeader: String?,
        out: BufferedOutputStream,
        headOnly: Boolean
    ) {
        val mime = inferMime(song)
        val fp = song.filePath
        if (fp.startsWith("content://")) {
            val uri = Uri.parse(fp)
            val totalSize = resolveContentLength(context, uri, song.fileSize)
            if (totalSize > 0 && !rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=")) {
                val (start, end) = parseRange(rangeHeader, totalSize)
                val len = (end - start + 1).coerceAtLeast(0L)
                val hdr = buildString {
                    append("HTTP/1.1 206 Partial Content\r\n")
                    append("Content-Type: ").append(mime).append("\r\n")
                    append("Content-Length: ").append(len).append("\r\n")
                    append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(totalSize).append("\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(hdr.toByteArray(Charsets.US_ASCII))
                if (!headOnly && len > 0) {
                    context.contentResolver.openInputStream(uri)?.use { raw ->
                        skipFully(raw, start)
                        copyFixed(raw, out, len)
                    }
                }
                out.flush()
            } else {
                val hdr = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: ").append(mime).append("\r\n")
                    if (totalSize > 0) append("Content-Length: ").append(totalSize).append("\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(hdr.toByteArray(Charsets.US_ASCII))
                if (!headOnly) {
                    context.contentResolver.openInputStream(uri)?.use { raw ->
                        raw.copyTo(out, 65536)
                    }
                }
                out.flush()
            }
            return
        }

        val file = File(fp.removePrefix("file://"))
        if (!file.exists() || !file.isFile) {
            sendStatus(out, 404, "Not Found", "text/plain", ByteArray(0), headOnly = headOnly)
            return
        }
        val totalSize = file.length()
        if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=") && totalSize > 0) {
            val (start, end) = parseRange(rangeHeader, totalSize)
            val len = (end - start + 1).coerceAtLeast(0L)
            val hdr = buildString {
                append("HTTP/1.1 206 Partial Content\r\n")
                append("Content-Type: ").append(mime).append("\r\n")
                append("Content-Length: ").append(len).append("\r\n")
                append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(totalSize).append("\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(hdr.toByteArray(Charsets.US_ASCII))
            if (!headOnly && len > 0) {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(start)
                    val buf = ByteArray(65536)
                    var rem = len
                    while (rem > 0) {
                        val n = raf.read(buf, 0, minOf(buf.size.toLong(), rem).toInt())
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        rem -= n
                    }
                }
            }
            out.flush()
        } else {
            val hdr = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: ").append(mime).append("\r\n")
                append("Content-Length: ").append(totalSize).append("\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(hdr.toByteArray(Charsets.US_ASCII))
            if (!headOnly) {
                FileInputStream(file).use { fis -> fis.copyTo(out, 65536) }
            }
            out.flush()
        }
    }

    private fun resolveContentLength(context: Context, uri: Uri, fallback: Long): Long {
        try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                if (afd.length >= 0) return afd.length
            }
        } catch (_: Exception) {}
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !c.isNull(idx)) {
                        val sz = c.getLong(idx)
                        if (sz > 0) return sz
                    }
                }
            }
        } catch (_: Exception) {}
        return fallback
    }

    private fun parseRange(header: String, total: Long): Pair<Long, Long> {
        val spec = header.removePrefix("bytes=").substringBefore(',').trim()
        val parts = spec.split('-', limit = 2)
        val start = parts.getOrNull(0)?.trim()?.toLongOrNull() ?: 0L
        val end = parts.getOrNull(1)?.trim()?.toLongOrNull() ?: (total - 1)
        val safeStart = start.coerceIn(0L, maxOf(0L, total - 1))
        val safeEnd = end.coerceIn(safeStart, maxOf(safeStart, total - 1))
        return safeStart to safeEnd
    }

    private fun skipFully(inp: InputStream, bytes: Long) {
        var remaining = bytes
        val skipBuf = ByteArray(8192)
        while (remaining > 0) {
            val skipped = inp.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                val n = inp.read(skipBuf, 0, minOf(skipBuf.size.toLong(), remaining).toInt())
                if (n <= 0) break
                remaining -= n
            }
        }
    }

    private fun copyFixed(inp: InputStream, out: BufferedOutputStream, count: Long) {
        val buf = ByteArray(65536)
        var rem = count
        while (rem > 0) {
            val n = inp.read(buf, 0, minOf(buf.size.toLong(), rem).toInt())
            if (n <= 0) break
            out.write(buf, 0, n)
            rem -= n
        }
    }

    private fun inferExt(song: Song): String {
        val fromName = song.fileName.substringAfterLast('.', "").lowercase(Locale.US)
        if (fromName in setOf("mp3", "m4a", "aac", "flac", "ogg", "wav", "wma", "opus")) return fromName
        return when (song.mimeType?.lowercase(Locale.US)) {
            "audio/mp4", "audio/m4a" -> "m4a"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/ogg", "application/ogg" -> "ogg"
            "audio/wav", "audio/x-wav" -> "wav"
            "audio/aac" -> "aac"
            else -> "mp3"
        }
    }

    private fun inferMime(song: Song): String {
        song.mimeType?.takeIf { it.startsWith("audio/") }?.let { return it }
        return when (inferExt(song)) {
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            "wma" -> "audio/x-ms-wma"
            else -> "audio/mpeg"
        }
    }

    private fun sendJson(out: BufferedOutputStream, code: Int, reason: String, bodyObj: Any) {
        val bytes = gson.toJson(bodyObj).toByteArray(Charsets.UTF_8)
        sendStatus(out, code, reason, "application/json; charset=utf-8", bytes, headOnly = false)
    }

    private fun sendStatus(
        out: BufferedOutputStream,
        code: Int,
        reason: String,
        contentType: String,
        body: ByteArray,
        headOnly: Boolean,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        val hdr = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            for ((k, v) in extraHeaders) append(k).append(": ").append(v).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(hdr.toByteArray(Charsets.US_ASCII))
        if (!headOnly && body.isNotEmpty()) out.write(body)
        out.flush()
    }

    private fun readLineAscii(inp: InputStream): String? {
        val baos = ByteArrayOutputStream(128)
        var b = inp.read()
        if (b == -1) return null
        while (b != -1) {
            if (b == 10) break
            if (b != 13) baos.write(b)
            b = inp.read()
        }
        return baos.toString("UTF-8")
    }

    private fun parseQuery(q: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (pair in q.split('&')) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            val k = if (idx >= 0) Uri.decode(pair.substring(0, idx)) else Uri.decode(pair)
            val v = if (idx >= 0) Uri.decode(pair.substring(idx + 1)) else ""
            map[k] = v
        }
        return map
    }

    fun getLocalIpv4Address(): String {
        return try {
            val ifaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            val candidates = mutableListOf<Pair<Int, String>>()
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase(Locale.US)
                val score = when {
                    name.startsWith("wlan") -> 100
                    name.startsWith("eth") -> 90
                    name.startsWith("ap") -> 80
                    name.startsWith("tun") || name.startsWith("rmnet") || name.startsWith("ccmni") -> 10
                    else -> 50
                }
                for (addr in iface.inetAddresses.toList()) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith("127.")) continue
                        val siteBoost = if (addr.isSiteLocalAddress) 10 else 0
                        candidates.add((score + siteBoost) to host)
                    }
                }
            }
            candidates.maxByOrNull { it.first }?.second ?: ""
        } catch (_: Exception) {
            ""
        }
    }
}
