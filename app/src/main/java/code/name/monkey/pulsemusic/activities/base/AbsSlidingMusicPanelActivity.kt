
package code.name.monkey.pulsemusic.activities.base

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.core.animation.doOnEnd
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.commit
import androidx.navigation.fragment.NavHostFragment
import code.name.monkey.appthemehelper.util.VersionUtils
import code.name.monkey.pulsemusic.*
import code.name.monkey.pulsemusic.activities.PermissionActivity
import code.name.monkey.pulsemusic.databinding.SlidingMusicPanelLayoutBinding
import code.name.monkey.pulsemusic.extensions.*
import code.name.monkey.pulsemusic.fragments.LibraryViewModel
import code.name.monkey.pulsemusic.fragments.NowPlayingScreen
import code.name.monkey.pulsemusic.fragments.NowPlayingScreen.*
import code.name.monkey.pulsemusic.fragments.base.AbsPlayerFragment
import code.name.monkey.pulsemusic.fragments.other.MiniPlayerFragment
import code.name.monkey.pulsemusic.fragments.player.adaptive.AdaptiveFragment
import code.name.monkey.pulsemusic.fragments.player.blur.BlurPlayerFragment
import code.name.monkey.pulsemusic.fragments.player.card.CardFragment
import code.name.monkey.pulsemusic.fragments.player.cardblur.CardBlurFragment
import code.name.monkey.pulsemusic.fragments.player.circle.CirclePlayerFragment
import code.name.monkey.pulsemusic.fragments.player.classic.ClassicPlayerFragment
import code.name.monkey.pulsemusic.fragments.player.color.ColorFragment
import code.name.monkey.pulsemusic.fragments.player.fit.FitFragment
import code.name.monkey.pulsemusic.fragments.player.flat.FlatPlayerFragment
import code.name.monkey.pulsemusic.fragments.player.full.FullPlayerFragment
import code.name.monkey.pulsemusic.fragments.player.gradient.GradientPlayerFragment
import code.name.monkey.pulsemusic.fragments.player.material.MaterialFragment
import code.name.monkey.pulsemusic.fragments.player.md3.MD3PlayerFragment
import code.name.monkey.pulsemusic.fragments.player.normal.PlayerFragment
import code.name.monkey.pulsemusic.fragments.player.peek.PeekPlayerFragment
import code.name.monkey.pulsemusic.fragments.player.plain.PlainPlayerFragment
import code.name.monkey.pulsemusic.fragments.player.simple.SimplePlayerFragment
import code.name.monkey.pulsemusic.fragments.player.tiny.TinyPlayerFragment
import code.name.monkey.pulsemusic.fragments.queue.PlayingQueueFragment
import code.name.monkey.pulsemusic.helper.MusicPlayerRemote
import code.name.monkey.pulsemusic.model.CategoryInfo
import code.name.monkey.pulsemusic.util.PreferenceUtil
import code.name.monkey.pulsemusic.util.ViewUtil
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetBehavior.*
import org.koin.androidx.viewmodel.ext.android.viewModel

