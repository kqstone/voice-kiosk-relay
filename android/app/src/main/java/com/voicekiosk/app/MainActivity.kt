package com.voicekiosk.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
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
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.voicekiosk.app.bridge.OriginBoundHost
import java.util.Calendar
import java.util.Random

/**
 * MainActivity for Voice Kiosk:
 * - Fullscreen WebView loading DASHBOARD_URL (fallback to local placeholder)
 * - Pure 2D Canvas vector PetAvatarView with 9 expressive states
 * - Structured Conversation HUD (Wake banner, User speech, AI reply card)
 * - OLED Always-On Display (AOD) standby vs. active visual power states
 * - Immersive sticky mode & FLAG_KEEP_SCREEN_ON
 * - Auto-reconnect when network returns & automatic weather sync
 * - Periodic memory monitor to reload WebView on memory growth
 * - Night mode black overlay dimming with configurable schedule
 * - OLED anti-burn-in position shifting with smooth translation animations
 * - Battery-optimization whitelist prompt & Keep-alive
 */
class MainActivity : Activity(), WakeWordEventBus.Listener, ReleaseUpdateChecker.Listener {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_AUDIO = 201
        private const val MEMORY_CHECK_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes
        private const val BURN_IN_SHIFT_INTERVAL_MS = 60 * 1000L // 60 seconds
        private const val WAKE_STATE_DURATION_MS = 3500L // 3.5 seconds
        private const val WAKE_SURPRISE_DURATION_MS = 650L // 650ms initial wake reaction before listening
        private const val TOUCH_WAKE_DURATION_MS = 8000L // 8 seconds of active display on touch
        private const val WEATHER_REFRESH_INTERVAL_MS = 30 * 60 * 1000L // 30 minutes
        const val HOST_SPEAK_MAX_CHARS = VoiceKioskService.HOST_SPEAK_MAX_CHARS
    }

    /** Main-frame URL, updated on the UI thread; read by the JS bridge thread (WebView APIs are UI-thread only). */
    @Volatile
    private var currentPageUrl: String = ""

    private lateinit var rootLayout: FrameLayout
    private lateinit var webView: WebView
    private lateinit var petAvatarView: PetAvatarView
    private lateinit var conversationHudLayout: LinearLayout
    private lateinit var wakeBanner: TextView
    private lateinit var userSpeechView: TextView
    private lateinit var replyCardView: TextView
    private lateinit var nightOverlayView: View
    private lateinit var settingsButton: TextView

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

    private val memoryCheckRunnable = object : Runnable {
        override fun run() {
            checkWebViewMemory()
            mainHandler.postDelayed(this, MEMORY_CHECK_INTERVAL_MS)
        }
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

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
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

        // 2. Build programmatically styled layout
        setupViews()

        // Apply Immersive Mode
        applyImmersiveMode()

        // 3. Register EventBus listener
        WakeWordEventBus.register(this)

        // 4. Setup Network Callback for Auto-reconnect & Weather refresh
        setupNetworkMonitoring()

        // 5. Load Dashboard URL
        loadDashboard()

        // 6. Check battery optimization whitelist
        promptBatteryOptimizationWhitelist()

        // 7. Request audio permission & start service
        checkAndRequestAudioPermission()

        // 8. Schedule periodic monitors
        mainHandler.postDelayed(memoryCheckRunnable, MEMORY_CHECK_INTERVAL_MS)
        mainHandler.postDelayed(burnInShiftRunnable, BURN_IN_SHIFT_INTERVAL_MS)
        mainHandler.postDelayed(weatherRefreshRunnable, WEATHER_REFRESH_INTERVAL_MS)

        // 9. Initial AOD state update and Weather refresh
        updateDisplayPowerAndAodState()
        refreshWeather(force = false)

        // 10. Register update listener and optionally run startup update check (default disabled, daily cap, silent badge only)
        val updateManager = AndroidReleaseUpdatePlatform.getManager(this)
        updateManager.registerListener(this)
        updateManager.maybeTriggerStartupCheck(AppConfig.isStartupUpdateCheckEnabled(this))
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
        updateDisplayPowerAndAodState()
        refreshWeather(force = false)
        val configuredUrl = AppConfig.getDashboardUrl(this)
        if (webView.url != null && webView.url != configuredUrl && !isOffline) {
            loadDashboard()
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

    override fun onDestroy() {
        Log.i(TAG, "MainActivity onDestroy")
        AndroidReleaseUpdatePlatform.getManager(this).unregisterListener(this)
        WakeWordEventBus.unregister(this)
        mainHandler.removeCallbacksAndMessages(null)
        unregisterNetworkMonitoring()
        try {
            webView.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying WebView", e)
        }
        super.onDestroy()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupViews() {
        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        // 1. WebView configuration (at bottom of rootLayout)
        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            addJavascriptInterface(VoiceKioskHost(), "VoiceKioskHost")
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        Log.w(TAG, "WebView load error: ${error?.description}")
                        isOffline = true
                        // Fallback to local placeholder page on network failure
                        if (view?.url != AppConfig.LOCAL_PLACEHOLDER_DASHBOARD_URL) {
                            view?.loadUrl(AppConfig.LOCAL_PLACEHOLDER_DASHBOARD_URL)
                        }
                    }
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    currentPageUrl = url.orEmpty()
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    currentPageUrl = url.orEmpty()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    currentPageUrl = view?.url ?: url.orEmpty()
                    refreshWeather(force = false)
                    updateDisplayPowerAndAodState()
                }
            }
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    onUserInteractionWake()
                }
                false
            }
        }
        rootLayout.addView(webView)

        // 2. PetAvatarView (dedicated companion pet avatar in lower half of screen)
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
        rootLayout.addView(petAvatarView)

        // 3. Structured Conversation HUD centered horizontally above the pet
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

        // Child 1: wakeBanner (compact rounded status pill)
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

        // Child 2: userSpeechView (rounded bubble showing user utterance)
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

        // Child 3: replyCardView (elevated dark card showing AI response or error)
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

        rootLayout.addView(conversationHudLayout)

        // 4. Night Mode dimming overlay (black overlay above pet & HUD, non-clickable so touches pass through)
        nightOverlayView = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            alpha = 0.0f
            visibility = View.GONE
            isClickable = false
            isFocusable = false
        }
        rootLayout.addView(nightOverlayView)

        // 5. Settings button in top-right of dashboard layout
        settingsButton = TextView(this).apply {
            val dp12 = dpToPx(12)
            val dp8 = dpToPx(8)
            setPadding(dp12, dp8, dp12, dp8)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            text = "⚙ 设置"
            gravity = Gravity.CENTER

            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(16).toFloat()
                setColor(Color.parseColor("#80000000"))
                setStroke(dpToPx(1), Color.parseColor("#40FFFFFF"))
            }

            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dpToPx(16)
                marginEnd = dpToPx(16)
            }
            layoutParams = lp

            setOnClickListener {
                val intent = Intent(this@MainActivity, SettingsActivity::class.java)
                startActivity(intent)
            }
        }
        rootLayout.addView(settingsButton)

        setContentView(rootLayout)
    }

    private fun loadDashboard() {
        val targetUrl = AppConfig.getDashboardUrl(this)
        Log.i(TAG, "Loading dashboard URL: $targetUrl")
        webView.loadUrl(targetUrl)
    }

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
    private fun updateDisplayPowerAndAodState() {
        if (isFinishing || isDestroyed) return
        val isAodStandby = !isConversationActive && !isTouchAwake

        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val isNight = AppConfig.isNightModeEnabled(this) &&
            AppConfig.isNightHour(hour, AppConfig.getNightModeStartHour(this), AppConfig.getNightModeEndHour(this))

        // Update Pet Avatar visibility based on settings
        if (::petAvatarView.isInitialized) {
            petAvatarView.visibility = if (AppConfig.isPetAvatarEnabled(this)) View.VISIBLE else View.GONE
        }

        val lp = window.attributes

        if (isAodStandby) {
            // Standby Mode:
            // 1. Hide settings button
            if (::settingsButton.isInitialized) {
                settingsButton.visibility = View.GONE
            }

            // 2. Hide conversation HUD
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

            // 3. Pet Avatar state: AOD_SLEEP or AOD_IDLE
            if (::petAvatarView.isInitialized) {
                petAvatarView.setPetState(
                    if (isNight) PetAvatarView.PetState.AOD_SLEEP else PetAvatarView.PetState.AOD_IDLE
                )
            }

            // 4. Configure nightOverlayView and window brightness
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

            // 5. Dispatch AOD mode state to WebView
            if (::webView.isInitialized) {
                webView.evaluateJavascript(
                    "if (typeof onAodModeChanged === 'function') onAodModeChanged(true);",
                    null
                )
            }
        } else {
            // Active Mode (via Voice Wake or Touch):
            // 1. Show settings button
            if (::settingsButton.isInitialized) {
                settingsButton.visibility = View.VISIBLE
            }

            // 2. Restore full screen brightness
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = lp

            // 3. Smoothly fade out night overlay
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

            // 4. If woken by touch only and pet was in AOD mode, wake it up to look attentively
            if (!isConversationActive && ::petAvatarView.isInitialized) {
                val currentPetState = petAvatarView.getPetState()
                if (currentPetState == PetAvatarView.PetState.AOD_IDLE ||
                    currentPetState == PetAvatarView.PetState.AOD_SLEEP
                ) {
                    petAvatarView.setPetState(PetAvatarView.PetState.FOLLOW_UP)
                }
            }

            // 5. Dispatch Active mode state to WebView
            if (::webView.isInitialized) {
                webView.evaluateJavascript(
                    "if (typeof onAodModeChanged === 'function') onAodModeChanged(false);",
                    null
                )
            }
        }
    }

    private fun checkAndUpdateNightMode() {
        updateDisplayPowerAndAodState()
    }

    private fun shiftStaticElementsForBurnIn() {
        // OLED Anti-Burn-In: shift static elements smoothly by small random offset (-8 to +8 px)
        val offsetX = (random.nextInt(17) - 8).toFloat()
        val offsetY = (random.nextInt(17) - 8).toFloat()

        if (::conversationHudLayout.isInitialized) {
            conversationHudLayout.animate().translationX(offsetX).translationY(offsetY).setDuration(900L).start()
        }
        if (::petAvatarView.isInitialized) {
            petAvatarView.animate().translationX(offsetX).translationY(offsetY).setDuration(900L).start()
        }
        if (::settingsButton.isInitialized) {
            settingsButton.animate().translationX(offsetX).translationY(offsetY).setDuration(900L).start()
        }
    }

    private fun checkWebViewMemory() {
        try {
            val runtime = Runtime.getRuntime()
            val usedMemMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
            Log.i(TAG, "WebView Memory check: used ${usedMemMb}MB / max ${runtime.maxMemory() / (1024 * 1024)}MB")

            if (usedMemMb > AppConfig.WEBVIEW_MEMORY_THRESHOLD_MB) {
                Log.w(TAG, "Memory threshold exceeded (${usedMemMb}MB > ${AppConfig.WEBVIEW_MEMORY_THRESHOLD_MB}MB), reloading WebView...")
                webView.reload()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking memory", e)
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
                        Log.i(TAG, "Network restored: auto-reconnecting dashboard and refreshing weather")
                        isOffline = false
                        webView.evaluateJavascript("if (typeof onNetworkStateChanged === 'function') onNetworkStateChanged(true);", null)
                        val targetUrl = AppConfig.getDashboardUrl(this@MainActivity)
                        if (webView.url != targetUrl && targetUrl != AppConfig.LOCAL_PLACEHOLDER_DASHBOARD_URL) {
                            webView.loadUrl(targetUrl)
                        }
                        refreshWeather(force = false)
                        VoiceKioskService.requestMetaHandshake(this@MainActivity)
                    }
                }

                override fun onLost(network: Network) {
                    mainHandler.post {
                        Log.w(TAG, "Network lost: switching to offline mode")
                        isOffline = true
                        webView.evaluateJavascript("if (typeof onNetworkStateChanged === 'function') onNetworkStateChanged(false);", null)
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
            pushWeatherToWebView(cached)
        }
        WeatherManager.fetchWeatherIfNeeded(this, force) { summary ->
            pushWeatherToWebView(summary)
        }
    }

    private fun pushWeatherToWebView(summary: String) {
        if (isFinishing || isDestroyed || !::webView.isInitialized) return
        val escaped = escapeJs(summary)
        webView.evaluateJavascript(
            "if (typeof onWeatherUpdated === 'function') onWeatherUpdated('$escaped');",
            null
        )
    }

    // --- WakeWordEventBus Listener & Pet State Synchronization ---

    override fun onWakeWordDetected(keyword: String) {
        Log.i(TAG, "UI received wake word event: '$keyword'")

        // 1. Mark conversation active
        isConversationActive = true

        // 2. Hide previous utterance and reply views
        if (::userSpeechView.isInitialized) userSpeechView.visibility = View.GONE
        if (::replyCardView.isInitialized) replyCardView.visibility = View.GONE

        // 3. Set pet state to WAKE_SURPRISE
        if (::petAvatarView.isInitialized) {
            petAvatarView.setPetState(PetAvatarView.PetState.WAKE_SURPRISE)
        }

        // 4. Schedule transition to LISTENING after 650ms if still in conversation
        mainHandler.removeCallbacks(wakeSurpriseToListeningRunnable)
        mainHandler.postDelayed(wakeSurpriseToListeningRunnable, WAKE_SURPRISE_DURATION_MS)

        // 5. Update display and AOD state
        updateDisplayPowerAndAodState()

        // 6. Update native UI banner
        showBanner("我在听...", "#10B981", "#34D399")

        // 7. Dispatch to WebView JavaScript
        val escaped = escapeJs(keyword)
        webView.evaluateJavascript("if (typeof onWakeWord === 'function') onWakeWord('$escaped');", null)

        // Cancel any auto-hide while ASR is active
        mainHandler.removeCallbacks(resetWakeStateRunnable)
    }

    override fun onAudioEnergy(energy: Float) {
        if (::petAvatarView.isInitialized) {
            petAvatarView.setAudioEnergy(energy)
        }
    }

    override fun onAsrDownloadProgress(progressPercent: Int, statusText: String) {
        Log.i(TAG, "ASR download progress: $progressPercent% - $statusText")
        val escaped = escapeJs(statusText)
        webView.evaluateJavascript(
            "if (typeof onAsrDownloadProgress === 'function') onAsrDownloadProgress($progressPercent, '$escaped');",
            null
        )
        if (progressPercent in 0..99) {
            isConversationActive = true
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
            if (::petAvatarView.isInitialized) {
                petAvatarView.setPetState(PetAvatarView.PetState.LISTENING)
            }
            if (::userSpeechView.isInitialized && ::conversationHudLayout.isInitialized) {
                userSpeechView.text = "🎙 你说：$text ..."
                userSpeechView.visibility = View.VISIBLE
                conversationHudLayout.visibility = View.VISIBLE
            }
            val escaped = escapeJs(text)
            webView.evaluateJavascript("if (typeof onAsrPartial === 'function') onAsrPartial('$escaped');", null)
        }
    }

    override fun onAsrFinalResult(text: String) {
        Log.i(TAG, "UI received ASR final result: '$text'")
        if (text.isEmpty()) {
            if (::petAvatarView.isInitialized) {
                petAvatarView.setPetState(PetAvatarView.PetState.CONFUSED)
            }
            showBanner("未听清，请再唤醒一次", "#6B7280", "#9CA3AF")
            webView.evaluateJavascript("if (typeof onAsrFinal === 'function') onAsrFinal('');", null)
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
            val escaped = escapeJs(text)
            webView.evaluateJavascript("if (typeof onAsrFinal === 'function') onAsrFinal('$escaped');", null)
        }
    }

    override fun onChatReply(replyText: String) {
        Log.i(TAG, "UI received chat reply: '$replyText'")
        if (::petAvatarView.isInitialized) {
            petAvatarView.setPetState(PetAvatarView.PetState.SPEAKING)
        }
        showBanner("正在回答...", "#4F46E5", "#818CF8")
        showReplyCard(replyText, isError = false)
        val escaped = escapeJs(replyText)
        webView.evaluateJavascript("if (typeof onChatReply === 'function') onChatReply('$escaped');", null)
    }

    override fun onFocusBoard(boardId: String) {
        Log.i(TAG, "UI received focus board event: '$boardId'")
        val escaped = escapeJs(boardId)
        webView.evaluateJavascript("if (typeof onFocusBoard === 'function') onFocusBoard('$escaped', 180);", null)
    }

    override fun onBridgeNotice(text: String) {
        if (isFinishing || isDestroyed || !::webView.isInitialized) return
        val escaped = escapeJs(text)
        webView.evaluateJavascript(
            "if (typeof onBridgeNotice === 'function') onBridgeNotice('$escaped');",
            null
        )
    }

    override fun onRequestPin(requestId: String, kind: String) {
        showNativePinDialog(requestId, kind)
    }

    /**
     * Numeric PIN dialog. The digits are handed to the dashboard via
     * `onPinEntered(requestId, pin)` and never posted as an approval from native code.
     */
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
        if (isFinishing || isDestroyed || !::webView.isInitialized) return
        val bound = AppConfig.getBoundBridgeOrigin(this)
        if (!OriginBoundHost.allowsPage(currentPageUrl, bound)) return
        val digits = pin.filter { it.isDigit() }.take(6)
        if (digits.length !in 4..6) return
        val idEsc = escapeJs(requestId)
        val pinEsc = escapeJs(digits)
        val kindEsc = escapeJs(if (kind == "reject") "reject" else "approve")
        webView.evaluateJavascript(
            "if (typeof onPinEntered === 'function') onPinEntered('$idEsc', '$pinEsc', '$kindEsc');",
            null
        )
    }

    override fun onChatOfflineError(errorMessage: String) {
        Log.w(TAG, "UI received offline/chat error: '$errorMessage'")
        if (::petAvatarView.isInitialized) {
            petAvatarView.setPetState(PetAvatarView.PetState.ERROR)
        }
        showBanner("⚠ 离线提示", "#DC2626", "#F87171")
        showReplyCard(errorMessage, isError = true)
        val escaped = escapeJs(errorMessage)
        webView.evaluateJavascript("if (typeof onChatOffline === 'function') onChatOffline('$escaped');", null)
        mainHandler.removeCallbacks(resetWakeStateRunnable)
        mainHandler.postDelayed(resetWakeStateRunnable, 5000L)
    }

    override fun onFollowUpListening(turnIndex: Int) {
        Log.i(TAG, "UI entering follow-up listening turn #$turnIndex")
        isConversationActive = true
        if (::petAvatarView.isInitialized) {
            petAvatarView.setPetState(PetAvatarView.PetState.FOLLOW_UP)
        }
        showBanner("对话中，继续说...", "#10B981", "#34D399")
        webView.evaluateJavascript("if (typeof onFollowUpListening === 'function') onFollowUpListening($turnIndex);", null)
        mainHandler.removeCallbacks(resetWakeStateRunnable)
    }

    override fun onStateChanged(state: String) {
        val escaped = escapeJs(state)
        webView.evaluateJavascript("if (typeof onPipelineStateChanged === 'function') onPipelineStateChanged('$escaped');", null)
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

    // --- ReleaseUpdateChecker Listener (Silent badge only; never pops dialogs or plays audio) ---

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
        updateDisplayPowerAndAodState()
    }

    private fun escapeJs(raw: String): String {
        return raw
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    /**
     * Origin-bound JS bridge for the kiosk dashboard. [deviceToken] returns the stored
     * device token only when the current WebView URL origin equals the origin the token
     * is bound to; otherwise "". Never logs the token.
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
