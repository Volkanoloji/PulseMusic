package code.name.monkey.pulsemusic.activities

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import androidx.navigation.fragment.NavHostFragment
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.navigation.contains
import code.name.monkey.pulsemusic.R
import code.name.monkey.pulsemusic.activities.base.AbsCastActivity
import code.name.monkey.pulsemusic.extensions.*
import code.name.monkey.pulsemusic.helper.MusicPlayerRemote
import code.name.monkey.pulsemusic.helper.SearchQueryHelper.getSongs
import code.name.monkey.pulsemusic.interfaces.IScrollHelper
import code.name.monkey.pulsemusic.model.CategoryInfo
import code.name.monkey.pulsemusic.model.Song
import code.name.monkey.pulsemusic.repository.PlaylistSongsLoader
import code.name.monkey.pulsemusic.service.MusicService
import code.name.monkey.pulsemusic.util.AppRater
import code.name.monkey.pulsemusic.util.JukeboxServer
import code.name.monkey.pulsemusic.util.PreferenceUtil
import code.name.monkey.pulsemusic.util.logE
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.math.sqrt

class MainActivity : AbsCastActivity(), SensorEventListener {

    // --- SENSÖR VE SUNUCU DEĞİŞKENLERİ ---
    private var sensorManager: SensorManager? = null
    private var isPocketModeActive = false
    private var jukeboxServer: JukeboxServer? = null // 🚀 Jukebox Sunucusu

    private var acceleration = 0f
    private var currentAcceleration = 0f
    private var lastAcceleration = 0f

    // Zaman Kilitleri (Spam engelleme)
    private var lastShakeTime: Long = 0
    private var lastFlipTime: Long = 0
    private var lastGestureTime: Long = 0

    companion object {
        const val TAG = "MainActivity"
        const val EXPAND_PANEL = "expand_panel"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTaskDescriptionColorAuto()
        hideStatusBar()
        updateTabs()
        AppRater.appLaunched(this)

        setupNavigationController()

        // 🚀 YT-DLP OTOMATİK GÜNCELLEME MOTORU (SADECE BU EKLENDİ)
        Thread {
            try {
                com.yausername.youtubedl_android.YoutubeDL.getInstance().init(applicationContext)
                android.util.Log.d("PULSE_MOTOR", "⏳ yt-dlp güncellemesi aranıyor...")
                com.yausername.youtubedl_android.YoutubeDL.getInstance().updateYoutubeDL(
                    applicationContext,
                    com.yausername.youtubedl_android.YoutubeDL.UpdateChannel.NIGHTLY
                )
                android.util.Log.d("PULSE_MOTOR", "✅ yt-dlp mermi gibi güncel!")
            } catch (e: Exception) {
                android.util.Log.e("PULSE_MOTOR", "⚠️ Güncelleme atlandı: ${e.message}")
            }
        }.start()

        // SENSÖR VE SUNUCU BAŞLATMA
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        acceleration = 10f
        currentAcceleration = SensorManager.GRAVITY_EARTH
        lastAcceleration = SensorManager.GRAVITY_EARTH

        startJukeboxServer() // 🚀 Jukebox Sunucusunu Ateşle!
        WhatsNewFragment.showChangeLog(this)
    }

