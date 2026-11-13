package com.voicekiosk.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * SettingsActivity for Voice Kiosk:
 * - Programmatic UI (no XML layout files)
 * - Custom wake word input with 1-6 Chinese characters validation & preset chip highlighting
 * - Verification against model tokens.txt before saving
 * - Threshold slider (range 0.10 - 0.50 in 0.05 steps, default 0.25)
 * - Zero-API-key weather location switcher (preset cities & geocoding search)
 * - OLED AOD standby & night mode schedule/dimmer controls
 * - Device token: DEVICE_TOKEN configured from NAS .env for bridge authentication
 *   (FLAG_SECURE, never echoed or logged)
 * - Immediate restart of VoiceKioskService upon save
 * - GitHub Release update checker card: current version display, manual check button,
 *   optional startup check toggle (default false, daily frequency cap, silent badge only),
 *   Chinese status feedback, and explicit user-confirmed browser launch for official Release/APK.
 */
class SettingsActivity : Activity(), ReleaseUpdateChecker.Listener {

    companion object {
        private const val TAG = "SettingsActivity"
        private const val MIN_THRESHOLD = 0.10f
        private const val THRESHOLD_STEP = 0.05f
        private const val MAX_STEPS = 8 // 0.10 to 0.50 -> 8 steps of 0.05

        private const val MIN_NIGHT_DIM = 0.30f
        private const val NIGHT_DIM_STEP = 0.05f
        private const val MAX_NIGHT_DIM_STEPS = 13 // 0.30 to 0.95 -> 13 steps of 0.05
    }

    // Wake word & threshold
    private lateinit var wakeWordEditText: EditText
    private val wakeWordPresetChips = mutableListOf<TextView>()
    private lateinit var thresholdSeekBar: SeekBar
    private lateinit var thresholdValueText: TextView

    // Weather & Location
    private var selectedWeatherCity: String = ""
    private var selectedWeatherLat: Float = AppConfig.DEFAULT_WEATHER_LATITUDE
    private var selectedWeatherLon: Float = AppConfig.DEFAULT_WEATHER_LONGITUDE
    private lateinit var weatherStatusText: TextView
    private lateinit var weatherCityEditText: EditText
    private val weatherPresetChips = mutableListOf<Pair<TextView, CityLocation>>()

    // OLED Standby & Night Mode
    private lateinit var onDeviceRoutingCheckBox: CheckBox
    private lateinit var onDeviceModelCheckBox: CheckBox
    private lateinit var onDeviceDebugCheckBox: CheckBox
    private lateinit var onDeviceChitchatCheckBox: CheckBox
    private lateinit var onDeviceSpeculativeCheckBox: CheckBox
    private lateinit var memoryExtractionCheckBox: CheckBox
    private lateinit var onDeviceWeatherCheckBox: CheckBox
    private var onDevicePlayerPackage: String = ""
    @Volatile private var modelDownloader: com.voicekiosk.app.ondevice.OnDeviceModelDownloader? = null
    private lateinit var nightModeCheckBox: CheckBox
    private var selectedNightStartHour: Int = AppConfig.NIGHT_MODE_START_HOUR
    private var selectedNightEndHour: Int = AppConfig.NIGHT_MODE_END_HOUR
    private lateinit var nightStartHourText: TextView
    private lateinit var nightEndHourText: TextView
    private lateinit var nightDimSeekBar: SeekBar
    private lateinit var nightDimValueText: TextView

    // Bridge & Dashboard URLs / device token
    private lateinit var bridgeUrlEditText: EditText
    private lateinit var deviceTokenEditText: EditText
    private lateinit var dashboardUrlEditText: EditText

    // App Version & Update Check
    private lateinit var currentVersionTextView: TextView
    private lateinit var updateStatusTextView: TextView
    private lateinit var checkUpdateButton: TextView
    private lateinit var startupCheckCheckBox: CheckBox
    private lateinit var updateActionButtonsRow: LinearLayout
    private lateinit var openReleasePageButton: TextView
    private lateinit var downloadApkBrowserButton: TextView

    private var pendingBrowserDialog: AlertDialog? = null
    private var isActivityVisible: Boolean = false
    private var refreshPlayerSettingsAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        Log.i(TAG, "SettingsActivity onCreate")

