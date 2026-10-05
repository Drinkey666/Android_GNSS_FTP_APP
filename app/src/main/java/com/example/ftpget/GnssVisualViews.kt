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

internal fun satelliteColor(system: String): Int = when (system) {
    "G" -> Color.rgb(27, 103, 203)
    "R" -> Color.rgb(217, 70, 73)
    "E" -> Color.rgb(25, 151, 120)
    "C" -> Color.rgb(236, 151, 40)
    else -> Color.rgb(129, 89, 180)
}

class SkyPlotView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var satellites: List<SatelliteDisplay> = emptyList()
    private val density = resources.displayMetrics.density

    fun updateSatellites(items: List<SatelliteDisplay>) {
        satellites = items
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) * 0.39f

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = Color.rgb(210, 204, 222)
        for (ring in 1..3) canvas.drawCircle(cx, cy, radius * ring / 3f, paint)
        canvas.drawLine(cx - radius, cy, cx + radius, cy, paint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(86, 69, 123)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 12f * density
        canvas.drawText("N", cx, cy - radius - 12f * density, paint)
        canvas.drawText("S", cx, cy + radius + 20f * density, paint)
        canvas.drawText("W", cx - radius - 14f * density, cy + 4f * density, paint)
        canvas.drawText("E", cx + radius + 14f * density, cy + 4f * density, paint)

        for (sat in satellites) {
            val angle = Math.toRadians(sat.azimuth.toDouble())
            val distance = radius * (90f - sat.elevation.coerceIn(0f, 90f)) / 90f
            val x = cx + distance * sin(angle).toFloat()
            val y = cy - distance * cos(angle).toFloat()
            paint.color = satelliteColor(sat.system)
            paint.alpha = if (sat.usedInFix) 255 else 165
            canvas.drawCircle(x, y, 13f * density, paint)
            paint.alpha = 255
            paint.color = Color.WHITE
            paint.textSize = 9f * density
            canvas.drawText("${sat.system}${sat.svid}", x, y + 3f * density, paint)
        }
        if (satellites.isEmpty()) {
            paint.color = Color.rgb(116, 111, 130)
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
    private val density = resources.displayMetrics.density

    fun updateSatellites(items: List<SatelliteDisplay>) {
        satellites = items.groupBy { it.system }
            .values.mapNotNull { group -> group.maxByOrNull { it.cn0 } }
            .sortedBy { it.system }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 30f * density
        val top = 16f * density
        val bottom = height - 35f * density
        val right = width - 10f * density
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 10f * density
        for (value in 0..5) {
            val y = bottom - (bottom - top) * value / 5f
            paint.color = Color.rgb(225, 221, 233)
            paint.strokeWidth = density
            canvas.drawLine(left, y, right, y, paint)
            paint.color = Color.rgb(116, 111, 130)
            canvas.drawText("${value * 10}", left - 16f * density, y + 3f * density, paint)
        }
        if (satellites.isEmpty()) {
            paint.color = Color.rgb(116, 111, 130)
            paint.textSize = 12f * density
            canvas.drawText("等待信号强度数据", width / 2f, height / 2f, paint)
            return
        }
        val cell = (right - left) / satellites.size
        val barWidth = min(40f * density, cell * 0.55f)
        satellites.forEachIndexed { index, sat ->
            val x = left + cell * (index + 0.5f)
            val barHeight = (bottom - top) * sat.cn0.coerceIn(0f, 50f) / 50f
            paint.color = satelliteColor(sat.system)
            canvas.drawRoundRect(RectF(x - barWidth / 2, bottom - barHeight,
                x + barWidth / 2, bottom), 4f * density, 4f * density, paint)
            paint.color = Color.rgb(36, 32, 51)
            canvas.drawText("${sat.system}${sat.svid}", x, bottom + 16f * density, paint)
            canvas.drawText("${sat.cn0.toInt()}", x, bottom - barHeight - 5f * density, paint)
        }
    }
}
