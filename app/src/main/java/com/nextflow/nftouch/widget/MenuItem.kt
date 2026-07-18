package com.nextflow.nftouch.widget

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.view.isVisible
import com.nextflow.nftouch.R

class MenuItem @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val ivLeading: ImageView
    private val tvTitle: TextView
    private val tvSubtitle: TextView
    private val viewRedDot: View
    private val tvTrailing: TextView
    private val ivTrailing: ImageView
    private val divider: View

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.widget_menu_item, this, true)

        ivLeading = findViewById(R.id.ivLeading)
        tvTitle = findViewById(R.id.tvTitle)
        tvSubtitle = findViewById(R.id.tvSubtitle)
        viewRedDot = findViewById(R.id.viewRedDot)
        tvTrailing = findViewById(R.id.tvTrailing)
        ivTrailing = findViewById(R.id.ivTrailing)
        divider = findViewById(R.id.divider)

        setShowTrailingIcon(true)
    }

    fun setLeadingIcon(@DrawableRes iconRes: Int) {
        ivLeading.setImageResource(iconRes)
    }

    fun setLeadingIconColor(color: Int) {
        ivLeading.setColorFilter(color)
    }

    fun setTitle(title: CharSequence) {
        tvTitle.text = title
    }

    fun setTitleColor(color: Int) {
        tvTitle.setTextColor(color)
    }

    /**
     * 设置副标题，null 或空字符串时隐藏
     */
    fun setSubtitle(subtitle: CharSequence?) {
        tvSubtitle.isVisible = !subtitle.isNullOrEmpty()
        tvSubtitle.text = subtitle
    }

    fun setSubtitleColor(color: Int) {
        tvSubtitle.setTextColor(color)
    }

    fun setTrailingText(text: CharSequence?) {
        tvTrailing.isVisible = !text.isNullOrEmpty()
        tvTrailing.text = text
    }

    fun setTrailingTextColor(color: Int) {
        tvTrailing.setTextColor(color)
    }

    fun setTrailingIcon(@DrawableRes iconRes: Int) {
        ivTrailing.setImageResource(iconRes)
    }

    fun setTrailingIconColor(color: Int) {
        ivTrailing.setColorFilter(color)
    }

    fun setShowTrailingIcon(show: Boolean) {
        ivTrailing.visibility = if (show) View.VISIBLE else View.GONE
        val lp = tvTrailing.layoutParams as MarginLayoutParams
        lp.marginEnd = if (show) 0 else TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_PT, 8f, resources.displayMetrics
        ).toInt()
        tvTrailing.layoutParams = lp
    }

    fun setShowDivider(show: Boolean) {
        divider.isVisible = show
    }

    fun getTrailingText(): CharSequence? {
        return tvTrailing.text
    }

    fun setShowRedDot(show: Boolean) {
        viewRedDot.visibility = if (show) View.VISIBLE else View.GONE
    }

    fun getTitle(): CharSequence? {
        return tvTitle.text
    }
}