        setupViews()
    }

    override fun onStart() {
        super.onStart()
        isActivityVisible = true
        AndroidReleaseUpdatePlatform.getManager(this).registerListener(this)
        refreshPlayerSettingsAction?.invoke()
    }

    override fun onStop() {
        isActivityVisible = false
        AndroidReleaseUpdatePlatform.getManager(this).unregisterListener(this)
        pendingBrowserDialog?.dismiss()
        pendingBrowserDialog = null
        super.onStop()
    }

    override fun onDestroy() {
        modelDownloader?.cancel()
        isActivityVisible = false
        AndroidReleaseUpdatePlatform.getManager(this).unregisterListener(this)
        pendingBrowserDialog?.dismiss()
        pendingBrowserDialog = null
        super.onDestroy()
    }

    private fun isUiSafeForInteraction(): Boolean {
        return isActivityVisible && !isFinishing && !isDestroyed
    }

    private fun setupViews() {
        // Initialize weather location state
        selectedWeatherCity = AppConfig.getWeatherCity(this)
        selectedWeatherLat = AppConfig.getWeatherLatitude(this)
        selectedWeatherLon = AppConfig.getWeatherLongitude(this)

        // Initialize night mode schedule state
        selectedNightStartHour = AppConfig.getNightModeStartHour(this)
        selectedNightEndHour = AppConfig.getNightModeEndHour(this)

        val rootScrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            isFillViewport = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dpToPx(20)
            setPadding(pad, pad, pad, pad)
        }
        rootScrollView.addView(container)

        // 1.1 Top Navigation Header
        val headerBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(20)
            }
            layoutParams = lp
        }

        val backButton = TextView(this).apply {
            text = "← 返回"
            setTextColor(Color.parseColor("#E5E7EB"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            val padH = dpToPx(12)
            val padV = dpToPx(6)
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(14).toFloat()
                setColor(Color.parseColor("#27272A"))
                setStroke(dpToPx(1), Color.parseColor("#3F3F46"))
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dpToPx(14)
            }
            layoutParams = lp
            isClickable = true
            isFocusable = true
            setOnClickListener {
                finish()
            }
        }
        headerBar.addView(backButton)

        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        }
        val titleView = TextView(this).apply {
            text = "Voice Kiosk 设置"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
        }
        titleBlock.addView(titleView)

        val subtitleView = TextView(this).apply {
            text = "配置唤醒词、天气位置、OLED 息屏待机与夜间护眼参数"
            setTextColor(Color.parseColor("#9CA3AF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(4)
            }
            layoutParams = lp
        }
        titleBlock.addView(subtitleView)
        headerBar.addView(titleBlock)
        container.addView(headerBar)

        // Section 1: Wake Word Card
        val wakeWordCard = createCardLayout()

        val wakeWordLabel = TextView(this).apply {
            text = "唤醒词 (1-6 个常用汉字)"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(8)
            }
            layoutParams = lp
        }
        wakeWordCard.addView(wakeWordLabel)

        // Quick Preset Chips
        val presetScrollView = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(12)
            }
            layoutParams = lp
        }
        val presetContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        wakeWordPresetChips.clear()
        val presets = listOf("你好小智", "嘿缪斯", "小爱同学", "小艺小艺", "小米小米", "你好问问")
        for (preset in presets) {
            val chip = TextView(this).apply {
                text = preset
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                val padH = dpToPx(10)
                val padV = dpToPx(6)
                setPadding(padH, padV, padH, padV)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dpToPx(8)
                }
                layoutParams = lp
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    wakeWordEditText.setText(preset)
                    wakeWordEditText.setSelection(preset.length)
                }
            }
            wakeWordPresetChips.add(chip)
            presetContainer.addView(chip)
        }
        presetScrollView.addView(presetContainer)
        wakeWordCard.addView(presetScrollView)

        // Wake word EditText
        wakeWordEditText = EditText(this).apply {
            val currentWakeWord = AppConfig.getWakeWord(this@SettingsActivity)
            setText(currentWakeWord)
            setSelection(currentWakeWord.length)
            hint = "例如：你好小智"
            setHintTextColor(Color.parseColor("#6B7280"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            isSingleLine = true
            maxLines = 1

            val padH = dpToPx(12)
            val padV = dpToPx(10)
            setPadding(padH, padV, padH, padV)

            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(8).toFloat()
                setColor(Color.parseColor("#1F2937"))
                setStroke(dpToPx(1), Color.parseColor("#4B5563"))
            }

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(8)
            }
            layoutParams = lp

            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    updateWakeWordChipHighlights(s?.toString() ?: "")
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        wakeWordCard.addView(wakeWordEditText)
        updateWakeWordChipHighlights(wakeWordEditText.text.toString())

        val wakeWordHint = TextView(this).apply {
            text = "保存时将转换为拼音并核对模型 tokens.txt 音素表，不支持的字词将拒绝保存。"
            setTextColor(Color.parseColor("#9CA3AF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        wakeWordCard.addView(wakeWordHint)

        container.addView(wakeWordCard)

        // Section 2: Threshold Slider Card
        val thresholdCard = createCardLayout().apply {
            val lp = layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            lp.topMargin = dpToPx(16)
            layoutParams = lp
        }

        val currentThreshold = AppConfig.getWakeThreshold(this)
        val initialProgress = ((currentThreshold - MIN_THRESHOLD) / THRESHOLD_STEP)
            .roundToInt().coerceIn(0, MAX_STEPS)

        thresholdValueText = TextView(this).apply {
            text = String.format(Locale.US, "唤醒阈值：%.2f", currentThreshold)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(4)
            }
            layoutParams = lp
        }
        thresholdCard.addView(thresholdValueText)

        val thresholdDesc = TextView(this).apply {
            text = "sherpa-onnx 官方默认 0.25。数值越小越灵敏，数值越大越难触发。推荐 0.20 - 0.25。"
            setTextColor(Color.parseColor("#9CA3AF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(16)
            }
            layoutParams = lp
        }
        thresholdCard.addView(thresholdDesc)

        thresholdSeekBar = SeekBar(this).apply {
            max = MAX_STEPS
            progress = initialProgress
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(8)
            }
            layoutParams = lp

            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, prog: Int, fromUser: Boolean) {
                    val calcValue = MIN_THRESHOLD + prog * THRESHOLD_STEP
                    thresholdValueText.text = String.format(Locale.US, "唤醒阈值：%.2f", calcValue)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        thresholdCard.addView(thresholdSeekBar)

        // Range labels under SeekBar
        val rangeLabelsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams = lp
        }
        val minLabel = TextView(this).apply {
            text = "0.10 (极灵敏)"
            setTextColor(Color.parseColor("#6B7280"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        }
        val midLabel = TextView(this).apply {
            text = "0.25 (推荐)"
            setTextColor(Color.parseColor("#10B981"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        }
        val maxLabel = TextView(this).apply {
            text = "0.50 (严格)"
            setTextColor(Color.parseColor("#6B7280"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.END
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        }
        rangeLabelsLayout.addView(minLabel)
        rangeLabelsLayout.addView(midLabel)
        rangeLabelsLayout.addView(maxLabel)
        thresholdCard.addView(rangeLabelsLayout)

        container.addView(thresholdCard)

        // Section 3: Weather & Location Settings Card
        val weatherCard = createCardLayout().apply {
            val lp = layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            lp.topMargin = dpToPx(16)
            layoutParams = lp
        }

        val weatherTitle = TextView(this).apply {
            text = "天气与位置设置 (免 Key 实时气象)"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(6)
            }
            layoutParams = lp
        }
        weatherCard.addView(weatherTitle)

        val cachedWeather = AppConfig.getCachedWeatherSummary(this)
        weatherStatusText = TextView(this).apply {
            val coordStr = String.format(Locale.US, "%.2f, %.2f", selectedWeatherLat, selectedWeatherLon)
            val base = "当前位置：$selectedWeatherCity ($coordStr)"
            text = if (cachedWeather.isNotBlank()) "$base · 缓存：$cachedWeather" else base
            setTextColor(Color.parseColor("#10B981"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(10)
            }
            layoutParams = lp
        }
        weatherCard.addView(weatherStatusText)

        // Preset City Chips ScrollView
        val weatherPresetScrollView = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(12)
            }
            layoutParams = lp
        }
        val weatherPresetContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        weatherPresetChips.clear()
        for (city in WeatherManager.PRESET_CITIES) {
            val chip = TextView(this).apply {
                text = city.name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                val padH = dpToPx(10)
                val padV = dpToPx(6)
                setPadding(padH, padV, padH, padV)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dpToPx(8)
                }
                layoutParams = lp
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    selectedWeatherCity = city.name
                    selectedWeatherLat = city.latitude
                    selectedWeatherLon = city.longitude
                    weatherCityEditText.setText(city.name)
                    weatherCityEditText.setSelection(city.name.length)
                    updateWeatherPresetChipHighlights(city.name)
                    val coord = String.format(Locale.US, "%.2f, %.2f", selectedWeatherLat, selectedWeatherLon)
                    val adminStr = if (city.admin1.isNotBlank() && city.admin1 != city.name) " (${city.admin1})" else ""
                    weatherStatusText.text = "已选预设：${city.name}$adminStr ($coord) · 保存后生效"
                    weatherStatusText.setTextColor(Color.parseColor("#10B981"))
                }
            }
            weatherPresetChips.add(chip to city)
            weatherPresetContainer.addView(chip)
        }
        weatherPresetScrollView.addView(weatherPresetContainer)
        weatherCard.addView(weatherPresetScrollView)
        updateWeatherPresetChipHighlights(selectedWeatherCity)

        // Custom city search row
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(8)
            }
            layoutParams = lp
        }

        weatherCityEditText = EditText(this).apply {
            setText(selectedWeatherCity)
            setSelection(selectedWeatherCity.length)
            hint = "输入城市名，如：青岛、海口"
            setHintTextColor(Color.parseColor("#6B7280"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            isSingleLine = true
            maxLines = 1

            val padH = dpToPx(12)
            val padV = dpToPx(10)
            setPadding(padH, padV, padH, padV)

            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(8).toFloat()
                setColor(Color.parseColor("#1F2937"))
                setStroke(dpToPx(1), Color.parseColor("#4B5563"))
            }

            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dpToPx(8)
            }
            layoutParams = lp

            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    updateWeatherPresetChipHighlights(s?.toString() ?: "")
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        searchRow.addView(weatherCityEditText)

        val searchCityButton = TextView(this).apply {
            text = "搜索定位"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            val padH = dpToPx(14)
            val padV = dpToPx(10)
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(8).toFloat()
                setColor(Color.parseColor("#0D9488")) // Teal
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams = lp
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val query = weatherCityEditText.text.toString().trim()
                if (query.isEmpty()) {
                    Toast.makeText(this@SettingsActivity, "请输入城市名称", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                text = "搜索中..."
                isEnabled = false
                alpha = 0.6f

                WeatherManager.searchCityLocation(query) { result ->
                    if (!isUiSafeForInteraction()) return@searchCityLocation
                    text = "搜索定位"
                    isEnabled = true
                    alpha = 1.0f

                    result.onSuccess { loc ->
                        selectedWeatherCity = loc.name
                        selectedWeatherLat = loc.latitude
                        selectedWeatherLon = loc.longitude
                        weatherCityEditText.setText(loc.name)
                        weatherCityEditText.setSelection(loc.name.length)
                        updateWeatherPresetChipHighlights(loc.name)
                        val adminStr = if (loc.admin1.isNotBlank()) " (${loc.admin1})" else ""
                        val coordStr = String.format(Locale.US, "%.2f, %.2f", loc.latitude, loc.longitude)
                        weatherStatusText.text = "已定位：${loc.name}$adminStr ($coordStr) · 保存后生效"
                        weatherStatusText.setTextColor(Color.parseColor("#10B981"))
                    }.onFailure { err ->
                        val msg = err.message ?: "请检查网络或尝试输入常见城市名"
                        weatherStatusText.text = "未找到该城市：$msg"
                        weatherStatusText.setTextColor(Color.parseColor("#F87171"))
                    }
                }
            }
        }
        searchRow.addView(searchCityButton)
        weatherCard.addView(searchRow)

        val weatherHint = TextView(this).apply {
            text = "使用 Open-Meteo 免密钥开源气象接口。保存后若位置变更将自动清除旧缓存并立即重新抓取天气。"
            setTextColor(Color.parseColor("#9CA3AF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        weatherCard.addView(weatherHint)

        container.addView(weatherCard)

        // Section 4: OLED AOD Standby & Night Mode Settings Card
        val oledCard = createCardLayout().apply {
            val lp = layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            lp.topMargin = dpToPx(16)
            layoutParams = lp
        }

        val oledTitle = TextView(this).apply {
            text = "OLED 息屏待机与夜间护眼设置"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(10)
            }
            layoutParams = lp
        }
        oledCard.addView(oledTitle)

        nightModeCheckBox = CheckBox(this).apply {
            text = "启用夜间深暗护眼模式（在设定夜间时段内自动施加变黑遮罩并降至最低硬件背光）"
            setTextColor(Color.parseColor("#E5E7EB"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            isChecked = AppConfig.isNightModeEnabled(this@SettingsActivity)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(12)
            }
            layoutParams = lp
        }
        oledCard.addView(nightModeCheckBox)

        val scheduleLabel = TextView(this).apply {
            text = "夜间变黑生效时间段 (24 小时制，支持跨午夜)"
            setTextColor(Color.parseColor("#D1D5DB"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(8)
            }
            layoutParams = lp
        }
        oledCard.addView(scheduleLabel)

        val scheduleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(14)
            }
            layoutParams = lp
        }

        // Start Hour Stepper Group
        val startLabel = TextView(this).apply {
            text = "开始 "
            setTextColor(Color.parseColor("#9CA3AF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        scheduleRow.addView(startLabel)

        val startMinusBtn = createStepperButton(" - ") {
            selectedNightStartHour = (selectedNightStartHour + 23) % 24
            nightStartHourText.text = String.format(Locale.US, "%02d:00", selectedNightStartHour)
        }
        scheduleRow.addView(startMinusBtn)

        nightStartHourText = TextView(this).apply {
            text = String.format(Locale.US, "%02d:00", selectedNightStartHour)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            val padH = dpToPx(8)
            setPadding(padH, 0, padH, 0)
        }
        scheduleRow.addView(nightStartHourText)

        val startPlusBtn = createStepperButton(" + ") {
            selectedNightStartHour = (selectedNightStartHour + 1) % 24
            nightStartHourText.text = String.format(Locale.US, "%02d:00", selectedNightStartHour)
        }
        scheduleRow.addView(startPlusBtn)

        val separatorLabel = TextView(this).apply {
            text = " 至 "
            setTextColor(Color.parseColor("#9CA3AF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            val padH = dpToPx(12)
            setPadding(padH, 0, padH, 0)
        }
        scheduleRow.addView(separatorLabel)

        // End Hour Stepper Group
        val endLabel = TextView(this).apply {
            text = "结束 "
            setTextColor(Color.parseColor("#9CA3AF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        scheduleRow.addView(endLabel)

        val endMinusBtn = createStepperButton(" - ") {
            selectedNightEndHour = (selectedNightEndHour + 23) % 24
            nightEndHourText.text = String.format(Locale.US, "%02d:00", selectedNightEndHour)
        }
        scheduleRow.addView(endMinusBtn)

        nightEndHourText = TextView(this).apply {
            text = String.format(Locale.US, "%02d:00", selectedNightEndHour)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            val padH = dpToPx(8)
            setPadding(padH, 0, padH, 0)
        }
        scheduleRow.addView(nightEndHourText)

        val endPlusBtn = createStepperButton(" + ") {
            selectedNightEndHour = (selectedNightEndHour + 1) % 24
            nightEndHourText.text = String.format(Locale.US, "%02d:00", selectedNightEndHour)
        }
        scheduleRow.addView(endPlusBtn)

        oledCard.addView(scheduleRow)

        // Night Dim Intensity Slider
        val currentNightDim = AppConfig.getNightModeDimAlpha(this)
        val initialNightDimProgress = ((currentNightDim - MIN_NIGHT_DIM) / NIGHT_DIM_STEP)
            .roundToInt().coerceIn(0, MAX_NIGHT_DIM_STEPS)

        nightDimValueText = TextView(this).apply {
            text = "夜间变黑遮罩强度：${(currentNightDim * 100).roundToInt()}%"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(4)
            }
            layoutParams = lp
        }
        oledCard.addView(nightDimValueText)

        val dimDesc = TextView(this).apply {
            text = "待机暗黑遮罩透明度。数值越高越黑，推荐 85%（纯黑 OLED 省电且防烧屏）。"
            setTextColor(Color.parseColor("#9CA3AF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(12)
            }
            layoutParams = lp
        }
        oledCard.addView(dimDesc)

        nightDimSeekBar = SeekBar(this).apply {
            max = MAX_NIGHT_DIM_STEPS
            progress = initialNightDimProgress
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(6)
            }
            layoutParams = lp

            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, prog: Int, fromUser: Boolean) {
                    val calcDim = MIN_NIGHT_DIM + prog * NIGHT_DIM_STEP
                    nightDimValueText.text = "夜间变黑遮罩强度：${(calcDim * 100).roundToInt()}%"
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        oledCard.addView(nightDimSeekBar)

        // Range labels under SeekBar
        val dimLabelsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams = lp
        }
        val minDimLabel = TextView(this).apply {
            text = "30% (微暗)"
            setTextColor(Color.parseColor("#6B7280"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        }
        val midDimLabel = TextView(this).apply {
            text = "85% (推荐)"
            setTextColor(Color.parseColor("#10B981"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        }
        val maxDimLabel = TextView(this).apply {
            text = "95% (极暗)"
            setTextColor(Color.parseColor("#6B7280"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.END
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        }
        dimLabelsLayout.addView(minDimLabel)
        dimLabelsLayout.addView(midDimLabel)
        dimLabelsLayout.addView(maxDimLabel)
        oledCard.addView(dimLabelsLayout)

        container.addView(oledCard)

        // Section 5: Bridge URL & Dashboard URL Card
        val urlCard = createCardLayout().apply {
            val lp = layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            lp.topMargin = dpToPx(16)
            layoutParams = lp
        }

        val bridgeUrlLabel = TextView(this).apply {
            text = "NAS 桥接服务地址 (BRIDGE_URL)"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(8)
            }
            layoutParams = lp
        }
        urlCard.addView(bridgeUrlLabel)

        bridgeUrlEditText = EditText(this).apply {
            val currentBridgeUrl = AppConfig.getBridgeUrl(this@SettingsActivity)
            setText(currentBridgeUrl)
            setSelection(currentBridgeUrl.length)
            hint = AppConfig.DEFAULT_BRIDGE_URL
            setHintTextColor(Color.parseColor("#6B7280"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            isSingleLine = true
            maxLines = 1

            val padH = dpToPx(12)
            val padV = dpToPx(10)
            setPadding(padH, padV, padH, padV)

            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(8).toFloat()
                setColor(Color.parseColor("#1F2937"))
                setStroke(dpToPx(1), Color.parseColor("#4B5563"))
            }

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(16)
            }
            layoutParams = lp
        }
        urlCard.addView(bridgeUrlEditText)

        val dashboardUrlLabel = TextView(this).apply {
            text = "仪表盘页面地址 (DASHBOARD_URL)"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(8)
            }
            layoutParams = lp
        }
        urlCard.addView(dashboardUrlLabel)

        dashboardUrlEditText = EditText(this).apply {
            val storedDashboardUrl = AppConfig.getStoredDashboardUrl(this@SettingsActivity)
            setText(storedDashboardUrl)
            if (storedDashboardUrl.isNotEmpty()) {
                setSelection(storedDashboardUrl.length)
            }
            hint = AppConfig.deriveDashboardUrl(AppConfig.getBridgeUrl(this@SettingsActivity))
            setHintTextColor(Color.parseColor("#6B7280"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            isSingleLine = true
            maxLines = 1

            val padH = dpToPx(12)
            val padV = dpToPx(10)
            setPadding(padH, padV, padH, padV)

            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(8).toFloat()
                setColor(Color.parseColor("#1F2937"))
                setStroke(dpToPx(1), Color.parseColor("#4B5563"))
            }

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(8)
            }
            layoutParams = lp
        }
        urlCard.addView(dashboardUrlEditText)

        val urlHint = TextView(this).apply {
            text = "留空将自动恢复默认配置。仪表盘默认加载桥接服务根路径。"
            setTextColor(Color.parseColor("#9CA3AF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(16)
            }
            layoutParams = lp
        }
        urlCard.addView(urlHint)

        val tokenLabel = TextView(this).apply {
            text = "设备令牌 (DEVICE_TOKEN)"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(4)
            }
            layoutParams = lp
        }
        urlCard.addView(tokenLabel)

        val tokenHint = TextView(this).apply {
            text = "粘贴 NAS .env 中的 DEVICE_TOKEN（明文文本输入，仅在保存时写入设置，从不上传或记入日志）。"
            setTextColor(Color.parseColor("#9CA3AF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(8)
            }
            layoutParams = lp
        }
        urlCard.addView(tokenHint)

        deviceTokenEditText = EditText(this).apply {
            val storedToken = AppConfig.getDeviceToken(this@SettingsActivity)
            setText(storedToken)
            if (storedToken.isNotEmpty()) {
                setSelection(storedToken.length)
            }
            hint = "粘贴 NAS .env 中的 DEVICE_TOKEN"
            setHintTextColor(Color.parseColor("#6B7280"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            isSingleLine = true
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE or
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO

            val padH = dpToPx(12)
            val padV = dpToPx(10)
            setPadding(padH, padV, padH, padV)

            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(8).toFloat()
                setColor(Color.parseColor("#1F2937"))
                setStroke(dpToPx(1), Color.parseColor("#4B5563"))
            }

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(8)
            }
            layoutParams = lp
        }
        urlCard.addView(deviceTokenEditText)

        val clearTokenButton = TextView(this).apply {
            text = "清除设备令牌"
            setTextColor(Color.parseColor("#F87171"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            val padH = dpToPx(10)
            val padV = dpToPx(6)
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(6).toFloat()
                setColor(Color.parseColor("#27272A"))
                setStroke(dpToPx(1), Color.parseColor("#7F1D1D"))
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(4)
            }
            layoutParams = lp
            setOnClickListener {
                deviceTokenEditText.setText("")
                AppConfig.clearDeviceToken(this@SettingsActivity)
                Toast.makeText(this@SettingsActivity, "已清除设备令牌", Toast.LENGTH_SHORT).show()
            }
        }
        urlCard.addView(clearTokenButton)

        container.addView(urlCard)

        // Section 6: Version & GitHub Public Release Update Check Card
        val updateCard = createCardLayout().apply {
            val lp = layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            lp.topMargin = dpToPx(16)
            layoutParams = lp
        }

        val updateSectionTitle = TextView(this).apply {
            text = "应用版本与升级检查"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(6)
            }
            layoutParams = lp
        }
        updateCard.addView(updateSectionTitle)

        val installedInfo = AndroidReleaseUpdatePlatform.readInstalledAppInfo(this)
        currentVersionTextView = TextView(this).apply {
            if (installedInfo.hasValidVersionInfo) {
                text = "当前版本：v${installedInfo.versionName} (versionCode ${installedInfo.versionCode} · ${installedInfo.applicationId})"
                setTextColor(Color.parseColor("#D1D5DB"))
            } else {
                val errSuffix = installedInfo.packageReadError?.takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""
                text = "当前版本：无法读取本机版本（未知状态$errSuffix，已禁用覆盖升级引导）"
                setTextColor(Color.parseColor("#F87171"))
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(8)
            }
            layoutParams = lp
        }
        updateCard.addView(currentVersionTextView)

        startupCheckCheckBox = CheckBox(this).apply {
            text = "启动时后台静默检查更新（默认关闭，每天最多 1 次，仅在设置按钮显示提示点，不弹窗不播音）"
            setTextColor(Color.parseColor("#E5E7EB"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            isChecked = AppConfig.isStartupUpdateCheckEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, isChecked ->
                AppConfig.setStartupUpdateCheckEnabled(this@SettingsActivity, isChecked)
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(10)
            }
            layoutParams = lp
        }
        updateCard.addView(startupCheckCheckBox)

        updateStatusTextView = TextView(this).apply {
            text = "点击「检查更新」将通过官方 GitHub Releases 公开接口核验最新正式版（不解析或执行 Release Notes HTML，不静默下载或自动安装）。"
            setTextColor(Color.parseColor("#9CA3AF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(12)
            }
            layoutParams = lp
        }
        updateCard.addView(updateStatusTextView)

        checkUpdateButton = TextView(this).apply {
            text = "检查更新"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            val padV = dpToPx(10)
            setPadding(0, padV, 0, padV)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(8).toFloat()
                setColor(Color.parseColor("#2563EB"))
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams = lp
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val manager = AndroidReleaseUpdatePlatform.getManager(this@SettingsActivity)
                if (manager.isCheckInFlight()) {
                    Toast.makeText(this@SettingsActivity, "正在检查更新中，请稍候...", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val triggered = manager.triggerCheck(forceNetwork = false)
                if (!triggered) {
                    Toast.makeText(this@SettingsActivity, "操作过于频繁，请稍候几秒再试", Toast.LENGTH_SHORT).show()
                }
            }
        }
        updateCard.addView(checkUpdateButton)

        updateActionButtonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(10)
            }
            layoutParams = lp
        }

        openReleasePageButton = TextView(this).apply {
            text = "查看官方 Release"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            val padV = dpToPx(9)
            setPadding(0, padV, 0, padV)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(8).toFloat()
                setColor(Color.parseColor("#374151"))
            }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dpToPx(8)
            }
            layoutParams = lp
            isClickable = true
            isFocusable = true
        }
        updateActionButtonsRow.addView(openReleasePageButton)

        downloadApkBrowserButton = TextView(this).apply {
            text = "浏览器下载 APK"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            val padV = dpToPx(9)
            setPadding(0, padV, 0, padV)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(8).toFloat()
                setColor(Color.parseColor("#059669"))
            }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
            isClickable = true
            isFocusable = true
        }
        updateActionButtonsRow.addView(downloadApkBrowserButton)

        updateCard.addView(updateActionButtonsRow)
        container.addView(updateCard)

        buildOnDeviceCard(container)

        // Action Buttons Row (Cancel, Save)
        val buttonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(28)
                bottomMargin = dpToPx(24)
            }
            layoutParams = lp
        }

        val cancelButton = TextView(this).apply {
            text = "取消"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            val padV = dpToPx(12)
            setPadding(0, padV, 0, padV)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(8).toFloat()
                setColor(Color.parseColor("#374151"))
            }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dpToPx(12)
            }
            layoutParams = lp
            isClickable = true
            isFocusable = true
            setOnClickListener {
                finish()
            }
        }
        buttonsRow.addView(cancelButton)

        val saveButton = TextView(this).apply {
            text = "保存并重启服务"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            val padV = dpToPx(12)
            setPadding(0, padV, 0, padV)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(8).toFloat()
                setColor(Color.parseColor("#10B981"))
            }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f)
            layoutParams = lp
            isClickable = true
            isFocusable = true
            setOnClickListener {
                handleSave()
            }
        }
        buttonsRow.addView(saveButton)

        container.addView(buttonsRow)

        setContentView(rootScrollView)
    }

    private fun updateWakeWordChipHighlights(currentText: String) {
        val trimmed = currentText.trim()
        for (chip in wakeWordPresetChips) {
            val isSelected = chip.text.toString() == trimmed
            chip.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(14).toFloat()
                if (isSelected) {
                    setColor(Color.parseColor("#065F46"))
                    setStroke(dpToPx(1), Color.parseColor("#10B981"))
                } else {
                    setColor(Color.parseColor("#374151"))
                }
            }
            chip.setTextColor(if (isSelected) Color.parseColor("#34D399") else Color.parseColor("#E5E7EB"))
        }
    }

    private fun updateWeatherPresetChipHighlights(currentCity: String) {
        val trimmed = currentCity.trim()
        val normalizedTrimmed = trimmed.removeSuffix("市")
        for ((chip, cityLoc) in weatherPresetChips) {
            val isSelected = cityLoc.name == trimmed ||
                (trimmed.isNotEmpty() && cityLoc.name.removeSuffix("市") == normalizedTrimmed)
            chip.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(14).toFloat()
                if (isSelected) {
                    setColor(Color.parseColor("#065F46"))
                    setStroke(dpToPx(1), Color.parseColor("#10B981"))
                } else {
                    setColor(Color.parseColor("#374151"))
                }
            }
            chip.setTextColor(if (isSelected) Color.parseColor("#34D399") else Color.parseColor("#E5E7EB"))
        }
    }

    private fun createStepperButton(btnText: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = btnText
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            val padH = dpToPx(10)
            val padV = dpToPx(4)
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(6).toFloat()
                setColor(Color.parseColor("#374151"))
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams = lp
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun createCardLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dpToPx(16)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(12).toFloat()
                setColor(Color.parseColor("#18181B"))
                setStroke(dpToPx(1), Color.parseColor("#27272A"))
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams = lp
        }
    }

    private fun handleSave() {
        val rawWord = wakeWordEditText.text.toString().trim().replace("\\s+".toRegex(), "")

        // 1. Validate empty
        if (rawWord.isEmpty()) {
            Toast.makeText(this, "唤醒词不能为空", Toast.LENGTH_SHORT).show()
            return
        }

        // 2. Validate max length (1-6 characters)
        if (rawWord.length > 6) {
            Toast.makeText(this, "唤醒词长度不能超过 6 个汉字", Toast.LENGTH_SHORT).show()
            return
        }

        // 3. Validate Chinese characters only
        val hasNonChinese = rawWord.any { it < '\u4e00' || it > '\u9fa5' }
        if (hasNonChinese) {
            Toast.makeText(this, "唤醒词只支持常用中文汉字", Toast.LENGTH_SHORT).show()
            return
        }

        // 4. Read model tokens.txt from assets
        val validTokens: Set<String>
        try {
            validTokens = assets.open(AppConfig.TOKENS_FILE).bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) trimmed.split(Regex("\\s+")).firstOrNull() else null
                }.toSet()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read tokens.txt from assets", e)
            Toast.makeText(this, "读取模型音素表失败", Toast.LENGTH_SHORT).show()
            return
        }

        // 5. Generate tokens (fast path for known words, PinyinConverter for custom words)
        val tokens: List<String> = when (rawWord) {
            "你好小智" -> listOf("n", "ǐ", "h", "ǎo", "x", "iǎo", "zh", "ì")
            "嘿缪斯" -> listOf("h", "ēi", "m", "iù", "s", "ī")
            "小爱同学" -> listOf("x", "iǎo", "ài", "t", "óng", "x", "ué")
            "小艺小艺" -> listOf("x", "iǎo", "y", "ì", "x", "iǎo", "y", "ì")
            "小米小米" -> listOf("x", "iǎo", "m", "ǐ", "x", "iǎo", "m", "ǐ")
            "你好问问" -> listOf("n", "ǐ", "h", "ǎo", "w", "èn", "w", "èn")
            else -> {
                val converted = PinyinConverter.textToTokens(rawWord)
                if (converted.isEmpty()) {
                    Toast.makeText(this, "拼音转换失败，请检查输入", Toast.LENGTH_SHORT).show()
                    return
                }
                converted
            }
        }

        // 6. Check every generated token exists in tokens.txt
        val missingTokens = tokens.filter { it !in validTokens }
        if (missingTokens.isNotEmpty()) {
            Toast.makeText(
                this,
                "模型音素表缺少以下拼音音素: ${missingTokens.joinToString(" ")}",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // 7. Calculate chosen threshold (0.10 to 0.50 in 0.05 steps)
        val chosenThreshold = MIN_THRESHOLD + thresholdSeekBar.progress * THRESHOLD_STEP
        val roundedThreshold = (chosenThreshold * 100).roundToInt() / 100f

        // 8. Save configurations & enforce token host-binding safety
        val previousBridgeUrl = AppConfig.getBridgeUrl(this)
        val rawBridgeUrl = bridgeUrlEditText.text.toString().trim()
        val finalBridgeUrl = if (rawBridgeUrl.isNotEmpty()) rawBridgeUrl else AppConfig.DEFAULT_BRIDGE_URL
        val newBridgeOrigin = AppConfig.normalizeBridgeOrigin(finalBridgeUrl)
        if (newBridgeOrigin.isEmpty()) {
            Toast.makeText(this, "桥接服务地址格式无效（需包含 http:// 或 https:// 主机地址）", Toast.LENGTH_SHORT).show()
            return
        }
        val previousBridgeOrigin = AppConfig.normalizeBridgeOrigin(previousBridgeUrl)
        val originChanged = previousBridgeOrigin.isNotEmpty() && previousBridgeOrigin != newBridgeOrigin

        val rawDashboardUrl = dashboardUrlEditText.text.toString().trim()
        val finalDashboardUrl = rawDashboardUrl

        // OLED & Night Mode settings
        val chosenNightDim = (MIN_NIGHT_DIM + nightDimSeekBar.progress * NIGHT_DIM_STEP).let { (it * 100).roundToInt() / 100f }
        AppConfig.setOnDeviceRoutingEnabled(this, onDeviceRoutingCheckBox.isChecked)
        AppConfig.setOnDeviceModelRoutingEnabled(this, onDeviceModelCheckBox.isChecked)
        AppConfig.setOnDeviceRouteDebugEnabled(this, onDeviceDebugCheckBox.isChecked)
        AppConfig.setOnDeviceChitchatEnabled(this, onDeviceChitchatCheckBox.isChecked)
        AppConfig.setOnDeviceSpeculativeCloudEnabled(this, onDeviceSpeculativeCheckBox.isChecked)
        AppConfig.setMemoryExtractionEnabled(this, memoryExtractionCheckBox.isChecked)
        AppConfig.setOnDeviceWeatherEnabled(this, onDeviceWeatherCheckBox.isChecked)
        AppConfig.setOnDevicePlayerPackage(this, onDevicePlayerPackage)
        AppConfig.setNightModeEnabled(this, nightModeCheckBox.isChecked)
        AppConfig.setNightModeStartHour(this, selectedNightStartHour)
        AppConfig.setNightModeEndHour(this, selectedNightEndHour)
        AppConfig.setNightModeDimAlpha(this, chosenNightDim)

        // Weather Location settings
        val typedCity = weatherCityEditText.text.toString().trim()
        if (typedCity.isNotEmpty() && typedCity != selectedWeatherCity) {
            val preset = WeatherManager.findPresetCity(typedCity)
            if (preset != null) {
                selectedWeatherCity = preset.name
                selectedWeatherLat = preset.latitude
                selectedWeatherLon = preset.longitude
            }
        }
        if (selectedWeatherCity.isEmpty()) {
            selectedWeatherCity = AppConfig.DEFAULT_WEATHER_CITY
            selectedWeatherLat = AppConfig.DEFAULT_WEATHER_LATITUDE
            selectedWeatherLon = AppConfig.DEFAULT_WEATHER_LONGITUDE
        }

        val prevCity = AppConfig.getWeatherCity(this)
        val prevLat = AppConfig.getWeatherLatitude(this)
        val prevLon = AppConfig.getWeatherLongitude(this)
        val locationChanged = prevCity != selectedWeatherCity ||
            abs(prevLat - selectedWeatherLat) > 0.0001f ||
            abs(prevLon - selectedWeatherLon) > 0.0001f

        AppConfig.setWeatherLocation(this, selectedWeatherCity, selectedWeatherLat, selectedWeatherLon)
        if (locationChanged) {
            AppConfig.setLastWeatherFetchMs(this, 0L)
            AppConfig.setCachedWeatherSummary(this, "")
            WeatherManager.fetchWeatherIfNeeded(applicationContext, force = true) {}
        }

        AppConfig.setWakeWord(this, rawWord)
        AppConfig.setWakeThreshold(this, roundedThreshold)
        AppConfig.setBridgeUrl(this, finalBridgeUrl)
        AppConfig.setDashboardUrl(this, finalDashboardUrl)
        val finalDeviceToken = deviceTokenEditText.text.toString().trim()
        AppConfig.setDeviceToken(this, finalDeviceToken)
        AppConfig.setStartupUpdateCheckEnabled(this, startupCheckCheckBox.isChecked)
        Log.i(TAG, "Successfully updated wake word to '$rawWord', threshold to $roundedThreshold, weather location to '$selectedWeatherCity' ($selectedWeatherLat, $selectedWeatherLon)")

        // 9. Restart VoiceKioskService immediately
        VoiceKioskService.restart(applicationContext)

        // 10. Confirmation toast and finish
        Toast.makeText(
            this,
            "设置已保存：唤醒词「$rawWord」，阈值 ${String.format(Locale.US, "%.2f", roundedThreshold)}",
            Toast.LENGTH_SHORT
        ).show()

        finish()
    }

    override fun onUpdateCheckStateChanged(inFlight: Boolean, lastResult: ReleaseUpdateChecker.CheckResult?) {
        if (!isUiSafeForInteraction()) {
            return
        }

        if (inFlight) {
            checkUpdateButton.text = "正在检查更新..."
            checkUpdateButton.isEnabled = false
            checkUpdateButton.alpha = 0.6f
            updateStatusTextView.text = "正在连接官方 GitHub Releases 校验最新正式版本与签名元数据..."
            updateStatusTextView.setTextColor(Color.parseColor("#60A5FA"))
            updateActionButtonsRow.visibility = View.GONE
            return
        }

        checkUpdateButton.text = "检查更新"
        checkUpdateButton.isEnabled = true
        checkUpdateButton.alpha = 1.0f

        if (lastResult == null) {
            updateActionButtonsRow.visibility = View.GONE
            return
        }

        when (lastResult) {
            is ReleaseUpdateChecker.CheckResult.UpdateAvailable -> {
                val rel = lastResult.release
                val sizeMb = String.format(Locale.US, "%.2f MB", rel.apk.size / (1024.0 * 1024.0))
                val detail = buildString {
                    append(lastResult.statusMessageZh)
                    append("\n文件：${rel.apk.name} ($sizeMb)")
                    append("\nSHA-256：${rel.apk.sha256.take(16)}...${rel.apk.sha256.takeLast(8)}")
                }
                updateStatusTextView.text = detail
                updateStatusTextView.setTextColor(
                    if (lastResult.signatureStatus == ReleaseUpdateChecker.SignatureTrustStatus.MATCHED) {
                        Color.parseColor("#34D399")
                    } else {
                        Color.parseColor("#FBBF24")
                    }
                )
                updateActionButtonsRow.visibility = View.VISIBLE
                openReleasePageButton.visibility = View.VISIBLE
                downloadApkBrowserButton.visibility = View.VISIBLE

                openReleasePageButton.setOnClickListener {
                    confirmAndOpenOfficialUrl(
                        title = "打开官方 GitHub Release 页面",
                        message = buildBrowserConfirmationMessage(
                            targetLabel = "官方 Release 说明页",
                            url = rel.htmlUrl,
                            release = rel,
                            signatureStatus = lastResult.signatureStatus
                        ),
                        url = rel.htmlUrl,
                        target = ReleaseUpdateChecker.OfficialBrowserTarget.ReleaseHtmlPage(
                            expectedTag = rel.tagName
                        )
                    )
                }
                downloadApkBrowserButton.setOnClickListener {
                    confirmAndOpenOfficialUrl(
                        title = "通过浏览器下载官方 APK",
                        message = buildBrowserConfirmationMessage(
                            targetLabel = "官方 APK 资产 (${rel.apk.name})",
                            url = rel.apk.downloadUrl,
                            release = rel,
                            signatureStatus = lastResult.signatureStatus
                        ),
                        url = rel.apk.downloadUrl,
                        target = ReleaseUpdateChecker.OfficialBrowserTarget.ReleaseApkAsset(
                            expectedTag = rel.tagName,
                            expectedAssetName = rel.apk.name
                        )
                    )
                }
            }

            is ReleaseUpdateChecker.CheckResult.UpToDate -> {
                updateStatusTextView.text = lastResult.statusMessageZh
                updateStatusTextView.setTextColor(Color.parseColor("#10B981"))
                updateActionButtonsRow.visibility = View.VISIBLE
                openReleasePageButton.visibility = View.VISIBLE
                downloadApkBrowserButton.visibility = View.GONE

                val rel = lastResult.latestRelease
                openReleasePageButton.setOnClickListener {
                    confirmAndOpenOfficialUrl(
                        title = "查看官方 GitHub Release 页面",
                        message = "即将使用系统浏览器打开官方 Release 页面：\n${rel.htmlUrl}",
                        url = rel.htmlUrl,
                        target = ReleaseUpdateChecker.OfficialBrowserTarget.ReleaseHtmlPage(
                            expectedTag = rel.tagName
                        )
                    )
                }
            }

            is ReleaseUpdateChecker.CheckResult.IncompatibleUpgrade -> {
                // Important: Signature conflict or SDK/ABI mismatch MUST NOT offer direct APK overwrite install guidance
                updateStatusTextView.text = lastResult.statusMessageZh
                updateStatusTextView.setTextColor(Color.parseColor("#F87171"))
                updateActionButtonsRow.visibility = View.VISIBLE
                openReleasePageButton.visibility = View.VISIBLE
                downloadApkBrowserButton.visibility = View.GONE

                val rel = lastResult.release
                openReleasePageButton.setOnClickListener {
                    confirmAndOpenOfficialUrl(
                        title = "查看官方 Release 详情（不兼容直接覆盖安装）",
                        message = "${lastResult.statusMessageZh}\n\n仅打开官方 Release 说明页供人工核查：\n${rel.htmlUrl}",
                        url = rel.htmlUrl,
                        target = ReleaseUpdateChecker.OfficialBrowserTarget.ReleaseHtmlPage(
                            expectedTag = rel.tagName
                        )
                    )
                }
            }

            is ReleaseUpdateChecker.CheckResult.NoPublishedRelease -> {
                updateStatusTextView.text = lastResult.statusMessageZh
                updateStatusTextView.setTextColor(Color.parseColor("#9CA3AF"))
                updateActionButtonsRow.visibility = View.VISIBLE
                openReleasePageButton.visibility = View.VISIBLE
                downloadApkBrowserButton.visibility = View.GONE

                openReleasePageButton.setOnClickListener {
                    confirmAndOpenOfficialUrl(
                        title = "前往官方 GitHub Releases 页面",
                        message = "官方仓库当前尚无正式 Release 发布。\n是否使用浏览器打开：\n${lastResult.officialReleasesUrl}",
                        url = lastResult.officialReleasesUrl,
                        target = ReleaseUpdateChecker.OfficialBrowserTarget.ReleasesIndexPage
                    )
                }
            }

            is ReleaseUpdateChecker.CheckResult.RateLimited -> {
                updateStatusTextView.text = lastResult.statusMessageZh
                updateStatusTextView.setTextColor(Color.parseColor("#FBBF24"))
                updateActionButtonsRow.visibility = View.GONE
            }

            is ReleaseUpdateChecker.CheckResult.NetworkFailure -> {
                updateStatusTextView.text = lastResult.statusMessageZh
                updateStatusTextView.setTextColor(Color.parseColor("#F87171"))
                updateActionButtonsRow.visibility = View.GONE
            }

            is ReleaseUpdateChecker.CheckResult.ValidationFailure -> {
                updateStatusTextView.text = lastResult.statusMessageZh
                updateStatusTextView.setTextColor(Color.parseColor("#F87171"))
                updateActionButtonsRow.visibility = View.GONE
            }

            is ReleaseUpdateChecker.CheckResult.LocalVersionUnknown -> {
                updateStatusTextView.text = lastResult.statusMessageZh
                updateStatusTextView.setTextColor(Color.parseColor("#F87171"))
                updateActionButtonsRow.visibility = View.GONE
            }
        }
    }

    private fun buildBrowserConfirmationMessage(
        targetLabel: String,
        url: String,
        release: ReleaseUpdateChecker.ValidatedReleaseInfo,
        signatureStatus: ReleaseUpdateChecker.SignatureTrustStatus
    ): String {
        val sigNotice = when (signatureStatus) {
            ReleaseUpdateChecker.SignatureTrustStatus.MATCHED ->
                "签名校验：本地应用签名证书与官方 Release 元数据一致，下载后可直接覆盖安装。"
            ReleaseUpdateChecker.SignatureTrustStatus.UNKNOWN_LOCAL_SIGNATURE ->
                "重要提示：未能读取当前设备应用签名证书（未知签名状态，非安全匹配保证）。若当前设备安装的是未签名构建或不同密钥构建，覆盖安装可能失败。"
            ReleaseUpdateChecker.SignatureTrustStatus.MISMATCHED ->
                "警告：签名证书不一致，无法直接覆盖安装！"
        }
        return buildString {
            append("目标：$targetLabel\n")
            append("版本：${release.tagName} (versionCode ${release.versionCode})\n")
            append("APK SHA-256：${release.apk.sha256}\n\n")
            append("$sigNotice\n\n")
            append("应用不会在后台静默下载或自动安装。确认后将调用系统浏览器打开以下官方 HTTPS 地址：\n$url")
        }
    }

    private fun confirmAndOpenOfficialUrl(
        title: String,
        message: String,
        url: String,
        target: ReleaseUpdateChecker.OfficialBrowserTarget
    ) {
        if (!isUiSafeForInteraction()) {
            return
        }
        pendingBrowserDialog?.dismiss()
        pendingBrowserDialog = null
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("确认打开浏览器") { _, _ ->
                openVerifiedHttpsUrlInBrowser(url, target)
            }
            .create()
        if (!isUiSafeForInteraction()) {
            return
        }
        pendingBrowserDialog = dialog
        dialog.show()
    }

    private fun openVerifiedHttpsUrlInBrowser(
        url: String,
        target: ReleaseUpdateChecker.OfficialBrowserTarget
    ) {
        if (!isUiSafeForInteraction()) {
            return
        }
        val verifiedUrl = try {
            ReleaseUpdateChecker.validateOfficialBrowserNavigationUrl(url, target)
        } catch (e: ReleaseUpdateChecker.ValidationException) {
            Log.e(TAG, "Blocked non-official or tampered browser URL ($url): ${e.code} - ${e.messageZh}")
            Toast.makeText(this, "已拦截非官方 Release 地址：${e.messageZh}", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(verifiedUrl)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
            if (!isUiSafeForInteraction()) {
                return
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch browser for $verifiedUrl", e)
            Toast.makeText(this, "无法打开系统浏览器：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    /**
     * S6: on-device features in one card, grouped as 本地指令 (all tiers) → 端侧模型 (tier
     * status, download/delete the tier's default model, model routing, speculative cloud) →
     * 长期记忆 → 调试与评测. Options that need the fast path are disabled while it is off.
     */
    private fun buildOnDeviceCard(container: LinearLayout) {
        val card = createCardLayout().apply {
            val lp = layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dpToPx(16)
            layoutParams = lp
        }
        fun title(t: String) = card.addView(TextView(this).apply {
            text = t
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
        })
        fun section(t: String) = card.addView(TextView(this).apply {
            text = t
            setTextColor(Color.parseColor("#93C5FD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dpToPx(12), 0, dpToPx(2))
        })
        fun note(t: String) = TextView(this).apply {
            text = t
            setTextColor(Color.parseColor("#A1A1AA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }.also { card.addView(it) }
        fun check(t: String, checked: Boolean) = CheckBox(this).apply {
            text = t
            setTextColor(Color.parseColor("#E5E7EB"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            isChecked = checked
        }.also { card.addView(it) }
        fun button(t: String, onClick: (Button) -> Unit) = Button(this).apply {
            text = t
            isAllCaps = false
            setOnClickListener { onClick(this) }
        }.also { card.addView(it) }

        title("端侧能力（实验）")
        note("默认全部关闭；关闭时语音链路与原来完全一致，所有句子照常走云端。")

        // ---- 本地指令（所有机型） ----
        section("本地指令（所有机型）")
        onDeviceRoutingCheckBox = check(
            "启用本地指令快路径：音量、暂停/继续/切歌、点歌、几点/几号/星期几、计时、天气、重新开始在本机直接执行",
            AppConfig.isOnDeviceRoutingEnabled(this)
        )
        onDeviceWeatherCheckBox = check("本地查天气（Open-Meteo，免密钥；默认城市用上面的天气城市，失败时走云端）", AppConfig.isOnDeviceWeatherEnabled(this))
        onDeviceChitchatCheckBox = check("简单寒暄（你好/谢谢/早安/再见）在本机直接回答（固定话术）", AppConfig.isOnDeviceChitchatEnabled(this))
        onDevicePlayerPackage = AppConfig.getOnDevicePlayerPackage(this)

        // ---- 音乐播放设置 ----
        section("音乐播放设置")
        val playerButton = Button(this).apply {
            isAllCaps = false
        }.also { card.addView(it) }
        val playerStatusNote = note("")

        fun refreshPlayerSettingUi() {
            val players = com.voicekiosk.app.ondevice.tools.AndroidLocalPlatform.players(this)
            val hasPlayers = players.isNotEmpty()
            val chosen = onDevicePlayerPackage

            val displayLabel = com.voicekiosk.app.ondevice.tools.AndroidLocalPlatform.formatPlayerLabel(
                storedPackage = chosen,
                availablePlayers = players,
                isInstalled = { pkg -> com.voicekiosk.app.ondevice.tools.AndroidLocalPlatform.isPackageInstalled(this, pkg) },
                installedLabel = { pkg -> com.voicekiosk.app.ondevice.tools.AndroidLocalPlatform.resolveInstalledAppLabel(this, pkg) }
            )

            if (!hasPlayers && chosen.isEmpty()) {
                playerButton.isEnabled = false
                playerButton.alpha = 0.5f
                playerButton.text = "默认音乐播放器：未检测到可用播放器"
                playerStatusNote.text = "未检测到可用音乐播放器（建议安装网易云音乐或系统音乐）"
                playerStatusNote.setTextColor(Color.parseColor("#9CA3AF"))
            } else {
                playerButton.isEnabled = true
                playerButton.alpha = 1.0f
                playerButton.text = "默认音乐播放器：$displayLabel"
                if (!hasPlayers) {
                    playerStatusNote.text = "注意：未检测到已扫描到的播放器，当前选定应用可能已被卸载或禁用"
                    playerStatusNote.setTextColor(Color.parseColor("#F59E0B"))
                } else {
                    playerStatusNote.text = "自选用于语音点歌的播放器；当选定播放器不可用时自动回退为“自动选择”。"
                    playerStatusNote.setTextColor(Color.parseColor("#A1A1AA"))
                }
            }
        }

        playerButton.setOnClickListener {
            val currentPlayers = com.voicekiosk.app.ondevice.tools.AndroidLocalPlatform.players(this)
            val optionLabels = mutableListOf<String>()
            val optionPkgs = mutableListOf<String>()

            // First option: "自动选择（推荐）"
            optionLabels.add("自动选择（推荐）")
            optionPkgs.add("")

            // Candidate players
            for (player in currentPlayers) {
                optionLabels.add("${player.second} (${player.first})")
                optionPkgs.add(player.first)
            }

            // Stored package handling if uninstalled / missing from scan
            val currentSelected = onDevicePlayerPackage
            if (currentSelected.isNotEmpty() && !optionPkgs.contains(currentSelected)) {
                val missingLabel = com.voicekiosk.app.ondevice.tools.AndroidLocalPlatform.formatPlayerLabel(
                    storedPackage = currentSelected,
                    availablePlayers = currentPlayers,
                    isInstalled = { pkg -> com.voicekiosk.app.ondevice.tools.AndroidLocalPlatform.isPackageInstalled(this, pkg) },
                    installedLabel = { pkg -> com.voicekiosk.app.ondevice.tools.AndroidLocalPlatform.resolveInstalledAppLabel(this, pkg) }
                )
                optionLabels.add(missingLabel)
                optionPkgs.add(currentSelected)
            }

            var selectedIndex = optionPkgs.indexOf(currentSelected).coerceAtLeast(0)

            AlertDialog.Builder(this)
                .setTitle(if (currentPlayers.isEmpty()) "未找到支持语音点歌的播放器" else "选择默认音乐播放器")
                .setSingleChoiceItems(optionLabels.toTypedArray(), selectedIndex) { _, which ->
                    selectedIndex = which
                }
                .setPositiveButton("确定") { dialog, _ ->
                    if (selectedIndex in optionPkgs.indices) {
                        onDevicePlayerPackage = optionPkgs[selectedIndex]
                        refreshPlayerSettingUi()
                    }
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        refreshPlayerSettingsAction = { refreshPlayerSettingUi() }
        refreshPlayerSettingUi()

        // ---- 端侧模型（仅高档机） ----
        section("端侧模型（仅高档机）")
        val tier = try {
            com.voicekiosk.app.ondevice.DeviceTierDetector.classify(com.voicekiosk.app.ondevice.DeviceTierDetector.collect(this)).tier
        } catch (e: Exception) {
            null
        }
        val defaultModel = tier?.let { com.voicekiosk.app.ondevice.OnDeviceModelCatalog.defaultFor(it) }
        val dl = com.voicekiosk.app.ondevice.OnDeviceModelDownloader(com.voicekiosk.app.ondevice.OnDeviceModelDownloader.defaultDir(this))
        val modelStatus = note("")
        val modelButton = Button(this).apply { isAllCaps = false }
        fun gb(b: Long) = "%.2f GB".format(b / 1e9)
        fun refreshModel() {
            val m = defaultModel
            when {
                tier == null -> { modelStatus.text = "机型档位检测失败，只用规则和云端。"; modelButton.visibility = View.GONE }
                m == null -> { modelStatus.text = "本机为${tier.name}档：不下载模型，只用规则和云端。"; modelButton.visibility = View.GONE }
                modelDownloader != null -> { modelButton.text = "取消下载"; modelButton.visibility = View.VISIBLE }
                dl.isReadyFast(m) -> {
                    modelStatus.text = "本机为${tier.name}档。已下载 ${m.displayName}（${gb(m.sizeBytes)}，已校验）。"
                    modelButton.text = "删除模型（释放 ${gb(m.sizeBytes)}）"
                    modelButton.visibility = View.VISIBLE
                }
                else -> {
                    modelStatus.text = "本机为${tier.name}档，可用 ${m.displayName}（${gb(m.sizeBytes)}，下载后校验 SHA256）。"
                    modelButton.text = "下载模型"
                    modelButton.visibility = View.VISIBLE
                }
            }
        }
        modelButton.setOnClickListener {
            val m = defaultModel ?: return@setOnClickListener
            val running = modelDownloader
            if (running != null) { running.cancel(); return@setOnClickListener }
            if (dl.isReadyFast(m)) {
                AlertDialog.Builder(this)
                    .setTitle("删除 ${m.displayName}？")
                    .setMessage("删除后本机只用规则和云端，需要时可以重新下载。")
                    .setPositiveButton("删除") { _, _ -> dl.delete(m); refreshModel() }
                    .setNegativeButton("取消", null)
                    .show()
                return@setOnClickListener
            }
            val d = com.voicekiosk.app.ondevice.OnDeviceModelDownloader(com.voicekiosk.app.ondevice.OnDeviceModelDownloader.defaultDir(this))
            modelDownloader = d
            refreshModel()
            modelStatus.text = "准备下载…"
            Thread({
                val r = d.ensure(m, object : com.voicekiosk.app.ondevice.OnDeviceModelDownloader.Listener {
                    override fun onProgress(model: com.voicekiosk.app.ondevice.OnDeviceModel, downloadedBytes: Long, totalBytes: Long) {
                        runOnUiThread { modelStatus.text = "下载中 ${gb(downloadedBytes)} / ${gb(totalBytes)}" }
                    }
                    override fun onStatus(model: com.voicekiosk.app.ondevice.OnDeviceModel, message: String) {
                        runOnUiThread { modelStatus.text = message }
                    }
                })
                runOnUiThread {
                    modelDownloader = null
                    refreshModel()
                    when (r) {
                        is com.voicekiosk.app.ondevice.OnDeviceModelDownloader.Result.Ready ->
                            modelStatus.text = modelStatus.text.toString() + " 保存设置后语音服务会加载它。"
                        is com.voicekiosk.app.ondevice.OnDeviceModelDownloader.Result.Failed -> modelStatus.text = "下载失败：${r.reason}"
                        com.voicekiosk.app.ondevice.OnDeviceModelDownloader.Result.Cancelled -> modelStatus.text = "已取消（再次下载会续传）。"
                    }
                }
            }, "VoiceKiosk-ModelDownload").apply { isDaemon = true }.start()
        }
        card.addView(modelButton)
        refreshModel()
        onDeviceModelCheckBox = check("规则没听懂时用端侧模型识别指令（模型就绪前直接走云端）", AppConfig.isOnDeviceModelRoutingEnabled(this))
        onDeviceSpeculativeCheckBox = check(
            "模型判断时同时请求云端（云端问题更快；但模型判为本地指令时，云端那一轮仍会留在对话记录里）",
            AppConfig.isOnDeviceSpeculativeCloudEnabled(this)
        )

        // ---- 长期记忆 ----
        section("长期记忆")
        memoryExtractionCheckBox = check(
            "对话结束后整理长期记忆：音乐偏好、称呼、作息、城市等自动记下，家人称呼和饮食偏好先语音询问；健康、财务、证件、联系方式、住址等从不记录",
            AppConfig.isMemoryExtractionEnabled(this)
        )
        note("需要在设置中配置设备令牌，并在桥上开启长期记忆（MEMORY_ENABLED）。高档机已下载模型时，规则没抽到的句子再交给模型。")
        button("查看已记住的内容") { showMemoryDialog() }

        // ---- 调试与评测 ----
        section("调试与评测")
        onDeviceDebugCheckBox = check("在看板回答下方显示路由来源与耗时", AppConfig.isOnDeviceRouteDebugEnabled(this))
        note("基准测试和路由评测在独立进程里运行，不影响语音助手；也可以在那里下载、删除实验模型。")
        button("端侧模型基准测试 / 路由评测") {
            startActivity(Intent(this, com.voicekiosk.app.ondevice.LlmBenchmarkActivity::class.java))
        }

        // options that only act through the fast path
        val dependents = listOf(onDeviceWeatherCheckBox, onDeviceChitchatCheckBox, playerButton, onDeviceModelCheckBox, onDeviceSpeculativeCheckBox, memoryExtractionCheckBox)
        fun syncEnabled() {
            val on = onDeviceRoutingCheckBox.isChecked
            dependents.forEach { it.isEnabled = on; it.alpha = if (on) 1f else 0.5f }
            onDeviceSpeculativeCheckBox.isEnabled = on && onDeviceModelCheckBox.isChecked
            onDeviceSpeculativeCheckBox.alpha = if (onDeviceSpeculativeCheckBox.isEnabled) 1f else 0.5f
        }
        onDeviceRoutingCheckBox.setOnCheckedChangeListener { _, _ -> syncEnabled() }
        onDeviceModelCheckBox.setOnCheckedChangeListener { _, _ -> syncEnabled() }
        syncEnabled()
        container.addView(card)
    }

    /** S5: the device's cached copy of the shared memory plus local candidates (read-only). */
    private fun showMemoryDialog() {
        val text = try {
            val cache = com.voicekiosk.app.memory.LocalMemoryCache(
                com.voicekiosk.app.memory.KeystoreCodec.cacheFile(this), com.voicekiosk.app.memory.KeystoreCodec()
            )
            val stored = cache.snapshot()
            val local = cache.candidates()
            buildString {
                if (stored.isEmpty() && local.isEmpty()) append("还没有记住任何内容（或尚未与桥同步）。")
                if (stored.isNotEmpty()) {
                    appendLine("已记住（全家共享，以桥上为准）：")
                    stored.forEach { appendLine("• ${it.content}") }
                }
                if (local.isNotEmpty()) {
                    appendLine()
                    appendLine("本机待处理：")
                    local.forEach { appendLine("• ${it.fact.content}（${if (it.state == "pending") "等你确认" else "待上传"}）") }
                }
                appendLine()
                append("想删除某条，直接对我说「忘掉：内容」。")
            }
        } catch (e: Exception) {
            "读取失败：${e.javaClass.simpleName}"
        }
        AlertDialog.Builder(this).setTitle("长期记忆").setMessage(text).setPositiveButton("关闭", null).show()
    }
}
