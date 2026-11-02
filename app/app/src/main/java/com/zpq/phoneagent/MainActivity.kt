package com.zpq.phoneagent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 主界面。三件事必须一眼看到：
 *   1) **本机地址**（IP:端口）—— 电脑要拿它连过来，不该让人去设置里翻；
 *   2) 服务是否在跑、界面能不能读；
 *   3) 设备别名 —— 十台同型号手机时靠它区分。
 *
 * 没有用 AndroidX/Compose（本工程刻意零第三方依赖，见 build.gradle 注释），
 * 所以样式全部用 GradientDrawable 在代码里画，颜色集中在本文件顶部的常量里。
 */
class MainActivity : android.app.Activity() {

    // ---- 设计令牌（改这里就能整体换肤）----
    private val cBg = 0xFFF4F6FA.toInt()
    private val cCard = 0xFFFFFFFF.toInt()
    private val cText = 0xFF0F172A.toInt()
    private val cMuted = 0xFF64748B.toInt()
    private val cPrimary = 0xFF2563EB.toInt()
    private val cAccent = 0xFF0EA5E9.toInt()
    private val cOk = 0xFF16A34A.toInt()
    private val cBad = 0xFFDC2626.toInt()
    private val cLine = 0xFFE2E8F0.toInt()

    private lateinit var addrValue: TextView
    private lateinit var dotService: View
    private lateinit var txtService: TextView
    private lateinit var txtTree: TextView
    private lateinit var txtVersion: TextView
    private lateinit var tokenText: TextView
    private lateinit var aliasEdit: EditText
    private lateinit var logText: TextView

    private fun dp(v: Int) = (resources.displayMetrics.density * v).toInt()

