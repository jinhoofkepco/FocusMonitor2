package io.remotestudy.student

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ImageView
import io.remotestudy.telegram.NormalizedBookRegion
import kotlin.math.abs

internal class BookRegionImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ImageView(context, attrs) {
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 230, 118)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 3f
    }
    private var region = NormalizedBookRegion.DEFAULT
    private var drag = Drag.NONE
    var onRegionChanged: ((NormalizedBookRegion) -> Unit)? = null

    init { scaleType = ScaleType.FIT_CENTER }

    fun region(): NormalizedBookRegion = region
    fun setRegion(value: NormalizedBookRegion) { region = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val content = contentRect() ?: return
        canvas.drawRect(
            content.left + region.left * content.width(),
            content.top + region.top * content.height(),
            content.left + region.right * content.width(),
            content.top + region.bottom * content.height(),
            border,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val content = contentRect() ?: return false
        val nx = ((event.x - content.left) / content.width()).coerceIn(0f, 1f)
        val ny = ((event.y - content.top) / content.height()).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drag = nearest(nx, ny)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val min = 0.08f
                region = when (drag) {
                    Drag.LEFT -> region.copy(left = nx.coerceAtMost(region.right - min))
                    Drag.TOP -> region.copy(top = ny.coerceAtMost(region.bottom - min))
                    Drag.RIGHT -> region.copy(right = nx.coerceAtLeast(region.left + min))
                    Drag.BOTTOM -> region.copy(bottom = ny.coerceAtLeast(region.top + min))
                    Drag.MOVE -> {
                        val width = region.right - region.left
                        val height = region.bottom - region.top
                        val left = (nx - width / 2).coerceIn(0f, 1f - width)
                        val top = (ny - height / 2).coerceIn(0f, 1f - height)
                        NormalizedBookRegion(left, top, left + width, top + height)
                    }
                    Drag.NONE -> region
                }
                invalidate()
                onRegionChanged?.invoke(region)
                return true
            }
            MotionEvent.ACTION_UP -> { drag = Drag.NONE; performClick(); return true }
            MotionEvent.ACTION_CANCEL -> { drag = Drag.NONE; return true }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun nearest(x: Float, y: Float): Drag {
        val distances = listOf(
            Drag.LEFT to abs(x - region.left),
            Drag.RIGHT to abs(x - region.right),
            Drag.TOP to abs(y - region.top),
            Drag.BOTTOM to abs(y - region.bottom),
        )
        val edge = distances.minBy { it.second }
        return if (edge.second < 0.06f) edge.first else Drag.MOVE
    }

    private fun contentRect(): RectF? {
        val drawable = drawable ?: return null
        if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) return null
        val scale = minOf(width.toFloat() / drawable.intrinsicWidth, height.toFloat() / drawable.intrinsicHeight)
        val w = drawable.intrinsicWidth * scale
        val h = drawable.intrinsicHeight * scale
        return RectF((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
    }

    private enum class Drag { NONE, LEFT, TOP, RIGHT, BOTTOM, MOVE }
}
