package com.zpq.phoneagent

import android.content.Context
import java.io.File

/** 端侧调用审计日志：每次请求一行（#id METHOD path -> CODE ms note）。
 *  落 App 私有目录，PC 侧通过 GET /file 或 adb 取回；诊断包由向导一键导出纯文本。
 *  注意：只记路径与耗时，**不记请求体内容**（里面常有聊天正文/客户信息）。 */
object AgentLog {
    private const val NAME = "agent_calls.log"
    private val lock = Any()

    fun line(c: Context, s: String) {
        synchronized(lock) {
            try {
                val f = File(c.filesDir, NAME)
                if (f.length() > 2_000_000) f.delete()          // 简单滚动，不无限长大
                f.appendText(s + "\n")
            } catch (_: Exception) {
            }
        }
    }

    fun tail(c: Context, n: Int = 60): String {
        return try {
            val f = File(c.filesDir, NAME)
            if (!f.exists()) return "(还没有调用记录)"
            val all = f.readText().lines().filter { it.isNotBlank() }
            all.takeLast(n).joinToString("\n")
        } catch (e: Exception) {
            "读日志失败：${e.message}"
        }
    }
}
