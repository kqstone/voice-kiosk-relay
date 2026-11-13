package com.voicekiosk.app.ui

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
import androidx.recyclerview.widget.RecyclerView
import com.voicekiosk.app.AppConfig
import com.voicekiosk.app.R
import com.voicekiosk.app.WeatherInfo
import com.voicekiosk.app.WeatherManager

/**
 * Pager adapter for the horizontal navigation container (ViewPager2).
 *
 * Page 0: Home screen (giant clock, Gregorian + weekday + Chinese lunar date, weather capsule, bottom glow arc).
 * Pages 1..N: Placeholder pages for upcoming phases (Music page, dynamic boards).
 *
 * NOTE: Contains zero hardcoded board content and zero estimated-time fields.
 */
class MainPagerAdapter(
    private var pageCount: Int = 3,
    private val onWakeInteraction: (() -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val VIEW_TYPE_HOME = 0
        const val VIEW_TYPE_PLACEHOLDER = 1
    }

    fun setPageCount(count: Int) {
        val coerced = count.coerceAtLeast(1)
        if (pageCount != coerced) {
            pageCount = coerced
            notifyDataSetChanged()
        }
    }

    override fun getItemCount(): Int = pageCount

    override fun getItemViewType(position: Int): Int {
        return if (position == 0) VIEW_TYPE_HOME else VIEW_TYPE_PLACEHOLDER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_HOME) {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.fragment_home, parent, false)
            HomeViewHolder(view, onWakeInteraction)
        } else {
            val frame = FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.BLACK)
            }
            PlaceholderViewHolder(frame)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is HomeViewHolder) {
            holder.bind()
        }
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (holder is HomeViewHolder) {
            holder.onAttached()
        }
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        super.onViewDetachedFromWindow(holder)
        if (holder is HomeViewHolder) {
            holder.onDetached()
        }
    }

    class PlaceholderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

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
