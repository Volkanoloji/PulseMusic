package code.name.monkey.pulsemusic.fragments.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import code.name.monkey.appthemehelper.ThemeStore // 🚀 YENİ: Uygulamanın kendi renk motorunu bağladık!
import code.name.monkey.pulsemusic.R
import code.name.monkey.pulsemusic.helper.MusicPlayerRemote

class EqualizerFragment : Fragment() {

    private var equalizer: Equalizer? = null
    private var presetReverb: PresetReverb? = null
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    private lateinit var eqSwitch: SwitchCompat
    private lateinit var eqGraphContainer: FrameLayout
    private lateinit var presetSpinner: Spinner
    private var spotifyEqView: SpotifyEqView? = null

    private val reverbNames = arrayOf("Kapalı", "Küçük Oda", "Orta Oda", "Büyük Oda", "Orta Salon", "Büyük Salon", "Plaka (Plate)")

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_equalizer, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.isClickable = true
        view.isFocusable = true
        view.setBackgroundColor(Color.parseColor("#000000"))

        val backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                requireActivity().supportFragmentManager.beginTransaction().remove(this@EqualizerFragment).commit()
                remove()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        view.findViewById<ImageButton>(R.id.backBtn).setOnClickListener {
            backCallback.handleOnBackPressed()
        }

        val sessionId = MusicPlayerRemote.audioSessionId
        if (sessionId <= 0) {
            Toast.makeText(context, "Ayar için önce şarkı açman lazım!", Toast.LENGTH_SHORT).show()
            backCallback.handleOnBackPressed()
            return
        }

        try {
            equalizer = Equalizer(0, sessionId)
            presetReverb = PresetReverb(0, sessionId)
            virtualizer = Virtualizer(0, sessionId)
            loudnessEnhancer = LoudnessEnhancer(sessionId)
        } catch (e: Exception) {
            Toast.makeText(context, "Cihaz donanımı desteklemiyor!", Toast.LENGTH_SHORT).show()
        }

        val eq = equalizer ?: return

        eqGraphContainer = view.findViewById(R.id.eqGraphContainer)
        presetSpinner = view.findViewById(R.id.presetSpinner)

