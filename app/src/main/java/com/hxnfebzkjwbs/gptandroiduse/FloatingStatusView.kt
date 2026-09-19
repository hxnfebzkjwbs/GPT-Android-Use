package com.hxnfebzkjwbs.gptandroiduse

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

class FloatingStatusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private var iconStyle = OverlaySettings.STYLE_RING
    private var taskStatus = ""

    fun setIconStyle(style: String) {
        iconStyle = OverlaySettings.normalizeIconStyle(style)
        invalidate()
    }

    fun setTaskStatus(status: String) {
        taskStatus = status
        contentDescription = when (status) {
            "连接" -> "GPT Android Use，正在连接"
            "进行" -> "GPT Android Use，任务进行中"
            "完成" -> "GPT Android Use，任务已完成"
            "失败" -> "GPT Android Use，任务失败"
            else -> "GPT Android Use"
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        if (size <= 0f) return

        val cx = width / 2f
        val cy = height / 2f
        val r = size * 0.44f

        when (iconStyle) {
            OverlaySettings.STYLE_ORBIT -> drawOrbit(canvas, cx, cy, r)
            OverlaySettings.STYLE_ROBOT -> drawRobot(canvas, cx, cy, r)
            OverlaySettings.STYLE_MINIMAL -> drawMinimal(canvas, cx, cy, r)
            else -> drawRing(canvas, cx, cy, r)
        }

        drawStatus(canvas, cx, cy, r)
        if (taskStatus == "连接" || taskStatus == "进行") {
            postInvalidateDelayed(48L)
        }
    }

    private fun drawRing(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(37, 39, 44)
        canvas.drawCircle(cx, cy, r, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.4f)
        paint.color = Color.argb(110, 255, 255, 255)
        canvas.drawCircle(cx, cy, r * 0.68f, paint)

        drawSpark(canvas, cx, cy, r * 0.34f, Color.WHITE)
    }

    private fun drawOrbit(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(30, 38, 50)
        canvas.drawCircle(cx, cy, r, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.4f)
        paint.color = Color.argb(165, 238, 242, 248)
        rect.set(cx - r * 0.64f, cy - r * 0.34f, cx + r * 0.64f, cy + r * 0.34f)
        canvas.save()
        canvas.rotate(-28f, cx, cy)
        canvas.drawOval(rect, paint)
        canvas.restore()

        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, r * 0.16f, paint)

        val angle = Math.toRadians(-28.0)
        val dotX = cx + (cos(angle) * r * 0.60f).toFloat()
        val dotY = cy + (sin(angle) * r * 0.60f).toFloat()
        canvas.drawCircle(dotX, dotY, r * 0.09f, paint)
    }

    private fun drawRobot(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(42, 43, 48)
        rect.set(cx - r, cy - r * 0.82f, cx + r, cy + r * 0.82f)
        canvas.drawRoundRect(rect, r * 0.38f, r * 0.38f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.5f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.WHITE
        canvas.drawLine(cx, cy - r * 0.82f, cx, cy - r * 1.02f, paint)

        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy - r * 1.06f, r * 0.07f, paint)
        canvas.drawCircle(cx - r * 0.34f, cy - r * 0.12f, r * 0.12f, paint)
        canvas.drawCircle(cx + r * 0.34f, cy - r * 0.12f, r * 0.12f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.5f)
        rect.set(cx - r * 0.32f, cy + r * 0.06f, cx + r * 0.32f, cy + r * 0.40f)
        canvas.drawArc(rect, 20f, 140f, false, paint)
    }

    private fun drawMinimal(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(245, 246, 248)
        canvas.drawCircle(cx, cy, r, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.2f)
        paint.color = Color.rgb(70, 73, 80)
        canvas.drawCircle(cx, cy, r, paint)

        drawSpark(canvas, cx, cy, r * 0.42f, Color.rgb(40, 42, 47))
    }

    private fun drawSpark(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        color: Int
    ) {
        path.reset()
        path.moveTo(cx, cy - radius)
        path.lineTo(cx + radius * 0.30f, cy - radius * 0.30f)
        path.lineTo(cx + radius, cy)
        path.lineTo(cx + radius * 0.30f, cy + radius * 0.30f)
        path.lineTo(cx, cy + radius)
        path.lineTo(cx - radius * 0.30f, cy + radius * 0.30f)
        path.lineTo(cx - radius, cy)
        path.lineTo(cx - radius * 0.30f, cy - radius * 0.30f)
        path.close()

        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawPath(path, paint)
    }

    private fun drawStatus(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        when (taskStatus) {
            "连接" -> drawSpinner(canvas, cx, cy, r)
            "进行" -> drawPulse(canvas, cx, cy, r)
            "完成" -> drawCheckBadge(canvas, cx, cy, r)
            "失败" -> drawAlertBadge(canvas, cx, cy, r)
        }
    }

    private fun drawSpinner(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val phase = (SystemClock.uptimeMillis() % 1_100L) / 1_100f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2.8f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.WHITE
        rect.set(cx - r * 0.88f, cy - r * 0.88f, cx + r * 0.88f, cy + r * 0.88f)
        canvas.drawArc(rect, phase * 360f, 94f, false, paint)
    }

    private fun drawPulse(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val phase = (SystemClock.uptimeMillis() % 1_000L) / 1_000f
        val wave = (0.5f + 0.5f * sin(phase * Math.PI * 2.0)).toFloat()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2.2f)
        paint.color = Color.argb((105 + 150 * wave).toInt(), 255, 255, 255)
        canvas.drawCircle(cx, cy, r * (0.76f + 0.11f * wave), paint)
    }

    private fun drawCheckBadge(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val bx = cx + r * 0.62f
        val by = cy + r * 0.62f
        val br = r * 0.34f

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(44, 125, 82)
        canvas.drawCircle(bx, by, br, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2.0f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = Color.WHITE
        path.reset()
        path.moveTo(bx - br * 0.48f, by)
        path.lineTo(bx - br * 0.12f, by + br * 0.34f)
        path.lineTo(bx + br * 0.52f, by - br * 0.38f)
        canvas.drawPath(path, paint)
    }

    private fun drawAlertBadge(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val bx = cx + r * 0.62f
        val by = cy + r * 0.62f
        val br = r * 0.34f

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(178, 64, 64)
        canvas.drawCircle(bx, by, br, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2.0f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.WHITE
        canvas.drawLine(bx, by - br * 0.48f, bx, by + br * 0.12f, paint)

        paint.style = Paint.Style.FILL
        canvas.drawCircle(bx, by + br * 0.48f, dp(1.2f), paint)
    }

    private fun dp(value: Float): Float =
        value * resources.displayMetrics.density
}
