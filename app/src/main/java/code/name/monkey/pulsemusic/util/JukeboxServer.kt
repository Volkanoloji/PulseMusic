package code.name.monkey.pulsemusic.util

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import code.name.monkey.pulsemusic.helper.MusicPlayerRemote
import fi.iki.elonen.NanoHTTPD
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.net.wifi.WifiManager
import android.media.AudioManager
import com.bumptech.glide.Glide
import java.io.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import android.provider.MediaStore
import android.os.Environment
import android.content.ContentValues
import android.media.MediaScannerConnection
import org.json.JSONArray
import org.json.JSONObject

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory

data class PlayerInfo(val title: String, val artist: String, val songId: Long, val albumId: Long, val isPlaying: Boolean, val position: Long, val duration: Long)

class JukeboxServer(
    val context: Context,
    port: Int = 9000,
    private val onCommandReceived: (action: String) -> Unit
) : NanoHTTPD(port) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val publicMusicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "PulseJukebox")
    private val downloadProgressMap = ConcurrentHashMap<String, Int>()

    init {
        if (!publicMusicDir.exists()) publicMusicDir.mkdirs()
        Thread { try { YoutubeDL.getInstance().init(context) } catch (e: Exception) {} }.start()
    }

    private fun getSafePlayerInfo(): PlayerInfo {
        val latch = CountDownLatch(1)
        var info = PlayerInfo("Müzik Durduruldu", "Pulse Pro", -1L, -1L, false, 0L, 0L)
        mainHandler.post {
            try {
                if (MusicPlayerRemote.currentSong.id != -1L) {
                    val song = MusicPlayerRemote.currentSong
                    info = PlayerInfo(song.title ?: "Bilinmeyen", song.artistName ?: "Bilinmeyen", song.id, song.albumId, MusicPlayerRemote.isPlaying, MusicPlayerRemote.songProgressMillis.toLong(), song.duration)
                }
            } catch (e: Exception) {}
            latch.countDown()
        }
        latch.await()
        return info
    }

    override fun serve(session: IHTTPSession?): Response {
        val uri = session?.uri ?: "/"
        val method = session?.method
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (uri == "/api/volume") {
            val volStr = session?.parameters?.get("level")?.get(0)
            if (volStr != null) {
                try {
                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val targetVol = (volStr.toFloat() / 100f * maxVol).toInt()
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                } catch(e: Exception) {}
            }
            return newFixedLengthResponse("OK")
        }

        if (uri == "/api/getvolume") {
            try {
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val pct = ((currentVol.toFloat() / maxVol.toFloat()) * 100).toInt()
                return newFixedLengthResponse(pct.toString())
            } catch (e: Exception) { return newFixedLengthResponse("50") }
        }

        if (uri == "/api/shuffle") { mainHandler.post { try { MusicPlayerRemote.toggleShuffleMode() } catch (e: Exception) { onCommandReceived("SHUFFLE") } }; return newFixedLengthResponse("OK") }
        if (uri == "/api/repeat") { mainHandler.post { try { MusicPlayerRemote.cycleRepeatMode() } catch (e: Exception) { onCommandReceived("REPEAT") } }; return newFixedLengthResponse("OK") }
        if (uri == "/api/forcepause") { mainHandler.post { MusicPlayerRemote.pauseSong() }; return newFixedLengthResponse("OK") }
        if (uri == "/api/forceresume") { mainHandler.post { MusicPlayerRemote.resumePlaying() }; return newFixedLengthResponse("OK") }
        if (uri == "/api/sync") { val pos = session?.parameters?.get("pos")?.get(0)?.toIntOrNull(); if (pos != null) mainHandler.post { MusicPlayerRemote.seekTo(pos) }; return newFixedLengthResponse("OK") }

        if (uri == "/api/stream") {
            try {
                val info = getSafePlayerInfo()
                val reqId = session?.parameters?.get("v")?.get(0)?.toLongOrNull() ?: info.songId
                val isDownload = session?.parameters?.get("download")?.get(0) == "true"

                if (reqId != -1L) {
                    val songUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, reqId)
                    val pfd = context.contentResolver.openFileDescriptor(songUri, "r")
                    if (pfd != null) {
                        val fileLength = pfd.statSize
                        val rangeHeader = session?.headers?.get("range")
                        var startFrom: Long = 0
                        var endAt: Long = fileLength - 1

                        if (!isDownload && rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                            val range = rangeHeader.substring(6).split("-")
                            startFrom = range[0].toLongOrNull() ?: 0L
                            if (range.size > 1 && range[1].isNotEmpty()) endAt = range[1].toLongOrNull() ?: endAt
                        }

                        val contentLength = endAt - startFrom + 1
                        val fis = FileInputStream(pfd.fileDescriptor)
                        fis.skip(startFrom)

                        val status = if (!isDownload && rangeHeader != null) Response.Status.PARTIAL_CONTENT else Response.Status.OK
                        val mimeType = if (isDownload) "application/octet-stream" else "audio/mpeg"

                        val res = newFixedLengthResponse(status, mimeType, fis, contentLength)
                        res.addHeader("Access-Control-Allow-Origin", "*")
                        if (isDownload) {
                            val safeTitle = info.title.replace("[^a-zA-Z0-9\\.\\-]".toRegex(), "_")
                            res.addHeader("Content-Disposition", "attachment; filename=\"$safeTitle.mp3\"")
                        } else {
                            res.addHeader("Accept-Ranges", "bytes")
                            res.addHeader("Content-Length", contentLength.toString())
                            res.addHeader("Content-Range", "bytes $startFrom-$endAt/$fileLength")
                        }
                        return res
                    }
                }
            } catch (e: Exception) {}
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Yok")
        }

        if (uri == "/api/albumart") {
            return try {
                val info = getSafePlayerInfo()
                val albumArtUri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), info.albumId)
                val bitmap = Glide.with(context).asBitmap().load(albumArtUri).submit(500, 500).get()
                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                val res = newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(baos.toByteArray()), baos.size().toLong())
                res.addHeader("Access-Control-Allow-Origin", "*")
                res
            } catch (e: Exception) { serveLogoResource() }
        }

        if (uri == "/api/logo") return serveLogoResource()

        if (uri.startsWith("/api/")) {
            if (uri == "/api/localsongs") {
                val latch = CountDownLatch(1); var jsonStr = "[]"
                mainHandler.post {
                    try {
                        val repo = code.name.monkey.pulsemusic.repository.RealSongRepository(context)
                        val jsonArray = JSONArray()
                        repo.songs().take(1000).forEach { song ->
                            val obj = JSONObject(); obj.put("id", song.id); obj.put("title", song.title ?: "Bilinmeyen"); obj.put("artist", song.artistName ?: "Bilinmeyen")
                            jsonArray.put(obj)
                        }
                        jsonStr = jsonArray.toString()
                    } catch (e: Exception) {}
                    latch.countDown()
                }; latch.await()
                val res = newFixedLengthResponse(Response.Status.OK, "application/json", jsonStr)
                res.addHeader("Access-Control-Allow-Origin", "*"); return res
            }

            if (uri == "/api/addtoqueue") {
                val id = session?.parameters?.get("id")?.get(0)?.toLongOrNull()
                val clear = session?.parameters?.get("clear")?.get(0) == "true"
                mainHandler.post {
                    if (clear) MusicPlayerRemote.clearQueue()
                    if (id != null) {
                        val song = code.name.monkey.pulsemusic.repository.RealSongRepository(context).song(id)
                        if (song.id != -1L) {
                            if(clear) MusicPlayerRemote.openQueue(java.util.ArrayList<code.name.monkey.pulsemusic.model.Song>().apply { add(song) }, 0, true)
                            else MusicPlayerRemote.enqueue(song)
                        }
                    }
                }
                return newFixedLengthResponse("OK")
            }

            if (uri == "/api/playmulti" && method == Method.POST) {
                try {
                    val body = HashMap<String, String>(); session.parseBody(body)
                    val idsStr = session.parameters["ids"]?.get(0) ?: ""
                    val ids = idsStr.split(",").mapNotNull { it.toLongOrNull() }
                    if(ids.isNotEmpty()) {
                        mainHandler.post {
                            MusicPlayerRemote.clearQueue()
                            val songs = java.util.ArrayList<code.name.monkey.pulsemusic.model.Song>()
                            val repo = code.name.monkey.pulsemusic.repository.RealSongRepository(context)
                            ids.forEach { id -> val s = repo.song(id); if(s.id != -1L) songs.add(s) }
                            if(songs.isNotEmpty()) MusicPlayerRemote.openQueue(songs, 0, true)
                        }
                    }
                    return newFixedLengthResponse("OK")
                } catch(e: Exception) { return newFixedLengthResponse("Hata") }
            }

            if (uri == "/api/queue") {
                val latch = CountDownLatch(1); var jsonStr = "[]"
                mainHandler.post {
                    try {
                        val jsonArray = JSONArray()
                        MusicPlayerRemote.playingQueue.take(30).forEach { song ->
                            val obj = JSONObject()
                            obj.put("id", song.id)
                            obj.put("title", song.title ?: "Bilinmeyen")
                            obj.put("artist", song.artistName ?: "Bilinmeyen")
                            jsonArray.put(obj)
                        }
                        jsonStr = jsonArray.toString()
                    } catch (e: Exception) {}
                    latch.countDown()
                }; latch.await()
                return newFixedLengthResponse(Response.Status.OK, "application/json", jsonStr)
            }

            if (uri == "/api/upload" && method == Method.POST) {
                try {
                    val files = HashMap<String, String>(); session.parseBody(files)
                    val tempFilePath = files["file"]; val fileName = session.parameters["file"]?.get(0) ?: "Sarki.mp3"
                    val queueOnly = session.parameters["queueOnly"]?.get(0) == "true"
                    if (tempFilePath != null) {
                        val destFile = File(publicMusicDir, fileName)
                        File(tempFilePath).copyTo(destFile, true, 128 * 1024)
                        MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), null) { _, audioUri ->
                            if (audioUri != null) mainHandler.postDelayed({
                                val song = code.name.monkey.pulsemusic.repository.RealSongRepository(context).song(ContentUris.parseId(audioUri))
                                if (song.id != -1L) {
                                    if (queueOnly) MusicPlayerRemote.enqueue(song)
                                    else MusicPlayerRemote.openQueue(java.util.ArrayList<code.name.monkey.pulsemusic.model.Song>().apply { add(song) }, 0, true)
                                }
                            }, 800)
                        }
                    }
                    return newFixedLengthResponse("OK")
                } catch (e: Exception) { return newFixedLengthResponse("Hata") }
            }

            if (uri == "/api/searchyt") {
                val query = session?.parameters?.get("q")?.get(0) ?: ""
                return try {
                    val apiUrl = "https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode(query, "UTF-8")
                    val html = java.net.URL(apiUrl).openConnection() as java.net.HttpURLConnection
                    html.setRequestProperty("User-Agent", "Mozilla/5.0"); val content = html.inputStream.bufferedReader().readText()
                    val pattern = """"videoRenderer":\{"videoId":"([^"]+)"[\s\S]*?"title":\{"runs":\[\{"text":"([^"]+)"\}[\s\S]*?"thumbnail":\{"thumbnails":\[\{"url":"([^"]+)""".toRegex()
                    val resArr = pattern.findAll(content).take(8).map { """{"videoId":"${it.groupValues[1]}", "title":"${it.groupValues[2].replace("\"", "")}", "thumbnail":"${it.groupValues[3]}"}""" }.joinToString(",")
                    newFixedLengthResponse(Response.Status.OK, "application/json", """{"items": [$resArr]}""")
                } catch (e: Exception) { newFixedLengthResponse("Hata") }
            }

            if (uri == "/api/progress") {
                val vid = session?.parameters?.get("videoId")?.get(0) ?: ""
                val prog = downloadProgressMap[vid] ?: -1
                return newFixedLengthResponse(prog.toString())
            }

            if (uri == "/api/ytdl") {
                val videoId = session?.parameters?.get("videoId")?.get(0)
                val rawTitle = session?.parameters?.get("title")?.get(0) ?: "Sarki"
                val thumbUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                val queueOnly = session?.parameters?.get("queueOnly")?.get(0) == "true"

                if (videoId != null) Thread {
                    Thread.currentThread().priority = Thread.MAX_PRIORITY
                    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                    val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PulseJukebox::DownloadLock")
                    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    val wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PulseJukebox::WifiLock")

                    try {
                        wakeLock.acquire(10 * 60 * 1000L); wifiLock.acquire(); downloadProgressMap[videoId] = 0
                        val tempAudio = File(context.cacheDir, "J_${videoId}.m4a")
                        val tempThumb = File(context.cacheDir, "T_${videoId}.jpg")

                        val req = YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId")
                        req.addOption("-f", "bestaudio[ext=m4a]/bestaudio")
                        req.addOption("--extractor-args", "youtube:player_client=android_vr,web_creator")
                        req.addOption("--no-playlist"); req.addOption("--no-mtime"); req.addOption("--buffer-size", "16K"); req.addOption("--concurrent-fragments", "4")
                        req.addOption("-o", tempAudio.absolutePath)

                        YoutubeDL.getInstance().execute(req) { progress, _, _ ->
                            val p = progress.toInt()
                            if (p >= 0) downloadProgressMap[videoId] = p
                        }

                        if (tempAudio.exists()) {
                            try {
                                val connection = java.net.URL(thumbUrl).openConnection()
                                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                                connection.connectTimeout = 5000; connection.readTimeout = 5000
                                connection.getInputStream().use { input -> tempThumb.outputStream().use { output -> input.copyTo(output) } }
                            } catch (e: Throwable) {}

                            try {
                                val audioFile = AudioFileIO.read(tempAudio)
                                val tag = audioFile.tag ?: audioFile.createDefaultTag()
                                val artist = if (rawTitle.contains("-")) rawTitle.split("-")[0].trim() else "YouTube"
                                val songName = if (rawTitle.contains("-")) rawTitle.split("-")[1].trim() else rawTitle

                                tag.setField(FieldKey.TITLE, songName); tag.setField(FieldKey.ARTIST, artist); tag.setField(FieldKey.ALBUM, songName)

                                if (tempThumb.exists() && tempThumb.length() > 0) {
                                    val artwork = ArtworkFactory.createArtworkFromFile(tempThumb)
                                    tag.setField(artwork)
                                }
                                audioFile.commit()
                            } catch (e: Throwable) {}

                            try {
                                val values = ContentValues().apply {
                                    put(MediaStore.MediaColumns.DISPLAY_NAME, "$rawTitle.m4a"); put(MediaStore.Audio.Media.TITLE, rawTitle)
                                    put(MediaStore.Audio.Media.ARTIST, "Pulse Jukebox"); put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/PulseJukebox")
                                    put(MediaStore.Audio.Media.IS_MUSIC, 1)
                                }
                                val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                                uri?.let { context.contentResolver.openOutputStream(it)?.use { out -> tempAudio.inputStream().use { input -> input.copyTo(out, 128 * 1024) } } }

                                tempAudio.delete(); if (tempThumb.exists()) tempThumb.delete()

                                mainHandler.postDelayed({
                                    val song = code.name.monkey.pulsemusic.repository.RealSongRepository(context).song(ContentUris.parseId(uri!!))
                                    if (song.id != -1L) {
                                        if (queueOnly) MusicPlayerRemote.enqueue(song)
                                        else MusicPlayerRemote.openQueue(java.util.ArrayList<code.name.monkey.pulsemusic.model.Song>().apply { add(song) }, 0, true)
                                    }
                                }, 800)
                            } catch (e: Throwable) {}
                        }
                    } catch (e: Throwable) {} finally {
                        if (wakeLock.isHeld) wakeLock.release(); if (wifiLock.isHeld) wifiLock.release(); downloadProgressMap.remove(videoId)
                    }
                }.start()
                return newFixedLengthResponse("OK")
            }

            if (uri == "/api/status") {
                val info = getSafePlayerInfo()
                val jsonStr = JSONObject().apply {
                    put("title", info.title)
                    put("artist", info.artist)
                    put("songId", info.songId)
                    put("isPlaying", info.isPlaying)
                    put("position", info.position)
                    put("duration", info.duration)
                }.toString()
                return newFixedLengthResponse(Response.Status.OK, "application/json", jsonStr)
            }
            val action = when (uri) { "/api/next" -> "NEXT"; "/api/prev" -> "BACK"; "/api/playpause" -> "TOGGLE"; else -> "" }
            if (action.isNotEmpty()) mainHandler.post { onCommandReceived(action) }
            return newFixedLengthResponse("OK")
        }

        val info = getSafePlayerInfo()
        val html = """
            <!DOCTYPE html><html><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0"><title>Pulse Jukebox VIP</title>
            <link rel="icon" type="image/png" href="/api/logo">
            <style>
                :root { 
                    --bg-color: #121212; --accent-color: #1DB954; --box-bg: rgba(255, 255, 255, 0.05); --text-color: #ffffff; 
                    --eq-scale: 1; --eq-glow: 0px; 
                }
                ::-webkit-scrollbar { width: 8px; }
                ::-webkit-scrollbar-track { background: rgba(0, 0, 0, 0.1); border-radius: 10px; }
                ::-webkit-scrollbar-thumb { background: rgba(255, 255, 255, 0.15); border-radius: 10px; transition: background 0.3s; }
                ::-webkit-scrollbar-thumb:hover { background: var(--accent-color); }
                body.light-mode ::-webkit-scrollbar-track { background: rgba(0, 0, 0, 0.05); }
                body.light-mode ::-webkit-scrollbar-thumb { background: rgba(0, 0, 0, 0.2); }
                body.light-mode ::-webkit-scrollbar-thumb:hover { background: var(--accent-color); }

                body { background: var(--bg-color); color: var(--text-color); font-family: 'Segoe UI', Tahoma, sans-serif; text-align: center; padding: 15px; margin:0; overflow-x: hidden; transition: background 1.5s cubic-bezier(0.4, 0, 0.2, 1); }
                body.light-mode { --bg-color: #f0f2f5; --text-color: #1c1e21; --box-bg: #ffffff; }
                body.light-mode .box { border-color: #e4e6eb; box-shadow: 0 4px 12px rgba(0,0,0,0.05); }
                body.light-mode .settings-btn { background: #fff; color: #1c1e21; border-color: #ccd0d5; }
                body.light-mode .dropdown-content { background: #fff; border-color: #ddd; color: #1c1e21; }
                body.light-mode .setting-desc { color: #666; }
                body.light-mode input[type="checkbox"], body.light-mode input[type="range"] { background: rgba(0,0,0,0.1); }

                .btn-logo { height: 24px; width: auto; filter: drop-shadow(0 0 5px var(--accent-color)); border-radius: 4px; }
                .header-logo { padding: 20px 0; display: flex; justify-content: center; align-items: center; margin-bottom: 20px; }
                .header-logo img { max-height: 80px; width: auto; filter: drop-shadow(0 0 10px rgba(255, 255, 255, 0.15)); }

                .settings-menu { position: fixed; top: 15px; right: 15px; z-index: 1000; }
                .settings-btn { background: rgba(40, 40, 40, 0.6); backdrop-filter: blur(10px); color: white; border: 1px solid rgba(255,255,255,0.1); padding: 8px 14px; border-radius: 20px; font-weight: bold; cursor: pointer; display: flex; align-items: center; gap: 8px; }
                
                .dropdown-content { display: none; position: absolute; right: 0; top: 40px; background: rgba(25,25,25,0.95); backdrop-filter: blur(15px); min-width: 320px; border-radius: 16px; padding: 15px; border: 1px solid rgba(255,255,255,0.1); box-shadow: 0 10px 30px rgba(0,0,0,0.5); }
                .dropdown-content.show { display: block; }
                
                .setting-item { display: flex; flex-direction: column; text-align: left; padding: 12px 0; border-bottom: 1px solid rgba(255,255,255,0.05); }
                .setting-item:last-child { border-bottom: none; }
                .setting-label { display: flex; align-items: center; justify-content: space-between; width: 100%; font-weight: bold; cursor: pointer; font-size: 0.95rem; }
                .label-left { display: flex; align-items: center; gap: 10px; }
                .setting-label svg { color: var(--accent-color); }
                .setting-desc { font-size: 0.75rem; color: #aaa; margin-top: 6px; margin-left: 30px; line-height: 1.3; }
                
                input[type="checkbox"] { -webkit-appearance: none; appearance: none; width: 44px; height: 24px; background: rgba(255, 255, 255, 0.15); border-radius: 20px; position: relative; cursor: pointer; outline: none; transition: background 0.3s ease; margin: 0; flex-shrink: 0; box-shadow: inset 0 0 5px rgba(0,0,0,0.2); }
                input[type="checkbox"]::after { content: ''; position: absolute; top: 2px; left: 2px; width: 20px; height: 20px; background: #ffffff; border-radius: 50%; transition: transform 0.3s cubic-bezier(0.4, 0.0, 0.2, 1); box-shadow: 0 2px 4px rgba(0,0,0,0.3); }
                input[type="checkbox"]:checked { background: var(--accent-color); }
                input[type="checkbox"]:checked::after { transform: translateX(20px); }

                .vol-container { display: flex; align-items: center; gap: 12px; margin-top: 15px; margin-left: 5px; }
                .vol-icon { opacity: 0.7; }
                
                .progress-container { display: flex; align-items: center; justify-content: center; gap: 12px; margin: 5px auto 25px auto; width: 90%; max-width: 400px; }
                .progress-container span { font-size: 0.8rem; font-weight: bold; opacity: 0.7; width: 40px; text-align: center; font-variant-numeric: tabular-nums; }
                input[type="range"].horizontal-range { -webkit-appearance: none; width: 100%; height: 6px; background: rgba(255, 255, 255, 0.1); border-radius: 5px; outline: none; cursor: pointer; }
                input[type="range"].horizontal-range::-webkit-slider-thumb { -webkit-appearance: none; appearance: none; width: 16px; height: 16px; border-radius: 50%; background: var(--accent-color); box-shadow: 0 0 10px rgba(0,0,0,0.5); transition: transform 0.1s ease; }
                input[type="range"].horizontal-range::-moz-range-thumb { width: 16px; height: 16px; border: none; border-radius: 50%; background: var(--accent-color); box-shadow: 0 0 10px rgba(0,0,0,0.5); transition: transform 0.1s ease; }
                input[type="range"].horizontal-range::-webkit-slider-thumb:hover { transform: scale(1.3); }
                input[type="range"].horizontal-range::-moz-range-thumb:hover { transform: scale(1.3); }

                .album-wrapper { position: relative; width: 230px; height: 230px; margin: 40px auto 20px auto; }
                .album-art { width: 100%; height: 100%; border-radius: 20px; box-shadow: 0 20px 50px rgba(0,0,0,0.5); overflow: hidden; border: 2px solid rgba(255,255,255,0.1); transition: border-radius 0.5s ease; position: relative; z-index: 2; background: var(--box-bg); }
                .album-art img { width: 100%; height: 100%; object-fit: cover; }
                .playing-spin { border-radius: 50% !important; animation: spin 10s linear infinite; }
                @keyframes spin { 100% { transform: rotate(360deg); } }

                .visualizer-ring { position: absolute; top: -15px; left: -15px; right: -15px; bottom: -15px; border-radius: 35px; border: 12px solid var(--accent-color); -webkit-mask-image: repeating-conic-gradient(black 0deg, black 4deg, transparent 4deg, transparent 8deg); mask-image: repeating-conic-gradient(black 0deg, black 4deg, transparent 4deg, transparent 8deg); opacity: 0; z-index: 1; pointer-events: none; transform: scale(var(--eq-scale)); filter: drop-shadow(0 0 var(--eq-glow) var(--accent-color)); transition: border-radius 0.5s ease, opacity 0.5s ease, transform 0.08s ease-out, filter 0.08s ease-out; }
                .visualizer-ring.spin-mode { border-radius: 50% !important; }

                .btn { display: inline-flex; justify-content: center; align-items: center; background: rgba(255,255,255,0.1); color: var(--text-color); border: none; width: 60px; height: 60px; border-radius: 50%; cursor: pointer; transition: 0.3s; }
                .btn.play { background: var(--accent-color); color: black !important; width: 75px; height: 75px; transition: background 0.8s, box-shadow 0.8s; }
                .pulse-anim { box-shadow: 0 0 0 calc(var(--eq-glow)*1.5) rgba(0,0,0,0); }
                .btn-small { width: 45px; height: 45px; opacity: 0.7; margin: 0 10px; }
                .btn-small:hover { opacity: 1; }

                .action-btns { display: flex; justify-content: center; gap: 15px; margin-bottom: 20px; }
                .chip-btn { background: rgba(255,255,255,0.1); border: 1px solid rgba(255,255,255,0.2); color: var(--text-color); padding: 8px 16px; border-radius: 20px; font-weight: bold; cursor: pointer; display: flex; align-items: center; gap: 6px; transition: 0.3s; font-size: 0.85rem; }
                .chip-btn:hover { background: var(--accent-color); color: black; }
                .chip-btn svg { width: 16px; height: 16px; }

                /* 🌟 SİNEMATİK TAM EKRAN SÖZLER */
                .lyrics-fullscreen { position: fixed; top: 0; left: 0; width: 100%; height: 100%; background-size: cover; background-position: center; z-index: 3000; display: none; flex-direction: column; align-items: center; justify-content: center; overflow: hidden; animation: fadeIn 0.4s ease; }
                @keyframes fadeIn { from { opacity: 0; transform: scale(1.05); } to { opacity: 1; transform: scale(1); } }
                .lyrics-overlay { position: absolute; top: 0; left: 0; width: 100%; height: 100%; background: var(--lyrics-overlay-tint, rgba(0,0,0,0.7)); backdrop-filter: blur(30px); -webkit-backdrop-filter: blur(30px); z-index: 1; }
                
                .close-lyrics-fullscreen { position: absolute; top: 30px; right: 40px; z-index: 3; color: #fff; font-size: 45px; font-weight: 300; cursor: pointer; opacity: 0.6; transition: 0.3s; line-height: 1; }
                .close-lyrics-fullscreen:hover { opacity: 1; transform: scale(1.1); color: var(--accent-color); text-shadow: 0 0 15px var(--accent-color); }
                
                .lyrics-container { position: relative; z-index: 2; width: 90%; max-width: 800px; height: 80vh; overflow-y: scroll; scroll-behavior: smooth; padding: 40vh 0; box-sizing: border-box; -webkit-mask-image: linear-gradient(transparent 0%, black 15%, black 85%, transparent 100%); mask-image: linear-gradient(transparent 0%, black 15%, black 85%, transparent 100%); }
                .lyrics-container::-webkit-scrollbar { display: none; }
                
                .lyric-line { font-size: 1.6rem; font-weight: 800; color: rgba(255,255,255,0.3); margin: 20px 0; padding: 5px 20px; transition: all 0.4s cubic-bezier(0.25, 0.46, 0.45, 0.94); cursor: pointer; transform-origin: center center; text-align: center; filter: blur(1.5px); line-height: 1.4; letter-spacing: -0.5px; }
                .lyric-line:hover { color: rgba(255,255,255,0.6); filter: blur(0px); }
                .lyric-line.active { color: #fff; font-size: 2.4rem; filter: blur(0px); text-shadow: 0 0 25px rgba(255,255,255,0.4); transform: scale(1.1); padding: 15px 20px; }
                
                .lyrics-error-log { position:relative; z-index: 5; color: #ff5555; font-weight: bold; font-family: 'Courier New', monospace; font-size: 1rem; line-height: 1.4; border: 2px solid #aa0000; background: rgba(20,0,0,0.8); padding: 15px; border-radius: 10px; margin-top: 15px; max-width: 500px; text-align: left; }
                
                /* 🎛️ ANALOG SENSATIONAL EQ MODAL */
                .modal-eq { display: none; position: fixed; z-index: 2000; left: 0; top: 0; width: 100%; height: 100%; background-color: rgba(0,0,0,0.8); backdrop-filter: blur(8px); }
                .modal-content-eq { background: transparent !important; border: none !important; padding: 0 !important; margin: 2% auto; width: 95%; max-width: 600px; position: relative; }
                
                .analog-faceplate { 
                    background: linear-gradient(145deg, rgba(255,255,255,0.1) 0%, rgba(0,0,0,0.4) 100%), var(--bg-color); 
                    border: 1px solid rgba(255,255,255,0.1); 
                    border-radius: 20px; 
                    padding: 30px 20px 20px 20px; 
                    box-shadow: 0 20px 40px rgba(0,0,0,0.8), inset 0 2px 5px rgba(255,255,255,0.1); 
                    color: var(--text-color); 
                    text-align: center; 
                    position: relative; 
                }
                
                .analog-close-btn { position: absolute; top: 10px; right: 20px; font-size: 35px; color: var(--text-color); opacity: 0.6; cursor: pointer; line-height: 1; z-index: 100; font-weight: bold; transition: 0.3s; }
                .analog-close-btn:hover { color: #ff0000; opacity: 1; text-shadow: 0 0 10px red; }

                .vu-container { display: flex; justify-content: space-around; margin-bottom: 30px; position: relative; }
                .vu-meter { width: 180px; height: 90px; background: #111; border: 4px solid rgba(255,255,255,0.1); border-radius: 100px 100px 0 0; position: relative; overflow: hidden; box-shadow: inset 0 0 15px rgba(0,0,0,0.8), 0 5px 10px rgba(0,0,0,0.5); }
                .vu-scale { width: 100%; height: 100%; background: radial-gradient(circle at 50% 100%, #e0c080 0%, #a0a070 100%); position: absolute; }
                .vu-scale-text { position: absolute; font-size: 0.7rem; font-weight: bold; color: black; opacity: 0.8; }
                .vu-needle { width: 3px; height: 90%; background: red; position: absolute; bottom: -5px; left: 50%; transform-origin: bottom center; transform: rotate(-70deg); transition: transform 0.05s ease-out; z-index: 2; border-radius: 3px; box-shadow: 2px 0 5px rgba(0,0,0,0.5); }
                .vu-cover { width: 100%; height: 100%; background: radial-gradient(circle at 50% 100%, transparent 60%, rgba(255,255,255,0.2) 100%); position: absolute; top: 0; left: 0; z-index: 1; }
                .vu-label { position: absolute; bottom: 5px; width: 100%; text-align: center; color: var(--accent-color); font-weight: bold; font-size: 0.9rem; text-shadow: 0 0 5px black; }
                .peak-led { width: 12px; height: 12px; background: #400; border-radius: 50%; border: 2px solid rgba(255,255,255,0.2); position: absolute; top: 10px; right: 10px; box-shadow: inset 0 2px 5px rgba(0,0,0,0.8); }
                .peak-led.active { background: #f00; box-shadow: 0 0 15px #f00, inset 0 0 5px #fff; }

                .eq-bands-container { display: flex; justify-content: space-around; align-items: center; padding: 20px 10px; background: rgba(0,0,0,0.2); border-radius: 15px; border: 1px inset rgba(255,255,255,0.05); margin: 20px 0; box-shadow: inset 0 5px 15px rgba(0,0,0,0.5); }
                .eq-mastering-group { display: grid; grid-template-columns: repeat(5, 1fr); gap: 30px 10px; width: 100%; } 
                
                .knob-container { display: flex; flex-direction: column; align-items: center; justify-content: flex-start; text-align: center; width: 100%; }
                .knob-label { font-size: 0.75rem; color: var(--text-color); font-weight: bold; margin-bottom: 8px; opacity: 0.8; text-shadow: 0 1px 2px rgba(0,0,0,0.5); }
                .knob-val { font-size: 0.75rem; color: var(--text-color); font-weight: bold; font-family: monospace; margin-top: 10px; display: block; text-shadow: 0 1px 2px rgba(0,0,0,0.5); }
                .knob-val span { color: var(--accent-color); font-weight: 900; } 
                
                .knob-control-wrapper { position: relative; width: 65px; height: 65px; cursor: pointer; -webkit-user-select: none; user-select: none; }
                .knob-face { width: 100%; height: 100%; background: radial-gradient(circle at 30% 30%, #444 0%, #111 100%); border-radius: 50%; border: 4px solid rgba(255,255,255,0.2); box-shadow: 0 5px 10px rgba(0,0,0,0.8), inset 0 2px 2px rgba(255,255,255,0.1); transform: rotate(0deg); position: absolute; top: 0; left: 0; }
                .knob-point { width: 8px; height: 8px; background: var(--accent-color); border-radius: 50%; border: 1px solid black; position: absolute; top: 8px; left: 50%; transform: translateX(-50%); box-shadow: 0 0 5px var(--accent-color); }
                .knob-ticks { width: 110%; height: 110%; position: absolute; top: -5%; left: -5%; background: repeating-conic-gradient(from 0deg, rgba(0,0,0,0.15) 0% 1deg, transparent 1deg 15deg); border-radius: 50%; }
                
                .preset-select { width: 100%; padding: 12px; border-radius: 8px; background: rgba(0,0,0,0.3); color: var(--text-color); border: 1px solid rgba(255,255,255,0.2); outline: none; margin-bottom: 10px; font-weight: bold; cursor: pointer; box-shadow: inset 0 2px 5px rgba(0,0,0,0.5); -webkit-appearance: none; appearance: none; text-align: center; }
                .preset-select option { background: var(--bg-color); color: var(--text-color); padding: 10px; font-weight: normal; }
                
                .box { text-align: left; background: var(--box-bg); padding: 18px; border-radius: 20px; border: 1px solid rgba(255,255,255,0.1); margin-top: 20px; backdrop-filter: blur(5px); transition: 0.4s; }
                .visitor-box { border-left: 5px solid #E91E63; }
                .song-item { display: flex; justify-content: space-between; align-items: center; padding: 12px 10px; border-bottom: 1px solid rgba(255,255,255,0.05); }
                .add-btn { background: var(--accent-color); color: black; border: none; padding: 8px 14px; border-radius: 10px; font-weight: bold; cursor: pointer; transition: 0.2s; min-width: 40px; text-align: center; font-size: 0.85rem;}
                .neon-spinner { width: 16px; height: 16px; border: 3px solid rgba(0,0,0,0.2); border-top: 3px solid black; border-radius: 50%; animation: spin-fast 1s linear infinite; display: inline-block; vertical-align: middle; margin-right: 6px; }
                @keyframes spin-fast { 100% { transform: rotate(360deg); } }
                .search-input { width: 100%; padding: 12px; border-radius: 12px; border: 1px solid rgba(255,255,255,0.1); background: rgba(0,0,0,0.1); color: var(--text-color); outline: none; margin-bottom: 12px; box-sizing: border-box; }
                h4 { margin: 0 0 15px 0; display: flex; align-items: center; gap: 8px; }
                .scroll-list { max-height: 250px; overflow-y: auto; scrollbar-width: thin; scrollbar-color: rgba(255, 255, 255, 0.15) rgba(0, 0, 0, 0.1); }
                #visitorFile { display: none; }
                .scan-btn { background: #E91E63; color: white; width: 100%; padding: 14px; border-radius: 12px; border: none; font-weight: bold; cursor: pointer; margin-bottom: 10px; display: flex; align-items: center; justify-content: center; gap: 8px; }
                .batch-btn { background: #1DB954; color: black; width: 100%; padding: 12px; border-radius: 10px; border: none; font-weight: 800; cursor: pointer; margin-bottom: 15px; display: none; }
                /* ❄️ EKSİK KAR YERÇEKİMİ MOTORU */
                .snowflake { 
                    position: fixed; 
                    top: -10%; 
                    z-index: 9999; 
                    pointer-events: none; 
                    animation-name: fall; 
                    animation-timing-function: linear; 
                    color: white; 
                    text-shadow: 0 0 5px rgba(255,255,255,0.8); 
                }
                @keyframes fall {
                    0% { transform: translateY(-10vh) translateX(0); }
                    100% { transform: translateY(110vh) translateX(var(--drift)); }
                }
            </style>
            <script>
                let snowInterval; let vFiles = []; let eqInterval = null; let vuInterval = null;
                let localAudioPlayer = new Audio();
                let currentLocalStreamId = -1;
                let currentPosMs = 0; let currentDurationMs = 0; let isSeeking = false;
                
                let currentLyricsData = []; 

                let audioCtx, sourceNode, pannerNode, preAmp, leftAnalyzer, rightAnalyzer, channelSplitter;
                let eqNodes = [];
                let eqBands = [32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000];
                let convolver, dryGain, wetGain;
                let isAudioFxInit = false; let is8DActive = false; let pannerInterval; let isReverbActive = false;

                const eqPresets = {
                    "custom": [0,0,0,0,0,0,0,0,0,0],
                    "flat": [0,0,0,0,0,0,0,0,0,0],
                    "bass": [7, 5, 3, 1, 0, 0, 0, 0, 1, 2],
                    "rock": [5, 4, 3, -1, -2, -1, 2, 4, 5, 6],
                    "electronic": [6, 5, 2, 0, -2, 0, 1, 4, 5, 6],
                    "vocal": [-2, -1, 0, 2, 4, 4, 3, 1, 0, -1],
                    "acoustic": [2, 2, 1, 0, 1, 2, 3, 4, 3, 2]
                };

                const P_ICON = '<svg viewBox="0 0 24 24" width="36" height="36" fill="currentColor"><path d="M8 5v14l11-7z"/></svg>';
                const S_ICON = '<svg viewBox="0 0 24 24" width="36" height="36" fill="currentColor"><path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z"/></svg>';

                function createReverbBuffer() {
                    let duration = 3.5; let decay = 2.0;
                    let sampleRate = audioCtx.sampleRate;
                    let length = sampleRate * duration;
                    let impulse = audioCtx.createBuffer(2, length, sampleRate);
                    let left = impulse.getChannelData(0); let right = impulse.getChannelData(1);
                    for (let i = 0; i < length; i++) {
                        let n = 1 - i / length;
                        left[i] = (Math.random() * 2 - 1) * Math.pow(n, decay);
                        right[i] = (Math.random() * 2 - 1) * Math.pow(n, decay);
                    }
                    return impulse;
                }

                function initAudioFX() {
                    if (isAudioFxInit) return;
                    try {
                        audioCtx = new (window.AudioContext || window.webkitAudioContext)();
                        localAudioPlayer.crossOrigin = "anonymous";
                        sourceNode = audioCtx.createMediaElementSource(localAudioPlayer);

                        preAmp = audioCtx.createGain();
                        preAmp.gain.value = 0.6; 
                        sourceNode.connect(preAmp);

                        let prevNode = preAmp;
                        eqBands.forEach((freq, i) => {
                            let filter = audioCtx.createBiquadFilter();
                            filter.type = (i === 0) ? "lowshelf" : (i === eqBands.length - 1) ? "highshelf" : "peaking";
                            filter.frequency.value = freq;
                            if(filter.type === "peaking") filter.Q.value = 1.414;
                            
                            let savedVal = localStorage.getItem('eq_band_' + i) || 0;
                            filter.gain.value = parseFloat(savedVal);
                            
                            eqNodes.push(filter);
                            prevNode.connect(filter);
                            prevNode = filter;
                        });

                        pannerNode = audioCtx.createStereoPanner();
                        prevNode.connect(pannerNode);
                        
                        convolver = audioCtx.createConvolver();
                        convolver.buffer = createReverbBuffer();
                        dryGain = audioCtx.createGain(); wetGain = audioCtx.createGain(); 
                        
                        pannerNode.connect(dryGain);
                        dryGain.connect(audioCtx.destination);
                        
                        pannerNode.connect(convolver);
                        convolver.connect(wetGain);
                        wetGain.connect(audioCtx.destination);

                        leftAnalyzer = audioCtx.createAnalyser(); leftAnalyzer.fftSize = 256;
                        rightAnalyzer = audioCtx.createAnalyser(); rightAnalyzer.fftSize = 256;
                        channelSplitter = audioCtx.createChannelSplitter(2);
                        
                        pannerNode.connect(channelSplitter);
                        channelSplitter.connect(leftAnalyzer, 0); 
                        channelSplitter.connect(rightAnalyzer, 1); 

                        isAudioFxInit = true;
                        
                        toggle8D();
                        toggleReverb();
                        updateEQUI(); 
                        startVUMeters();
                    } catch(e) { console.error("Audio API Error:", e); }
                }

                function changeEQBand(index, value) {
                    if(isAudioFxInit) eqNodes[index].gain.value = parseFloat(value);
                    localStorage.setItem('eq_band_' + index, value);
                    document.getElementById('eq_val_' + index).innerHTML = (value > 0 ? '+' : '') + value + ' <span>dB</span>';
                    document.getElementById('presetSelect').value = "custom";
                }

                function applyPreset(presetName) {
                    let values = eqPresets[presetName];
                    values.forEach((val, i) => {
                        updateKnobUI(i, val); 
                        changeEQBand(i, val); 
                    });
                    if (presetName !== "custom") {
                        document.getElementById('presetSelect').value = presetName;
                    }
                }

                function updateEQUI() {
                    eqBands.forEach((freq, i) => {
                        let val = localStorage.getItem('eq_band_' + i) || 0;
                        updateKnobUI(i, val);
                        document.getElementById('eq_val_' + i).innerHTML = (val > 0 ? '+' : '') + val + ' <span>dB</span>';
                    });
                }

                function updateKnobUI(index, dbValue) {
                    let knobFace = document.getElementById('knob_face_' + index);
                    let angle;
                    if (dbValue === 0) angle = 0;
                    else if (dbValue > 0) angle = (dbValue / 12) * 140; 
                    else angle = (dbValue / 12) * 140; 
                    knobFace.style.transform = 'rotate(' + angle + 'deg)';
                }

                let activeKnob = null;
                function onKnobStart(e, index) {
                    initAudioFX(); 
                    activeKnob = { index: index, startY: e.clientY, startValue: parseFloat(localStorage.getItem('eq_band_' + index) || 0) };
                    window.addEventListener('mousemove', onKnobMove);
                    window.addEventListener('mouseup', onKnobEnd);
                    e.preventDefault();
                }

                function onKnobMove(e) {
                    if (!activeKnob) return;
                    let deltaY = activeKnob.startY - e.clientY; 
                    let stepPerPixel = 0.15; 
                    let newValue = activeKnob.startValue + (deltaY * stepPerPixel);
                    
                    newValue = Math.max(-12, Math.min(12, newValue));
                    newValue = Math.round(newValue * 2) / 2; 

                    if (newValue !== parseFloat(localStorage.getItem('eq_band_' + activeKnob.index) || 0)) {
                        updateKnobUI(activeKnob.index, newValue);
                        changeEQBand(activeKnob.index, newValue);
                    }
                }

                function onKnobEnd() {
                    activeKnob = null;
                    window.removeEventListener('mousemove', onKnobMove);
                    window.removeEventListener('mouseup', onKnobEnd);
                }

                function startVUMeters() {
                    const dataL = new Uint8Array(leftAnalyzer.frequencyBinCount);
                    const dataR = new Uint8Array(rightAnalyzer.frequencyBinCount);
                    const needleL = document.getElementById('vuNeedle_L');
                    const needleR = document.getElementById('vuNeedle_R');
                    const peakL = document.getElementById('peakLed_L');
                    const peakR = document.getElementById('peakLed_R');

                    if(vuInterval) clearInterval(vuInterval);
                    vuInterval = setInterval(() => {
                        if(!isAudioFxInit || localAudioPlayer.paused || localAudioPlayer.volume === 0) {
                            needleL.style.transform = 'rotate(-70deg)'; needleR.style.transform = 'rotate(-70deg)';
                            peakL.classList.remove("active"); peakR.classList.remove("active");
                            return;
                        }
                        
                        leftAnalyzer.getByteTimeDomainData(dataL);
                        rightAnalyzer.getByteTimeDomainData(dataR);
                        
                        let rmsL = getRMS(dataL); let rmsR = getRMS(dataR);
                        let angleL = rmsToAngle(rmsL); let angleR = rmsToAngle(rmsR);
                        
                        needleL.style.transform = 'rotate(' + angleL + 'deg)';
                        needleR.style.transform = 'rotate(' + angleR + 'deg)';
                        
                        if(rmsL > 0.9) peakL.classList.add("active"); else peakL.classList.remove("active");
                        if(rmsR > 0.9) peakR.classList.add("active"); else peakR.classList.remove("active");

                    }, 50); 
                }

                function getRMS(data) {
                    let sum = 0;
                    for(let i=0; i<data.length; i++) {
                        let sample = (data[i] / 128.0) - 1.0; sum += (sample * sample);
                    }
                    return Math.sqrt(sum / data.length) * 1.5; 
                }

                function rmsToAngle(rms) {
                    if (rms <= 0.001) return -70; 
                    let db = 20 * Math.log10(rms);
                    if (db < -20) return -70 + ((db + 30) / 10) * 10;
                    if (db < 0) return (db / 20) * 60; 
                    if (db > 0) return Math.min(25, (db / 3) * 20); 
                    return 0;
                }

                function toggle8D() {
                    is8DActive = document.getElementById('8dToggle').checked;
                    localStorage.setItem('8dFx', is8DActive);
                    if(!isAudioFxInit) return;
                    
                    clearInterval(pannerInterval);
                    if(is8DActive) {
                        let val = -1; let step = 0.03;
                        pannerInterval = setInterval(() => {
                            val += step; if(val >= 1 || val <= -1) step = -step;
                            pannerNode.pan.value = val;
                        }, 100);
                    } else { pannerNode.pan.value = 0; }
                }

                // 🌬️ YENİ: REVERB YOĞUNLUK KONTROLÜ
                function changeReverbLevel(val) {
                    localStorage.setItem('reverbLevel', val);
                    document.getElementById('reverbLevelVal').innerText = val + '%';
                    if(!isAudioFxInit) return;
                    
                    if(isReverbActive) {
                        wetGain.gain.value = (val / 100) * 2.0;
                        dryGain.gain.value = 1.0 - (val / 200); 
                    }
                }

                function toggleReverb() {
                    isReverbActive = document.getElementById('reverbToggle').checked;
                    localStorage.setItem('reverbFx', isReverbActive);
                    if(!isAudioFxInit) return;
                    
                    if(isReverbActive) {
                        let val = localStorage.getItem('reverbLevel') || 50;
                        wetGain.gain.value = (val / 100) * 2.0;
                        dryGain.gain.value = 1.0 - (val / 200);
                    } else {
                        wetGain.gain.value = 0.0;
                        dryGain.gain.value = 1.0;
                    }
                }

                function refreshQueue() {
                    fetch('/api/queue').then(r => r.json()).then(d => {
                        var h = ""; d.forEach((s, i) => h += '<div class="song-item"><span><b>' + (i+1) + '.</b> ' + s.title + '</span></div>'); 
                        document.getElementById('queueList').innerHTML = h;
                    });
                }

                function renderPlaylists() {
                    let pList = JSON.parse(localStorage.getItem('vip_playlists') || '{}');
                    let h = "";
                    for(let name in pList) {
                        h += '<div class="song-item"><span>📁 <b>' + name + '</b> (' + pList[name].length + ' Şarkı)</span><button class="add-btn" onclick="playPlaylist(\'' + name + '\')">Oynat</button></div>';
                    }
                    document.getElementById('customPlaylists').innerHTML = h || "<small style='opacity:0.5'>Henüz liste yok.</small>";
                }

                function saveCurrentAsPlaylist() {
                    let name = prompt("Bu listeye ne isim verelim?");
                    if(!name) return;
                    fetch('/api/queue').then(r=>r.json()).then(d => {
                        if(d.length === 0) return alert("Şu an çalan şarkı yok!");
                        let pList = JSON.parse(localStorage.getItem('vip_playlists') || '{}');
                        let ids = d.map(s => s.id);
                        pList[name] = ids;
                        localStorage.setItem('vip_playlists', JSON.stringify(pList));
                        renderPlaylists();
                    });
                }

                function playPlaylist(name) {
                    let pList = JSON.parse(localStorage.getItem('vip_playlists') || '{}');
                    let ids = pList[name];
                    if(ids && ids.length > 0) {
                        let fd = new FormData(); fd.append("ids", ids.join(','));
                        fetch('/api/playmulti', {method: 'POST', body: fd}).then(()=> setTimeout(refreshQueue, 500));
                    }
                }

                function parseLRC(lrcText) {
                    const lines = lrcText.split('\n');
                    const regex = /^\[(\d{2}):(\d{2}\.\d{2,3})\](.*)/;
                    let parsed = [];
                    lines.forEach(line => {
                        const match = line.match(regex);
                        if(match) {
                            let m = parseInt(match[1], 10);
                            let s = parseFloat(match[2]);
                            let txt = match[3].trim();
                            parsed.push({ time: (m * 60) + s, text: txt || '&nbsp;' });
                        }
                    });
                    return parsed;
                }

                function renderSyncedLyrics(lrcText) {
                    currentLyricsData = parseLRC(lrcText);
                    let html = '';
                    if (currentLyricsData.length > 0) {
                        currentLyricsData.forEach((line, i) => {
                            html += '<div class="lyric-line" id="lyric_' + i + '" onclick="seekToLyric(' + line.time + ')">' + line.text + '</div>';
                        });
                        document.getElementById('lyricsContainer').innerHTML = html;
                    } else {
                        document.getElementById('lyricsContainer').innerHTML = '<div class="lyric-line active" style="font-size:1.5rem;">' + lrcText.replace(/\n/g, '</div><div class="lyric-line active" style="font-size:1.5rem;">') + '</div>';
                    }
                }
                
                function seekToLyric(timeSec) {
                    let targetMs = Math.floor(timeSec * 1000);
                    if (document.getElementById('localToggle').checked) localAudioPlayer.currentTime = targetMs / 1000;
                    fetch('/api/sync?pos=' + targetMs); 
                    currentPosMs = targetMs;
                }

                function logLyricsError(type, error) {
                    console.error("🚨 [Logcat] " + type + ":", error);
                    document.getElementById('lyricsContainer').innerHTML = '<div class="lyrics-error-log">' +
                        '🚨 MOTOR ARIZASI:<br>' +
                        'Şarkı sözleri çekilirken teknik bir hata oluştu.<br><br>' +
                        'Arıza Tipi: <span style="color:#ffaaaa">' + type + '</span><br>' +
                        'Hata Mesajı: <small>' + error.message + '</small><br><br>' +
                        'Arıza konsola (Logcat / F12) basıldı.' +
                        '</div>';
                }

                function fetchLyrics() {
                    const modal = document.getElementById('lyricsFullscreen');
                    const content = document.getElementById('lyricsContainer');
                    const bgImg = document.getElementById('coverImg').src;
                    
                    let rawTitle = document.getElementById('sn').innerText;
                    const title = rawTitle.replace(/\(.*\)|\[.*\]/g, '').replace(/official|video|audio|lyric|remastered/gi, '').trim(); 
                    const artist = document.getElementById('artistName').innerText.split(',')[0].trim();
                    
                    currentLyricsData = []; 
                    modal.style.display = "flex";
                    modal.style.backgroundImage = 'url(' + bgImg + ')'; 
                    
                    content.innerHTML = '<div style="margin-top:10%; text-align:center;"><div class="neon-spinner" style="width:40px;height:40px;border-width:5px;"></div><br><br><span style="font-size:1.5rem; color:#fff; font-weight:bold;">Aranıyor:<br>' + artist + ' - ' + title + '</span></div>';

                    fetch('https://lrclib.net/api/search?track_name=' + encodeURIComponent(title) + '&artist_name=' + encodeURIComponent(artist))
                        .then(r => r.json())
                        .then(d => {
                            if(d && d.length > 0 && (d[0].syncedLyrics || d[0].plainLyrics)) {
                                if(d[0].syncedLyrics) renderSyncedLyrics(d[0].syncedLyrics);
                                else content.innerHTML = '<div class="lyric-line active" style="font-size:1.5rem;">' + d[0].plainLyrics.replace(/\n/g, '</div><div class="lyric-line active" style="font-size:1.5rem;">') + '</div>';
                            } else {
                                fetch('https://api.lyrics.ovh/v1/' + encodeURIComponent(artist) + '/' + encodeURIComponent(title))
                                    .then(r => {
                                        if (r.status === 404) return null; 
                                        if (!r.ok) throw new Error("API HTTP Hatası: " + r.status);
                                        return r.text(); 
                                    })
                                    .then(text => {
                                        if (text === null) {
                                            content.innerHTML = "<div style='margin-top:20%; color:#fff; font-size:1.5rem;'>Sözler maalesef dijital veritabanında bulunamadı. 😔</div>";
                                            return;
                                        }
                                        try {
                                            let d2 = JSON.parse(text); 
                                            if(d2.lyrics) {
                                                content.innerHTML = '<div class="lyric-line active" style="font-size:1.5rem;">' + d2.lyrics.replace(/\n/g, '</div><div class="lyric-line active" style="font-size:1.5rem;">') + '</div>';
                                            } else {
                                                content.innerHTML = "<div style='margin-top:20%; color:#fff; font-size:1.5rem;'>Sözler maalesef dijital veritabanında bulunamadı. 😔</div>";
                                            }
                                        } catch(e) { throw new Error("OVH API geçersiz yanıt verdi."); }
                                    }).catch(e => { logLyricsError("OVH API ÇÖKMESİ", e); });
                            }
                        }).catch(e => { logLyricsError("LRCLIB AĞ HATASI", e); });
                }

                function downloadCurrentSong() {
                    let sid = document.getElementById('sn').getAttribute('data-id');
                    if(sid && sid !== "-1") window.location.href = '/api/stream?v=' + sid + '&download=true';
                }

                function toggleQueue() { localStorage.setItem('queueMode', document.getElementById('queueToggle').checked); }

                function initSettings() {
                    const isLight = localStorage.getItem('lightTheme') === 'true';
                    const isSnow = localStorage.getItem('snowEffect') === 'true';
                    const isSpin = localStorage.getItem('spinEffect') !== 'false';
                    const isLocal = localStorage.getItem('localMode') === 'true'; 
                    const isQueue = localStorage.getItem('queueMode') === 'true';
                    
                    if (isLight) document.body.classList.add('light-mode');
                    document.getElementById('themeToggle').checked = isLight;
                    document.getElementById('snowToggle').checked = isSnow;
                    document.getElementById('spinToggle').checked = isSpin;
                    document.getElementById('localToggle').checked = isLocal;
                    document.getElementById('queueToggle').checked = isQueue;
                    
                    document.getElementById('8dToggle').checked = localStorage.getItem('8dFx') === 'true';
                    document.getElementById('reverbToggle').checked = localStorage.getItem('reverbFx') === 'true';
                    
                    let revLvl = localStorage.getItem('reverbLevel') || 50;
                    document.getElementById('reverbSlider').value = revLvl;
                    document.getElementById('reverbLevelVal').innerText = revLvl + '%';
                    
                    updateEQUI();
                    
                    if (isSnow) startSnow();
                    
                    const img = document.getElementById('coverImg');
                    if (img.complete) extractColor(img); else img.onload = function() { extractColor(this); };

                    if(isLocal) {
                        let savedVol = localStorage.getItem('pcVolume') || '50';
                        document.getElementById('volSlider').value = savedVol; updateSliderColor(document.getElementById('volSlider'));
                        localAudioPlayer.volume = savedVol / 100.0;
                        fetch('/api/volume?level=0'); 
                        document.getElementById('eqMenuBtn').style.display = 'flex'; 
                    } else {
                        document.getElementById('eqMenuBtn').style.display = 'none';
                        let pcTime = Math.floor(localAudioPlayer.currentTime * 1000);
                        localAudioPlayer.pause(); currentLocalStreamId = -1; 
                        
                        fetch('/api/sync?pos=' + pcTime).then(() => {
                            fetch('/api/forceresume'); 
                            fetch('/api/getvolume').then(r => r.text()).then(v => {
                                document.getElementById('volSlider').value = v; updateSliderColor(document.getElementById('volSlider'));
                            });
                        });
                    }

                    localAudioPlayer.onended = () => { fetch('/api/next'); };
                    renderPlaylists();
                }

                document.addEventListener('keydown', function(e) {
                    if (document.activeElement.tagName === "INPUT" || document.activeElement.tagName === "SELECT") return; 
                    if (e.code === 'Space') { e.preventDefault(); togglePlay(); }
                    else if (e.code === 'ArrowRight') { e.preventDefault(); sendQuiet('/api/next', document.createElement('div')); }
                    else if (e.code === 'ArrowLeft') { e.preventDefault(); sendQuiet('/api/prev', document.createElement('div')); }
                    else if (e.code === 'ArrowUp') { e.preventDefault(); let s = document.getElementById('volSlider'); s.value = Math.min(100, parseInt(s.value) + 5); changeVolume(s.value); }
                    else if (e.code === 'ArrowDown') { e.preventDefault(); let s = document.getElementById('volSlider'); s.value = Math.max(0, parseInt(s.value) - 5); changeVolume(s.value); }
                });

                function formatTime(ms) {
                    if (isNaN(ms) || ms < 0) return "0:00";
                    let totalSeconds = Math.floor(ms / 1000);
                    let m = Math.floor(totalSeconds / 60);
                    let s = totalSeconds % 60;
                    return m + ":" + (s < 10 ? "0" + s : s);
                }

                function updateProgressUI() {
                    if (!isSeeking && currentDurationMs > 0) {
                        let pct = (currentPosMs / currentDurationMs) * 100;
                        let pBar = document.getElementById('songProgressBar');
                        pBar.value = pct;
                        pBar.style.background = 'linear-gradient(to right, var(--accent-color) ' + pct + '%, rgba(255,255,255,0.1) ' + pct + '%)';
                        document.getElementById('currentTimeText').innerText = formatTime(currentPosMs);
                        document.getElementById('totalTimeText').innerText = formatTime(currentDurationMs);
                    }
                }

                setInterval(() => {
                    const isLocal = document.getElementById('localToggle').checked;
                    const pBtn = document.getElementById('playBtn');
                    const isPlaying = pBtn.classList.contains("pulse-anim"); 
                    if (isPlaying && !isSeeking && currentDurationMs > 0) {
                        if (isLocal) currentPosMs = localAudioPlayer.currentTime * 1000;
                        else currentPosMs += 250; 
                        if (currentPosMs > currentDurationMs) currentPosMs = currentDurationMs;
                        updateProgressUI();
                        
                        if (currentLyricsData.length > 0 && document.getElementById('lyricsFullscreen').style.display === 'flex') {
                            let currSec = currentPosMs / 1000;
                            let activeIdx = -1;
                            for(let i=0; i<currentLyricsData.length; i++) {
                                if (currSec >= currentLyricsData[i].time) activeIdx = i;
                                else break; 
                            }
                            if (activeIdx !== -1) {
                                let oldActive = document.querySelector('.lyric-line.active');
                                let newActive = document.getElementById('lyric_' + activeIdx);
                                if (oldActive !== newActive) {
                                    if(oldActive) oldActive.classList.remove('active');
                                    if(newActive) {
                                        newActive.classList.add('active');
                                        let container = document.getElementById('lyricsContainer');
                                        container.scrollTo({
                                            top: newActive.offsetTop - container.clientHeight / 2 + newActive.clientHeight / 2,
                                            behavior: 'smooth'
                                        });
                                    }
                                }
                            }
                        }
                    }
                }, 250);

                function onSeekPreview(val) {
                    isSeeking = true;
                    let targetMs = (val / 100) * currentDurationMs;
                    document.getElementById('currentTimeText').innerText = formatTime(targetMs);
                    let pBar = document.getElementById('songProgressBar');
                    pBar.style.background = 'linear-gradient(to right, var(--accent-color) ' + val + '%, rgba(255,255,255,0.1) ' + val + '%)';
                }

                function onSeekEnd(val) {
                    let targetMs = Math.floor((val / 100) * currentDurationMs);
                    const isLocal = document.getElementById('localToggle').checked;
                    if (isLocal) localAudioPlayer.currentTime = targetMs / 1000;
                    fetch('/api/sync?pos=' + targetMs); 
                    currentPosMs = targetMs; isSeeking = false;
                }

                function updateSliderColor(el) { el.style.background = 'linear-gradient(to right, var(--accent-color) ' + el.value + '%, rgba(255,255,255,0.1) ' + el.value + '%)'; }

                function extractColor(img) {
                    try {
                        const canvas = document.createElement('canvas'); const ctx = canvas.getContext('2d');
                        canvas.width = 1; canvas.height = 1; ctx.drawImage(img, 0, 0, 1, 1);
                        const [r, g, b] = ctx.getImageData(0, 0, 1, 1).data;
                        const darken = document.body.classList.contains('light-mode') ? 0.95 : 0.35;
                        document.documentElement.style.setProperty('--bg-color', 'rgb(' + Math.floor(r*darken) + ',' + Math.floor(g*darken) + ',' + Math.floor(b*darken) + ')');
                        document.documentElement.style.setProperty('--accent-color', 'rgb(' + r + ',' + g + ',' + b + ')');
                        
                        const isDarkMode = !document.body.classList.contains('light-mode');
                        let multiplier = isDarkMode ? 0.2 : 0.6; 
                        document.documentElement.style.setProperty('--lyrics-overlay-tint', 'rgba(' + Math.floor(r*multiplier) + ',' + Math.floor(g*multiplier) + ',' + Math.floor(b*multiplier) + ', 0.7)');

                    } catch(e) {}
                }

                function toggleSettingsMenu() { document.getElementById("settingsMenu").classList.toggle("show"); }
                function toggleTheme() { localStorage.setItem('lightTheme', document.getElementById('themeToggle').checked); location.reload(); }
                function toggleSnow() { localStorage.setItem('snowEffect', document.getElementById('snowToggle').checked); location.reload(); }
                function toggleSpin() { localStorage.setItem('spinEffect', document.getElementById('spinToggle').checked); location.reload(); }
                
                function toggleLocal() { 
                    const isLocal = document.getElementById('localToggle').checked;
                    localStorage.setItem('localMode', isLocal); 
                    
                    if (isLocal) {
                        initAudioFX(); 
                        if(audioCtx.state === 'suspended') audioCtx.resume();
                        document.getElementById('eqMenuBtn').style.display = 'flex';

                        let savedVol = localStorage.getItem('pcVolume') || '50';
                        document.getElementById('volSlider').value = savedVol; updateSliderColor(document.getElementById('volSlider'));
                        localAudioPlayer.volume = savedVol / 100.0;
                        checkPlayerStatus(); 
                    } else {
                        document.getElementById('eqMenuBtn').style.display = 'none';
                        let pcTime = Math.floor(localAudioPlayer.currentTime * 1000);
                        localAudioPlayer.pause(); currentLocalStreamId = -1; 
                        
                        fetch('/api/sync?pos=' + pcTime).then(() => {
                            fetch('/api/forceresume'); 
                            fetch('/api/getvolume').then(r => r.text()).then(v => {
                                document.getElementById('volSlider').value = v; updateSliderColor(document.getElementById('volSlider'));
                            });
                        });
                    }
                }

                function togglePlay() {
                    const isLocal = document.getElementById('localToggle').checked;
                    if (isLocal) {
                        if(!isAudioFxInit) initAudioFX();
                        if(audioCtx && audioCtx.state === 'suspended') audioCtx.resume();
                        if (localAudioPlayer.paused) localAudioPlayer.play().catch(e=>{});
                        else localAudioPlayer.pause();
                        checkPlayerStatus(); 
                    } else { fetch('/api/playpause'); }
                }

                function sendQuiet(p, btn) { fetch(p); let old = btn.style.opacity; let oldC = btn.style.color; btn.style.opacity = '1'; btn.style.color = 'var(--accent-color)'; setTimeout(() => { btn.style.opacity = old; btn.style.color = oldC; }, 300); }
                function send(p) { fetch(p); } 
                
                function changeVolume(val) { 
                    updateSliderColor(document.getElementById('volSlider')); 
                    const isLocal = document.getElementById('localToggle').checked;
                    if(isLocal) { localAudioPlayer.volume = val / 100.0; localStorage.setItem('pcVolume', val); } 
                    else { fetch('/api/volume?level=' + val); }
                }
                
                function createSnowflake() { 
                    const s = document.createElement('div'); 
                    s.innerHTML = '❄'; 
                    s.className = 'snowflake'; 
                    s.style.left = (Math.random() * 100) + 'vw'; 
                    s.style.animationDuration = (Math.random() * 3 + 2) + 's'; 
                    s.style.opacity = Math.random() * 0.8 + 0.2;
                    s.style.fontSize = (Math.random() * 10 + 10) + 'px';
                    s.style.setProperty('--drift', (Math.random() * 20 - 10) + 'vw');
                    document.body.appendChild(s); 
                    setTimeout(() => s.remove(), 5000); 
                }
                function startSnow() { if(!snowInterval) snowInterval = setInterval(createSnowflake, 150); }
                function stopSnow() { clearInterval(snowInterval); snowInterval = null; document.querySelectorAll('.snowflake').forEach(e => e.remove()); }

                function handleVisitorFiles(input) {
                    const list = document.getElementById('visitorList'); const bBtn = document.getElementById('batchBtn');
                    list.innerHTML = ""; vFiles = Array.from(input.files).filter(f => f.type.startsWith('audio/'));
                    if(vFiles.length > 0) { bBtn.style.display = "block"; bBtn.innerHTML = vFiles.length + " Şarkıyı Kuyruğa Ekle"; }
                    vFiles.forEach((f, i) => {
                        const item = document.createElement('div'); item.className = 'song-item';
                        item.innerHTML = '<div style="text-align:left; flex:1;"><b>' + f.name.split('.')[0] + '</b></div><span id="vs_' + i + '" style="font-size:0.8rem; opacity:0.6;">Bekliyor...</span>';
                        list.appendChild(item);
                    });
                }

                async function uploadAll() {
                    const btn = document.getElementById('batchBtn'); btn.disabled = true; btn.innerHTML = "Yükleniyor... 🎶";
                    for (let i = 0; i < vFiles.length; i++) {
                        const st = document.getElementById('vs_' + i); st.innerHTML = "⏳";
                        const fd = new FormData(); fd.append("file", vFiles[i], vFiles[i].name);
                        const forceQueue = (i === 0) ? "false" : "true"; 
                        await fetch('/api/upload?queueOnly=' + forceQueue, { method: 'POST', body: fd });
                        st.innerHTML = "✅";
                    }
                    setTimeout(() => location.reload(), 1200);
                }

                function checkPlayerStatus() {
                    fetch('/api/status').then(r => r.json()).then(data => {
                        const titleEl = document.getElementById('sn');
                        const art = document.getElementById('albumArtContainer');
                        const pBtn = document.getElementById('playBtn');
                        const visRing = document.getElementById('visRing');
                        const isSpin = localStorage.getItem('spinEffect') !== 'false';
                        const isLocal = document.getElementById('localToggle').checked; 
                        
                        const currentId = titleEl.getAttribute('data-id');
                        if (currentId && currentId !== data.songId.toString() && data.songId !== -1) { location.reload(); return; }

                        if (data.duration) currentDurationMs = data.duration;
                        if (!isLocal && !isSeeking) { if (Math.abs(currentPosMs - data.position) > 2000) currentPosMs = data.position; }

                        if (isLocal) {
                            if (data.isPlaying) fetch('/api/forcepause'); 
                            if (data.songId !== -1 && currentLocalStreamId !== data.songId) {
                                localAudioPlayer.src = '/api/stream?v=' + data.songId; 
                                currentLocalStreamId = data.songId;
                                localAudioPlayer.onloadedmetadata = () => { 
                                    localAudioPlayer.currentTime = data.position / 1000; 
                                    if(!isAudioFxInit) initAudioFX(); 
                                    localAudioPlayer.play().then(() => { if(audioCtx && audioCtx.state === 'suspended') audioCtx.resume(); }).catch(e=>{}); 
                                };
                            }
                            if (localAudioPlayer.paused) {
                                pBtn.innerHTML = P_ICON; art.classList.remove("playing-spin"); pBtn.classList.remove("pulse-anim");
                                visRing.style.opacity = '0'; 
                            } else {
                                pBtn.innerHTML = S_ICON; pBtn.classList.add("pulse-anim");
                                if(isSpin) { art.classList.add("playing-spin"); visRing.classList.add("spin-mode"); } else { art.classList.remove("playing-spin"); visRing.classList.remove("spin-mode"); }
                                visRing.style.opacity = '1'; 
                                fetch('/api/sync?pos=' + Math.floor(localAudioPlayer.currentTime * 1000));
                            }
                        } else {
                            if(data.title === "Müzik Durduruldu" || !data.isPlaying) {
                                pBtn.innerHTML = P_ICON; art.classList.remove("playing-spin"); pBtn.classList.remove("pulse-anim");
                                visRing.style.opacity = '0'; 
                            } else {
                                pBtn.innerHTML = S_ICON; pBtn.classList.add("pulse-anim");
                                if(isSpin) { art.classList.add("playing-spin"); visRing.classList.add("spin-mode"); } else { art.classList.remove("playing-spin"); visRing.classList.remove("spin-mode"); }
                                visRing.style.opacity = '1'; 
                            }
                        }
                    }).catch(e => {});
                }

                setInterval(checkPlayerStatus, 3000);
                
                function searchYT() {
                    var q = document.getElementById('ytQ').value; if(!q) return;
                    fetch('/api/searchyt?q=' + encodeURIComponent(q)).then(r => r.json()).then(d => {
                        var h = ""; d.items.forEach(s => h += '<div class="song-item"><div style="display:flex; align-items:center;"><img src="' + s.thumbnail + '" style="width:45px;height:45px;border-radius:8px;margin-right:12px;"><b>' + s.title + '</b></div><button class="add-btn" style="background:#ff0000; color:white;" onclick="playYT(\'' + s.videoId + '\', \'' + s.title.replace(/'/g, "\\'") + '\', \'' + s.thumbnail + '\', this)">▶</button></div>');
                        document.getElementById('list').innerHTML = h;
                    });
                }
                
                function playYT(id, t, thumb, btn) { 
                    let qMode = document.getElementById('queueToggle').checked;
                    btn.innerHTML = '<div class="neon-spinner"></div>'; btn.style.width = '50px';
                    fetch('/api/ytdl?videoId=' + id + '&title=' + encodeURIComponent(t) + '&thumbnail=' + encodeURIComponent(thumb) + '&queueOnly=' + qMode); 
                    let poll = setInterval(() => {
                        fetch('/api/progress?videoId=' + id).then(r => r.text()).then(prog => {
                            let p = parseInt(prog);
                            if(p === -1 || p === 100) { clearInterval(poll); setTimeout(() => { btn.innerHTML = "✅"; }, 1000); refreshQueue(); }
                        });
                    }, 500); 
                }
                
                window.onload = function() {
                    initSettings(); refreshQueue();
                    fetch('/api/localsongs').then(r => r.json()).then(d => { 
                        var h = ""; d.slice(0,50).forEach(s => h += '<div class="song-item"><div style="text-align:left; flex:1; cursor:pointer;" onclick="addToQueue(' + s.id + ', this, true)"><b>' + s.title + '</b><br><small style="opacity:0.6">' + s.artist + '</small></div><button class="add-btn" style="background:rgba(255,255,255,0.1); color:var(--text-color);" onclick="addToQueue(' + s.id + ', this, false)">➕</button></div>');
                        document.getElementById('localList').innerHTML = h;
                    });
                    checkPlayerStatus();
                };
                
                function addToQueue(id, btn, forcePlay) { 
                    let qMode = document.getElementById('queueToggle').checked;
                    let clear = forcePlay ? true : !qMode;
                    if(!forcePlay) btn.innerText = "✅"; 
                    fetch('/api/addtoqueue?id=' + id + (clear ? '&clear=true' : '')).then(() => { 
                        setTimeout(refreshQueue, 400); 
                        if(!forcePlay) setTimeout(() => { btn.innerText = "➕"; }, 2000); 
                    }); 
                }
            </script>
            </head><body>
                
                <div id="lyricsFullscreen" class="lyrics-fullscreen">
                    <div class="lyrics-overlay"></div>
                    <span class="close-lyrics-fullscreen" onclick="document.getElementById('lyricsFullscreen').style.display='none'">&times;</span>
                    <div id="lyricsContainer" class="lyrics-container"></div>
                </div>

                <div id="eqModal" class="modal-eq">
                    <div class="modal-content-eq">
                        <div class="analog-faceplate">
                            <span class="analog-close-btn" onclick="document.getElementById('eqModal').style.display='none'">&times;</span>
                            <h2 style="margin-top:0; margin-bottom: 20px; color:var(--text-color); text-shadow: 0 2px 4px rgba(0,0,0,0.5);">🎛️ Stereophonic Mastering EQ</h2>
                            
                            <div class="vu-container">
                                <div class="vu-meter" id="vuMeter_L">
                                    <div class="vu-scale">
                                        <div class="vu-scale-text" style="left:5%; bottom:20px;">-20</div>
                                        <div class="vu-scale-text" style="left:30%; bottom:50px;">-7</div>
                                        <div class="vu-scale-text" style="left:50%; bottom:60px; color:red;">0</div>
                                        <div class="vu-scale-text" style="right:5%; bottom:20px; color:red;">+3</div>
                                    </div>
                                    <div class="vu-needle" id="vuNeedle_L"></div>
                                    <div class="vu-cover"></div>
                                    <div class="vu-label">LEFT</div>
                                    <div class="peak-led" id="peakLed_L" title="sol peak"></div>
                                </div>
                                <div class="vu-meter" id="vuMeter_R">
                                    <div class="vu-scale">
                                        <div class="vu-scale-text" style="left:5%; bottom:20px;">-20</div>
                                        <div class="vu-scale-text" style="left:30%; bottom:50px;">-7</div>
                                        <div class="vu-scale-text" style="left:50%; bottom:60px; color:red;">0</div>
                                        <div class="vu-scale-text" style="right:5%; bottom:20px; color:red;">+3</div>
                                    </div>
                                    <div class="vu-needle" id="vuNeedle_R"></div>
                                    <div class="vu-cover"></div>
                                    <div class="vu-label">RIGHT</div>
                                    <div class="peak-led" id="peakLed_R" title="sag peak"></div>
                                </div>
                            </div>

                            <select id="presetSelect" class="preset-select" onchange="applyPreset(this.value)">
                                <option value="custom">Manual (Custom)</option>
                                <option value="flat">Mastering (Flat)</option>
                                <option value="bass">Warm Bass 🔥</option>
                                <option value="rock">Rock Club 🎸</option>
                                <option value="electronic">Techno Synth ⚡</option>
                                <option value="vocal">Radio Vocal 🎤</option>
                                <option value="acoustic">Acoustic Room 🎻</option>
                            </select>

                            <div class="eq-bands-container">
                                <div class="eq-mastering-group">
                                    <div class="knob-container"><span class="knob-label">32 Hz</span><div class="knob-control-wrapper" onmousedown="onKnobStart(event, 0)"><div class="knob-ticks"></div><div class="knob-face" id="knob_face_0"><div class="knob-point"></div></div></div><span class="knob-val" id="eq_val_0">0 <span>dB</span></span></div>
                                    <div class="knob-container"><span class="knob-label">64 Hz</span><div class="knob-control-wrapper" onmousedown="onKnobStart(event, 1)"><div class="knob-ticks"></div><div class="knob-face" id="knob_face_1"><div class="knob-point"></div></div></div><span class="knob-val" id="eq_val_1">0 <span>dB</span></span></div>
                                    <div class="knob-container"><span class="knob-label">125 Hz</span><div class="knob-control-wrapper" onmousedown="onKnobStart(event, 2)"><div class="knob-ticks"></div><div class="knob-face" id="knob_face_2"><div class="knob-point"></div></div></div><span class="knob-val" id="eq_val_2">0 <span>dB</span></span></div>
                                    <div class="knob-container"><span class="knob-label">250 Hz</span><div class="knob-control-wrapper" onmousedown="onKnobStart(event, 3)"><div class="knob-ticks"></div><div class="knob-face" id="knob_face_3"><div class="knob-point"></div></div></div><span class="knob-val" id="eq_val_3">0 <span>dB</span></span></div>
                                    <div class="knob-container"><span class="knob-label">500 Hz</span><div class="knob-control-wrapper" onmousedown="onKnobStart(event, 4)"><div class="knob-ticks"></div><div class="knob-face" id="knob_face_4"><div class="knob-point"></div></div></div><span class="knob-val" id="eq_val_4">0 <span>dB</span></span></div>
                                    <div class="knob-container"><span class="knob-label">1 kHz</span><div class="knob-control-wrapper" onmousedown="onKnobStart(event, 5)"><div class="knob-ticks"></div><div class="knob-face" id="knob_face_5"><div class="knob-point"></div></div></div><span class="knob-val" id="eq_val_5">0 <span>dB</span></span></div>
                                    <div class="knob-container"><span class="knob-label">2 kHz</span><div class="knob-control-wrapper" onmousedown="onKnobStart(event, 6)"><div class="knob-ticks"></div><div class="knob-face" id="knob_face_6"><div class="knob-point"></div></div></div><span class="knob-val" id="eq_val_6">0 <span>dB</span></span></div>
                                    <div class="knob-container"><span class="knob-label">4 kHz</span><div class="knob-control-wrapper" onmousedown="onKnobStart(event, 7)"><div class="knob-ticks"></div><div class="knob-face" id="knob_face_7"><div class="knob-point"></div></div></div><span class="knob-val" id="eq_val_7">0 <span>dB</span></span></div>
                                    <div class="knob-container"><span class="knob-label">8 kHz</span><div class="knob-control-wrapper" onmousedown="onKnobStart(event, 8)"><div class="knob-ticks"></div><div class="knob-face" id="knob_face_8"><div class="knob-point"></div></div></div><span class="knob-val" id="eq_val_8">0 <span>dB</span></span></div>
                                    <div class="knob-container"><span class="knob-label">16 kHz</span><div class="knob-control-wrapper" onmousedown="onKnobStart(event, 9)"><div class="knob-ticks"></div><div class="knob-face" id="knob_face_9"><div class="knob-point"></div></div></div><span class="knob-val" id="eq_val_9">0 <span>dB</span></span></div>
                                </div>
                            </div>

                            <div style="padding: 10px 20px; background: rgba(0,0,0,0.2); border-radius: 10px; margin-top: 15px; border: 1px inset rgba(255,255,255,0.05);">
                                <div style="display:flex; justify-content:space-between; margin-bottom:5px; font-size:0.8rem; font-weight:bold; color:var(--text-color); opacity: 0.9;">
                                    <span>🏟️ Yankı Yoğunluğu (Reverb Level)</span>
                                    <span id="reverbLevelVal">50%</span>
                                </div>
                                <input type="range" id="reverbSlider" min="0" max="100" value="50" class="horizontal-range" oninput="changeReverbLevel(this.value)">
                            </div>

                            <div style="display:flex; justify-content:space-around; margin-top:20px; padding-top:15px; border-top:1px solid rgba(0,0,0,0.1);">
                                <label class="setting-label" style="width:auto; gap:10px; color:var(--text-color);"><input type="checkbox" id="8dToggle" onchange="toggle8D()"> 🎧 8D Simulation</label>
                                <label class="setting-label" style="width:auto; gap:10px; color:var(--text-color);"><input type="checkbox" id="reverbToggle" onchange="toggleReverb()"> 🏟️ Concert Hall (Active)</label>
                            </div>
                        </div>
                    </div>
                </div>

                <div class="header-logo"><img src="/api/logo" alt="Pulse Jukebox Logo"></div>

                <div class="settings-menu">
                    <button class="settings-btn" onclick="toggleSettingsMenu()">
                        <img src="/api/logo" class="btn-logo"> VIP Panel
                    </button>
                    
                    <div class="dropdown-content" id="settingsMenu">
                        <div class="setting-item"><label class="setting-label"><div class="label-left"><svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"></path></svg>Aydınlık Mod</div><input type="checkbox" id="themeToggle" onchange="toggleTheme()"></label></div>
                        <div class="setting-item"><label class="setting-label"><div class="label-left"><svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2v20M17 4l-5 5-5-5M17 20l-5-5-5 5M2 12h20M4 7l5 5-5 5M20 7l-5 5 5 5"></path></svg>Kar Efekti</div><input type="checkbox" id="snowToggle" onchange="toggleSnow()"></label></div>
                        <div class="setting-item"><label class="setting-label"><div class="label-left"><svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><circle cx="12" cy="12" r="3"></circle></svg>Plak Modu</div><input type="checkbox" id="spinToggle" onchange="toggleSpin()"></label></div>
                        
                        <div class="setting-item" style="border-top:1px solid rgba(255,255,255,0.1); padding-top:15px; margin-top:5px;">
                            <label class="setting-label"><div class="label-left"><svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 18v-6a9 9 0 0 1 18 0v6"></path><path d="M21 19a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2h3zM3 19a2 2 0 0 0 2 2h1a2 2 0 0 0 2-2v-3a2 2 0 0 0-2-2H3z"></path></svg>Bu Cihazda Çal</div><input type="checkbox" id="localToggle" onchange="toggleLocal()"></label>
                            <div class="setting-desc">Müziği teypten değil, bilgisayar/telefon üzerinden dinle.</div>
                        </div>

                        <div class="setting-item">
                            <label class="setting-label"><div class="label-left"><svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01"></path></svg>Kuyruk Modu</div><input type="checkbox" id="queueToggle" onchange="toggleQueue()"></label>
                            <div class="setting-desc">Tıklanan şarkıyı anında çalmak yerine sıraya ekler.</div>
                        </div>

                        <button id="eqMenuBtn" class="chip-btn" style="width:100%; justify-content:center; margin-top:10px; display:none;" onclick="document.getElementById('eqModal').style.display='block'; toggleSettingsMenu();">
                            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="4" y1="21" x2="4" y2="14"></line><line x1="4" y1="10" x2="4" y2="3"></line><line x1="12" y1="21" x2="12" y2="12"></line><line x1="12" y1="8" x2="12" y2="3"></line><line x1="20" y1="21" x2="20" y2="16"></line><line x1="20" y1="12" x2="20" y2="3"></line><line x1="1" y1="14" x2="7" y2="14"></line><line x1="9" y1="8" x2="15" y2="8"></line><line x1="17" y1="16" x2="23" y2="16"></line></svg> Analog Mastering EQ
                        </button>

                        <div class="setting-item" style="border-top:1px solid rgba(255,255,255,0.1); padding-top:15px; margin-top:10px;">
                            <label class="setting-label" style="cursor:default;"><div class="label-left"><svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 14.899A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.5 8.242M12 12v9"></path><path d="M8 17l4 4 4-4"></path></svg>Ana Ses Kumandası</div></label>
                            <div class="vol-container">
                                <svg class="vol-icon" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"></polygon></svg>
                                <input type="range" class="horizontal-range" id="volSlider" min="0" max="100" oninput="changeVolume(this.value)">
                                <svg class="vol-icon" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"></polygon><path d="M19.07 4.93a10 10 0 0 1 0 14.14M15.54 8.46a5 5 0 0 1 0 7.07"></path></svg>
                            </div>
                        </div>
                    </div>
                </div>
                
                <div class="album-wrapper" id="albumWrapper">
                    <div class="visualizer-ring" id="visRing"></div>
                    <div class="album-art" id="albumArtContainer"><img id="coverImg" src="/api/albumart?v=${System.currentTimeMillis()}" crossorigin="anonymous"></div>
                </div>
                
                <h2 id="sn" data-id="${info.songId}" style="font-size:1.4rem; margin:10px 0">${info.title}</h2>
                <div id="artistName" style="color:var(--accent-color); font-weight:bold; margin-bottom:15px; transition:0.8s;">${info.artist}</div>
                
                <div class="action-btns">
                    <button class="chip-btn" onclick="fetchLyrics()"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2c5.523 0 10 4.477 10 10a9.99 9.99 0 0 1-2.122 6.128L22 22l-3.878-2.122A9.99 9.99 0 1 1 12 2z"></path><path d="M8 10h8"></path><path d="M8 14h4"></path></svg> Sözler</button>
                    <button class="chip-btn" onclick="downloadCurrentSong()"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="7 10 12 15 17 10"></polyline><line x1="12" y1="15" x2="12" y2="3"></line></svg> İndir</button>
                </div>

                <div class="progress-container">
                    <span id="currentTimeText">0:00</span>
                    <input type="range" class="horizontal-range" id="songProgressBar" min="0" max="100" value="0" step="0.1" oninput="onSeekPreview(this.value)" onchange="onSeekEnd(this.value)">
                    <span id="totalTimeText">0:00</span>
                </div>
                
                <div style="margin-bottom: 30px; display:flex; justify-content:center; align-items:center; gap: 8px;">
                    <button class="btn btn-small" onclick="sendQuiet('/api/shuffle', this)" title="Karıştır"><svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor"><path d="M10.59 9.17L5.41 4 4 5.41l5.17 5.17 1.42-1.41zM14.5 4l2.04 2.04L4 18.59 5.41 20 17.96 7.46 20 9.5V4h-5.5zm.33 9.41l-1.41 1.41 3.13 3.13L14.5 20H20v-5.5l-2.04 2.04-3.13-3.13z"/></svg></button>
                    <button class="btn" onclick="send('/api/prev')"><svg viewBox="0 0 24 24" width="28" height="28" fill="currentColor"><path d="M6 6h2v12H6zm3.5 6l8.5 6V6z"/></svg></button>
                    <button class="btn play" id="playBtn" onclick="togglePlay()"></button>
                    <button class="btn" onclick="send('/api/next')"><svg viewBox="0 0 24 24" width="28" height="28" fill="currentColor"><path d="M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z"/></svg></button>
                    <button class="btn btn-small" onclick="sendQuiet('/api/repeat', this)" title="Tekrarla"><svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor"><path d="M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4z"/></svg></button>
                </div>

                <div class="box visitor-box">
                    <h4 style="color:#E91E63;"><svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="5" y="2" width="14" height="20" rx="2" ry="2"></rect><line x1="12" y1="18" x2="12.01" y2="18"></line></svg> Telefonundan Toplu Şarkı Ekle</h4>
                    <button class="scan-btn" onclick="document.getElementById('visitorFile').click()">➕ Şarkıları Seç</button>
                    <input type="file" id="visitorFile" multiple accept="audio/*" onchange="handleVisitorFiles(this)">
                    <button id="batchBtn" class="batch-btn" onclick="uploadAll()"></button>
                    <div id="visitorList" class="scroll-list"></div>
                </div>
                
                <div class="box">
                    <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:15px;">
                        <h4 style="margin:0;"><svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="8" y1="6" x2="21" y2="6"></line><line x1="8" y1="12" x2="21" y2="12"></line><line x1="8" y1="18" x2="21" y2="18"></line><line x1="3" y1="6" x2="3.01" y2="6"></line><line x1="3" y1="12" x2="3.01" y2="12"></line><line x1="3" y1="18" x2="3.01" y2="18"></line></svg> Sıradaki Şarkılar</h4>
                        <button class="add-btn" style="background:var(--accent-color); color:black;" onclick="saveCurrentAsPlaylist()">💾 Listeyi Kaydet</button>
                    </div>
                    <div id="queueList" class="scroll-list"></div>
                </div>

                <div class="box" style="border-left: 5px solid var(--accent-color);">
                    <h4 style="color:var(--accent-color);"><svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="12 2 2 7 12 12 22 7 12 2"></polygon><polyline points="2 17 12 22 22 17"></polyline><polyline points="2 12 12 17 22 12"></polyline></svg> Özel Listelerim</h4>
                    <div id="customPlaylists" class="scroll-list"></div>
                </div>

                <div class="box" style="border-left: 5px solid #ff0000;">
                    <h4 style="color:#ff0000;"><svg viewBox="0 0 24 24" width="24" height="24" fill="currentColor"><path d="M21.582,6.186c-0.23-0.86-0.908-1.538-1.768-1.768C18.254,4,12,4,12,4S5.746,4,4.186,4.418c-0.86,0.23-1.538,0.908-1.768,1.768C2,7.746,2,12,2,12s0,4.254,0.418,5.814c0.23,0.86,0.908,1.538,1.768,1.768C5.746,20,12,20,12,20s6.254,0,7.814-0.418c0.86-0.23,1.538-0.908,1.768-1.768C22,16.254,22,12,22,12S22,7.746,21.582,6.186z M10,15.464V8.536L16,12L10,15.464z"/></svg> YouTube'dan Çal</h4>
                    <div class="search-container"><input type="text" id="ytQ" class="search-input" placeholder="Ara..." onkeyup="if(event.key==='Enter') searchYT()"><button class="add-btn" style="background:#ff0000; color:white;" onclick="searchYT()">Ara</button></div>
                    <div id="list"></div>
                </div>

                <div class="box" style="border-left: 5px solid #2196F3;">
                    <h4 style="color:#2196F3;"><svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"></path></svg> Ana Cihaz Arşivi</h4>
                    <div id="localList" class="scroll-list"></div>
                </div>
            </body></html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun serveLogoResource(): Response {
        return try {
            var resId = context.resources.getIdentifier("ic_splash", "drawable", context.packageName)
            if (resId == 0) resId = context.resources.getIdentifier("ic_splash", "mipmap", context.packageName)
            val logoBitmap = android.graphics.BitmapFactory.decodeResource(context.resources, resId)
            val baos = ByteArrayOutputStream()
            logoBitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
            val res = newFixedLengthResponse(Response.Status.OK, "image/png", ByteArrayInputStream(baos.toByteArray()), baos.size().toLong())
            res.addHeader("Access-Control-Allow-Origin", "*"); res
        } catch (e: Exception) { newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Yok") }
    }
}