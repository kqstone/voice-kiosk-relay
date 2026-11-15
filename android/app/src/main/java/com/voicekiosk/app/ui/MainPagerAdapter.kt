package com.voicekiosk.app.ui

import android.content.res.Configuration
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.voicekiosk.app.AppConfig
import com.voicekiosk.app.R
import com.voicekiosk.app.WeatherInfo
import com.voicekiosk.app.WeatherManager
import com.voicekiosk.app.data.BoardComponent
import com.voicekiosk.app.data.BoardModel
import com.voicekiosk.app.media.MediaControlAction
import com.voicekiosk.app.media.MusicState

/**
 * Pager adapter for the horizontal navigation container (ViewPager2).
 *
 * Page 0: Home screen (giant clock, Gregorian + weekday + Chinese lunar date, weather capsule, bottom glow arc, Now-Playing widget).
 * Pages 1..N: Dynamic board pages fetched from the bridge (Title, Checklist, List, KeyValue).
 *
 * NOTE: Contains ZERO hardcoded board content and ZERO estimated-time fields.
 */
class MainPagerAdapter(
    private var boards: List<BoardModel> = emptyList(),
    private val onWakeInteraction: (() -> Unit)? = null,
    private val onItemToggle: ((boardId: String, itemId: String, completed: Boolean) -> Unit)? = null,
    private val onMediaControl: ((action: MediaControlAction) -> Unit)? = null,
    private val onOpenNotificationSettings: (() -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val VIEW_TYPE_HOME = 0
        const val VIEW_TYPE_BOARD = 1
    }

    private var musicState: MusicState? = null
    private var activeHomeViewHolder: HomeViewHolder? = null
    private val boardIdMap = mutableMapOf<String, Long>()
    private var nextBoardLongId = 100L

    init {
        try {
            setHasStableIds(true)
        } catch (_: Throwable) {}
    }

    private fun getStableBoardId(boardId: String): Long {
        return boardIdMap.getOrPut(boardId) { nextBoardLongId++ }
    }

    override fun getItemId(position: Int): Long {
        if (position == 0) return 0L
        val boardIndex = position - 1
        return if (boardIndex in boards.indices) {
            getStableBoardId(boards[boardIndex].boardId)
        } else {
            RecyclerView.NO_ID
        }
    }

    fun getBoardPagePosition(boardId: String): Int {
        val index = boards.indexOfFirst { it.boardId == boardId }
        if (index == -1) return -1
        return index + 1
    }

    fun updateMusicState(state: MusicState) {
        musicState = state
        activeHomeViewHolder?.updateMusicState(state)
    }

    fun updateHomeMusicVisibility(visible: Boolean, state: MusicState? = null) {
        if (state != null) {
            musicState = state
        }
        activeHomeViewHolder?.setMusicVisibility(visible, state ?: musicState)
    }

    fun getActiveHomeViewHolder(): HomeViewHolder? = activeHomeViewHolder

    fun onConfigurationChanged(newConfig: Configuration? = null) {
        activeHomeViewHolder?.reinflate(newConfig)
        try {
            notifyDataSetChanged()
        } catch (_: Throwable) {}
    }

    fun reinflateHome(newConfig: Configuration? = null) {
        activeHomeViewHolder?.reinflate(newConfig)
    }

    fun getBoards(): List<BoardModel> = boards

    fun getMusicState(): MusicState? = musicState

    fun setBoards(newBoards: List<BoardModel>) {
        this.boards = newBoards
        try {
            notifyDataSetChanged()
        } catch (_: Throwable) {}
    }

    fun updateBoard(board: BoardModel) {
        val index = boards.indexOfFirst { it.boardId == board.boardId }
        if (index != -1) {
            val mutable = boards.toMutableList()
            mutable[index] = board
            boards = mutable
            val adapterPosition = index + 1
            try {
                notifyItemChanged(adapterPosition)
            } catch (_: Throwable) {}
        } else {
            val mutable = boards.toMutableList()
            mutable.add(board)
            boards = mutable
            try {
                notifyDataSetChanged()
            } catch (_: Throwable) {}
        }
    }

    override fun getItemCount(): Int {
        return 1 + boards.size
    }

    override fun getItemViewType(position: Int): Int {
        return if (position == 0) VIEW_TYPE_HOME else VIEW_TYPE_BOARD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HOME -> {
                val container = FrameLayout(parent.context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
                HomeViewHolder(container, onWakeInteraction, onMediaControl, onOpenNotificationSettings)
            }
            else -> {
                val view = inflater.inflate(R.layout.fragment_board, parent, false)
                BoardViewHolder(view, onItemToggle)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HomeViewHolder -> {
                activeHomeViewHolder = holder
                val state = musicState
                val shouldShow = state != null && state.hasActiveSession && state.title.isNotEmpty()
                holder.bind(musicState, isMusicVisible = shouldShow)
            }
            is BoardViewHolder -> {
                val boardIndex = position - 1
                if (boardIndex in boards.indices) {
                    holder.bind(boards[boardIndex])
                }
            }
        }
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        when (holder) {
            is HomeViewHolder -> {
                activeHomeViewHolder = holder
                holder.onAttached()
            }
        }
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        super.onViewDetachedFromWindow(holder)
        when (holder) {
            is HomeViewHolder -> {
                if (activeHomeViewHolder === holder) {
                    activeHomeViewHolder = null
                }
                holder.onDetached()
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        when (holder) {
            is HomeViewHolder -> {
                if (activeHomeViewHolder === holder) {
                    activeHomeViewHolder = null
                }
                holder.onDetached()
            }
        }
    }

    class BoardViewHolder(
        itemView: View,
        private val onItemToggle: ((boardId: String, itemId: String, completed: Boolean) -> Unit)?
    ) : RecyclerView.ViewHolder(itemView) {
        private val tvBoardTitle: TextView = itemView.findViewById(R.id.tvBoardTitle)
        private val tvBoardTag: TextView = itemView.findViewById(R.id.tvBoardTag)
        private val tvBoardBadge: TextView = itemView.findViewById(R.id.tvBoardBadge)
        private val tvBoardSubtitle: TextView = itemView.findViewById(R.id.tvBoardSubtitle)
        private val rvBoardContent: RecyclerView = itemView.findViewById(R.id.rvBoardContent)

        fun bind(board: BoardModel) {
            tvBoardTitle.text = board.titleComponent.title

            if (board.titleComponent.tag.isNotEmpty()) {
                tvBoardTag.text = board.titleComponent.tag
                tvBoardTag.visibility = View.VISIBLE
            } else {
                tvBoardTag.visibility = View.GONE
            }

            if (board.titleComponent.subtitle.isNotEmpty()) {
                tvBoardSubtitle.text = board.titleComponent.subtitle
                tvBoardSubtitle.visibility = View.VISIBLE
            } else {
                tvBoardSubtitle.visibility = View.GONE
            }

            if (board.titleComponent.badge.isNotEmpty()) {
                tvBoardBadge.text = board.titleComponent.badge
                tvBoardBadge.visibility = View.VISIBLE
            } else {
                tvBoardBadge.visibility = View.GONE
            }

            // Adaptive layout: 2-column grid in landscape (screenshot 5), single column in portrait (screenshot 2)
            val isLandscape = itemView.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val currentLm = rvBoardContent.layoutManager
            val needsGrid = isLandscape
            val isCurrentlyGrid = currentLm is GridLayoutManager
            if (currentLm == null || (needsGrid != isCurrentlyGrid)) {
                rvBoardContent.layoutManager = if (isLandscape) {
                    GridLayoutManager(itemView.context, 2)
                } else {
                    LinearLayoutManager(itemView.context)
                }
            }

            // Flatten board components to display items
            val displayItems = mutableListOf<BoardItemDisplay>()
            for (comp in board.components) {
                when (comp) {
                    is BoardComponent.Checklist -> {
                        for (it in comp.items) {
                            displayItems.add(BoardItemDisplay.Task(it))
                        }
                    }
                    is BoardComponent.ListComp -> {
                        for (it in comp.items) {
                            displayItems.add(BoardItemDisplay.PlainText(it))
                        }
                    }
                    is BoardComponent.KeyValue -> {
                        for (p in comp.pairs) {
                            displayItems.add(BoardItemDisplay.KeyValue(p))
                        }
                    }
                }
            }

            val existingAdapter = rvBoardContent.adapter as? TaskCardAdapter
            if (existingAdapter != null && existingAdapter.boardId == board.boardId) {
                existingAdapter.updateItems(displayItems)
            } else {
                val newAdapter = TaskCardAdapter(
                    boardId = board.boardId,
                    items = displayItems,
                    onItemToggle = { itemId, completed ->
                        val currentAdapter = rvBoardContent.adapter as? TaskCardAdapter
                        val total = currentAdapter?.getTaskCount() ?: 0
                        val compCount = currentAdapter?.getCompletedTaskCount() ?: 0
                        if (total > 0) {
                            tvBoardBadge.text = "$compCount / $total 完成"
                            tvBoardBadge.visibility = View.VISIBLE
                        }
                        onItemToggle?.invoke(board.boardId, itemId, completed)
                    }
                )
                rvBoardContent.adapter = newAdapter
            }
        }
    }

    class HomeViewHolder(
        itemView: View,
        private val onWakeInteraction: (() -> Unit)?,
        private val onMediaControl: ((MediaControlAction) -> Unit)? = null,
        private val onOpenNotificationSettings: (() -> Unit)? = null
    ) : RecyclerView.ViewHolder(itemView), WeatherManager.WeatherListener {

        private var tvDateLine: TextView? = null
        private var textClock: TextClock? = null
        private var weatherCapsule: LinearLayout? = null
        private var tvWeatherIcon: TextView? = null
        private var tvWeatherTemp: TextView? = null
        private var tvWeatherDesc: TextView? = null
        private var tvSlideHint: TextView? = null
        private var tvWakePrompt: TextView? = null

        private var layoutWeatherDetails: View? = null
        private var layoutWeatherForecast: View? = null
        private var layoutRightContainer: ViewGroup? = null

        private var tvApparentTemp: TextView? = null
        private var tvHumidity: TextView? = null
        private var tvWind: TextView? = null
        private var tvSun: TextView? = null
        private var tvAqiBadge: TextView? = null
        private var recyclerForecast5Day: RecyclerView? = null
        private var forecastAdapter: Forecast5DayAdapter? = null
        private var lastBoundWeatherInfo: WeatherInfo? = null

        var musicWidgetController: HomeMusicWidgetController? = null
            private set

        private var currentMusicState: MusicState? = null
        private var isMusicVisible: Boolean = false
        private var isAttachedToWindow: Boolean = false
        private var inflatedOrientation: Int = Configuration.ORIENTATION_UNDEFINED

        private val mainHandler = Handler(Looper.getMainLooper())
        private val dateRefreshRunnable = object : Runnable {
            override fun run() {
                updateDateLine()
                try {
                    mainHandler.postDelayed(this, 60 * 1000L)
                } catch (_: Throwable) {}
            }
        }

        init {
            val container = itemView as? ViewGroup
            if (container != null && container.childCount == 0) {
                inflateLayout()
            } else {
                bindViews(itemView)
                try {
                    inflatedOrientation = itemView.resources?.configuration?.orientation
                        ?: Configuration.ORIENTATION_UNDEFINED
                } catch (_: Throwable) {
                    inflatedOrientation = Configuration.ORIENTATION_UNDEFINED
                }
            }
        }

        private fun inflateLayout() {
            val container = itemView as? ViewGroup ?: return
            try {
                val inflater = LayoutInflater.from(container.context)
                val contentView = inflater.inflate(R.layout.fragment_home, container, false)
                container.addView(contentView)
                bindViews(contentView)
                inflatedOrientation = container.resources?.configuration?.orientation
                    ?: Configuration.ORIENTATION_UNDEFINED
            } catch (_: Throwable) {
                bindViews(container)
            }
        }

        private fun bindViews(root: View) {
            tvDateLine = root.findViewById(R.id.tvDateLine)
            textClock = root.findViewById(R.id.textClock)
            weatherCapsule = root.findViewById(R.id.weatherCapsule)
            tvWeatherIcon = root.findViewById(R.id.tvWeatherIcon)
            tvWeatherTemp = root.findViewById(R.id.tvWeatherTemp)
            tvWeatherDesc = root.findViewById(R.id.tvWeatherDesc)
            tvSlideHint = root.findViewById(R.id.tvSlideHint)
            tvWakePrompt = root.findViewById(R.id.tvWakePrompt)

            layoutWeatherDetails = root.findViewById(R.id.layoutWeatherDetails)
            layoutWeatherForecast = root.findViewById(R.id.layoutWeatherForecast)
            layoutRightContainer = root.findViewById(R.id.layoutRightContainer)

            tvApparentTemp = root.findViewById(R.id.tvApparentTemp)
            tvHumidity = root.findViewById(R.id.tvHumidity)
            tvWind = root.findViewById(R.id.tvWind)
            tvSun = root.findViewById(R.id.tvSun)
            tvAqiBadge = root.findViewById(R.id.tvAqiBadge)
            recyclerForecast5Day = root.findViewById(R.id.recyclerForecast5Day)

            recyclerForecast5Day?.let { rv ->
                try {
                    rv.layoutManager = LinearLayoutManager(rv.context)
                    val adapter = Forecast5DayAdapter()
                    forecastAdapter = adapter
                    rv.adapter = adapter
                } catch (_: Throwable) {}
            }

            val widgetView: View? = root.findViewById(R.id.layoutNowPlaying)
            musicWidgetController = if (widgetView != null) {
                try {
                    HomeMusicWidgetController(widgetView, onMediaControl, onOpenNotificationSettings)
                } catch (_: Throwable) {
                    null
                }
            } else {
                null
            }
        }

        fun reinflate(newConfig: Configuration? = null) {
            val container = itemView as? ViewGroup ?: return
            musicWidgetController?.onDetached()
            container.removeAllViews()

            try {
                val inflater = LayoutInflater.from(container.context)
                val contentView = inflater.inflate(R.layout.fragment_home, container, false)
                container.addView(contentView)
                bindViews(contentView)
                inflatedOrientation = newConfig?.orientation
                    ?: container.resources?.configuration?.orientation
                    ?: Configuration.ORIENTATION_UNDEFINED
            } catch (_: Throwable) {
                bindViews(container)
            }

            // Restore cached weather state immediately without network overhead
            lastBoundWeatherInfo?.let { bindWeatherInfo(it) }

            // Rebind state with new views - animate = false strictly avoids secondary animation/flash
            bind(currentMusicState, isMusicVisible)

            if (isAttachedToWindow) {
                musicWidgetController?.onAttached()
            }
        }

        fun bind(musicState: MusicState? = null, isMusicVisible: Boolean? = null) {
            if (musicState != null) {
                currentMusicState = musicState
            }
            val targetVisibility = isMusicVisible ?: currentMusicState?.let {
                it.hasActiveSession && it.title.isNotEmpty()
            } ?: false
            this.isMusicVisible = targetVisibility

            val currentOrientation = try {
                itemView.resources?.configuration?.orientation ?: Configuration.ORIENTATION_UNDEFINED
            } catch (_: Throwable) {
                Configuration.ORIENTATION_UNDEFINED
            }

            if (inflatedOrientation != Configuration.ORIENTATION_UNDEFINED &&
                currentOrientation != Configuration.ORIENTATION_UNDEFINED &&
                inflatedOrientation != currentOrientation
            ) {
                reinflate()
                return
            }

            updateDateLine()

            val context = itemView.context
            val wakeWord = try { AppConfig.getWakeWord(context) } catch (_: Throwable) { "小智" }
            tvWakePrompt?.text = "说 \"$wakeWord\" 或点击唤醒语音助手"
            tvWakePrompt?.setOnClickListener {
                onWakeInteraction?.invoke()
            }

            weatherCapsule?.setOnClickListener {
                try {
                    WeatherManager.fetchWeatherIfNeeded(context, force = true) { _ ->
                        WeatherManager.getCachedWeatherInfo(context)?.let { info ->
                            bindWeatherInfo(info)
                        }
                    }
                } catch (_: Throwable) {}
            }

            try {
                val cached = WeatherManager.getCachedWeatherInfo(context)
                if (cached != null) {
                    bindWeatherInfo(cached)
                } else {
                    val summary = AppConfig.getCachedWeatherSummary(context)
                    if (summary.isNotEmpty()) {
                        val parsed = WeatherManager.parseCachedSummary(summary)
                        if (parsed != null) {
                            bindWeatherInfo(parsed)
                        } else {
                            try {
                                tvWeatherIcon?.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_weather_cloudy, 0, 0, 0)
                                tvWeatherIcon?.text = ""
                            } catch (_: Throwable) {
                                tvWeatherIcon?.text = "⛅"
                            }
                            tvWeatherTemp?.text = "--°"
                            tvWeatherDesc?.text = summary
                        }
                    } else {
                        try {
                            tvWeatherIcon?.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_weather_cloudy, 0, 0, 0)
                            tvWeatherIcon?.text = ""
                        } catch (_: Throwable) {
                            tvWeatherIcon?.text = "⛅"
                        }
                        tvWeatherTemp?.text = "--°"
                        tvWeatherDesc?.text = "正在获取天气..."
                    }
                }
            } catch (_: Throwable) {}

            val stateToBind = musicState ?: currentMusicState
            if (stateToBind != null) {
                musicWidgetController?.bind(stateToBind)
            }
            setMusicCardVisible(this.isMusicVisible, animate = false)
        }

        fun updateMusicState(state: MusicState) {
            currentMusicState = state
            // 以 hasActiveSession（或非空曲目）为准控制卡片显隐，避免暂停时反复跳变
            val shouldShowCard = state.hasActiveSession && state.title.isNotEmpty()
            setMusicCardVisible(shouldShowCard)
            musicWidgetController?.bind(state)
        }

        fun setMusicVisibility(visible: Boolean, state: MusicState? = null) {
            if (state != null) {
                currentMusicState = state
                musicWidgetController?.bind(state)
            }
            setMusicCardVisible(visible, animate = true)
        }

        fun setMusicCardVisible(shouldShowCard: Boolean, animate: Boolean = true) {
            isMusicVisible = shouldShowCard
            musicWidgetController?.setVisible(shouldShowCard, animate = animate)

            val detailsView = layoutWeatherDetails
            val forecastView = layoutWeatherForecast
            val isLand = isLandscapeLayout()

            if (!animate || itemView.isAttachedToWindow != true) {
                if (shouldShowCard) {
                    detailsView?.visibility = View.GONE
                    detailsView?.alpha = 0f
                    forecastView?.visibility = View.GONE
                    forecastView?.alpha = 0f
                } else {
                    detailsView?.visibility = View.VISIBLE
                    detailsView?.alpha = 1f
                    forecastView?.visibility = View.VISIBLE
                    forecastView?.alpha = 1f
                }
                return
            }

            if (isLand) {
                // Cross-Fade in Landscape: 5-day forecast fades out (250ms), music card fades in
                if (shouldShowCard) {
                    try {
                        forecastView?.animate()?.alpha(0f)?.setDuration(250L)?.withEndAction {
                            forecastView.visibility = View.GONE
                        }?.start()
                        detailsView?.animate()?.alpha(0f)?.setDuration(250L)?.withEndAction {
                            detailsView.visibility = View.GONE
                        }?.start()
                    } catch (_: Throwable) {
                        forecastView?.visibility = View.GONE
                        detailsView?.visibility = View.GONE
                    }
                } else {
                    try {
                        forecastView?.visibility = View.VISIBLE
                        forecastView?.alpha = 0f
                        forecastView?.animate()?.alpha(1f)?.setDuration(250L)?.start()

                        detailsView?.visibility = View.VISIBLE
                        detailsView?.alpha = 0f
                        detailsView?.animate()?.alpha(1f)?.setDuration(250L)?.start()
                    } catch (_: Throwable) {
                        forecastView?.visibility = View.VISIBLE
                        forecastView?.alpha = 1f
                        detailsView?.visibility = View.VISIBLE
                        detailsView?.alpha = 1f
                    }
                }
            } else {
                // Accordion height collapse in Portrait via TransitionManager
                try {
                    val container = itemView as? ViewGroup
                    if (container != null && container.isAttachedToWindow) {
                        val transition = android.transition.TransitionSet().apply {
                            ordering = android.transition.TransitionSet.ORDERING_TOGETHER
                            addTransition(android.transition.ChangeBounds())
                            addTransition(android.transition.Fade())
                            duration = 250L
                        }
                        android.transition.TransitionManager.beginDelayedTransition(container, transition)
                    }
                } catch (_: Throwable) {}

                if (shouldShowCard) {
                    detailsView?.visibility = View.GONE
                    forecastView?.visibility = View.GONE
                } else {
                    detailsView?.visibility = View.VISIBLE
                    detailsView?.alpha = 1f
                    forecastView?.visibility = View.VISIBLE
                    forecastView?.alpha = 1f
                }
            }
        }

        fun onAttached() {
            isAttachedToWindow = true
            updateDateLine()
            try {
                mainHandler.removeCallbacks(dateRefreshRunnable)
                mainHandler.postDelayed(dateRefreshRunnable, 60 * 1000L)
            } catch (_: Throwable) {}
            try {
                WeatherManager.addListener(this)
                val context = itemView.context
                WeatherManager.getCachedWeatherInfo(context)?.let { bindWeatherInfo(it) }
            } catch (_: Throwable) {}
            musicWidgetController?.onAttached()
        }

        fun onDetached() {
            isAttachedToWindow = false
            musicWidgetController?.onDetached()
            try {
                WeatherManager.removeListener(this)
                mainHandler.removeCallbacks(dateRefreshRunnable)
            } catch (_: Throwable) {}
        }

        override fun onWeatherUpdated(info: WeatherInfo) {
            bindWeatherInfo(info)
        }

        private fun updateDateLine() {
            try {
                tvDateLine?.text = LunarCalendarHelper.formatFullDateLine()
            } catch (_: Throwable) {}
        }

        fun bindWeatherInfo(info: WeatherInfo) {
            lastBoundWeatherInfo = info
            try {
                tvWeatherIcon?.setCompoundDrawablesWithIntrinsicBounds(info.iconRes, 0, 0, 0)
                tvWeatherIcon?.text = ""
            } catch (_: Throwable) {
                tvWeatherIcon?.text = info.iconSymbol
            }
            tvWeatherTemp?.text = "${info.temperatureC}°"
            val range = if (info.minTempC != null && info.maxTempC != null) {
                "${info.minTempC}°~${info.maxTempC}°"
            } else {
                null
            }
            val aqiPart = if (info.aqi != null && !info.aqiLevelZh.isNullOrBlank()) {
                "AQI ${info.aqi} ${info.aqiLevelZh}"
            } else {
                null
            }
            tvWeatherDesc?.text = listOfNotNull(
                info.city.takeIf { it.isNotBlank() },
                info.conditionZh,
                range,
                aqiPart
            ).joinToString(" · ")

            // Bind Today Weather Details
            tvApparentTemp?.text = info.apparentTempC?.let { "体感 ${it}°" } ?: "体感 --°"
            tvHumidity?.text = info.relativeHumidity?.let { "湿度 ${it}%" } ?: "湿度 --%"
            tvWind?.text = info.windSpeedKmh?.let { "风速 ${it.toInt()} km/h" } ?: "风速 --"
            val sunStr = listOfNotNull(
                WeatherManager.formatSunTime(info.sunrise)?.let { "日出 $it" },
                WeatherManager.formatSunTime(info.sunset)?.let { "日落 $it" }
            ).joinToString(" · ")
            tvSun?.text = if (sunStr.isNotBlank()) sunStr else "日出 -- · 日落 --"

            // AQI Badge: strictly GONE when null / invalid
            if (info.aqi != null && !info.aqiLevelZh.isNullOrBlank()) {
                tvAqiBadge?.visibility = View.VISIBLE
                tvAqiBadge?.text = "AQI ${info.aqi} ${info.aqiLevelZh}"
            } else {
                tvAqiBadge?.visibility = View.GONE
            }

            // 5-Day forecast list
            if (info.forecastDays.isNotEmpty()) {
                forecastAdapter?.updateItems(info.forecastDays.take(5))
            }
        }

        fun isLandscapeLayout(): Boolean {
            return itemView.findViewById<View>(R.id.layoutHomeContent) != null ||
                inflatedOrientation == Configuration.ORIENTATION_LANDSCAPE
        }

        fun getInflatedOrientation(): Int = inflatedOrientation

        fun getDateLine(): TextView? = tvDateLine
        fun getTextClock(): TextClock? = textClock
        fun getWeatherCapsule(): LinearLayout? = weatherCapsule
        fun getLayoutWeatherDetails(): View? = layoutWeatherDetails
        fun getLayoutWeatherForecast(): View? = layoutWeatherForecast
        fun getLayoutRightContainer(): ViewGroup? = layoutRightContainer
        fun isMusicCardVisible(): Boolean = isMusicVisible
    }
}