abstract class AbsSlidingMusicPanelActivity : AbsMusicServiceActivity(),
    SharedPreferences.OnSharedPreferenceChangeListener {
    companion object {
        val TAG: String = AbsSlidingMusicPanelActivity::class.java.simpleName
    }

    var fromNotification = false
    private var windowInsets: WindowInsetsCompat? = null
    protected val libraryViewModel by viewModel<LibraryViewModel>()
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<FrameLayout>
    private lateinit var playerFragment: AbsPlayerFragment
    private var miniPlayerFragment: MiniPlayerFragment? = null
    private var nowPlayingScreen: NowPlayingScreen? = null
    private var taskColor: Int = 0
    private var paletteColor: Int = android.graphics.Color.WHITE
    private var navBarColor = 0

    private val panelState: Int get() = bottomSheetBehavior.state
    private var panelStateBefore: Int? = null
    private var panelStateCurrent: Int? = null
    private lateinit var binding: SlidingMusicPanelLayoutBinding
    private var isInOneTabMode = false

    private var navigationBarColorAnimator: ValueAnimator? = null
    private val argbEvaluator: ArgbEvaluator = ArgbEvaluator()

    // 🚨 NAVİGASYON KORUMASI: Çökmeyi engelleyen asıl mekanizma
    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (handleBackPress()) return

            val fragment = supportFragmentManager.findFragmentById(R.id.fragment_container)

            // Eğer yerinde NavHost varsa (Normal akış)
            if (fragment is NavHostFragment) {
                if (!fragment.navController.navigateUp()) {
                    finish()
                }
            } else {
                // Eğer YouTube ekranı gibi harici bir fragment takılıysa
                if (!supportFragmentManager.popBackStackImmediate()) {
                    // Backstack boşsa Activity'yi bitir veya ana haritayı (NavHost) geri yükle
                    finish()
                }
            }
        }
    }

    // Dışarıdaki Fragment/Adapter'lar için behavior erişimi
    fun getBottomSheetBehavior(): BottomSheetBehavior<FrameLayout> = bottomSheetBehavior

    private val bottomSheetCallbackList by lazy {
        object : BottomSheetCallback() {
            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                setMiniPlayerAlphaProgress(slideOffset)
                navigationBarColorAnimator?.cancel()
                setNavigationBarColorPreOreo(
                    argbEvaluator.evaluate(slideOffset, surfaceColor(), navBarColor) as Int
                )
            }

            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (panelStateCurrent != null) panelStateBefore = panelStateCurrent
                panelStateCurrent = newState
                when (newState) {
                    STATE_EXPANDED -> {
                        onPanelExpanded()
                        if (PreferenceUtil.lyricsScreenOn && PreferenceUtil.showLyrics) keepScreenOn(true)
                    }
                    STATE_COLLAPSED -> {
                        onPanelCollapsed()
                        if ((PreferenceUtil.lyricsScreenOn && PreferenceUtil.showLyrics) || !PreferenceUtil.isScreenOnEnabled) keepScreenOn(false)
                    }
                    STATE_HIDDEN -> MusicPlayerRemote.clearQueue()
                    else -> {}
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!hasPermissions()) {
            startActivity(Intent(this, PermissionActivity::class.java))
            finish()
        }
        binding = SlidingMusicPanelLayoutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.setOnApplyWindowInsetsListener { _, insets ->
            windowInsets = WindowInsetsCompat.toWindowInsetsCompat(insets)
            insets
        }
        chooseFragmentForTheme()
        setupSlidingUpPanel()
        setupBottomSheet()
        updateColor()
        if (!PreferenceUtil.materialYou) {
            binding.slidingPanel.backgroundTintList = ColorStateList.valueOf(darkAccentColor())
            navigationView.backgroundTintList = ColorStateList.valueOf(darkAccentColor())
        }
        navBarColor = surfaceColor()
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
    }

    private fun setupBottomSheet() {
        bottomSheetBehavior = from(binding.slidingPanel)
        bottomSheetBehavior.addBottomSheetCallback(bottomSheetCallbackList)
        bottomSheetBehavior.isHideable = PreferenceUtil.swipeDownToDismiss
        bottomSheetBehavior.significantVelocityThreshold = 300
        setMiniPlayerAlphaProgress(0F)
    }

    override fun onResume() {
        super.onResume()
        PreferenceUtil.registerOnSharedPreferenceChangedListener(this)
        if (nowPlayingScreen != PreferenceUtil.nowPlayingScreen) postRecreate()
        if (bottomSheetBehavior.state == STATE_EXPANDED) setMiniPlayerAlphaProgress(1f)
    }

    override fun onDestroy() {
        super.onDestroy()
        bottomSheetBehavior.removeBottomSheetCallback(bottomSheetCallbackList)
        PreferenceUtil.unregisterOnSharedPreferenceChangedListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        when (key) {
            SWIPE_DOWN_DISMISS -> bottomSheetBehavior.isHideable = PreferenceUtil.swipeDownToDismiss
            TOGGLE_ADD_CONTROLS -> miniPlayerFragment?.setUpButtons()
            NOW_PLAYING_SCREEN_ID -> {
                chooseFragmentForTheme()
                binding.slidingPanel.updateLayoutParams<ViewGroup.LayoutParams> {
                    height = if (nowPlayingScreen != Peek) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
                }
                onServiceConnected()
            }
            ALBUM_COVER_TRANSFORM, CAROUSEL_EFFECT, ALBUM_COVER_STYLE, TOGGLE_VOLUME, EXTRA_SONG_INFO, CIRCLE_PLAY_BUTTON -> {
                chooseFragmentForTheme()
                onServiceConnected()
            }
            LIBRARY_CATEGORIES -> updateTabs()
            TAB_TEXT_MODE -> navigationView.labelVisibilityMode = PreferenceUtil.tabTitleMode
            TOGGLE_FULL_SCREEN -> recreate()
            SCREEN_ON_LYRICS -> keepScreenOn(bottomSheetBehavior.state == STATE_EXPANDED && PreferenceUtil.lyricsScreenOn && PreferenceUtil.showLyrics || PreferenceUtil.isScreenOnEnabled)
            KEEP_SCREEN_ON -> maybeSetScreenOn()
        }
    }

    fun collapsePanel() { bottomSheetBehavior.state = STATE_COLLAPSED }
    fun expandPanel() { bottomSheetBehavior.state = STATE_EXPANDED }

    private fun setMiniPlayerAlphaProgress(progress: Float) {
        if (progress < 0) return
        val alpha = 1 - progress
        miniPlayerFragment?.view?.alpha = 1 - (progress / 0.2F)
        miniPlayerFragment?.view?.isGone = alpha == 0f
        if (!isLandscape) {
            binding.navigationView.translationY = progress * 500
            binding.navigationView.alpha = alpha
        }
        binding.playerFragmentContainer.alpha = (progress - 0.2F) / 0.2F
    }

    private fun animateNavigationBarColor(color: Int) {
        if (VersionUtils.hasOreo()) return
        navigationBarColorAnimator?.cancel()
        navigationBarColorAnimator = ValueAnimator.ofArgb(window.navigationBarColor, color).apply {
            duration = ViewUtil.retro_MUSIC_ANIM_TIME.toLong()
            interpolator = PathInterpolator(0.4f, 0f, 1f, 1f)
            addUpdateListener { animation -> setNavigationBarColorPreOreo(animation.animatedValue as Int) }
            start()
        }
    }

    open fun onPanelCollapsed() {
        setMiniPlayerAlphaProgress(0F)
        animateNavigationBarColor(surfaceColor())
        setLightStatusBarAuto()
        setLightNavigationBarAuto()
        setTaskDescriptionColor(taskColor)
    }

    open fun onPanelExpanded() {
        setMiniPlayerAlphaProgress(1F)
        onPaletteColorChanged()
    }

    private fun setupSlidingUpPanel() {
        binding.slidingPanel.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                binding.slidingPanel.viewTreeObserver.removeOnGlobalLayoutListener(this)
                if (nowPlayingScreen != Peek) {
                    binding.slidingPanel.updateLayoutParams<ViewGroup.LayoutParams> { height = ViewGroup.LayoutParams.MATCH_PARENT }
                }
                if (panelState == STATE_EXPANDED) onPanelExpanded() else onPanelCollapsed()
            }
        })
    }

    val navigationView get() = binding.navigationView
    val slidingPanel get() = binding.slidingPanel
    val isBottomNavVisible get() = navigationView.isVisible && navigationView is BottomNavigationView

    override fun onServiceConnected() {
        super.onServiceConnected()
        hideBottomSheet(false)
    }

    override fun onQueueChanged() {
        super.onQueueChanged()
        if (currentFragment(R.id.fragment_container) !is PlayingQueueFragment) {
            hideBottomSheet(MusicPlayerRemote.playingQueue.isEmpty())
        }
    }

    private fun handleBackPress(): Boolean {
        if (panelState == STATE_EXPANDED || (panelState == STATE_SETTLING && panelStateBefore != STATE_EXPANDED)) {
            collapsePanel()
            return true
        }
        return false
    }

    private fun onPaletteColorChanged() {
        if (panelState == STATE_EXPANDED) {
            navBarColor = surfaceColor()
            setTaskDescColor(paletteColor)
            val isColorLight = paletteColor.isColorLight
            when (nowPlayingScreen) {
                Normal, Flat, Material -> { setLightNavigationBar(true); setLightStatusBar(isColorLight) }
                Card, Blur, BlurCard -> { animateNavigationBarColor(android.graphics.Color.BLACK); navBarColor = android.graphics.Color.BLACK; setLightStatusBar(false); setLightNavigationBar(true) }
                Color, Tiny, Gradient -> { animateNavigationBarColor(paletteColor); navBarColor = paletteColor; setLightNavigationBar(isColorLight); setLightStatusBar(isColorLight) }
                Full -> { animateNavigationBarColor(paletteColor); navBarColor = paletteColor; setLightNavigationBar(isColorLight); setLightStatusBar(false) }
                else -> setLightStatusBar(false)
            }
        }
    }

    private fun setTaskDescColor(color: Int) {
        taskColor = color
        if (panelState == STATE_COLLAPSED) setTaskDescriptionColor(color)
    }

    fun updateTabs() {
        binding.navigationView.menu.clear()
        val currentTabs: List<CategoryInfo> = PreferenceUtil.libraryCategory
        for (tab in currentTabs) {
            if (tab.visible) {
                val category = tab.category
                binding.navigationView.menu.add(0, category.id, 0, category.stringRes)
                    .setIcon(category.icon)
            }
        }
        isInOneTabMode = binding.navigationView.menu.size() == 1
        if (isInOneTabMode) binding.navigationView.isVisible = false
    }

    private fun updateColor() {
        libraryViewModel.paletteColor.observe(this) { color ->
            this.paletteColor = color
            onPaletteColorChanged()
        }
    }

    fun setBottomNavVisibility(visible: Boolean, animate: Boolean = false, hideBottomSheet: Boolean = MusicPlayerRemote.playingQueue.isEmpty()) {
        if (isInOneTabMode) {
            hideBottomSheet(hide = hideBottomSheet, animate = animate, isBottomNavVisible = false)
            return
        }
        if (visible xor navigationView.isVisible) {
            val mAnimate = animate && bottomSheetBehavior.state == STATE_COLLAPSED
            if (mAnimate) {
                if (visible) { binding.navigationView.bringToFront(); binding.navigationView.show() } else binding.navigationView.hide()
            } else {
                binding.navigationView.isVisible = visible
                if (visible && bottomSheetBehavior.state != STATE_EXPANDED) binding.navigationView.bringToFront()
            }
        }
        hideBottomSheet(hide = hideBottomSheet, animate = animate, isBottomNavVisible = visible && navigationView is BottomNavigationView)
    }

    fun hideBottomSheet(hide: Boolean, animate: Boolean = false, isBottomNavVisible: Boolean = navigationView.isVisible && navigationView is BottomNavigationView) {
        val heightOfBar = windowInsets.getBottomInsets() + dip(R.dimen.mini_player_height)
        val heightOfBarWithTabs = heightOfBar + dip(R.dimen.bottom_nav_height)
        if (hide) {
            bottomSheetBehavior.peekHeight = (-windowInsets.getBottomInsets()).coerceAtLeast(0)
            bottomSheetBehavior.state = STATE_COLLAPSED
            libraryViewModel.setFabMargin(this, if (isBottomNavVisible) dip(R.dimen.bottom_nav_height) else 0)
        } else if (MusicPlayerRemote.playingQueue.isNotEmpty()) {
            binding.slidingPanel.elevation = 0F
            binding.navigationView.elevation = 5F
            if (isBottomNavVisible) {
                if (animate) bottomSheetBehavior.peekHeightAnimate(heightOfBarWithTabs) else bottomSheetBehavior.peekHeight = heightOfBarWithTabs
                libraryViewModel.setFabMargin(this, dip(R.dimen.bottom_nav_mini_player_height))
            } else {
                if (animate) bottomSheetBehavior.peekHeightAnimate(heightOfBar).doOnEnd { binding.slidingPanel.bringToFront() } else {
                    bottomSheetBehavior.peekHeight = heightOfBar
                    binding.slidingPanel.bringToFront()
                }
                libraryViewModel.setFabMargin(this, dip(R.dimen.mini_player_height))
            }
        }
    }

    fun setAllowDragging(allowDragging: Boolean) {
        bottomSheetBehavior.isDraggable = allowDragging
        hideBottomSheet(false)
    }

    private fun chooseFragmentForTheme() {
        nowPlayingScreen = PreferenceUtil.nowPlayingScreen
        val fragment: AbsPlayerFragment = when (nowPlayingScreen) {
            Blur -> BlurPlayerFragment()
            Adaptive -> AdaptiveFragment()
            Normal -> PlayerFragment()
            Card -> CardFragment()
            BlurCard -> CardBlurFragment()
            Fit -> FitFragment()
            Flat -> FlatPlayerFragment()
            Full -> FullPlayerFragment()
            Plain -> PlainPlayerFragment()
            Simple -> SimplePlayerFragment()
            Material -> MaterialFragment()
            Color -> ColorFragment()
            Gradient -> GradientPlayerFragment()
            Tiny -> TinyPlayerFragment()
            Peek -> PeekPlayerFragment()
            Circle -> CirclePlayerFragment()
            Classic -> ClassicPlayerFragment()
            MD3 -> MD3PlayerFragment()
            else -> PlayerFragment()
        }
        supportFragmentManager.commit { replace(R.id.playerFragmentContainer, fragment) }
        supportFragmentManager.executePendingTransactions()
        playerFragment = whichFragment(R.id.playerFragmentContainer)
        miniPlayerFragment = whichFragment<MiniPlayerFragment>(R.id.miniPlayerFragment)
        miniPlayerFragment?.view?.setOnClickListener { expandPanel() }
    }
}