// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import android.view.WindowInsets
import pt.creativemoods.scorepuff.data.models.ScreenRect

class RatingBadgeView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val star = Path()
    private val density = resources.displayMetrics.density
    private val textScale = density * resources.configuration.fontScale
    private var titles = emptyList<TitleBadge>()
    private var contentViewport: ScreenRect? = null
    private val origin = IntArray(2)
    private var safeInsets = android.graphics.Rect()
    private var lastLayout = emptyList<PositionedBadge>()
    var onBadgeCount: ((Int) -> Unit)? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isClickable = false
        isFocusable = false
    }

    fun submit(newTitles: List<TitleBadge>, viewport: ScreenRect? = null) {
        if (titles == newTitles && contentViewport == viewport) return
        contentViewport = viewport
        titles = newTitles
        if (newTitles.isEmpty()) lastLayout = emptyList()
        invalidate()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val safe = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            safeInsets.set(safe.left, safe.top, safe.right, safe.bottom)
        } else {
            @Suppress("DEPRECATION")
            safeInsets.set(insets.stableInsetLeft, insets.stableInsetTop, insets.stableInsetRight, insets.stableInsetBottom)
            insets.displayCutout?.let {
                safeInsets.left = maxOf(safeInsets.left, it.safeInsetLeft)
                safeInsets.top = maxOf(safeInsets.top, it.safeInsetTop)
                safeInsets.right = maxOf(safeInsets.right, it.safeInsetRight)
                safeInsets.bottom = maxOf(safeInsets.bottom, it.safeInsetBottom)
            }
        }
        invalidate()
        return insets
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        getLocationOnScreen(origin)
        // WindowManager may offset the overlay on some OEMs: use its measured origin.
        val visible = android.graphics.Rect()
        getWindowVisibleDisplayFrame(visible)
        val screenViewport = ScreenRect(origin[0] + safeInsets.left, origin[1] + safeInsets.top,
            origin[0] + width - safeInsets.right, origin[1] + height - safeInsets.bottom)
            .intersect(ScreenRect(visible.left, visible.top, visible.right, visible.bottom))
        val viewport = contentViewport?.let { screenViewport?.intersect(it) } ?: screenViewport?.takeIf { contentViewport == null }
        val layout = if (viewport == null) emptyList() else BadgeLayout.place(titles.map { it.title }, viewport, density)
        if (layout != lastLayout) {
            lastLayout = layout
            // Posting keeps state updates outside Canvas drawing.
            post {
                if (isAttachedToWindow && lastLayout == layout) onBadgeCount?.invoke(layout.size)
            }
        }
        val contents = titles.associate { it.title.key to it.content }
        var loadingVisible = false
        for (badge in layout) {
            val content = contents[badge.key] ?: continue
            val r = badge.bounds
            val left = (r.left - origin[0]).toFloat()
            val top = (r.top - origin[1]).toFloat()
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(250, 29, 9, 47)
            val inset = 0.65f * density
            val corner = r.height / 2f
            canvas.drawRoundRect(left, top, left + r.width, top + r.height, corner, corner, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.3f * density
            paint.color = Color.rgb(255, 204, 0)
            canvas.drawRoundRect(left + inset, top + inset, left + r.width - inset,
                top + r.height - inset, corner, corner, paint)
            paint.style = Paint.Style.FILL
            if (content == BadgeContent.Loading) {
                loadingVisible = true
                val cx = left + r.width / 2f
                val cy = top + r.height / 2f
                val radius = 6 * density
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.8f * density
                paint.strokeCap = Paint.Cap.ROUND
                paint.color = Color.argb(80, 255, 255, 255)
                canvas.drawCircle(cx, cy, radius, paint)
                paint.color = Color.WHITE
                val angle = (android.os.SystemClock.uptimeMillis() % 1200L) * 360f / 1200f
                canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, angle, 100f, false, paint)
                continue
            }
            val ambiguous = content == BadgeContent.Ambiguous
            if (ambiguous) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.3f * density
                paint.color = Color.rgb(255, 204, 0)
                canvas.drawCircle(left + r.width / 2f, top + r.height / 2f, 7 * density, paint)
            }
            paint.style = Paint.Style.FILL
            val label = if (content is BadgeContent.Score) content.label else "?"
            paint.textSize = (if (ambiguous) 10 else 12) * textScale
            // Fit even 10.0 and larger system font sizes without truncating the number.
            if (!ambiguous) {
                star.reset()
                val cx = left + 6 * density
                val cy = top + r.height / 2f
                for (point in 0 until 10) {
                    val angle = Math.PI * point / 5 - Math.PI / 2
                    val radius = (if (point % 2 == 0) 3.5f else 1.65f) * density
                    val x = cx + kotlin.math.cos(angle).toFloat() * radius
                    val y = cy + kotlin.math.sin(angle).toFloat() * radius
                    if (point == 0) star.moveTo(x, y) else star.lineTo(x, y)
                }
                star.close()
                paint.color = Color.rgb(255, 204, 0)
                canvas.drawPath(star, paint)
            }
            val availableWidth = if (ambiguous) 10 * density else r.width - 13 * density
            val availableHeight = if (ambiguous) 12 * density else r.height - 4 * density
            val metrics = paint.fontMetrics
            val fit = minOf(1f, availableWidth / paint.measureText(label),
                availableHeight / (metrics.descent - metrics.ascent))
            paint.textSize *= fit
            paint.color = if (ambiguous) Color.rgb(255, 204, 0) else Color.WHITE
            val fittedMetrics = paint.fontMetrics
            val baseline = top + r.height / 2f - (fittedMetrics.ascent + fittedMetrics.descent) / 2f
            val textLeft = if (ambiguous) left else left + 10 * density
            val textWidth = if (ambiguous) r.width.toFloat() else r.width - 12 * density
            canvas.drawText(label, textLeft + (textWidth - paint.measureText(label)) / 2f, baseline, paint)
        }
        // Animate only when a loading icon actually fits in the visible viewport.
        if (loadingVisible && isAttachedToWindow) postInvalidateDelayed(50)
    }
}
