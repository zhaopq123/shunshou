package com.zpq.phoneagent

import android.content.Context
import android.content.SharedPreferences

/** token 存本地 SharedPreferences：由首启向导（或 adb 一次性写入）设置。
 *  ⚠ 未设置 token 时 HTTP **不做鉴权**（M1 期局域网内为方便自测的刻意选择）；
 *  M2 的向导里必须强制设置 token，并在 /selftest 里把"无 token"标成警告项。 */
object AgentPrefs {
    private const val FILE = "agent"
    private const val KEY_TOKEN = "token"
    private const val KEY_ALIAS = "alias"
    fun token(c: Context): String =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_TOKEN, "") ?: ""
    fun setToken(c: Context, v: String) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_TOKEN, v.trim()).apply()
    }

    /** 设备别名：一台电脑连多部同型号手机时，靠它区分（"客厅机"/"测试机"）。空 = 用默认别名。 */
    fun alias(c: Context): String =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_ALIAS, "")?.trim() ?: ""
    fun setAlias(c: Context, v: String) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_ALIAS, v.trim()).apply()
    }

    /** 默认别名：型号 + android_id 后 4 位。同型号十台机也不会撞。 */
    fun defaultAlias(c: Context): String {
        val id = try {
            android.provider.Settings.Secure.getString(c.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID) ?: ""
        } catch (e: Exception) { "" }
        val tail = if (id.length >= 4) id.takeLast(4) else id
        return (android.os.Build.MODEL ?: "phone") + "-" + tail
    }

    fun displayAlias(c: Context): String = alias(c).ifEmpty { defaultAlias(c) }
}
