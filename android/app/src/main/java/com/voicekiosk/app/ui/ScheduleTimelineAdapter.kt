package com.voicekiosk.app.ui

import android.app.AlertDialog
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.voicekiosk.app.R
import com.voicekiosk.app.countdown.CountdownItem
import com.voicekiosk.app.schedule.ScheduleItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Timeline card list adapter for alarms, countdowns, and schedules on the Schedule Board page.
 *
 * Supports:
 * - Direct toggle-to-complete (optimistic UI update + bridge synchronization).
 * - Delete confirmation dialog with explicit user prompt.
 * - Category dot, recurrence tags, and human-friendly time labels.
 */
class ScheduleTimelineAdapter(
    private var items: List<ScheduleItem> = emptyList(),
    private val onToggle: ((scheduleId: String) -> Unit)? = null,
    private val onDelete: ((scheduleId: String) -> Unit)? = null
) : RecyclerView.Adapter<ScheduleTimelineAdapter.ViewHolder>() {

    fun setItems(newItems: List<ScheduleItem>) {
        this.items = newItems
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_schedule_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        if (position in items.indices) {
            holder.bind(items[position])
        }
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cardContainer: LinearLayout = itemView.findViewById(R.id.cardContainer)
        val categoryRow: LinearLayout = itemView.findViewById(R.id.categoryRow)
        val viewCategoryDot: View = itemView.findViewById(R.id.viewCategoryDot)
        val tvCategoryLabel: TextView = itemView.findViewById(R.id.tvCategoryLabel)
        val tvTargetTime: TextView = itemView.findViewById(R.id.tvTargetTime)
        val tvItemTitle: TextView = itemView.findViewById(R.id.tvItemTitle)
        val tvRecurrenceTag: TextView = itemView.findViewById(R.id.tvRecurrenceTag)
        val btnDelete: FrameLayout = itemView.findViewById(R.id.btnDelete)
        val checkboxContainer: FrameLayout = itemView.findViewById(R.id.checkboxContainer)
        val ivCheckbox: ImageView = itemView.findViewById(R.id.ivCheckbox)

        fun bind(item: ScheduleItem) {
            val context = itemView.context

            // Title
            tvItemTitle.text = item.title.ifEmpty {
                when (item.type) {
                    ScheduleItem.TYPE_ALARM -> "闹钟"
                    ScheduleItem.TYPE_COUNTDOWN -> "倒计时"
                    else -> "日程"
                }
            }

            // Category Dot and Label
            when (item.type) {
                ScheduleItem.TYPE_ALARM -> {
                    tvCategoryLabel.text = "闹钟"
                    viewCategoryDot.setBackgroundColor(context.getColor(R.color.accent_amber))
                }
                ScheduleItem.TYPE_COUNTDOWN -> {
                    tvCategoryLabel.text = "倒计时"
                    viewCategoryDot.setBackgroundColor(context.getColor(R.color.accent_cyan))
                }
                else -> {
                    tvCategoryLabel.text = "日程"
                    viewCategoryDot.setBackgroundColor(context.getColor(R.color.accent_purple))
                }
            }

            // Target Time Tag
            val zoneId = ZoneId.systemDefault()
            val itemDate = Instant.ofEpochMilli(item.targetTimeMillis).atZone(zoneId)
            val today = LocalDate.now(zoneId)
            val timeStr = itemDate.format(DateTimeFormatter.ofPattern("HH:mm"))

            if (item.type == ScheduleItem.TYPE_COUNTDOWN) {
                val remSecs = ((item.targetTimeMillis - System.currentTimeMillis() + 999L) / 1000L).coerceAtLeast(0L).toInt()
                if (remSecs > 0 && !item.isCompleted) {
                    tvTargetTime.text = "倒计时 ${CountdownItem.formatTime(remSecs)}"
                } else {
                    tvTargetTime.text = "倒计时 00:00"
                }
            } else {
                val dayPrefix = when (itemDate.toLocalDate()) {
                    today -> "今天"
                    today.plusDays(1) -> "明天"
                    else -> itemDate.format(DateTimeFormatter.ofPattern("M月d日"))
                }
                tvTargetTime.text = "$dayPrefix $timeStr"
            }

            // Recurrence Tag
            if (item.recurrence.isNotEmpty()) {
                val humanRecurrence = when {
                    item.recurrence.contains("BYDAY=MO,TU,WE,TH,FR") -> "工作日"
                    item.recurrence.contains("BYDAY=SA,SU") -> "周末"
                    item.recurrence.contains("DAILY") -> "每天"
                    else -> "重复"
                }
                tvRecurrenceTag.text = humanRecurrence
                tvRecurrenceTag.visibility = View.VISIBLE
            } else {
                tvRecurrenceTag.visibility = View.GONE
            }

            // Checkbox and Strike-through styling
            if (item.isCompleted) {
                ivCheckbox.setImageResource(R.drawable.ic_checkbox_checked)
                tvItemTitle.paintFlags = tvItemTitle.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                tvItemTitle.setTextColor(context.getColor(R.color.text_muted))
            } else {
                ivCheckbox.setImageResource(R.drawable.ic_checkbox_unchecked)
                tvItemTitle.paintFlags = tvItemTitle.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                tvItemTitle.setTextColor(context.getColor(R.color.text_primary))
            }

            // Toggle Click
            checkboxContainer.setOnClickListener {
                if (!item.isCompleted) {
                    onToggle?.invoke(item.id)
                }
            }
            cardContainer.setOnClickListener {
                if (!item.isCompleted) {
                    onToggle?.invoke(item.id)
                }
            }

            // Delete Click & Long Click: Confirmation dialog
            val showDeleteConfirmation = {
                val titleForPrompt = item.title.ifEmpty { "此事项" }
                AlertDialog.Builder(context)
                    .setTitle("删除事项")
                    .setMessage("确定要删除“$titleForPrompt”吗？")
                    .setPositiveButton("删除") { _, _ ->
                        onDelete?.invoke(item.id)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }

            btnDelete.setOnClickListener {
                showDeleteConfirmation()
            }
            cardContainer.setOnLongClickListener {
                showDeleteConfirmation()
                true
            }
        }
    }
}
