package com.example.ftpget

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

data class GnssTableColumn(val title: String, val widthDp: Int, val gravity: Int = Gravity.END)

data class GnssTableRow(
    val values: List<String>,
    val system: String? = null,
    val usedInFix: Boolean? = null
)

/** 固定列宽、可横向滚动的 GNSS 数据表，重复利用行视图以便每秒刷新。 */
class GnssDataTableView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : HorizontalScrollView(context, attrs) {
    private val density = resources.displayMetrics.density
    private val mutedColor = Color.rgb(116, 111, 130)
    private val textColor = Color.rgb(36, 32, 51)
    private val dividerColor = Color.rgb(233, 229, 240)
    private val table = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val emptyView = TextView(context).apply {
        text = "等待数据…"
        setTextColor(mutedColor)
        textSize = 12f
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(12), 0)
        minHeight = dp(54)
    }
    private val rowViews = mutableListOf<LinearLayout>()
    private var columns: List<GnssTableColumn> = emptyList()

    init {
        isFillViewport = true
        isHorizontalScrollBarEnabled = true
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        addView(table, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun configure(newColumns: List<GnssTableColumn>) {
        if (columns == newColumns) return
        columns = newColumns
        rowViews.clear()
        table.removeAllViews()
        table.addView(createRow(newColumns.map { it.title }, header = true))
        table.addView(emptyView)
    }

    fun updateRows(rows: List<GnssTableRow>, emptyText: String = "等待数据…") {
        if (columns.isEmpty()) return
        emptyView.text = emptyText
        emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE

        while (rowViews.size < rows.size) {
            val row = createRow(List(columns.size) { "" }, header = false)
            row.background = rowBackground(rowViews.size)
            table.addView(row, table.childCount - 1)
            rowViews.add(row)
        }
        rowViews.forEachIndexed { index, rowView ->
            if (index >= rows.size) {
                rowView.visibility = View.GONE
                return@forEachIndexed
            }
            val row = rows[index]
            rowView.visibility = View.VISIBLE
            columns.indices.forEach { cellIndex ->
                val cell = rowView.getChildAt(cellIndex) as TextView
                cell.text = row.values.getOrElse(cellIndex) { "—" }
                cell.setTextColor(when {
                    cellIndex == 0 && row.system != null -> satelliteColor(row.system)
                    cellIndex == columns.lastIndex && row.usedInFix == true -> Color.rgb(14, 147, 113)
                    cellIndex == columns.lastIndex && row.usedInFix == false -> mutedColor
                    else -> textColor
                })
            }
        }
    }

    private fun createRow(values: List<String>, header: Boolean): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(if (header) 40 else 42)
            background = if (header) rowBackground(-1) else rowBackground(0)
        }
        columns.forEachIndexed { index, column ->
            row.addView(TextView(context).apply {
                text = values.getOrElse(index) { "" }
                gravity = Gravity.CENTER_VERTICAL or if (header) Gravity.CENTER_HORIZONTAL else column.gravity
                setPadding(dp(7), dp(3), dp(7), dp(3))
                setSingleLine(true)
                textSize = if (header) 11f else 12f
                typeface = if (header || index == 0) Typeface.DEFAULT_BOLD else Typeface.MONOSPACE
                setTextColor(if (header) mutedColor else textColor)
            }, LinearLayout.LayoutParams(dp(column.widthDp), dp(if (header) 40 else 42)))
        }
        return row
    }

    private fun rowBackground(index: Int): GradientDrawable = GradientDrawable().apply {
        setColor(when {
            index < 0 -> Color.rgb(244, 240, 251)
            index % 2 == 0 -> Color.WHITE
            else -> Color.rgb(250, 248, 253)
        })
        setStroke(dp(1), dividerColor)
    }

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
}
