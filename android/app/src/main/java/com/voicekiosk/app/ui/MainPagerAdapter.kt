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
 * Page 0: Home screen (giant clock, Gregorian + weekday + Chinese lunar date, weather capsule, bottom glow arc).
 * Page 1 (Dynamic): Music page when playback is active (auto-removed 1 minute after stopping).
 * Pages 1..N (or 2..N+1): Dynamic board pages fetched from the bridge (Title, Checklist, List, KeyValue).
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
        const val VIEW_TYPE_MUSIC = 2
    }

    private var musicState: MusicState? = null
    private var isMusicPageVisible: Boolean = false
    private val boardIdMap = mutableMapOf<String, Long>()
    private var nextBoardLongId = 100L

    init {
        setHasStableIds(true)
    }

    private fun getStableBoardId(boardId: String): Long {
        return boardIdMap.getOrPut(boardId) { nextBoardLongId++ }
    }

    override fun getItemId(position: Int): Long {
        if (position == 0) return 0L
        if (isMusicPageVisible) {
            if (position == 1) return 1L
            val boardIndex = position - 2
            return if (boardIndex in boards.indices) {
                getStableBoardId(boards[boardIndex].boardId)
            } else {
                RecyclerView.NO_ID
            }
        } else {
            val boardIndex = position - 1
            return if (boardIndex in boards.indices) {
                getStableBoardId(boards[boardIndex].boardId)
            } else {
                RecyclerView.NO_ID
            }
        }
    }

    fun getBoardPagePosition(boardId: String): Int {
        val index = boards.indexOfFirst { it.boardId == boardId }
        if (index == -1) return -1
        return if (isMusicPageVisible) index + 2 else index + 1
    }

    fun isMusicPageVisible(): Boolean = isMusicPageVisible

    fun setMusicPageVisible(visible: Boolean, state: MusicState? = null) {
        val wasVisible = isMusicPageVisible
        isMusicPageVisible = visible
        if (state != null) {
            musicState = state
        }
        if (!wasVisible && visible) {
            notifyItemInserted(1)
        } else if (wasVisible && !visible) {
            notifyItemRemoved(1)
        } else if (visible) {
            notifyItemChanged(1)
        }
    }

    fun updateMusicState(state: MusicState) {
        musicState = state
        if (isMusicPageVisible) {
            notifyItemChanged(1)
        }
    }

    fun setBoards(newBoards: List<BoardModel>) {
        this.boards = newBoards
        notifyDataSetChanged()
    }

    fun updateBoard(board: BoardModel) {
        val index = boards.indexOfFirst { it.boardId == board.boardId }
        if (index != -1) {
            val mutable = boards.toMutableList()
            mutable[index] = board
            boards = mutable
            val adapterPosition = if (isMusicPageVisible) index + 2 else index + 1
            notifyItemChanged(adapterPosition)
        } else {
            val mutable = boards.toMutableList()
            mutable.add(board)
            boards = mutable
            notifyDataSetChanged()
        }
    }

    override fun getItemCount(): Int {
        val base = 1 + boards.size
        return if (isMusicPageVisible) base + 1 else base
    }

    override fun getItemViewType(position: Int): Int {
        if (position == 0) return VIEW_TYPE_HOME
        if (isMusicPageVisible) {
            if (position == 1) return VIEW_TYPE_MUSIC
            return VIEW_TYPE_BOARD
        }
        return VIEW_TYPE_BOARD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HOME -> {
                val view = inflater.inflate(R.layout.fragment_home, parent, false)
                HomeViewHolder(view, onWakeInteraction)
            }
            VIEW_TYPE_MUSIC -> {
                val view = inflater.inflate(R.layout.fragment_music, parent, false)
                MusicViewHolder(view, onMediaControl, onOpenNotificationSettings)
            }
            else -> {
                val view = inflater.inflate(R.layout.fragment_board, parent, false)
                BoardViewHolder(view, onItemToggle)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HomeViewHolder -> holder.bind()
            is MusicViewHolder -> {
                musicState?.let { holder.bind(it) }
            }
            is BoardViewHolder -> {
                val boardIndex = if (isMusicPageVisible) position - 2 else position - 1
                if (boardIndex in boards.indices) {
                    holder.bind(boards[boardIndex])
                }
            }
        }
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        when (holder) {
            is HomeViewHolder -> holder.onAttached()
            is MusicViewHolder -> holder.onAttached()
        }
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        super.onViewDetachedFromWindow(holder)
        when (holder) {
            is HomeViewHolder -> holder.onDetached()
            is MusicViewHolder -> holder.onDetached()
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        when (holder) {
            is HomeViewHolder -> holder.onDetached()
            is MusicViewHolder -> holder.onDetached()
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
        private val onWakeInteraction: (() -> Unit)?
    ) : RecyclerView.ViewHolder(itemView), WeatherManager.WeatherListener {

        private val tvDateLine: TextView? = itemView.findViewById(R.id.tvDateLine)
        private val textClock: TextClock? = itemView.findViewById(R.id.textClock)
        private val weatherCapsule: LinearLayout? = itemView.findViewById(R.id.weatherCapsule)
        private val tvWeatherIcon: TextView? = itemView.findViewById(R.id.tvWeatherIcon)
        private val tvWeatherTemp: TextView? = itemView.findViewById(R.id.tvWeatherTemp)
        private val tvWeatherDesc: TextView? = itemView.findViewById(R.id.tvWeatherDesc)
        private val tvSlideHint: TextView? = itemView.findViewById(R.id.tvSlideHint)
        private val tvWakePrompt: TextView? = itemView.findViewById(R.id.tvWakePrompt)

        private val mainHandler = Handler(Looper.getMainLooper())
        private val dateRefreshRunnable = object : Runnable {
            override fun run() {
                updateDateLine()
                mainHandler.postDelayed(this, 60 * 1000L)
            }
        }

        fun bind() {
            updateDateLine()

            val context = itemView.context
            val wakeWord = AppConfig.getWakeWord(context)
            tvWakePrompt?.text = "说 \"$wakeWord\" 或点击唤醒语音助手"
            tvWakePrompt?.setOnClickListener {
                onWakeInteraction?.invoke()
            }

            weatherCapsule?.setOnClickListener {
                WeatherManager.fetchWeatherIfNeeded(context, force = true) { _ ->
                    WeatherManager.getCachedWeatherInfo(context)?.let { info ->
                        bindWeatherInfo(info)
                    }
                }
            }

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
                        tvWeatherIcon?.text = "⛅"
                        tvWeatherTemp?.text = "--°"
                        tvWeatherDesc?.text = summary
                    }
                } else {
                    tvWeatherIcon?.text = "⛅"
                    tvWeatherTemp?.text = "--°"
                    tvWeatherDesc?.text = "正在获取天气..."
                }
            }
        }

        fun onAttached() {
            updateDateLine()
            mainHandler.removeCallbacks(dateRefreshRunnable)
            mainHandler.postDelayed(dateRefreshRunnable, 60 * 1000L)
            WeatherManager.addListener(this)
            val context = itemView.context
            WeatherManager.getCachedWeatherInfo(context)?.let { bindWeatherInfo(it) }
        }

        fun onDetached() {
            WeatherManager.removeListener(this)
            mainHandler.removeCallbacks(dateRefreshRunnable)
        }

        override fun onWeatherUpdated(info: WeatherInfo) {
            bindWeatherInfo(info)
        }

        private fun updateDateLine() {
            tvDateLine?.text = LunarCalendarHelper.formatFullDateLine()
        }

        fun bindWeatherInfo(info: WeatherInfo) {
            tvWeatherIcon?.text = info.iconSymbol
            tvWeatherTemp?.text = "${info.temperatureC}°"
            val desc = if (info.minTempC != null && info.maxTempC != null) {
                "${info.conditionZh} · 空气优 | ${info.minTempC}° / ${info.maxTempC}°"
            } else {
                "${info.conditionZh} · ${info.city}"
            }
            tvWeatherDesc?.text = desc
        }
    }
}
