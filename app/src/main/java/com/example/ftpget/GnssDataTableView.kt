package com.example.ftpget

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
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
    private var palette = GnssViewPalette.initial()
    private val table = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val emptyView = TextView(context).apply {
        text = "等待数据…"
        setTextColor(palette.muted)
        textSize = 12f
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(12), 0)
        minHeight = dp(54)
    }
    private val rowViews = mutableListOf<LinearLayout>()
    private var columns: List<GnssTableColumn> = emptyList()
    private var lastRows: List<GnssTableRow> = emptyList()
    private var lastEmptyText = "等待数据…"

    init {
        isFillViewport = true
        isHorizontalScrollBarEnabled = true
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        setBackgroundColor(palette.background)
        addView(table, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun setPalette(colors: GnssViewPalette) {
        if (palette == colors) return
        palette = colors
        setBackgroundColor(colors.background)
        emptyView.setTextColor(colors.muted)
        val existingColumns = columns
        columns = emptyList()
        if (existingColumns.isNotEmpty()) {
            configure(existingColumns)
            updateRows(lastRows, lastEmptyText)
        }
    }

    fun configure(newColumns: List<GnssTableColumn>) {
        if (columns == newColumns) return
        columns = newColumns
        rowViews.clear()
        table.removeAllViews()
        table.dividerDrawable = GradientDrawable().apply {
            setColor(palette.grid)
            setSize(1, dp(1))
        }
        table.showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        table.addView(createRow(newColumns.map { it.title }, header = true))
        table.addView(emptyView)
    }

    fun updateRows(rows: List<GnssTableRow>, emptyText: String = "等待数据…") {
        if (columns.isEmpty()) return
        lastRows = rows
        lastEmptyText = emptyText
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
                    cellIndex == 0 && row.system != null -> satelliteColor(row.system, palette.dark)
                    cellIndex == columns.lastIndex && row.usedInFix == true -> palette.success
                    cellIndex == columns.lastIndex && row.usedInFix == false -> palette.muted
                    else -> palette.foreground
                })
            }
        }
    }

    private fun createRow(values: List<String>, header: Boolean): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            background = if (header) rowBackground(-1) else rowBackground(0)
        }
        columns.forEachIndexed { index, column ->
            row.addView(TextView(context).apply {
                text = values.getOrElse(index) { "" }
                gravity = Gravity.CENTER_VERTICAL or column.gravity
                setPadding(dp(10), dp(3), dp(10), dp(3))
                setSingleLine(true)
                textSize = if (header) 11f else 12f
                typeface = if (header || index == 0) Typeface.DEFAULT_BOLD else Typeface.MONOSPACE
                setTextColor(if (header) palette.muted else palette.foreground)
            }, LinearLayout.LayoutParams(dp(column.widthDp), dp(44)))
        }
        return row
    }

    private fun rowBackground(index: Int): ColorDrawable = ColorDrawable(when {
            index < 0 -> palette.header
            index % 2 == 0 -> palette.background
            else -> palette.alternate
        })

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
}
