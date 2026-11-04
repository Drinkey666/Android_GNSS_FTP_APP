package com.example.ftpget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.location.GnssStatus
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

data class SatelliteDisplay(
    val system: String,
    val svid: Int,
    val cn0: Float,
    val elevation: Float,
    val azimuth: Float,
    val usedInFix: Boolean
)

internal fun satelliteSystem(type: Int): String = when (type) {
    GnssStatus.CONSTELLATION_GPS -> "G"
    GnssStatus.CONSTELLATION_GLONASS -> "R"
    GnssStatus.CONSTELLATION_GALILEO -> "E"
    GnssStatus.CONSTELLATION_BEIDOU -> "C"
    GnssStatus.CONSTELLATION_QZSS -> "J"
    GnssStatus.CONSTELLATION_SBAS -> "S"
    GnssStatus.CONSTELLATION_IRNSS -> "I"
    else -> "?"
}

internal fun satelliteColor(system: String, dark: Boolean = false): Int = when (system) {
    "G" -> if (dark) Color.rgb(132, 178, 233) else Color.rgb(45, 98, 167)
    "R" -> if (dark) Color.rgb(239, 154, 160) else Color.rgb(171, 70, 81)
    "E" -> if (dark) Color.rgb(123, 211, 188) else Color.rgb(24, 119, 98)
    "C" -> if (dark) Color.rgb(241, 193, 113) else Color.rgb(155, 103, 30)
    else -> if (dark) Color.rgb(195, 165, 231) else Color.rgb(115, 78, 158)
}

class SkyPlotView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var satellites: List<SatelliteDisplay> = emptyList()
    private var palette = GnssViewPalette.initial()
    private val density = resources.displayMetrics.density

    fun setPalette(colors: GnssViewPalette) {
        if (palette != colors) { palette = colors; invalidate() }
    }

    fun updateSatellites(items: List<SatelliteDisplay>) {
        satellites = items
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(palette.background)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(min(width, height) * 0.39f,
            min(width, height) / 2f - 28f * density).coerceAtLeast(0f)

        paint.style = Paint.Style.STROKE
        for (ring in 1..3) {
            paint.strokeWidth = if (ring == 3) 1.5f * density else density
            paint.color = if (ring == 3) palette.muted else palette.grid
            paint.alpha = if (ring == 3) 155 else 255
            canvas.drawCircle(cx, cy, radius * ring / 3f, paint)
        }
        paint.alpha = 255
        paint.strokeWidth = density
        paint.color = palette.grid
        canvas.drawLine(cx - radius, cy, cx + radius, cy, paint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, paint)

        paint.style = Paint.Style.FILL
        paint.color = palette.muted
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 12f * density
        canvas.drawText("N", cx + 20f * density, cy - radius - 12f * density, paint)
        canvas.drawText("S", cx + 20f * density, cy + radius + 20f * density, paint)
        canvas.drawText("W", cx - radius - 22f * density, cy + 4f * density, paint)
        canvas.drawText("E", cx + radius + 22f * density, cy + 4f * density, paint)
        canvas.drawCircle(cx, cy, 2f * density, paint)

        for (sat in satellites) {
            val angle = Math.toRadians(sat.azimuth.toDouble())
            val distance = radius * (90f - sat.elevation.coerceIn(0f, 90f)) / 90f
            val x = cx + distance * sin(angle).toFloat()
            val y = cy - distance * cos(angle).toFloat()
            val color = satelliteColor(sat.system, palette.dark)
            paint.color = color
            paint.style = if (sat.usedInFix) Paint.Style.FILL else Paint.Style.STROKE
            paint.strokeWidth = 2f * density
            canvas.drawCircle(x, y, 12f * density, paint)
            paint.style = Paint.Style.FILL
            paint.color = if (sat.usedInFix) palette.satelliteText else color
            paint.textSize = 9f * density
            canvas.drawText("${sat.system}${sat.svid}", x, y + 3f * density, paint)
        }
        if (satellites.isEmpty()) {
            paint.color = palette.muted
            paint.textSize = 12f * density
            canvas.drawText("等待卫星信号", cx, cy + 5f * density, paint)
        }
    }
}

class SignalBarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var satellites: List<SatelliteDisplay> = emptyList()
    private var palette = GnssViewPalette.initial()
    private val density = resources.displayMetrics.density

    fun setPalette(colors: GnssViewPalette) {
        if (palette != colors) { palette = colors; invalidate() }
    }

    fun updateSatellites(items: List<SatelliteDisplay>) {
        satellites = items.groupBy { it.system }
            .values.mapNotNull { group -> group.maxByOrNull { it.cn0 } }
            .sortedBy { it.system }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(palette.background)
        val left = 64f * density
        val right = (width - 44f * density).coerceAtLeast(left + density)
        val top = 34f * density
        val bottom = (height - 12f * density).coerceAtLeast(top + density)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 9f * density
        for (value in 0..5) {
            val x = left + (right - left) * value / 5f
            paint.color = palette.grid
            canvas.drawLine(x, top, x, bottom, paint)
            paint.style = Paint.Style.FILL
            paint.color = palette.muted
            canvas.drawText("${value * 10}", x, 22f * density, paint)
            paint.style = Paint.Style.STROKE
        }
        paint.style = Paint.Style.FILL
        if (satellites.isEmpty()) {
            paint.color = palette.muted
            paint.textSize = 12f * density
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("等待信号强度数据", width / 2f, height / 2f, paint)
            return
        }
        val rowHeight = (bottom - top) / satellites.size
        satellites.forEachIndexed { index, sat ->
            val y = top + rowHeight * (index + 0.5f)
            paint.textAlign = Paint.Align.LEFT
            paint.textSize = 11f * density
            paint.color = palette.foreground
            canvas.drawText("${sat.system}${sat.svid}", 12f * density, y + 4f * density, paint)
            paint.color = palette.header
            canvas.drawRoundRect(RectF(left, y - 6f * density,
                right, y + 6f * density), 6f * density, 6f * density, paint)
            paint.color = satelliteColor(sat.system, palette.dark)
            val filled = left + (right - left) * sat.cn0.coerceIn(0f, 50f) / 50f
            canvas.drawRoundRect(RectF(left, y - 6f * density,
                filled, y + 6f * density), 6f * density, 6f * density, paint)
            paint.textAlign = Paint.Align.RIGHT
            paint.textSize = 11f * density
            paint.color = palette.foreground
            canvas.drawText("${sat.cn0.toInt()}", width - 12f * density, y + 4f * density, paint)
        }
    }
}
