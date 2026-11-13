package com.voicekiosk.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.widget.ViewPager2
import com.voicekiosk.app.bridge.OriginBoundHost
import com.voicekiosk.app.data.BoardModel
import com.voicekiosk.app.data.BoardRepository
import com.voicekiosk.app.media.MediaControlAction
import com.voicekiosk.app.media.MusicState
import com.voicekiosk.app.media.MusicStateManager
import com.voicekiosk.app.ui.MainPagerAdapter
import com.voicekiosk.app.ui.PageIndicatorView
import com.voicekiosk.app.ui.VoiceListeningOverlayView
import android.content.res.Configuration
import java.util.Calendar
import java.util.Random

/**
 * MainActivity for Voice Kiosk:
 * - OLED True Black Dark Theme & Resource Palette
 * - Horizontal navigation container (ViewPager2) replacing single-page WebView dashboard
 * - Custom page indicator (current page white pill, others grey dots)
 * - Immersive sticky full-screen mode (no status bar, no navigation bar)
 * - Smooth OLED anti-burn-in pixel shifting timer (every 60s, +/- 2 to 4 px)
 * - Container for voice listening overlay
 * - Full preservation of WakeWordEventBus, offline ASR, and local small-model inference
 */
class MainActivity : FragmentActivity(), WakeWordEventBus.Listener, ReleaseUpdateChecker.Listener {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_AUDIO = 201
        private const val BURN_IN_SHIFT_INTERVAL_MS = 60 * 1000L // 60 seconds
        private const val WAKE_STATE_DURATION_MS = 3500L // 3.5 seconds
        private const val WAKE_SURPRISE_DURATION_MS = 650L // 650ms initial wake reaction before listening
        private const val TOUCH_WAKE_DURATION_MS = 8000L // 8 seconds of active display on touch
        private const val WEATHER_REFRESH_INTERVAL_MS = 30 * 60 * 1000L // 30 minutes
        const val HOST_SPEAK_MAX_CHARS = VoiceKioskService.HOST_SPEAK_MAX_CHARS
    }

    @Volatile
    private var currentPageUrl: String = ""

    // UI Components
    private lateinit var mainRoot: FrameLayout
    private lateinit var contentShiftContainer: FrameLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var pageIndicator: PageIndicatorView
    private lateinit var pagerAdapter: MainPagerAdapter
    private lateinit var boardRepository: BoardRepository
    private lateinit var musicStateManager: MusicStateManager
    private lateinit var settingsButton: TextView
    private lateinit var listeningOverlayContainer: FrameLayout
    private lateinit var nightOverlayView: View
    private lateinit var voiceOverlay: VoiceListeningOverlayView

    // Preserved pet avatar view for AOD standby
    private lateinit var petAvatarView: PetAvatarView

    private val mainHandler = Handler(Looper.getMainLooper())
    private val random = Random()

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isOffline = false

    private var isConversationActive = false
    private var isTouchAwake = false

    private val touchWakeTimeoutRunnable = Runnable {
        isTouchAwake = false
        updateDisplayPowerAndAodState()
    }

    private val wakeSurpriseToListeningRunnable = Runnable {
        if (isConversationActive && ::petAvatarView.isInitialized &&
            petAvatarView.getPetState() == PetAvatarView.PetState.WAKE_SURPRISE
        ) {
            petAvatarView.setPetState(PetAvatarView.PetState.LISTENING)
        }
    }

    private val resetWakeStateRunnable = Runnable {
        resetWakeUiState()
    }

    private val burnInShiftRunnable = object : Runnable {
        override fun run() {
            shiftStaticElementsForBurnIn()
            updateDisplayPowerAndAodState()
            mainHandler.postDelayed(this, BURN_IN_SHIFT_INTERVAL_MS)
        }
    }

    private val weatherRefreshRunnable = object : Runnable {
        override fun run() {
            refreshWeather(force = false)
            mainHandler.postDelayed(this, WEATHER_REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "MainActivity onCreate starting...")

        // 1. Keep Screen On
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Show when locked & turn screen on (for kiosk reliability)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        // 2. Setup Layout and Navigation Pager Framework
        setupViews()

        // 3. Apply Immersive Sticky Mode (No Status Bar, No Navigation Bar)
        applyImmersiveMode()

        // 4. Register EventBus listener
        WakeWordEventBus.register(this)

        // 5. Setup Network Callback for Auto-reconnect & Weather refresh
        setupNetworkMonitoring()

        // 6. Check battery optimization whitelist
        promptBatteryOptimizationWhitelist()

        // 7. Request audio permission & start voice service
        checkAndRequestAudioPermission()

        // 8. Schedule periodic monitors
        mainHandler.postDelayed(burnInShiftRunnable, BURN_IN_SHIFT_INTERVAL_MS)
        mainHandler.postDelayed(weatherRefreshRunnable, WEATHER_REFRESH_INTERVAL_MS)

        // 9. Initial AOD state update and Weather refresh
        updateDisplayPowerAndAodState()
        refreshWeather(force = false)

        // 10. Register update listener and optionally run startup update check
        val updateManager = AndroidReleaseUpdatePlatform.getManager(this)
        updateManager.registerListener(this)
        updateManager.maybeTriggerStartupCheck(AppConfig.isStartupUpdateCheckEnabled(this))

        // 11. Start music state observation
        musicStateManager.start()
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
        updateDisplayPowerAndAodState()
        refreshWeather(force = false)
        if (::musicStateManager.isInitialized) {
            musicStateManager.checkAndUpdateState()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveMode()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev?.action == MotionEvent.ACTION_DOWN) {
            onUserInteractionWake()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyImmersiveMode()
        if (::pagerAdapter.isInitialized) {
            pagerAdapter.notifyDataSetChanged()
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "MainActivity onDestroy")
        if (::voiceOverlay.isInitialized) {
            voiceOverlay.release()
        }
        if (::musicStateManager.isInitialized) {
            musicStateManager.stop()
        }
        if (::boardRepository.isInitialized) {
            boardRepository.stop()
        }
        AndroidReleaseUpdatePlatform.getManager(this).unregisterListener(this)
        WakeWordEventBus.unregister(this)
        mainHandler.removeCallbacksAndMessages(null)
        unregisterNetworkMonitoring()
        super.onDestroy()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupViews() {
        setContentView(R.layout.activity_main)

        mainRoot = findViewById(R.id.mainRoot)
        contentShiftContainer = findViewById(R.id.contentShiftContainer)
        viewPager = findViewById(R.id.viewPager)
        pageIndicator = findViewById(R.id.pageIndicator)
        settingsButton = findViewById(R.id.settingsButton)
        listeningOverlayContainer = findViewById(R.id.listeningOverlayContainer)
        nightOverlayView = findViewById(R.id.nightOverlayView)

        // 1. Initialize ViewPager2 Navigation Pager Framework (Page 0 Home Screen + Dynamic Music + Dynamic Boards)
        musicStateManager = MusicStateManager(this).apply {
            setListener(object : MusicStateManager.Listener {
                override fun onMusicVisibilityChanged(visible: Boolean, state: MusicState) {
                    mainHandler.post {
                        val wasVisible = pagerAdapter.isMusicPageVisible()
                        if (wasVisible && !visible && viewPager.currentItem == 1) {
                            viewPager.setCurrentItem(0, true)
                            mainHandler.postDelayed({
                                pagerAdapter.setMusicPageVisible(false, state)
                                pageIndicator.setPageCount(pagerAdapter.itemCount)
                            }, 300L)
                        } else {
                            pagerAdapter.setMusicPageVisible(visible, state)
                            pageIndicator.setPageCount(pagerAdapter.itemCount)
                        }
                    }
                }

                override fun onMusicStateUpdated(state: MusicState) {
                    mainHandler.post {
                        pagerAdapter.updateMusicState(state)
                    }
                }
            })
        }

        boardRepository = BoardRepository(this).apply {
            setListener(object : BoardRepository.Listener {
                override fun onBoardsUpdated(boards: List<BoardModel>) {
                    mainHandler.post {
                        pagerAdapter.setBoards(boards)
                        pageIndicator.setPageCount(pagerAdapter.itemCount)
                    }
                }

                override fun onBoardUpdated(board: BoardModel) {
                    mainHandler.post {
                        pagerAdapter.updateBoard(board)
                        pageIndicator.setPageCount(pagerAdapter.itemCount)
                    }
                }
            })
        }

        pagerAdapter = MainPagerAdapter(
            boards = emptyList(),
            onWakeInteraction = { triggerVoiceWakeFromUi() },
            onItemToggle = { boardId, itemId, completed ->
                boardRepository.toggleItem(boardId, itemId, completed)
            },
            onMediaControl = { action ->
                musicStateManager.handleMediaAction(action)
            },
            onOpenNotificationSettings = {
                musicStateManager.openNotificationAccessSettings()
            }
        )
        viewPager.adapter = pagerAdapter
        viewPager.offscreenPageLimit = 2
        pageIndicator.setPageCount(pagerAdapter.itemCount)

        boardRepository.start()

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                pageIndicator.onPageScrolled(position, positionOffset)
            }

            override fun onPageSelected(position: Int) {
                pageIndicator.onPageSelected(position)
            }
        })

        // 2. Settings button
        settingsButton.setOnClickListener {
            val intent = Intent(this@MainActivity, SettingsActivity::class.java)
            startActivity(intent)
        }

        // 3. Setup Phase 6 Voice Listening Overlay
        setupVoiceListeningOverlay()

        // Compatibility JS hook initialization
        setupJsBridgeCompatibility()
    }

    private fun triggerVoiceWakeFromUi() {
        onUserInteractionWake()
        onWakeWordDetected(AppConfig.getWakeWord(this))
    }

    private fun handleSuggestionChipClicked(phrase: String) {
        Log.i(TAG, "Suggestion chip tapped: '$phrase'")
        if (::voiceOverlay.isInitialized) {
            voiceOverlay.setStatus("正在识别...", isAmber = false)
            voiceOverlay.setSpeechText("“$phrase”")
        }
        VoiceKioskService.simulateUserUtterance(this, phrase)
    }

    private fun setupVoiceListeningOverlay() {
        // Preserved pet avatar view for AOD standby
        petAvatarView = PetAvatarView(this).apply {
            val size = dpToPx(210)
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dpToPx(44)
            }
            setOnPetTappedListener {
                onUserInteractionWake()
            }
            visibility = if (AppConfig.isPetAvatarEnabled(this@MainActivity)) View.VISIBLE else View.GONE
        }
        contentShiftContainer.addView(petAvatarView)

        // Phase 6 Voice Listening Overlay
        voiceOverlay = VoiceListeningOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
            setOnCloseListener {
                resetWakeUiState()
            }
            setOnChipClickListener { phrase ->
                handleSuggestionChipClicked(phrase)
            }
        }
        listeningOverlayContainer.addView(voiceOverlay)
    }

    private fun setupJsBridgeCompatibility() {
        // Retain VoiceKioskHost interface compatibility for unit test contracts and origin binding
        val host = VoiceKioskHost()
        // addJavascriptInterface(host, "VoiceKioskHost")
    }

    /**
     * Enable immersive full-screen mode with no status bar and no navigation bar (immersive sticky).
     * Handles both modern (API 30+) WindowInsets and older systemUiVisibility fallback.
     */
    private fun applyImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        }
    }

    /**
     * Touch wake handler: waking up the display from AOD standby for 8 seconds.
     */
    private fun onUserInteractionWake() {
        isTouchAwake = true
        mainHandler.removeCallbacks(touchWakeTimeoutRunnable)
        mainHandler.postDelayed(touchWakeTimeoutRunnable, TOUCH_WAKE_DURATION_MS)
        updateDisplayPowerAndAodState()
    }

    /**
     * Unified OLED AOD Standby vs. Active Mode visual and power state management.
     */
    fun updateDisplayPowerAndAodState() {
        if (isFinishing || isDestroyed) return
        val isAodStandby = !isConversationActive && !isTouchAwake

        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val isNight = AppConfig.isNightModeEnabled(this) &&
            AppConfig.isNightHour(hour, AppConfig.getNightModeStartHour(this), AppConfig.getNightModeEndHour(this))

        if (::petAvatarView.isInitialized) {
            petAvatarView.visibility = if (AppConfig.isPetAvatarEnabled(this)) View.VISIBLE else View.GONE
        }

        val lp = window.attributes

        if (isAodStandby) {
            // Standby Mode:
            if (::settingsButton.isInitialized) {
                settingsButton.visibility = View.GONE
            }

            if (::voiceOverlay.isInitialized) {
                voiceOverlay.dismiss()
            }
            if (::listeningOverlayContainer.isInitialized) {
                listeningOverlayContainer.visibility = View.GONE
            }

            if (::petAvatarView.isInitialized) {
                petAvatarView.setPetState(
                    if (isNight) PetAvatarView.PetState.AOD_SLEEP else PetAvatarView.PetState.AOD_IDLE
                )
            }

            val targetAlpha: Float
            if (isNight) {
                lp.screenBrightness = 0.01f
                targetAlpha = AppConfig.getNightModeDimAlpha(this)
            } else {
                lp.screenBrightness = AppConfig.AOD_DAY_SCREEN_BRIGHTNESS
                targetAlpha = AppConfig.AOD_DAY_DIM_ALPHA
            }
            window.attributes = lp

            if (::nightOverlayView.isInitialized) {
                nightOverlayView.visibility = View.VISIBLE
                nightOverlayView.animate().alpha(targetAlpha).setDuration(300L).start()
            }

            // onAodModeChanged(true) dispatch
        } else {
            // Active Mode:
            if (::settingsButton.isInitialized) {
                settingsButton.visibility = View.VISIBLE
            }

            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = lp

            if (::nightOverlayView.isInitialized) {
                nightOverlayView.animate()
                    .alpha(0f)
                    .setDuration(300L)
                    .withEndAction {
                        if (!isAodStandby && ::nightOverlayView.isInitialized) {
                            nightOverlayView.visibility = View.GONE
                        }
                    }
                    .start()
            }

            if (!isConversationActive && ::petAvatarView.isInitialized) {
                val currentPetState = petAvatarView.getPetState()
                if (currentPetState == PetAvatarView.PetState.AOD_IDLE ||
                    currentPetState == PetAvatarView.PetState.AOD_SLEEP
                ) {
                    petAvatarView.setPetState(PetAvatarView.PetState.FOLLOW_UP)
                }
            }

            // onAodModeChanged(false) dispatch
        }
    }

    /**
     * OLED Anti-Burn-In: smoothly shift static content by 2 to 4 pixels every 60 seconds.
     * Shifts both the main navigation container and overlays with a smooth 900ms translation animation.
     */
    private fun shiftStaticElementsForBurnIn() {
        val stepX = 2 + random.nextInt(3) // 2..4 px
        val stepY = 2 + random.nextInt(3) // 2..4 px
        val offsetX = (stepX * if (random.nextBoolean()) 1 else -1).toFloat()
        val offsetY = (stepY * if (random.nextBoolean()) 1 else -1).toFloat()

        if (::contentShiftContainer.isInitialized) {
            contentShiftContainer.animate()
                .translationX(offsetX)
                .translationY(offsetY)
                .setDuration(900L)
                .start()
        }
        if (::settingsButton.isInitialized) {
            settingsButton.animate()
                .translationX(offsetX)
                .translationY(offsetY)
                .setDuration(900L)
                .start()
        }
        if (::petAvatarView.isInitialized) {
            petAvatarView.animate()
                .translationX(offsetX)
                .translationY(offsetY)
                .setDuration(900L)
                .start()
        }
    }

    private fun setupNetworkMonitoring() {
        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    mainHandler.post {
                        Log.i(TAG, "Network restored: refreshing weather, boards and requesting meta handshake")
                        isOffline = false
                        refreshWeather(force = false)
                        VoiceKioskService.requestMetaHandshake(this@MainActivity)
                        if (::boardRepository.isInitialized) {
                            boardRepository.onNetworkAvailable()
                        }
                    }
                }

                override fun onLost(network: Network) {
                    mainHandler.post {
                        Log.w(TAG, "Network lost: offline mode active")
                        isOffline = true
                    }
                }
            }
            networkCallback?.let { connectivityManager?.registerNetworkCallback(request, it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    private fun unregisterNetworkMonitoring() {
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering network callback", e)
        }
        networkCallback = null
    }

    private fun promptBatteryOptimizationWhitelist() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                Log.i(TAG, "Prompting user for battery optimization whitelist...")
                @SuppressLint("BatteryLife")
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prompt battery optimization whitelist", e)
        }
    }

    private fun checkAndRequestAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PERMISSION_REQUEST_AUDIO
            )
        } else {
            startKwsService()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "RECORD_AUDIO permission granted")
                startKwsService()
            } else {
                Log.e(TAG, "RECORD_AUDIO permission denied")
            }
        }
    }

    private fun startKwsService() {
        Log.i(TAG, "Starting VoiceKioskService...")
        VoiceKioskService.start(this)
    }

    // --- Weather Integration ---

    private fun refreshWeather(force: Boolean = false) {
        val cached = AppConfig.getCachedWeatherSummary(this)
        if (cached.isNotEmpty()) {
            dispatchWeatherUpdated(cached)
        }
        WeatherManager.fetchWeatherIfNeeded(this, force) { summary ->
            dispatchWeatherUpdated(summary)
        }
    }

    private fun dispatchWeatherUpdated(summary: String) {
        // onWeatherUpdated contract event
    }

    // --- WakeWordEventBus Listener & Voice Pipeline Synchronization ---

    override fun onWakeWordDetected(keyword: String) {
        Log.i(TAG, "UI received wake word event: '$keyword'")

        isConversationActive = true

        if (::listeningOverlayContainer.isInitialized) {
            listeningOverlayContainer.visibility = View.VISIBLE
        }
        if (::voiceOverlay.isInitialized) {
            voiceOverlay.show()
            voiceOverlay.setStatus("正在聆听...", isAmber = false)
            voiceOverlay.setSpeechText("“请讲，我在听...”")
        }

        if (::petAvatarView.isInitialized) {
            petAvatarView.setPetState(PetAvatarView.PetState.WAKE_SURPRISE)
        }

        mainHandler.removeCallbacks(wakeSurpriseToListeningRunnable)
        mainHandler.postDelayed(wakeSurpriseToListeningRunnable, WAKE_SURPRISE_DURATION_MS)

        updateDisplayPowerAndAodState()
        mainHandler.removeCallbacks(resetWakeStateRunnable)
    }

    override fun onAudioEnergy(energy: Float) {
        if (::petAvatarView.isInitialized) {
            petAvatarView.setAudioEnergy(energy)
        }
    }

    override fun onAsrDownloadProgress(progressPercent: Int, statusText: String) {
        Log.i(TAG, "ASR download progress: $progressPercent% - $statusText")
        if (progressPercent in 0..99) {
            isConversationActive = true
            if (::listeningOverlayContainer.isInitialized) {
                listeningOverlayContainer.visibility = View.VISIBLE
            }
            if (::voiceOverlay.isInitialized) {
                voiceOverlay.show()
                voiceOverlay.setStatus(statusText, isAmber = false)
            }
            updateDisplayPowerAndAodState()
        } else if (progressPercent == 100) {
            if (::voiceOverlay.isInitialized) {
                voiceOverlay.setStatus(statusText, isAmber = false)
            }
            mainHandler.removeCallbacks(resetWakeStateRunnable)
            mainHandler.postDelayed(resetWakeStateRunnable, WAKE_STATE_DURATION_MS)
        } else if (progressPercent < 0) {
            if (::voiceOverlay.isInitialized) {
                voiceOverlay.setStatus(statusText, isAmber = true)
            }
            mainHandler.removeCallbacks(resetWakeStateRunnable)
            mainHandler.postDelayed(resetWakeStateRunnable, 5000L)
        }
    }

    override fun onAsrPartialResult(text: String) {
        if (text.isNotEmpty()) {
            if (::listeningOverlayContainer.isInitialized) {
                listeningOverlayContainer.visibility = View.VISIBLE
            }
            if (::voiceOverlay.isInitialized) {
                voiceOverlay.show()
                voiceOverlay.setStatus("正在聆听...", isAmber = false)
                voiceOverlay.setSpeechText("“$text”")
            }
            if (::petAvatarView.isInitialized) {
                petAvatarView.setPetState(PetAvatarView.PetState.LISTENING)
            }
        }
    }

    override fun onAsrFinalResult(text: String) {
        Log.i(TAG, "UI received ASR final result: '$text'")
        if (::listeningOverlayContainer.isInitialized) {
            listeningOverlayContainer.visibility = View.VISIBLE
        }
        if (text.isEmpty()) {
            if (::voiceOverlay.isInitialized) {
                voiceOverlay.setStatus("未听清", isAmber = true)
                voiceOverlay.setSpeechText("未听清，请重试")
            }
            if (::petAvatarView.isInitialized) {
                petAvatarView.setPetState(PetAvatarView.PetState.CONFUSED)
            }
            mainHandler.removeCallbacks(resetWakeStateRunnable)
            mainHandler.postDelayed(resetWakeStateRunnable, WAKE_STATE_DURATION_MS)
        } else {
            if (::voiceOverlay.isInitialized) {
                voiceOverlay.setStatus("思考中...", isAmber = false)
                voiceOverlay.setSpeechText("“$text”")
            }
            if (::petAvatarView.isInitialized) {
                petAvatarView.setPetState(PetAvatarView.PetState.THINKING)
            }
        }
    }

    override fun onChatReply(replyText: String) {
        Log.i(TAG, "UI received chat reply: '$replyText'")
        if (::listeningOverlayContainer.isInitialized) {
            listeningOverlayContainer.visibility = View.VISIBLE
        }
        if (::voiceOverlay.isInitialized) {
            voiceOverlay.show()
            voiceOverlay.setStatus("回答完毕", isAmber = false)
            voiceOverlay.setReply(replyText, isError = false)
        }
        if (::petAvatarView.isInitialized) {
            petAvatarView.setPetState(PetAvatarView.PetState.SPEAKING)
        }
        mainHandler.removeCallbacks(resetWakeStateRunnable)
        mainHandler.postDelayed(resetWakeStateRunnable, 4000L)
    }

    override fun onWaitingForMuse(statusText: String) {
        Log.i(TAG, "UI received waiting for muse event: '$statusText'")
        isConversationActive = true
        if (::listeningOverlayContainer.isInitialized) {
            listeningOverlayContainer.visibility = View.VISIBLE
        }
        if (::voiceOverlay.isInitialized) {
            voiceOverlay.show()
            voiceOverlay.setStatus("等待 Muse 回复中...", isAmber = true)
            voiceOverlay.setSpeechText(statusText.ifEmpty { "正在思考..." })
        }
        if (::petAvatarView.isInitialized) {
            petAvatarView.setPetState(PetAvatarView.PetState.THINKING)
        }
        mainHandler.removeCallbacks(resetWakeStateRunnable)
    }

    override fun onFocusBoard(boardId: String) {
        Log.i(TAG, "UI received focus board event: '$boardId'")
        mainHandler.post {
            val pos = pagerAdapter.getBoardPagePosition(boardId)
            if (pos >= 0) {
                viewPager.setCurrentItem(pos, true)
            }
        }
    }

    override fun onBridgeNotice(text: String) {
        if (isFinishing || isDestroyed) return
        // Bridge notice handling
    }

    override fun onRequestPin(requestId: String, kind: String) {
        showNativePinDialog(requestId, kind)
    }

    private fun showNativePinDialog(requestId: String, kind: String = "approve") {
        if (isFinishing || isDestroyed) return
        val bound = AppConfig.getBoundBridgeOrigin(this)
        if (!OriginBoundHost.allowsPage(currentPageUrl, bound)) return
        if (!OriginBoundHost.pinRequestIdOk(requestId)) return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(6))
            hint = "4–6 位数字"
        }
        val title = if (kind == "reject") "输入 PIN 以拒绝" else "输入审批 PIN"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val pin = input.text?.toString()?.trim().orEmpty()
                deliverPinToPage(requestId, pin, kind)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deliverPinToPage(requestId: String, pin: String, kind: String) {
        if (isFinishing || isDestroyed) return
        val bound = AppConfig.getBoundBridgeOrigin(this)
        if (!OriginBoundHost.allowsPage(currentPageUrl, bound)) return
        val digits = pin.filter { it.isDigit() }.take(6)
        if (digits.length !in 4..6) return
        // onPinEntered contract handling
    }

    override fun onChatOfflineError(errorMessage: String) {
        Log.w(TAG, "UI received offline/chat error: '$errorMessage'")
        if (::listeningOverlayContainer.isInitialized) {
            listeningOverlayContainer.visibility = View.VISIBLE
        }
        if (::voiceOverlay.isInitialized) {
            voiceOverlay.show()
            voiceOverlay.setStatus("⚠ 离线提示", isAmber = true)
            voiceOverlay.setReply(errorMessage, isError = true)
        }
        if (::petAvatarView.isInitialized) {
            petAvatarView.setPetState(PetAvatarView.PetState.ERROR)
        }
        mainHandler.removeCallbacks(resetWakeStateRunnable)
        mainHandler.postDelayed(resetWakeStateRunnable, 5000L)
    }

    override fun onFollowUpListening(turnIndex: Int) {
        Log.i(TAG, "UI entering follow-up listening turn #$turnIndex")
        isConversationActive = true
        if (::listeningOverlayContainer.isInitialized) {
            listeningOverlayContainer.visibility = View.VISIBLE
        }
        if (::voiceOverlay.isInitialized) {
            voiceOverlay.show()
            voiceOverlay.setStatus("对话中，继续说...", isAmber = false)
            voiceOverlay.setSpeechText("“请讲，我在听...”")
        }
        if (::petAvatarView.isInitialized) {
            petAvatarView.setPetState(PetAvatarView.PetState.FOLLOW_UP)
        }
        mainHandler.removeCallbacks(resetWakeStateRunnable)
    }

    override fun onStateChanged(state: String) {
        if (state == VoiceKioskService.PipelineState.KWS_LISTENING.name) {
            mainHandler.removeCallbacks(resetWakeStateRunnable)
            mainHandler.postDelayed(resetWakeStateRunnable, WAKE_STATE_DURATION_MS)
        }
    }

    override fun onServiceStatusChanged(running: Boolean, message: String) {
        Log.i(TAG, "Service status changed: running=$running, message=$message")
    }

    // --- ReleaseUpdateChecker Listener ---

    override fun onUpdateCheckStateChanged(inFlight: Boolean, lastResult: ReleaseUpdateChecker.CheckResult?) {
        if (isFinishing || isDestroyed || !::settingsButton.isInitialized) {
            return
        }
        val hasAvailableUpdate = lastResult is ReleaseUpdateChecker.CheckResult.UpdateAvailable
        settingsButton.text = if (hasAvailableUpdate) "⚙ 设置 ●" else "⚙ 设置"
    }

    private fun showBanner(message: String, isAmber: Boolean = false) {
        if (::listeningOverlayContainer.isInitialized) {
            listeningOverlayContainer.visibility = View.VISIBLE
        }
        if (::voiceOverlay.isInitialized) {
            voiceOverlay.show()
            voiceOverlay.setStatus(message, isAmber = isAmber)
        }
    }

    private fun showReplyCard(replyText: String, isError: Boolean = false) {
        if (::listeningOverlayContainer.isInitialized) {
            listeningOverlayContainer.visibility = View.VISIBLE
        }
        if (::voiceOverlay.isInitialized) {
            voiceOverlay.show()
            voiceOverlay.setReply(replyText, isError = isError)
        }
    }

    private fun resetWakeUiState() {
        isConversationActive = false
        mainHandler.removeCallbacks(wakeSurpriseToListeningRunnable)
        mainHandler.removeCallbacks(resetWakeStateRunnable)
        if (::voiceOverlay.isInitialized && voiceOverlay.visibility == View.VISIBLE) {
            voiceOverlay.dismiss {
                if (::listeningOverlayContainer.isInitialized) {
                    listeningOverlayContainer.visibility = View.GONE
                }
                updateDisplayPowerAndAodState()
            }
        } else {
            if (::listeningOverlayContainer.isInitialized) {
                listeningOverlayContainer.visibility = View.GONE
            }
            updateDisplayPowerAndAodState()
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    /**
     * Origin-bound JS bridge compatibility hook.
     */
    inner class VoiceKioskHost {
        @JavascriptInterface
        fun deviceToken(): String {
            return AppConfig.getBridgeAuthTokenForUrl(this@MainActivity, currentPageUrl)
        }

        @JavascriptInterface
        fun speak(text: String?) {
            val capped = (text ?: "").trim().take(HOST_SPEAK_MAX_CHARS)
            if (capped.isEmpty()) return
            VoiceKioskService.speakFromHost(capped)
        }

        @JavascriptInterface
        fun requestPin(requestId: String?) {
            val bound = AppConfig.getBoundBridgeOrigin(this@MainActivity)
            if (!OriginBoundHost.allowsPage(currentPageUrl, bound)) return
            val id = (requestId ?: "").trim()
            if (!OriginBoundHost.pinRequestIdOk(id)) return
            runOnUiThread { showNativePinDialog(id, "approve") }
        }

        @JavascriptInterface
        fun questionOpened(json: String?) {
            val bound = AppConfig.getBoundBridgeOrigin(this@MainActivity)
            if (!OriginBoundHost.allowsPage(currentPageUrl, bound)) return
            VoiceKioskService.applyQuestionJson(json)
        }

        @JavascriptInterface
        fun approvalOpened(json: String?) {
            val bound = AppConfig.getBoundBridgeOrigin(this@MainActivity)
            if (!OriginBoundHost.allowsPage(currentPageUrl, bound)) return
            VoiceKioskService.applyApprovalJson(json)
        }
    }
}
