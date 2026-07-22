package com.ble.notification.sdk

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 持久化日志缓冲区。日志写入 SharedPreferences，跨 APP 重启保留。
 */
object LogRepository {

    private const val PREFS_NAME = "ble_log_buffer"
    private const val KEY_LOGS = "log_entries"
    private const val MAX_ENTRIES = 200

    private val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val lock = Any()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 追加一条带时间戳的日志。
     */
    fun append(context: Context, msg: String) {
        val ts = sdf.format(Date())
        val line = "$ts $msg"
        synchronized(lock) {
            val existing = prefs(context).getString(KEY_LOGS, "") ?: ""
            val updated = if (existing.isEmpty()) line else "$existing\n$line"
            // 超过上限时截断旧日志
            val trimmed = trimToLimit(updated)
            prefs(context).edit().putString(KEY_LOGS, trimmed).apply()
        }
    }

    /**
     * 获取所有缓存日志（用于启动时恢复）。
     */
    fun getAll(context: Context): String =
        prefs(context).getString(KEY_LOGS, "") ?: ""

    /**
     * 清空日志。
     */
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_LOGS).apply()
    }

    private fun trimToLimit(text: String): String {
        val lines = text.split("\n")
        return if (lines.size > MAX_ENTRIES) {
            lines.takeLast(MAX_ENTRIES).joinToString("\n")
        } else {
            text
        }
    }
}
