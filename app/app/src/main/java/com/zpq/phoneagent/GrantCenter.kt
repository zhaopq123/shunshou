package com.zpq.phoneagent

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast

/**
 * 权限中心 —— 把「顺手」需要的系统开关收成「一键开 / 一键关」。
 *
 * 两条路，能直写就不求人：
 *   1) 有 WRITE_SECURE_SETTINGS（adb 一次性 `pm grant`）：无障碍、插电常亮
 *      直接写系统设置，点一下全生效，不用人在设置页里翻。
 *   2) 没有：降级成跳转 —— 打开对应设置页，人点完返回，队列自动推进下一步。
 *
 * 「一键关」是同一套流程反着走，并把开的时候改过的值还原（原值存 SharedPreferences）。
 */
object GrantCenter {

    const val PKG = "com.zpq.phoneagent"
    private const val SVC = "com.zpq.phoneagent/com.zpq.phoneagent.AgentService"
    private const val PREFS = "grant_backup"

    const val ON = 0
    const val OFF = 1
    const val UNKNOWN = 2

    data class Item(
        val key: String,
        val title: String,
        val desc: String,
        val state: Int,
        val note: String = ""
    )

    // ---------------------------------------------------------------- 读状态

    fun items(ctx: Context): List<Item> {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val secure = hasSecure(ctx)
        val svc = enabledServices(ctx).contains(SVC)
        val battery = pm.isIgnoringBatteryOptimizations(PKG)
        val notify = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        } else true
        val stayOn = Settings.Global.getInt(ctx.contentResolver, "stay_on_while_plugged_in", 0) != 0
        val timeout = Settings.System.getInt(ctx.contentResolver, "screen_off_timeout", 60000) >= 600000
        // 无障碍没开、又没有直写权：多半就是被 MIUI 判成「未知来源应用」拒了敏感权限
        val svcNote = if (svc || secure) "" else "被系统判为未知来源应用时，UI 开关会被拒"
        return listOf(
            Item("accessibility", "无障碍服务",
                "读屏幕、点按、输入都靠它。关着＝电脑只能看不能动",
                if (svc) ON else OFF, svcNote),
            Item("battery", "电池白名单",
                "熄屏后不被系统冻结，HTTP 端口才不会失联",
                if (battery) ON else OFF),
            Item("notify", "通知权限",
                "前台常驻通知；被系统杀进程时你能第一时间看到",
                if (notify) ON else OFF),
            Item("stayon", "插电常亮",
                "插着线时屏幕不灭，无线调试才不会断线",
                if (stayOn) ON else OFF),
            Item("timeout", "息屏超时 ≥10 分钟",
                "黑屏前留够时间，别刚连上就断",
                if (timeout) ON else OFF),
            Item("autostart", "自启动 / 后台",
                "重启后自己爬起来，不用人再去点图标",
                UNKNOWN, "系统页里才能确认"),
            Item("miui", "未知来源敏感权限",
                "小米把非商店安装的 App 当未知来源，会拒掉上面这些权限",
                if (secure) ON else UNKNOWN,
                if (secure) "已用直写绕过" else "MIUI 专有页")
        )
    }

    fun enabledServices(ctx: Context): List<String> =
        (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .split(':').map { it.trim() }.filter { it.isNotEmpty() }

    /** 有它，无障碍与全局设置可以直写；没有就只能跳页 */
    fun hasSecure(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED

    /** 「去开启」：把人送到对应系统页。小米专有页打不开就落到本应用详情页 */
    fun openOne(a: Activity, key: String): Boolean {
        val i = when (key) {
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "battery" -> Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$PKG")
            )
            "notify" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, PKG)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$PKG"))
            }
            "stayon", "timeout" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            "autostart" -> Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            }
            // MIUI 的权限编辑页；不存在时下面 catch 里回落
            "miui" -> Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", PKG)
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$PKG"))
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            a.startActivity(i)
            true
        } catch (e: Exception) {
            try {
                a.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$PKG"))
                )
                true
            } catch (e2: Exception) {
                false
            }
        }
    }

    // ---------------------------------------------------------------- 一二键流程

    private val queue = mutableListOf<Pair<String, (Activity) -> Boolean>>()
    private var running = false

    fun isRunning() = running

    /** 点按钮：清空队列，从第一步开始 */
    fun start(act: Activity, on: Boolean) {
        if (running) {
            Toast.makeText(act, "正在执行，等这一步回来", Toast.LENGTH_SHORT).show()
            return
        }
        queue.clear()
        queue.addAll(if (on) enableSteps() else disableSteps())
        running = true
        advance(act)
    }

    /** 从设置页 / 权限弹窗回来时调用：继续队列 */
    fun resume(act: Activity) {
        if (running) advance(act)
    }

    private fun advance(act: Activity) {
        while (queue.isNotEmpty()) {
            val (name, step) = queue.removeAt(0)
            val waitUser = try {
                step(act)
            } catch (e: Exception) {
                Toast.makeText(act, "$name 失败：${e.message}", Toast.LENGTH_LONG).show()
                false
            }
            if (waitUser) return
        }
        if (running) {
            running = false
            Toast.makeText(act, "已处理完，可点「重新自检」看结果", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- 开启

    private fun enableSteps(): List<Pair<String, (Activity) -> Boolean>> = listOf(
        "无障碍服务" to { a: Activity ->
            if (hasSecure(a)) {
                setAccessibility(a, true)
                false
            } else {
                jump(a, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        },
        "插电常亮" to { a: Activity ->
            if (hasSecure(a)) {
                val sp = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                if (!sp.contains("stay_on")) {
                    sp.edit().putInt("stay_on", Settings.Global.getInt(
                        a.contentResolver, "stay_on_while_plugged_in", 0)).apply()
                }
                Settings.Global.putInt(a.contentResolver, "stay_on_while_plugged_in", 7)
                false
            } else {
                jump(a, Intent(Settings.ACTION_DISPLAY_SETTINGS))
            }
        },
        "息屏超时" to { a: Activity ->
            val cr = a.contentResolver
            val sp = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!sp.contains("timeout")) {
                sp.edit().putInt("timeout", Settings.System.getInt(cr, "screen_off_timeout", 60000)).apply()
            }
            if (hasSecure(a)) {
                Settings.System.putInt(cr, "screen_off_timeout", Int.MAX_VALUE)
                false
            } else if (Settings.System.canWrite(a)) {
                Settings.System.putInt(cr, "screen_off_timeout", Int.MAX_VALUE)
                false
            } else {
                jump(a, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$PKG")))
            }
        },
        "电池白名单" to { a: Activity ->
            val pm = a.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(PKG)) {
                false
            } else {
                try {
                    jump(a, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$PKG")))
                } catch (e: Exception) {
                    jump(a, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        },
        "通知权限" to { a: Activity ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
                a.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
                true     // 等系统弹窗回来
            } else false
        },
        "自启动（小米）" to { a: Activity ->
            val i = Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            }
            jump(a, i)
        }
    )

    // ---------------------------------------------------------------- 关闭

    private fun disableSteps(): List<Pair<String, (Activity) -> Boolean>> = listOf(
        "无障碍服务" to { a: Activity ->
            if (hasSecure(a)) {
                setAccessibility(a, false)
                false
            } else {
                jump(a, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        },
        "插电常亮" to { a: Activity ->
            if (hasSecure(a)) {
                val sp = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val old = sp.getInt("stay_on", 0)
                Settings.Global.putInt(a.contentResolver, "stay_on_while_plugged_in", old)
                false
            } else {
                jump(a, Intent(Settings.ACTION_DISPLAY_SETTINGS))
            }
        },
        "息屏超时" to { a: Activity ->
            val sp = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val old = sp.getInt("timeout", 60000)
            if (hasSecure(a) || Settings.System.canWrite(a)) {
                Settings.System.putInt(a.contentResolver, "screen_off_timeout", old)
                false
            } else {
                jump(a, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$PKG")))
            }
        },
        "电池白名单" to { a: Activity ->
            jump(a, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        },
        "通知开关" to { a: Activity ->
            jump(a, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$PKG")))
        },
        "自启动（小米）" to { a: Activity ->
            val i = Intent().apply {
                component = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            }
            jump(a, i)
        }
    )

    // ---------------------------------------------------------------- 工具

    /** 跳设置页；能打开就返回 true（需要人点），打不开就 false（跳过） */
    private fun jump(a: Activity, i: Intent): Boolean = try {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        a.startActivity(i)
        true
    } catch (e: Exception) {
        false
    }

    /** 直写无障碍开关：先清掉指向自己但已失效的条目，再按需加回 */
    private fun setAccessibility(a: Activity, on: Boolean) {
        val list = enabledServices(a).toMutableList()
        list.remove(SVC)
        if (on) list.add(SVC)
        Settings.Secure.putString(
            a.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            list.joinToString(":")
        )
        Settings.Secure.putInt(
            a.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            if (list.isEmpty()) 0 else 1
        )
    }
}
