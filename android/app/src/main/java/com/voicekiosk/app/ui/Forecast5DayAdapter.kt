package com.voicekiosk.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.voicekiosk.app.DayForecastItem
import com.voicekiosk.app.R
import com.voicekiosk.app.WeatherManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class Forecast5DayAdapter(
    private var items: List<DayForecastItem> = emptyList()
) : RecyclerView.Adapter<Forecast5DayAdapter.DayViewHolder>() {

    fun updateItems(newItems: List<DayForecastItem>) {
        this.items = newItems
        try {
            notifyDataSetChanged()
        } catch (_: Throwable) {}
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DayViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_weather_forecast_day, parent, false)
        return DayViewHolder(view)
    }

    override fun onBindViewHolder(holder: DayViewHolder, position: Int) {
        if (position in items.indices) {
            holder.bind(items[position], position)
        }
    }

    class DayViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvDayName: TextView = itemView.findViewById(R.id.tvDayName)
        private val ivDayIcon: ImageView = itemView.findViewById(R.id.ivDayIcon)
        private val tvDayCondition: TextView = itemView.findViewById(R.id.tvDayCondition)
        private val tvDayPrecip: TextView = itemView.findViewById(R.id.tvDayPrecip)
        private val tvDayTempRange: TextView = itemView.findViewById(R.id.tvDayTempRange)

        fun bind(item: DayForecastItem, position: Int) {
            tvDayName.text = formatDayLabel(item.date, position)
            ivDayIcon.setImageResource(WeatherManager.weatherCodeToDrawableRes(item.weatherCode))
            tvDayCondition.text = item.conditionZh

            if (item.precipitationProb != null && item.precipitationProb > 0) {
                tvDayPrecip.visibility = View.VISIBLE
                tvDayPrecip.text = "${item.precipitationProb}%"
            } else {
                tvDayPrecip.visibility = View.GONE
            }

            val min = item.minTempC
            val max = item.maxTempC
            tvDayTempRange.text = when {
                min != null && max != null -> "${min}° ~ ${max}°"
                max != null -> "${max}°"
                min != null -> "${min}°"
                else -> "--"
            }
        }

        private fun formatDayLabel(dateStr: String, position: Int): String {
            if (position == 0) return "今天"
            if (position == 1) return "明天"
            if (position == 2) return "后天"
            return try {
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
                val d = sdf.parse(dateStr)
                if (d != null) {
                    val cal = Calendar.getInstance()
                    cal.time = d
                    when (cal.get(Calendar.DAY_OF_WEEK)) {
                        Calendar.MONDAY -> "周一"
                        Calendar.TUESDAY -> "周二"
                        Calendar.WEDNESDAY -> "周三"
                        Calendar.THURSDAY -> "周四"
                        Calendar.FRIDAY -> "周五"
                        Calendar.SATURDAY -> "周六"
                        Calendar.SUNDAY -> "周日"
                        else -> dateStr.substringAfterLast("-")
                    }
                } else {
                    dateStr.substringAfterLast("-")
                }
            } catch (_: Throwable) {
                "第${position + 1}天"
            }
        }
    }
}
