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
    private lateinit var grantItems: LinearLayout
    private var grantSig = ""
    private lateinit var tabBtns: List<Button>
    private lateinit var tabPages: List<LinearLayout>
    private var curTab = 0

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

        // ---- 分类页：概览 / 权限 / 设置 ----
        // 一页只装一块，别让人一路往下滚。切换只改可见性，不重建视图。
        val mkPage = { LinearLayout(this).apply { orientation = LinearLayout.VERTICAL } }
        val page1 = mkPage()
        val page2 = mkPage()
        val page3 = mkPage()
        tabPages = listOf(page1, page2, page3)

        val tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabBtns = listOf("概览", "权限", "设置").mapIndexed { i, t ->
            Button(this).apply {
                text = t
                isAllCaps = false
                textSize = 14f
                stateListAnimator = null
                minHeight = dp(40)
                setOnClickListener { selectTab(i) }
                layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                    if (i > 0) leftMargin = dp(8)
                }
            }
        }
        tabBtns.forEach { tabBar.addView(it) }
        root.addView(tabBar)
        root.addView(gap(14))
        root.addView(page1)
        root.addView(page2)
        root.addView(page3)

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
        page1.addView(addrCard)
        page1.addView(gap(14))

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
        page1.addView(statusCard)
        page1.addView(gap(14))

        // ---- 权限卡：一键开 / 一键关 ----
        // 顺序是刻意的：先直写能写的（有无 WRITE_SECURE_SETTINGS 决定），
        // 再弹需要人确认的（电池、通知），最后跳小米的自启动页。
        val grantCard = card()
        grantCard.addView(label("权限 · 顺手要能干活，得先放开这些"))
        grantItems = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        grantCard.addView(grantItems.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        })
        grantCard.addView(gap(12))
        val grantBtnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        grantBtnRow.addView(action("一键开启全部", true) { GrantCenter.start(this, true) }
            .apply { layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f) })
        grantBtnRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(10), 1) })
        grantBtnRow.addView(action("一键关闭全部", false) { GrantCenter.start(this, false) }
            .apply { layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f) })
        grantCard.addView(grantBtnRow)
        page2.addView(grantCard)

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
        page3.addView(aliasCard)
        page3.addView(gap(14))

        // ---- 操作 ----
        page1.addView(action("开启无障碍服务", true) {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "打不开设置页：${e.message}", Toast.LENGTH_LONG).show()
            }
        }.apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)) })
        page1.addView(gap(10))
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(action("生成/清除 token", false) {
            val cur = AgentPrefs.token(this)
            AgentPrefs.setToken(this, if (cur.isEmpty()) System.currentTimeMillis().toString(16) else "")
            refresh()
        }.apply { layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f) })
        row2.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(10), 1) })
        row2.addView(action("重新自检", false) { refresh() }
            .apply { layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f) })
        page1.addView(row2)
        page1.addView(gap(14))

        // ---- token ----
        val tokenCard = card()
        tokenCard.addView(label("访问 token"))
        tokenText = body("", 13f, cText).apply {
            setPadding(0, dp(6), 0, 0)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        tokenCard.addView(tokenText)
        page3.addView(tokenCard)
        page3.addView(gap(14))

        // ---- 日志 ----
        val logCard = card()
        logCard.addView(label("最近调用"))
        logText = body("", 11f, cMuted).apply {
            setPadding(0, dp(8), 0, 0)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        logCard.addView(logText)
        page3.addView(logCard)

        val scroll = ScrollView(this).apply { setBackgroundColor(cBg) }
        scroll.addView(root)
        setContentView(scroll)
        selectTab(0)
        refresh()
    }

    /** 切页：只改可见性与按钮样式，不重建视图（否则滚动位置和输入焦点都会丢） */
    private fun selectTab(i: Int) {
        curTab = i
        tabPages.forEachIndexed { idx, p ->
            p.visibility = if (idx == i) View.VISIBLE else View.GONE
        }
        tabBtns.forEachIndexed { idx, b ->
            val on = idx == i
            b.background = if (on) rounded(cPrimary, 12) else rounded(0xFFEFF3F8.toInt(), 12)
            b.setTextColor(if (on) Color.WHITE else cMuted)
        }
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

        if (::grantItems.isInitialized) {
            val items = GrantCenter.items(this)
            val sig = items.joinToString("|") { "${it.key}:${it.state}" } +
                    "|" + GrantCenter.hasSecure(this)
            // 只在状态变化时重建：两秒一次的刷新如果每次都重建，
            // 正好点在「去开启」上会被换掉，表现为"点了没反应"
            if (sig != grantSig) {
                grantSig = sig
                grantItems.removeAllViews()
                for (it in items) {
                    val (mark, color) = when (it.state) {
                        GrantCenter.ON -> "已开" to cOk
                        GrantCenter.OFF -> "未开" to cBad
                        else -> "需手动" to cMuted
                    }
                    grantItems.addView(grantRow(it, mark, color))
                }
                if (!GrantCenter.hasSecure(this)) {
                    grantItems.addView(gap(8))
                    grantItems.addView(body(
                        "没拿到 WRITE_SECURE_SETTINGS：上面几项只能跳设置页手点。" +
                                "电脑上跑一次 adb pm grant 之后，「一键开启全部」就能直接生效。",
                        11f, cMuted
                    ))
                }
            }
        }
    }

    /** 权限清单一行：左边标题+状态+为什么需要，右边「去开启」直达系统页 */
    private fun grantRow(item: GrantCenter.Item, mark: String, color: Int): LinearLayout {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        left.addView(body("${item.title}  $mark", 13f, color, true))
        val desc = item.desc + if (item.note.isEmpty()) "" else "（${item.note}）"
        left.addView(body(desc, 11f, cMuted))
        line.addView(left)
        if (item.state != GrantCenter.ON) {
            line.addView(body("去开启 ›", 12f, cPrimary).apply {
                setPadding(dp(10), dp(8), dp(2), dp(8))
                setOnClickListener {
                    if (!GrantCenter.openOne(this@MainActivity, item.key)) {
                        Toast.makeText(this@MainActivity, "这个页面打不开，去应用详情里找",
                            Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }
        return line
    }

    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (::addrValue.isInitialized) refresh()
            ticker.postDelayed(this, 2000)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 通知权限弹窗回来：让权限队列继续往下走
        GrantCenter.resume(this)
    }

    override fun onResume() {
        super.onResume()
        // 从无障碍设置页回来时刷新一下，别让用户以为没生效
        if (::addrValue.isInitialized) refresh()
        // 一键授权是多步的：从任何一个设置页/弹窗回来都要接着推进
        GrantCenter.resume(this)
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
