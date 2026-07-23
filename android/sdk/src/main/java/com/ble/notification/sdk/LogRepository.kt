package com.ble.notification.sdk

import android.content.Context
import android.content.SharedPreferences
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
     * 如果最新一条日志的消息内容与传入的 msg 相同，则更新其时间戳并返回 (formattedLine, true)；
     * 否则在末尾追加新行并返回 (formattedLine, false)。
     */
    fun appendOrUpdate(context: Context, msg: String): Pair<String, Boolean> {
        val ts = sdf.format(Date())
        val newLine = "$ts $msg"
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
