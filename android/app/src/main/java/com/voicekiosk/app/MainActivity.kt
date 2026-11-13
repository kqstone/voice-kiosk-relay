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
import com.voicekiosk.app.ui.MainPagerAdapter
import com.voicekiosk.app.ui.PageIndicatorView
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
    private lateinit var settingsButton: TextView
    private lateinit var listeningOverlayContainer: FrameLayout
    private lateinit var nightOverlayView: View

    // Preserved Conversation HUD views (used during voice listening until Phase 6 overlay)
    private lateinit var petAvatarView: PetAvatarView
    private lateinit var conversationHudLayout: LinearLayout
    private lateinit var wakeBanner: TextView
    private lateinit var userSpeechView: TextView
    private lateinit var replyCardView: TextView

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
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
        updateDisplayPowerAndAodState()
        refreshWeather(force = false)
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

    override fun onDestroy() {
        Log.i(TAG, "MainActivity onDestroy")
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

        // 1. Initialize ViewPager2 Navigation Pager Framework
        pagerAdapter = MainPagerAdapter(pageCount = 3)
        viewPager.adapter = pagerAdapter
        viewPager.offscreenPageLimit = 2
        pageIndicator.setPageCount(pagerAdapter.itemCount)

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

        // 3. Setup conversation HUD views in listeningOverlayContainer for voice interaction continuity
        setupConversationHudViews()

        // Compatibility JS hook initialization
        setupJsBridgeCompatibility()
    }

    private fun setupConversationHudViews() {
        petAvatarView = PetAvatarView(this).apply {
            val size = dpToPx(210)
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dpToPx(44)
            }
            setOnPetTappedListener {
                onUserInteractionWake()
            }
        }
        listeningOverlayContainer.addView(petAvatarView)

        val hudMaxWidth = (resources.displayMetrics.widthPixels * 0.86f).toInt()
        conversationHudLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val lp = FrameLayout.LayoutParams(
                if (hudMaxWidth > 0) hudMaxWidth else FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dpToPx(250)
            }
            layoutParams = lp
            visibility = View.GONE
        }

        wakeBanner = TextView(this).apply {
            val padH = dpToPx(16)
            val padV = dpToPx(6)
            setPadding(padH, padV, padH, padV)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            text = "我在听..."
            gravity = Gravity.CENTER

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            layoutParams = lp
            visibility = View.GONE
        }
        conversationHudLayout.addView(wakeBanner)

        userSpeechView = TextView(this).apply {
            val padH = dpToPx(14)
            val padV = dpToPx(8)
            setPadding(padH, padV, padH, padV)
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(16).toFloat()
                setColor(Color.parseColor("#CC0F172A"))
                setStroke(dpToPx(1), Color.parseColor("#38BDF8"))
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dpToPx(8)
            }
            layoutParams = lp
            visibility = View.GONE
        }
        conversationHudLayout.addView(userSpeechView)

        replyCardView = TextView(this).apply {
            val padH = dpToPx(18)
            val padV = dpToPx(12)
            setPadding(padH, padV, padH, padV)
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setLineSpacing(0f, 1.25f)
            maxLines = 6
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(18).toFloat()
                setColor(Color.parseColor("#E61E293B"))
                setStroke(dpToPx(1.5f).coerceAtLeast(1), Color.parseColor("#6366F1"))
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dpToPx(8)
            }
            layoutParams = lp
            visibility = View.GONE
        }
        conversationHudLayout.addView(replyCardView)

        listeningOverlayContainer.addView(conversationHudLayout)
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

            if (::listeningOverlayContainer.isInitialized) {
                listeningOverlayContainer.visibility = View.GONE
            }
            if (::conversationHudLayout.isInitialized) {
                conversationHudLayout.visibility = View.GONE
            }
            if (::wakeBanner.isInitialized) {
                wakeBanner.visibility = View.GONE
            }
            if (::userSpeechView.isInitialized) {
                userSpeechView.visibility = View.GONE
            }
            if (::replyCardView.isInitialized) {
                replyCardView.visibility = View.GONE
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
        if (::conversationHudLayout.isInitialized) {
            conversationHudLayout.animate()
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
                        Log.i(TAG, "Network restored: refreshing weather and requesting meta handshake")
                        isOffline = false
                        refreshWeather(force = false)
                        VoiceKioskService.requestMetaHandshake(this@MainActivity)
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
        if (::userSpeechView.isInitialized) userSpeechView.visibility = View.GONE
        if (::replyCardView.isInitialized) replyCardView.visibility = View.GONE

        if (::petAvatarView.isInitialized) {
            petAvatarView.setPetState(PetAvatarView.PetState.WAKE_SURPRISE)
        }

        mainHandler.removeCallbacks(wakeSurpriseToListeningRunnable)
        mainHandler.postDelayed(wakeSurpriseToListeningRunnable, WAKE_SURPRISE_DURATION_MS)

        updateDisplayPowerAndAodState()
        showBanner("我在听...", "#10B981", "#34D399")

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
            updateDisplayPowerAndAodState()
            showBanner(statusText, "#2563EB", "#60A5FA")
        } else if (progressPercent == 100) {
            showBanner(statusText, "#10B981", "#34D399")
            mainHandler.removeCallbacks(resetWakeStateRunnable)
            mainHandler.postDelayed(resetWakeStateRunnable, WAKE_STATE_DURATION_MS)
        } else if (progressPercent < 0) {
            showBanner(statusText, "#DC2626", "#F87171")
            mainHandler.removeCallbacks(resetWakeStateRunnable)
            mainHandler.postDelayed(resetWakeStateRunnable, 5000L)
        }
    }

    override fun onAsrPartialResult(text: String) {
        if (text.isNotEmpty()) {
            if (::listeningOverlayContainer.isInitialized) {
                listeningOverlayContainer.visibility = View.VISIBLE
            }
            if (::petAvatarView.isInitialized) {
                petAvatarView.setPetState(PetAvatarView.PetState.LISTENING)
            }
            if (::userSpeechView.isInitialized && ::conversationHudLayout.isInitialized) {
                userSpeechView.text = "🎙 你说：$text ..."
                userSpeechView.visibility = View.VISIBLE
                conversationHudLayout.visibility = View.VISIBLE
            }
        }
    }

    override fun onAsrFinalResult(text: String) {
        Log.i(TAG, "UI received ASR final result: '$text'")
        if (::listeningOverlayContainer.isInitialized) {
            listeningOverlayContainer.visibility = View.VISIBLE
        }
        if (text.isEmpty()) {
            if (::petAvatarView.isInitialized) {
                petAvatarView.setPetState(PetAvatarView.PetState.CONFUSED)
            }
            showBanner("未听清，请再唤醒一次", "#6B7280", "#9CA3AF")
            mainHandler.removeCallbacks(resetWakeStateRunnable)
            mainHandler.postDelayed(resetWakeStateRunnable, WAKE_STATE_DURATION_MS)
        } else {
            if (::petAvatarView.isInitialized) {
                petAvatarView.setPetState(PetAvatarView.PetState.THINKING)
            }
            if (::userSpeechView.isInitialized && ::conversationHudLayout.isInitialized) {
                userSpeechView.text = "🎙 你说：$text"
                userSpeechView.visibility = View.VISIBLE
                conversationHudLayout.visibility = View.VISIBLE
            }
            showBanner("思考中...", "#0284C7", "#38BDF8")
        }
    }

    override fun onChatReply(replyText: String) {
        Log.i(TAG, "UI received chat reply: '$replyText'")
        if (::listeningOverlayContainer.isInitialized) {
            listeningOverlayContainer.visibility = View.VISIBLE
        }
        if (::petAvatarView.isInitialized) {
            petAvatarView.setPetState(PetAvatarView.PetState.SPEAKING)
        }
        showBanner("正在回答...", "#4F46E5", "#818CF8")
        showReplyCard(replyText, isError = false)
    }

    override fun onWaitingForMuse(statusText: String) {
        Log.i(TAG, "UI received waiting for muse event: '$statusText'")
        isConversationActive = true
        if (::listeningOverlayContainer.isInitialized) {
            listeningOverlayContainer.visibility = View.VISIBLE
        }
        if (::petAvatarView.isInitialized) {
            petAvatarView.setPetState(PetAvatarView.PetState.THINKING)
        }
        showBanner("等待回复中...", "#D97706", "#FBBF24")
        showReplyCard(statusText, isError = false)
        mainHandler.removeCallbacks(resetWakeStateRunnable)
    }

    override fun onFocusBoard(boardId: String) {
        Log.i(TAG, "UI received focus board event: '$boardId'")
        // Handled in Phase 4 for dynamic board page selection
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
        if (::petAvatarView.isInitialized) {
            petAvatarView.setPetState(PetAvatarView.PetState.ERROR)
        }
        showBanner("⚠ 离线提示", "#DC2626", "#F87171")
        showReplyCard(errorMessage, isError = true)
        mainHandler.removeCallbacks(resetWakeStateRunnable)
        mainHandler.postDelayed(resetWakeStateRunnable, 5000L)
    }

    override fun onFollowUpListening(turnIndex: Int) {
        Log.i(TAG, "UI entering follow-up listening turn #$turnIndex")
        isConversationActive = true
        if (::listeningOverlayContainer.isInitialized) {
            listeningOverlayContainer.visibility = View.VISIBLE
        }
        if (::petAvatarView.isInitialized) {
            petAvatarView.setPetState(PetAvatarView.PetState.FOLLOW_UP)
        }
        showBanner("对话中，继续说...", "#10B981", "#34D399")
        mainHandler.removeCallbacks(resetWakeStateRunnable)
    }

    override fun onStateChanged(state: String) {
        if (state == VoiceKioskService.PipelineState.KWS_LISTENING.name) {
            val currentText = if (::wakeBanner.isInitialized) wakeBanner.text?.toString().orEmpty() else ""
            val isErrorOrNotice = currentText.startsWith("⚠") || currentText.startsWith("未听清")
            if (!isErrorOrNotice) {
                mainHandler.removeCallbacks(resetWakeStateRunnable)
                mainHandler.postDelayed(resetWakeStateRunnable, WAKE_STATE_DURATION_MS)
            }
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

    private fun showBanner(message: String, bgColorHex: String, borderColorHex: String) {
        if (!::wakeBanner.isInitialized || !::conversationHudLayout.isInitialized) return
        wakeBanner.text = message
        wakeBanner.visibility = View.VISIBLE
        val bgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(18).toFloat()
            setColor(Color.parseColor(bgColorHex))
            setStroke(dpToPx(1.5f).coerceAtLeast(1), Color.parseColor(borderColorHex))
        }
        wakeBanner.background = bgDrawable
        conversationHudLayout.visibility = View.VISIBLE
    }

    private fun showReplyCard(replyText: String, isError: Boolean = false) {
        if (!::replyCardView.isInitialized || !::conversationHudLayout.isInitialized) return
        replyCardView.text = replyText
        val bgColor = Color.parseColor("#E61E293B")
        val strokeColor = if (isError) Color.parseColor("#DC2626") else Color.parseColor("#6366F1")
        val textColor = if (isError) Color.parseColor("#FCA5A5") else Color.parseColor("#F8FAFC")
        replyCardView.setTextColor(textColor)
        replyCardView.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(18).toFloat()
            setColor(bgColor)
            setStroke(dpToPx(1.5f).coerceAtLeast(1), strokeColor)
        }
        replyCardView.visibility = View.VISIBLE
        conversationHudLayout.visibility = View.VISIBLE

        replyCardView.alpha = 0f
        replyCardView.translationY = dpToPx(12).toFloat()
        replyCardView.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(240L)
            .start()
    }

    private fun resetWakeUiState() {
        isConversationActive = false
        mainHandler.removeCallbacks(wakeSurpriseToListeningRunnable)
        if (::conversationHudLayout.isInitialized) {
            conversationHudLayout.visibility = View.GONE
        }
        if (::wakeBanner.isInitialized) {
            wakeBanner.visibility = View.GONE
        }
        if (::userSpeechView.isInitialized) {
            userSpeechView.visibility = View.GONE
        }
        if (::replyCardView.isInitialized) {
            replyCardView.visibility = View.GONE
        }
        if (::listeningOverlayContainer.isInitialized) {
            listeningOverlayContainer.visibility = View.GONE
        }
        updateDisplayPowerAndAodState()
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
