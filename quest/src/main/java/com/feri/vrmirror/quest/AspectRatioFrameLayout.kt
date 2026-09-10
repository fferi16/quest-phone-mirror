package com.feri.vrmirror.quest

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Olyan konténer, amely a rendelkezésre álló helyen belül a megadott
 * képarányt tartja (a videó nem torzul, fekete sáv kerül mellé).
 */
class AspectRatioFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /** szélesség / magasság */
    private var ratio = 9f / 19.5f

    fun setAspectRatio(widthOverHeight: Float) {
        if (widthOverHeight > 0f && widthOverHeight != ratio) {
            ratio = widthOverHeight
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec)
        val maxH = MeasureSpec.getSize(heightMeasureSpec)
        if (maxW == 0 || maxH == 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        var w = maxW
        var h = (w / ratio).toInt()
        if (h > maxH) {
            h = maxH
            w = (h * ratio).toInt()
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        )
    }
}
