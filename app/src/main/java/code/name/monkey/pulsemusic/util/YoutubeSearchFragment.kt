package code.name.monkey.pulsemusic.fragments

import android.content.ContentUris
import android.content.ContentValues
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import code.name.monkey.appthemehelper.ThemeStore
import android.content.res.ColorStateList
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import code.name.monkey.pulsemusic.R
import code.name.monkey.pulsemusic.databinding.FragmentYoutubeSearchBinding
import code.name.monkey.pulsemusic.repository.RealSongRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.net.URLEncoder

// 🔥 Metadata ve Kapak resmi için gerekli kütüphaneler
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory

class YoutubeSearchFragment : Fragment(R.layout.fragment_youtube_search) {

    private var _binding: FragmentYoutubeSearchBinding? = null
    private val binding get() = _binding!!
    private val youtubeResults = mutableListOf<JSONObject>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        _binding = FragmentYoutubeSearchBinding.bind(view)

        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        val accentColor = ThemeStore.accentColor(requireContext())
        val dimmedColor = (accentColor and 0x00FFFFFF) or 0x33000000 // %20 opaklık
// 🎨 Renkleri temaya bağla
        binding.searchButton.backgroundTintList = ColorStateList.valueOf(dimmedColor)
        binding.searchIcon.setColorFilter(accentColor)
        binding.searchIcon.alpha = 0.7f
        binding.searchButton.setTextColor(accentColor)
        binding.progressBar.setIndicatorColor(accentColor)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            binding.searchEditText.textCursorDrawable?.setTint(accentColor)
        }
        binding.searchButton.setOnClickListener {
            val query = binding.searchEditText.text.toString().trim()
            if (query.isNotEmpty()) searchOnYoutube(query)
            else Toast.makeText(context, "Bir arama terimi girin", Toast.LENGTH_SHORT).show()
        }
    }

    private fun searchOnYoutube(query: String) {
        binding.emptyState.visibility = View.GONE
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.visibility = View.VISIBLE
        youtubeResults.clear()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val apiUrl = "https://www.youtube.com/results?search_query=$encodedQuery"

                val connection = URL(apiUrl).openConnection().apply {
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    connectTimeout = 15000
                    readTimeout = 15000
                }

                val html = connection.getInputStream().bufferedReader().readText()
                val pattern = Regex("""\"videoRenderer\":\{\"videoId\":\"([^\"]+)\".*?\"title\":\{\"runs\":\[\{\"text\":\"([^\"]+)\"\}""")
                val matches = pattern.findAll(html).take(15).toList()

                matches.forEach { match ->
                    val videoId = match.groupValues[1]
                    val title = match.groupValues[2]

                    val jsonObj = JSONObject().apply {
                        put("videoId", videoId)
                        put("title", title)
                        put("thumbnail", "https://i.ytimg.com/vi/$videoId/mqdefault.jpg")
                    }
                    youtubeResults.add(jsonObj)
                }

                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.recyclerView.adapter = YoutubeAdapter(youtubeResults) { id, t, thumb ->
                        downloadAndPlay(id, t, thumb) // 🔥 Thumbnail URL artık gidiyor
                    }
                }
            } catch (e: Exception) {
                Log.e("YT_SEARCH", "Arama hatası", e)
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(context, "Bağlantı hatası!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun downloadAndPlay(videoId: String, title: String, thumbnailUrl: String) {
        // 🚨 ÇÖKME KORUMASI 1: Fragment'tan bağımsız ApplicationContext'i alıyoruz.
        // Bu, sen geri bassan da uygulama kapanmadığı sürece ASLA null olmaz.
        val appContext = context?.applicationContext ?: return

        Toast.makeText(appContext, "İndirme başlatıldı...", Toast.LENGTH_SHORT).show()

        // 🚨 ÇÖKME KORUMASI 2: lifecycleScope yerine CoroutineScope(Dispatchers.IO) kullanıyoruz.
        // lifecycleScope geri tuşuna basınca iptal olur ve crash yapabilir.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val tempAudio = File(appContext.cacheDir, "YT_$videoId.m4a")
            val tempThumb = File(appContext.cacheDir, "THUMB_$videoId.jpg")

            try {
                // Motoru her ihtimale karşı hazırla
                try { com.yausername.youtubedl_android.YoutubeDL.getInstance().init(appContext) } catch (e: Exception) {}

                if (tempAudio.exists()) tempAudio.delete()

                val request = com.yausername.youtubedl_android.YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId")
                request.addOption("-f", "ba[ext=m4a]/bestaudio/best")
                request.addOption("--extractor-args", "youtube:player_client=android_vr,web_creator")
                request.addOption("-o", tempAudio.absolutePath)

                // İndirme başlatılıyor (Logcat'e zorla log yazdırıyoruz)
                android.util.Log.w("PULSE_MOTOR", "İndirme Başladı: $videoId")
                com.yausername.youtubedl_android.YoutubeDL.getInstance().execute(request, null)

                // Kapak resmini çek
                try {
                    java.net.URL(thumbnailUrl).openStream().use { input ->
                        tempThumb.outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (e: Exception) { android.util.Log.e("PULSE", "Resim hatası") }

                // Metadata ve kapak mühürleme (Fragment'tan bağımsız)
                if (tempAudio.exists()) {
                    try {
                        val audioFile = AudioFileIO.read(tempAudio)
                        val tag = audioFile.tag ?: audioFile.createDefaultTag()
                        tag.setField(FieldKey.TITLE, title)

                        // 🎨 BURAYI DEĞİŞTİR: "Pulse Jukebox" yerine başlığın ilk kısmını (Sanatçıyı) yazalım
                        val artist = if (title.contains("-")) title.split("-")[0].trim() else "YouTube"
                        tag.setField(FieldKey.ARTIST, artist)

                        if (tempThumb.exists()) {
                            val artwork = ArtworkFactory.createArtworkFromFile(tempThumb)
                            tag.setField(artwork)
                        }
                        audioFile.commit()
                    } catch (e: Exception) { Log.e("PULSE", "Tagleme hatası") }
                }

                // Kütüphaneye kaydetme sürecine geç (Tamamen bağımsız Context ile)
                if (tempAudio.exists() && tempAudio.length() > 50000) {
                    saveToMediaStore(appContext, tempAudio, title)
                }

            } catch (e: Exception) {
                android.util.Log.e("PULSE_CRASH", "KRİTİK HATA: ${e.message}")
            } finally {
                if (tempThumb.exists()) tempThumb.delete()
            }
        }
    }

    private suspend fun saveToMediaStore(appContext: android.content.Context, tempAudio: File, title: String) {
        try {
            val cleanFileName = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$cleanFileName.m4a")
                put(MediaStore.Audio.Media.TITLE, title)
                put(MediaStore.Audio.Media.ARTIST, "Pulse Jukebox")
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/m4a")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/PulseJukebox")
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
            }

            val uri = appContext.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let { contentUri ->
                appContext.contentResolver.openOutputStream(contentUri)?.use { out ->
                    tempAudio.inputStream().use { input -> input.copyTo(out) }
                }
                tempAudio.delete()

                // 🚨 UI İŞLEMLERİ: Sadece burada UI Thread'e geçiyoruz ama Fragment kontrolü yapıyoruz.
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(appContext, "İndirme tamamlandı", Toast.LENGTH_SHORT).show()

                    // Eğer kullanıcı geri basmadıysa ve hala o ekrandaysa listeye ekle
                    if (isAdded && _binding != null) {
                        try {
                            val song = RealSongRepository(appContext).song(ContentUris.parseId(contentUri))
                            if (song.id != -1L) code.name.monkey.pulsemusic.helper.MusicPlayerRemote.enqueue(song)
                        } catch (e: Exception) {
                            android.util.Log.e("PULSE", "Oynatma hatası atlandı")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MEDIASTORE", "Kayıt hatası: ${e.message}")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}