    override fun onResume() {
        super.onResume()
        sensorManager?.let { sm ->
            sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_UI)
            sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD), SensorManager.SENSOR_DELAY_UI)
            sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_PROXIMITY), SensorManager.SENSOR_DELAY_UI)

            // Cep Modu Işık Sensörü
            sm.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { lightSensor ->
                sm.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_UI)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        jukeboxServer?.stop() // 🛑 Uygulama kapanırken sunucuyu kapat
    }

    // --- JUKEBOX SUNUCUSU METODLARI ---
    private fun startJukeboxServer() {
        try {
            jukeboxServer?.stop()

            // Context olarak 'this' gönderiyoruz
            jukeboxServer = JukeboxServer(this, 9000) { action ->
                // Dışarıdan gelen Next/Prev komutlarını ana thread'e taşıyoruz
                executeMediaCommand(action, "Jukebox: $action")
            }

            jukeboxServer?.start()

            runOnUiThread {
                val ip = getLocalIpAddress()
                Toast.makeText(this, "🚀 Jukebox Aktif!\nhttp://$ip:9000", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            android.util.Log.e("JUKEBOX", "Sunucu başlatılamadı: ${e.message}")
        }
    }

    // --- TEK VE GERÇEK KOMUT MERKEZİ ---
    private fun executeMediaCommand(action: String, message: String) {
        runOnUiThread {
            try {
                when (action) {
                    "NEXT" -> MusicPlayerRemote.playNextSong()
                    "BACK" -> MusicPlayerRemote.back()
                    "TOGGLE" -> {
                        if (MusicPlayerRemote.isPlaying) {
                            MusicPlayerRemote.pauseSong()
                        } else {
                            MusicPlayerRemote.resumePlaying()
                        }
                    }
                }
                // Komut geldikçe ekranda bilgi verelim
                val status = "Kuyruk: ${MusicPlayerRemote.position + 1} / ${MusicPlayerRemote.playingQueue.size}"
                Toast.makeText(this, "$message\n$status", Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                android.util.Log.e("JUKEBOX_ERROR", "Medya Hatası: " + e.message)
            }
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val ip = address.hostAddress
                        if (ip != null && !ip.startsWith("10.0.2.")) {
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return "IP_BULUNAMADI"
    }

    // --- SENSÖR HANDLER'LARI ---
    override fun onSensorChanged(event: SensorEvent?) {
        val sensorEvent = event ?: return

        when (sensorEvent.sensor.type) {
            Sensor.TYPE_LIGHT -> {
                val lightLevel = sensorEvent.values[0]
                isPocketModeActive = lightLevel < 3.0f
            }
            Sensor.TYPE_PROXIMITY -> {
                if (PreferenceUtil.isAirGestureEnabled) handleAirGesture(sensorEvent)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                if (PreferenceUtil.isShakeToSkipEnabled) handleShakeToSkip(sensorEvent)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                if (PreferenceUtil.isFlipToPauseEnabled) handleFlipToPause(sensorEvent)
            }
        }
    }

    private fun handleShakeToSkip(event: SensorEvent) {
        if (isPocketModeActive) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val currentTime = System.currentTimeMillis()

        lastAcceleration = currentAcceleration
        currentAcceleration = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        val delta = currentAcceleration - lastAcceleration
        acceleration = acceleration * 0.9f + delta

        if (acceleration > 8 && (currentTime - lastShakeTime > 2000)) {
            lastShakeTime = currentTime
            executeMediaCommand("NEXT", "Pulse 🚀: SALLANDI!")
            acceleration = 0f
        }
    }

    private fun handleFlipToPause(event: SensorEvent) {
        val zMagnet = event.values[2]
        val currentTime = System.currentTimeMillis()

        if (zMagnet > 30.0 && (currentTime - lastFlipTime > 2000)) {
            lastFlipTime = currentTime
            executeMediaCommand("TOGGLE", "Pulse 🚀: TERS DÖNDÜ!")
        }
    }

    private fun handleAirGesture(event: SensorEvent) {
        if (isPocketModeActive) return
        val distance = event.values[0]
        val currentTime = System.currentTimeMillis()

        if (distance < 1.0 && (currentTime - lastGestureTime > 1500)) {
            lastGestureTime = currentTime
            executeMediaCommand("NEXT", "Pulse 🚀: EL SALLANDI!")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // --- NAVİGASYON SİSTEMİ (DOKUNULMADI) ---
    private fun setupNavigationController() {
        val navController = findNavController(R.id.fragment_container)
        val navInflater = navController.navInflater
        val navGraph = navInflater.inflate(R.navigation.main_graph)

        val categoryInfo: CategoryInfo = PreferenceUtil.libraryCategory.first { it.visible }
        if (categoryInfo.visible) {
            if (!navGraph.contains(PreferenceUtil.lastTab)) PreferenceUtil.lastTab = categoryInfo.category.id
            navGraph.setStartDestination(
                if (PreferenceUtil.rememberLastTab) {
                    PreferenceUtil.lastTab.let { if (it == 0) categoryInfo.category.id else it }
                } else categoryInfo.category.id
            )
        }
        navController.graph = navGraph

        // 🚨 YENİ VE HATASIZ NAVİGASYON MANTIĞI
        navigationView.setOnItemSelectedListener { item ->
            // Doğrudan ID üzerinden fragment'ı buluyoruz
            val fragment = supportFragmentManager.findFragmentById(R.id.fragment_container)

            // 🛠️ Kontrol: Eğer şu anki ekran NavHostFragment DEĞİLSE (yani YouTube fragmanıysa)
            if (fragment !is NavHostFragment) {
                // Önce YouTube fragmanını kapat
                supportFragmentManager.popBackStackImmediate()

                // Sonra istediğimiz sekmeye git
                androidx.navigation.ui.NavigationUI.onNavDestinationSelected(item, navController)
            } else {
                // Her şey normalse standart navigasyon
                androidx.navigation.ui.NavigationUI.onNavDestinationSelected(item, navController)
            }
            true
        }

        navigationView.setOnItemReselectedListener {
            // Buradaki 'currentFragment' metodunu da garantiye alalım:
            val f = supportFragmentManager.findFragmentById(R.id.fragment_container)
            if (f is NavHostFragment) {
                val current = f.childFragmentManager.fragments.firstOrNull()
                if (current is IScrollHelper) current.scrollToTop()
            }
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            if (destination.id == navGraph.startDestinationId) {
                val f = supportFragmentManager.findFragmentById(R.id.fragment_container)
                f?.enterTransition = null
            }
            when (destination.id) {
                R.id.action_home, R.id.action_song, R.id.action_album, R.id.action_artist, R.id.action_folder, R.id.action_playlist, R.id.action_genre, R.id.action_search -> {
                    if (PreferenceUtil.rememberLastTab) saveTab(destination.id)
                    setBottomNavVisibility(visible = true, animate = true)
                }
                R.id.playing_queue_fragment -> setBottomNavVisibility(visible = false, hideBottomSheet = true)
                else -> setBottomNavVisibility(visible = false, animate = true)
            }
        }
    }

    private fun saveTab(id: Int) {
        if (PreferenceUtil.libraryCategory.firstOrNull { it.category.id == id }?.visible == true) {
            PreferenceUtil.lastTab = id
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val expand = intent?.extra<Boolean>(EXPAND_PANEL)?.value ?: false
        if (expand && PreferenceUtil.isExpandPanel) {
            fromNotification = true
            slidingPanel.bringToFront()
            expandPanel()
            intent?.removeExtra(EXPAND_PANEL)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        intent ?: return
        handlePlaybackIntent(intent)
    }

    private fun handlePlaybackIntent(intent: Intent) {
        lifecycleScope.launch(IO) {
            val uri: Uri? = intent.data
            val mimeType: String? = intent.type
            var handled = false
            if (intent.action != null && intent.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
                val songs: List<Song> = getSongs(intent.extras!!)
                if (MusicPlayerRemote.shuffleMode == MusicService.SHUFFLE_MODE_SHUFFLE) {
                    MusicPlayerRemote.openAndShuffleQueue(songs, true)
                } else {
                    MusicPlayerRemote.openQueue(songs, 0, true)
                }
                handled = true
            }
            if (uri != null && uri.toString().isNotEmpty()) {
                MusicPlayerRemote.playFromUri(this@MainActivity, uri)
                handled = true
            } else if (MediaStore.Audio.Playlists.CONTENT_TYPE == mimeType) {
                val id = parseLongFromIntent(intent, "playlistId", "playlist")
                if (id >= 0L) {
                    val position: Int = intent.getIntExtra("position", 0)
                    val songs: List<Song> = PlaylistSongsLoader.getPlaylistSongList(get(), id)
                    MusicPlayerRemote.openQueue(songs, position, true)
                    handled = true
                }
            } else if (MediaStore.Audio.Albums.CONTENT_TYPE == mimeType) {
                val id = parseLongFromIntent(intent, "albumId", "album")
                if (id >= 0L) {
                    val position: Int = intent.getIntExtra("position", 0)
                    val songs = libraryViewModel.albumById(id).songs
                    MusicPlayerRemote.openQueue(songs, position, true)
                    handled = true
                }
            } else if (MediaStore.Audio.Artists.CONTENT_TYPE == mimeType) {
                val id = parseLongFromIntent(intent, "artistId", "artist")
                if (id >= 0L) {
                    val position: Int = intent.getIntExtra("position", 0)
                    val songs: List<Song> = libraryViewModel.artistById(id).songs
                    MusicPlayerRemote.openQueue(songs, position, true)
                    handled = true
                }
            }
            if (handled) setIntent(Intent())
        }
    }

    private fun parseLongFromIntent(intent: Intent, longKey: String, stringKey: String): Long {
        var id = intent.getLongExtra(longKey, -1)
        if (id < 0) {
            val idString = intent.getStringExtra(stringKey)
            if (idString != null) {
                try { id = idString.toLong() } catch (e: NumberFormatException) { logE(e) }
            }
        }
        return id
    }
}