package com.hasan.clearvoice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.util.ArrayDeque

/**
 * Live waveform: grey bars = raw input peaks, blue bars = cleaned
 * output peaks, scrolling left as new frames arrive (10 ms each,
 * pushed by the recording loop). Pure framework View — no libraries.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val maxBars = 56
    private val inputs = ArrayDeque<Float>()
    private val outputs = ArrayDeque<Float>()

    private val inputPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5A6B7D.toInt()
        strokeCap = Paint.Cap.ROUND
    }
    private val outputPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF38B6FF.toInt()
        strokeCap = Paint.Cap.ROUND
    }
    private val idlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF22314A.toInt()
        strokeCap = Paint.Cap.ROUND
    }

    /** Push one frame's peaks (0..1). Safe to call from any thread. */
    fun push(inputPeak: Float, outputPeak: Float) {
        synchronized(this) {
            if (inputs.size >= maxBars) {
                inputs.removeFirst()
                outputs.removeFirst()
            }
            inputs.addLast(inputPeak.coerceIn(0f, 1f))
            outputs.addLast(outputPeak.coerceIn(0f, 1f))
        }
        postInvalidate()
    }

    fun clearAll() {
        synchronized(this) {
            inputs.clear()
            outputs.clear()
        }
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val midY = h / 2f
        val slot = w / maxBars
        val inArr: FloatArray
        val outArr: FloatArray
        synchronized(this) {
            inArr = inputs.toFloatArray()
            outArr = outputs.toFloatArray()
        }
        inputPaint.strokeWidth = slot * 0.52f
        outputPaint.strokeWidth = slot * 0.30f
        idlePaint.strokeWidth = slot * 0.30f

        // Idle dotted centre line when nothing has been recorded yet.
        if (inArr.isEmpty()) {
            var x = slot / 2f
            var i = 0
            while (i < maxBars) {
                canvas.drawLine(x, midY - 2f, x, midY + 2f, idlePaint)
                x += slot
                i++
            }
            return
        }
        var i = 0
        while (i < inArr.size) {
            val x = slot * (i + 0.5f)
            val inH = (inArr[i] * (h * 0.46f)).coerceAtLeast(2f)
            val outH = (outArr[i] * (h * 0.46f)).coerceAtLeast(2f)
            canvas.drawLine(x, midY - inH, x, midY + inH, inputPaint)
            canvas.drawLine(x, midY - outH, x, midY + outH, outputPaint)
            i++
        }
    }
}
