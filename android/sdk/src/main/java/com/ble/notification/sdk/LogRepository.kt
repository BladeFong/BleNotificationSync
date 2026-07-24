package com.ble.notification.sdk

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 持久化日志缓冲区。日志写入 SharedPreferences，跨 APP 重启保留。
 * 支持相同状态消息的时间戳智能更新与去重。
 */
object LogRepository {

    private const val PREFS_NAME = "ble_log_buffer"
    private const val KEY_LOGS = "log_entries"
    private const val MAX_ENTRIES = 200

    private val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val lock = Any()

    // 敏感日志全局 debug 开关。线上正式发布时，通过将其置为 false 关停敏感日志持久化。
    var isDebugEnabled = true

    fun logd(tag: String, msg: String) {
        if (isDebugEnabled) {
            Log.d(tag, msg)
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 追加一条带时间戳的日志，并返回格式化后的单行日志。
     */
    fun append(context: Context, msg: String): String {
        return appendOrUpdate(context, msg).first
    }

    /**
     * 追加或更新日志。
     */
    fun appendOrUpdate(context: Context, msg: String): Pair<String, Boolean> {
        val ts = sdf.format(Date())
        val newLine = "$ts $msg"

        logd("BleLog", msg)

        // 若不是 Debug 调试模式，则严禁在 SharedPreferences 中持久化记录该日志（防范敏感通知数据明文存盘）
        if (!isDebugEnabled) {
            return Pair(newLine, false)
        }

        synchronized(lock) {
            val existing = prefs(context).getString(KEY_LOGS, "") ?: ""
            if (existing.isEmpty()) {
                prefs(context).edit().putString(KEY_LOGS, newLine).apply()
                return Pair(newLine, false)
            }

            val lines = existing.split("\n").toMutableList()
            val lastLine = lines.lastOrNull() ?: ""
            // 提取上一条日志的消息文本（去掉 HH:mm:ss 9字符前缀）
            val lastMsg = if (lastLine.length > 9 && lastLine[8] == ' ') lastLine.substring(9) else lastLine

            return if (lastMsg == msg) {
                // 内容一致：替换最后一行的时间戳
                lines[lines.size - 1] = newLine
                val updated = trimToLimit(lines.joinToString("\n"))
                prefs(context).edit().putString(KEY_LOGS, updated).apply()
                Pair(newLine, true)
            } else {
                // 内容不一致：追加新行
                lines.add(newLine)
                val updated = trimToLimit(lines.joinToString("\n"))
                prefs(context).edit().putString(KEY_LOGS, updated).apply()
                Pair(newLine, false)
            }
        }
    }

    /**
     * 获取所有缓存日志（用于启动时恢复）。
     */
    fun getAll(context: Context): String =
        if (!isDebugEnabled) "" else prefs(context).getString(KEY_LOGS, "") ?: ""

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