    private fun rounded(color: Int, radiusDp: Int, strokeDp: Int = 0, strokeColor: Int = 0) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
            if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
        }

    private fun label(s: String) = TextView(this).apply {
        text = s
        textSize = 12f
        setTextColor(cMuted)
        letterSpacing = 0.04f
    }

    private fun body(s: CharSequence, size: Float = 14f, color: Int = cText, bold: Boolean = false) =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun card(padDp: Int = 18): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(cCard, 20)
        setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
        elevation = dp(2).toFloat()
    }

    private fun action(label: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setTextColor(if (primary) Color.WHITE else cPrimary)
        background = if (primary) rounded(cPrimary, 14)
        else rounded(Color.TRANSPARENT, 14, 1, cPrimary)
        stateListAnimator = null
        minHeight = dp(48)
        setOnClickListener { onClick() }
    }

    private fun gap(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }

        // ---- 头部：图标 + 名字 ----
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(ImageView(this).apply {
            // 用前景层 + 自绘深色圆角底，而不是直接 setImageResource(R.drawable.ic_launcher)：
            // API 26+ 上那个名字解析到 adaptive-icon，塞进 ImageView 渲染不稳（它本来是给桌面用的）。
            setImageResource(R.drawable.ic_launcher_foreground)
            background = rounded(0xFF0F172A.toInt(), 14)
            setPadding(dp(2), dp(2), dp(2), dp(2))
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        })
        val headText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        headText.addView(body("顺手", 24f, cText, true))
        headText.addView(body("手机界面代理", 12f, cMuted))
        head.addView(headText)
        root.addView(head)
        root.addView(gap(20))

        // ---- 地址卡 ----
        val addrCard = card()
        addrCard.addView(label("本机地址"))
        addrValue = body("—", 26f, cPrimary, true).apply {
            setPadding(0, dp(6), 0, dp(2))
            typeface = android.graphics.Typeface.MONOSPACE
        }
        addrCard.addView(addrValue)
        addrCard.addView(body("电脑上跑 ph agent-find 就能自动找到我", 12f, cMuted))
        addrCard.addView(gap(12))
        val copyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        copyRow.addView(action("复制地址", false) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("addr", addrValue.text))
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
        }.apply { layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f) })
        addrCard.addView(copyRow)
        root.addView(addrCard)
        root.addView(gap(14))

        // ---- 状态卡 ----
        val statusCard = card()
        statusCard.addView(label("运行状态"))
        statusCard.addView(gap(8))
        dotService = View(this).apply {
            background = rounded(cMuted, 6)
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(10) }
        }
        txtService = body("检查中…", 14f)
        statusCard.addView(row(dotService, txtService))
        txtTree = body("", 13f, cMuted)
        statusCard.addView(row(null, txtTree))
        txtVersion = body("", 13f, cMuted)
        statusCard.addView(row(null, txtVersion))
        root.addView(statusCard)
        root.addView(gap(14))

        // ---- 别名卡 ----
        val aliasCard = card()
        aliasCard.addView(label("设备别名（多台同型号手机靠它区分）"))
        aliasEdit = EditText(this).apply {
            hint = "例如：客厅机"
            setHintTextColor(0xFF94A3B8.toInt())   // 默认 hint 太淡，浅底上几乎看不见
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            background = rounded(0xFFF1F5F9.toInt(), 12)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        aliasCard.addView(aliasEdit.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        })
        aliasCard.addView(gap(10))
        aliasCard.addView(action("保存别名", false) {
            AgentPrefs.setAlias(this, aliasEdit.text.toString())
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            refresh()
        }.apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)) })
        root.addView(aliasCard)
        root.addView(gap(14))

        // ---- 操作 ----
        root.addView(action("开启无障碍服务", true) {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "打不开设置页：${e.message}", Toast.LENGTH_LONG).show()
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)) })
        root.addView(gap(10))
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(action("生成/清除 token", false) {
            val cur = AgentPrefs.token(this)
            AgentPrefs.setToken(this, if (cur.isEmpty()) System.currentTimeMillis().toString(16) else "")
            refresh()
        }.apply { layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f) })
        row2.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(10), 1) })
        row2.addView(action("重新自检", false) { refresh() }
            .apply { layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f) })
        root.addView(row2)
        root.addView(gap(14))

        // ---- token ----
        val tokenCard = card()
        tokenCard.addView(label("访问 token"))
        tokenText = body("", 13f, cText).apply {
            setPadding(0, dp(6), 0, 0)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        tokenCard.addView(tokenText)
        root.addView(tokenCard)
        root.addView(gap(14))

        // ---- 日志 ----
        val logCard = card()
        logCard.addView(label("最近调用"))
        logText = body("", 11f, cMuted).apply {
            setPadding(0, dp(8), 0, 0)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        logCard.addView(logText)
        root.addView(logCard)

        val scroll = ScrollView(this).apply { setBackgroundColor(cBg) }
        scroll.addView(root)
        setContentView(scroll)
        refresh()
    }

    private fun row(dot: View?, text: TextView): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (dot != null) addView(dot)
            addView(text)
            setPadding(0, dp(2), 0, dp(2))
        }

    private fun refresh() {
        val ip = AgentService.localIp()
        addrValue.text = if (ip.isEmpty()) "未连 WiFi" else "$ip:${AgentService.PORT}"

        val running = AgentService.isRunning()
        dotService.background = rounded(if (running) cOk else cBad, 6)
        txtService.text = if (running) "服务运行中" else "服务未运行 —— 点下面「开启无障碍服务」"
        txtService.setTextColor(if (running) cText else cBad)

        val svc = AgentService.instance
        val root = try { svc?.rootInActiveWindow } catch (e: Exception) { null }
        // 用实时前台（应用窗口），不用 lastPkg —— lastPkg 会被输入法/系统窗口覆盖
        val now = try { svc?.foregroundNow() } catch (e: Exception) { null } ?: AgentService.lastPkg
        txtTree.text = "界面 " + (if (root == null) "读不到" else "可读") + " · " + now.ifEmpty { "—" }
        txtVersion.text = "别名 " + AgentPrefs.displayAlias(this) +
                " · epoch " + UiTree.epoch.get()

        if (::aliasEdit.isInitialized && aliasEdit.text.isNullOrEmpty()) {
            aliasEdit.setText(AgentPrefs.alias(this))
        }
        val t = AgentPrefs.token(this)
        tokenText.text = if (t.isEmpty()) "未设置（局域网内不鉴权，建议现在生成）" else t

        val tail = AgentLog.tail(this, 12).trim()
        logText.text = tail.ifEmpty { "（还没有调用）" }
    }

    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (::addrValue.isInitialized) refresh()
            ticker.postDelayed(this, 2000)
        }
    }

    override fun onResume() {
        super.onResume()
        // 从无障碍设置页回来时刷新一下，别让用户以为没生效
        if (::addrValue.isInitialized) refresh()
        ticker.removeCallbacks(tick)
        ticker.postDelayed(tick, 2000)   // 之后每 2 秒自刷：epoch/前台 不再是打开那一刻的快照
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tick)     // 退到后台就停，别白耗电
    }

    override fun onDestroy() {
        super.onDestroy()
        // 主 Activity 退出不杀服务：服务由系统按无障碍开关生命周期管理
        finish()
    }
}
