package com.guardian.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * v1.6: seven small bars — trackers blocked per day, today on the right.
 * Plain Canvas drawing, no chart library (keeps the app dependency-free and
 * auditable). Redraws only when the numbers actually change.
 */
class WeekChartView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private var days: List<Pair<String, Long>> = emptyList()
    private val density = resources.displayMetrics.density
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4CC38A") }
    private val barDim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2E6B55") }
    private val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#33415C") }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8FA0BC"); textSize = 11f * density; textAlign = Paint.Align.CENTER
    }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C9D3E3"); textSize = 10f * density; textAlign = Paint.Align.CENTER
    }
    private val r = RectF()

    fun setDays(d: List<Pair<String, Long>>) {
        if (d == days) return
        days = d
        contentDescription = "Blocked per day: " + d.joinToString { "${it.first} ${it.second}" }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (days.isEmpty()) return
        val max = days.maxOf { it.second }.coerceAtLeast(1L)
        val labelH = 16f * density
        val valueH = 14f * density
        val chartTop = valueH
        val chartBottom = height - labelH
        val slot = width / days.size.toFloat()
        val bw = slot * 0.56f
        val radius = 4f * density
        days.forEachIndexed { i, (day, n) ->
            val cx = slot * i + slot / 2
            val h = (chartBottom - chartTop) * (n.toFloat() / max)
            r.set(cx - bw / 2, chartBottom - maxOf(h, 2f * density), cx + bw / 2, chartBottom)
            canvas.drawRoundRect(r, radius, radius,
                if (n == 0L) empty else if (i == days.lastIndex) bar else barDim)
            if (n > 0) canvas.drawText(compact(n), cx, r.top - 3f * density, value)
            canvas.drawText(day, cx, height - 3f * density, label)
        }
    }

    private fun compact(n: Long): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 10_000 -> "${n / 1000}k"
        n >= 1_000 -> "%.1fk".format(n / 1000.0)
        else -> n.toString()
    }
}