        // 🟩 TEMA UYUMLU EKRANI YUVAYA OTURT
        spotifyEqView = SpotifyEqView(requireContext(), eq).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            onBandLevelChanged = {
                if (presetSpinner.selectedItemPosition != 0) {
                    presetSpinner.setSelection(0)
                }
            }
        }
        eqGraphContainer.addView(spotifyEqView)

        eqSwitch = view.findViewById(R.id.eqMasterSwitch)
        eqSwitch.isChecked = eq.enabled
        eqSwitch.setOnCheckedChangeListener { _, isChecked ->
            eq.enabled = isChecked
            presetReverb?.enabled = isChecked
            virtualizer?.enabled = isChecked
            loudnessEnhancer?.enabled = isChecked
            updateListState(isChecked)
        }

        val presets = mutableListOf<String>("Düz / Manuel")
        for (i in 0 until eq.numberOfPresets) { presets.add(eq.getPresetName(i.toShort())) }

        val presetAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, presets)
        presetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        presetSpinner.adapter = presetAdapter

        presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position > 0) {
                    eq.usePreset((position - 1).toShort())
                    spotifyEqView?.updateLevelsFromEq()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val reverbValText = view.findViewById<TextView>(R.id.reverbValText)
        view.findViewById<SeekBar>(R.id.reverbSeekBar).apply {
            progress = presetReverb?.preset?.toInt() ?: 0
            reverbValText.text = reverbNames[progress]
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    reverbValText.text = reverbNames[progress]
                    if (fromUser && presetReverb?.enabled == true) presetReverb?.preset = progress.toShort()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        view.findViewById<SeekBar>(R.id.loudnessSlider).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && loudnessEnhancer?.enabled == true) loudnessEnhancer?.setTargetGain(progress * 50)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        view.findViewById<SeekBar>(R.id.virtualizerSlider).apply {
            progress = virtualizer?.roundedStrength?.toInt() ?: 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && virtualizer?.enabled == true && virtualizer?.strengthSupported == true) virtualizer?.setStrength(progress.toShort())
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        updateListState(eq.enabled)
    }

    private fun updateListState(enabled: Boolean) {
        eqGraphContainer.alpha = if (enabled) 1.0f else 0.4f
        presetSpinner.isEnabled = enabled
        view?.findViewById<SeekBar>(R.id.reverbSeekBar)?.isEnabled = enabled
        view?.findViewById<SeekBar>(R.id.loudnessSlider)?.isEnabled = enabled
        view?.findViewById<SeekBar>(R.id.virtualizerSlider)?.isEnabled = enabled
        spotifyEqView?.invalidate()
    }

    override fun onDestroy() {
        super.onDestroy()
        equalizer?.release()
        presetReverb?.release()
        virtualizer?.release()
        loudnessEnhancer?.release()
    }

    // ==========================================
    // 🟩 TEMA UYUMLU ÖZEL ÇİZİM MOTORU
    // ==========================================
    @SuppressLint("ViewConstructor")
    private inner class SpotifyEqView(context: Context, private val eq: Equalizer) : View(context) {

        var onBandLevelChanged: (() -> Unit)? = null

        private val density = context.resources.displayMetrics.density
        private val bandLevels = ShortArray(eq.numberOfBands.toInt())
        private var activeBand = -1

        // 🚀 TEMA RENGİNİ ÇEKEN MÜŞÜR (Uygulamanın kendi motorundan alıyoruz)
        private val themeColor = ThemeStore.accentColor(context)

        // Çizim Fırçaları
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = themeColor
            strokeWidth = 4f * density
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#888888")
            textSize = 12f * density
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        init {
            updateLevelsFromEq()
        }

        fun updateLevelsFromEq() {
            for (i in 0 until eq.numberOfBands) {
                bandLevels[i] = eq.getBandLevel(i.toShort())
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (eq.numberOfBands.toInt() == 0) return

            val w = width.toFloat()
            val h = height.toFloat()

            val paddingX = 24f * density
            val paddingTop = 20f * density
            val paddingBottom = 36f * density

            val graphWidth = w - (2 * paddingX)
            val graphHeight = h - paddingTop - paddingBottom
            val bandCount = eq.numberOfBands.toInt()
            val stepX = graphWidth / (bandCount - 1)

            val minLevel = eq.bandLevelRange[0]
            val maxLevel = eq.bandLevelRange[1]
            val range = maxLevel - minLevel

            val path = Path()
            val pointsX = FloatArray(bandCount)
            val pointsY = FloatArray(bandCount)

            for (i in 0 until bandCount) {
                val level = bandLevels[i]
                val normalized = (level - minLevel).toFloat() / range.toFloat()

                val cx = paddingX + (i * stepX)
                val cy = paddingTop + graphHeight - (normalized * graphHeight)

                pointsX[i] = cx
                pointsY[i] = cy

                if (i == 0) path.moveTo(cx, cy)
                else path.lineTo(cx, cy)
            }

            // 🚀 TEMA RENGİNDE SİS (GRADIENT) ÇİZ
            val fillPath = Path(path)
            fillPath.lineTo(pointsX.last(), h - paddingBottom)
            fillPath.lineTo(pointsX.first(), h - paddingBottom)
            fillPath.close()

            fillPaint.shader = LinearGradient(
                0f, paddingTop, 0f, h - paddingBottom,
                (themeColor and 0x00FFFFFF) or 0x66000000, // Tema Rengi %40 Saydam
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(fillPath, fillPaint)

            canvas.drawPath(path, linePaint)

            for (i in 0 until bandCount) {
                val radius = if (i == activeBand) 8f * density else 5f * density
                canvas.drawCircle(pointsX[i], pointsY[i], radius, dotPaint)

                val freq = eq.getCenterFreq(i.toShort()) / 1000
                val freqStr = if (freq >= 1000) "${freq / 1000}k" else "$freq"
                canvas.drawText(freqStr, pointsX[i], h - (10f * density), textPaint)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!eq.enabled) return false

            val x = event.x
            val y = event.y

            val paddingX = 24f * density
            val graphWidth = width.toFloat() - (2 * paddingX)
            val bandCount = eq.numberOfBands.toInt()
            val stepX = graphWidth / (bandCount - 1)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    parent.requestDisallowInterceptTouchEvent(true)

                    var closestDist = Float.MAX_VALUE
                    for (i in 0 until bandCount) {
                        val cx = paddingX + (i * stepX)
                        val dist = Math.abs(x - cx)
                        if (dist < closestDist && dist < (stepX / 1.5f)) {
                            closestDist = dist
                            activeBand = i
                        }
                    }
                    if (activeBand != -1) invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (activeBand != -1) {
                        val paddingTop = 20f * density
                        val paddingBottom = 36f * density
                        val graphHeight = height.toFloat() - paddingTop - paddingBottom

                        var newY = y
                        if (newY < paddingTop) newY = paddingTop
                        if (newY > height.toFloat() - paddingBottom) newY = height.toFloat() - paddingBottom

                        val normalized = 1f - ((newY - paddingTop) / graphHeight)
                        val minLevel = eq.bandLevelRange[0]
                        val maxLevel = eq.bandLevelRange[1]
                        val range = maxLevel - minLevel

                        val newLevel = (minLevel + (normalized * range)).toInt().toShort()

                        eq.setBandLevel(activeBand.toShort(), newLevel)
                        bandLevels[activeBand] = newLevel

                        onBandLevelChanged?.invoke()
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    activeBand = -1
                    parent.requestDisallowInterceptTouchEvent(false)
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}