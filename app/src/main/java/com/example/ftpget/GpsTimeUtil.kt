package com.example.ftpget

import java.util.Calendar
import java.util.TimeZone

data class IgsTimeParams(val week: Int, val year: Int, val doy: String)

object GpsTimeUtil {
    // 往回推算 daysAgo 天，并生成 V3 命名所需的参数
    fun getIgsV3TimeParams(daysAgo: Int = 0): IgsTimeParams {
        val targetCal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        targetCal.add(Calendar.DAY_OF_YEAR, -daysAgo)

        // 获取年份 (例如 2026)
        val year = targetCal.get(Calendar.YEAR)
        // 获取年积日 (Day of Year)，必须补齐3位 (例如 086)
        val doy = String.format("%03d", targetCal.get(Calendar.DAY_OF_YEAR))

        // 计算 GPS 周
        val epochCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(1980, 0, 6, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val diffDays = (targetCal.timeInMillis - epochCal.timeInMillis) / (1000 * 60 * 60 * 24)
        val gpsWeek = (diffDays / 7).toInt()

        return IgsTimeParams(gpsWeek, year, doy)
    }
}