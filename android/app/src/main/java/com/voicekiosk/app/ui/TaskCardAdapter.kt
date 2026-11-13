package com.voicekiosk.app.ui

import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.voicekiosk.app.R
import com.voicekiosk.app.data.ChecklistItem
import com.voicekiosk.app.data.ListItem
import com.voicekiosk.app.data.KeyValuePair

sealed class BoardItemDisplay {
    data class Task(val item: ChecklistItem) : BoardItemDisplay()
    data class PlainText(val item: ListItem) : BoardItemDisplay()
    data class KeyValue(val item: KeyValuePair) : BoardItemDisplay()
}

/**
 * Adapter for rendering generic board content: task checklist cards, plain text items,
 * and key-value pairs.
 *
 * NOTE: Contains ZERO estimated-time fields per user decision.
 * Contains ZERO hardcoded domain or subject strings.
 */
class TaskCardAdapter(
    val boardId: String,
    private val items: MutableList<BoardItemDisplay>,
    private val onItemToggle: (itemId: String, completed: Boolean) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    fun updateItems(newItems: List<BoardItemDisplay>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    companion object {
        const val VIEW_TYPE_TASK = 1
        const val VIEW_TYPE_PLAIN = 2
        const val VIEW_TYPE_KEY_VALUE = 3
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is BoardItemDisplay.Task -> VIEW_TYPE_TASK
            is BoardItemDisplay.PlainText -> VIEW_TYPE_PLAIN
            is BoardItemDisplay.KeyValue -> VIEW_TYPE_KEY_VALUE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_TASK -> {
                val v = inflater.inflate(R.layout.item_task_card, parent, false)
                TaskViewHolder(v)
            }
            VIEW_TYPE_PLAIN -> {
                val v = inflater.inflate(R.layout.item_board_list, parent, false)
                PlainViewHolder(v)
            }
            VIEW_TYPE_KEY_VALUE -> {
                val v = inflater.inflate(R.layout.item_board_key_value, parent, false)
                KeyValueViewHolder(v)
            }
            else -> throw IllegalArgumentException("Unknown viewType: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is BoardItemDisplay.Task -> (holder as TaskViewHolder).bind(item.item)
            is BoardItemDisplay.PlainText -> (holder as PlainViewHolder).bind(item.item)
            is BoardItemDisplay.KeyValue -> (holder as KeyValueViewHolder).bind(item.item)
        }
    }

    fun getTaskCount(): Int = items.count { it is BoardItemDisplay.Task }

    fun getCompletedTaskCount(): Int = items.count { it is BoardItemDisplay.Task && it.item.completed }

    inner class TaskViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardContainer: LinearLayout = itemView.findViewById(R.id.cardContainer)
        private val categoryRow: LinearLayout = itemView.findViewById(R.id.categoryRow)
        private val viewCategoryDot: View = itemView.findViewById(R.id.viewCategoryDot)
        private val tvCategoryLabel: TextView = itemView.findViewById(R.id.tvCategoryLabel)
        private val tvTaskText: TextView = itemView.findViewById(R.id.tvTaskText)
        private val checkboxContainer: FrameLayout = itemView.findViewById(R.id.checkboxContainer)
        private val ivCheckbox: ImageView = itemView.findViewById(R.id.ivCheckbox)

        fun bind(task: ChecklistItem) {
            val context = itemView.context

            // Category dot and label
            if (task.label.isNotEmpty()) {
                categoryRow.visibility = View.VISIBLE
                tvCategoryLabel.text = task.label
                val dotColor = try {
                    if (task.colorHint.isNotEmpty()) Color.parseColor(task.colorHint) else ContextCompat.getColor(context, R.color.accent_cyan)
                } catch (_: Exception) {
                    ContextCompat.getColor(context, R.color.accent_cyan)
                }
                viewCategoryDot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(dotColor)
                }
            } else {
                categoryRow.visibility = View.GONE
            }

            // Task text
            tvTaskText.text = task.text

            // Apply completion state visually
            applyState(task.completed)

            val toggleAction = View.OnClickListener {
                val target = !task.completed
                task.completed = target
                applyState(target)
                onItemToggle(task.id, target)
            }

            cardContainer.setOnClickListener(toggleAction)
            checkboxContainer.setOnClickListener(toggleAction)
        }

        private fun applyState(completed: Boolean) {
            val context = itemView.context
            if (completed) {
                tvTaskText.paintFlags = tvTaskText.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                tvTaskText.setTextColor(ContextCompat.getColor(context, R.color.text_muted))
                categoryRow.alpha = 0.5f
                ivCheckbox.setImageResource(R.drawable.ic_checkbox_checked)
            } else {
                tvTaskText.paintFlags = tvTaskText.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                tvTaskText.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                categoryRow.alpha = 1.0f
                ivCheckbox.setImageResource(R.drawable.ic_checkbox_unchecked)
            }
        }
    }

    class PlainViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvListText: TextView = itemView.findViewById(R.id.tvListText)
        fun bind(item: ListItem) {
            tvListText.text = item.text
        }
    }

    class KeyValueViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvKey: TextView = itemView.findViewById(R.id.tvKey)
        private val tvValue: TextView = itemView.findViewById(R.id.tvValue)
        fun bind(item: KeyValuePair) {
            tvKey.text = item.key
            tvValue.text = item.value
        }
    }
}
