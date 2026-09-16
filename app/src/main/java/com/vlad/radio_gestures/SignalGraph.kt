package com.vlad.radio_gestures

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.max
import kotlin.math.min

class SignalGraph(context: Context) : View(context) {
    private val points = ArrayDeque<Int>()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 5f; style = Paint.Style.STROKE }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f }
    private var baseline = 0

    fun add(rssi: Int) {
        if (points.size >= 180) points.removeFirst()
        points.addLast(rssi)
        if (baseline == 0) baseline = rssi
        invalidate()
    }

    fun delta(): Int = if (points.isEmpty()) 0 else points.last() - baseline

    fun clear() { points.clear(); baseline = 0; invalidate() }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        c.drawColor(0xFF101010.toInt())
        val w = width.toFloat(); val h = height.toFloat()
        for (i in 0..4) { val y = h * i / 4f; c.drawLine(0f,y,w,y,grid) }
        if (points.size < 2) return
        val minV = min(-100, points.minOrNull()!! - 5)
        val maxV = max(-20, points.maxOrNull()!! + 5)
        val range = (maxV - minV).toFloat().coerceAtLeast(1f)
        val path = Path()
        points.forEachIndexed { i, v ->
            val x = i * w / 179f
            val y = h - ((v - minV) / range) * h
            if (i == 0) path.moveTo(x,y) else path.lineTo(x,y)
        }
        c.drawPath(path, line)
    }
}
