package com.voicekiosk.app.ui

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView

/**
 * Pager adapter for the horizontal navigation container (ViewPager2).
 * In Phase 2, this provides the empty pager framework with placeholder pages
 * to verify swiping, page indicator transitions, and OLED dark layout.
 * Phases 3 to 6 will bind the actual Home, Music, and dynamic Board pages.
 *
 * NOTE: Contains zero hardcoded board content and zero estimated-time fields.
 */
class MainPagerAdapter(
    private var pageCount: Int = 3
) : RecyclerView.Adapter<MainPagerAdapter.PageViewHolder>() {

    class PageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    fun setPageCount(count: Int) {
        val coerced = count.coerceAtLeast(1)
        if (pageCount != coerced) {
            pageCount = coerced
            notifyDataSetChanged()
        }
    }

    override fun getItemCount(): Int = pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val frame = FrameLayout(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }
        return PageViewHolder(frame)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        // Pure black OLED placeholder container. No hardcoded board content or estimated time.
    }
}
