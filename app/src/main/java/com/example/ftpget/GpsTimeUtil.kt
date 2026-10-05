package com.example.ftpget

import java.util.Calendar
import java.util.TimeZone

/** 产品下载命名参数：week 为目录 GPS 周，year 为 UTC 年，doy 为补齐三位的年积日(001～366)。 */
data class IgsTimeParams(val week: Int, val year: Int, val doy: String)

/**
 * 从手机系统日期构造产品目录/文件名；不是 GnssClock → 观测 GPST 的转换函数。
 * 按 UTC 日历天推算周目录，未作闰秒边界精细换算，不能用于计算伪距或代替原始测量时标。
 */
object GpsTimeUtil {
    // daysAgo=0 今天、1 昨天；按 UTC 日历减整天，避免按本地午夜误取日期。
    fun getIgsV3TimeParams(daysAgo: Int = 0): IgsTimeParams {
        val targetCal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        targetCal.add(Calendar.DAY_OF_YEAR, -daysAgo)

        // 获取年份 (例如 2026)
        val year = targetCal.get(Calendar.YEAR)
        // 获取年积日 (Day of Year)，必须补齐3位 (例如 086)
        val doy = String.format("%03d", targetCal.get(Calendar.DAY_OF_YEAR))

        // 以 1980-01-06 为起点，整天差/7 得目录周；不包含周内秒，也不验证产品覆盖。
        val epochCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(1980, 0, 6, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val diffDays = (targetCal.timeInMillis - epochCal.timeInMillis) / (1000 * 60 * 60 * 24)
        val gpsWeek = (diffDays / 7).toInt()

        return IgsTimeParams(gpsWeek, year, doy)
    }
}
