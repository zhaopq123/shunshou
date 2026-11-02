package com.zpq.phoneagent

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicLong

/**
 * 把 AccessibilityNodeInfo 树序列化成**与 uiautomator dump 同 schema 的 XML**。
 *
 * 为什么必须同 schema：phone.py 的整条上层链（nodes()/fmt()/select_nodes()/act()/BatchRunner
 * 和 37 个业务脚本）吃的就是这个 XML。字段名或 bounds 格式一变，上层就得重写——
 * 那是本项目最关键的一条约束（见 PLAN.md 阶段 1）。
 *
 * ── 2026-11-02 v3：两条被实测打出来的契约漏洞（不是猜的，是 ui_diff.py 逐节点比出来的）
 *   ① 只读 rootInActiveWindow ⇒ 状态栏/导航栏/别的包的弹窗/输入法窗口全看不见。
 *      实测同一瞬间 `ph xml` 44 节点 3 扇窗（com.zpq.phoneagent + com.miui.home 导航条 +
 *      com.android.systemui 状态栏），我这边只有 1 扇窗 11 节点。稳定漏读 6 个候选节点，
 *      全是状态栏（时间、信号、电量、通知）。这个洞会让"系统授权弹窗"这类**跨包窗口**
 *      直接被当成不存在——比"少个时钟"严重得多。现改成遍历 windows 森林。
 *   ② text 里的换行被我 esc() 换成了空格 ⇒ 多行节点的文字对不上，`ph tap-text` 按整段
 *      文字匹配会 NOT_FOUND。uiautomator 用的是 `&#10;`（实测 ref 里 3 处）。已对齐。
 *   顺带把属性名对齐：`visible` → `visible-to-user`，补 `hint` / `drawing-order` / `display-id`，
 *   `<hierarchy>` 上不再挂自造的 `pkg`（uiautomator 没有这个属性）。
 *
 * ── 2026-11-02 v4：补上 NAF（第 3 个契约破洞，ui_diff 的 schema 断言就卡这一个字段）。
 *   规则见 nafOf() 的注释：不是照文档抄的，是从 4 份参照 dump 里 21 个 NAF 节点反推并全量回验的。
 *   同时纠正交接文档里"只标叶子节点"的说法 —— 那条只中 4/9。
 *
 * ── 2026-11-02 v5：修第 4 个破洞 —— **不可见节点**（A6 断言在小米内容中心页被打红后定位的）。
 *   实测同一屏（com.miui.newhome）三方对照：
 *     agent 默认(flag 关)   87 节点 / 可见 57 / 候选 49  ← 容器被框架"扁平化"掉了
 *     agent full(flag 开)  209 节点 / 可见 144 / 候选 51
 *     u2 参照              144 节点 / 可见 144 / 候选 37
 *   ⇒ u2 的真身 = **flag 开 + 只保留 visible-to-user=true**（144 == 144，逐节点只差新闻滚动的 2 条时效文本）。
 *   之前"默认关 flag 才与 u2 对齐"的判断是错的：那只在桌面这种容器本来就不重要的页面上碰巧成立。
 *   所以现在：flag 常驻开；默认 /ui 过滤不可见节点（等价 u2）；?full=1 额外保留不可见节点（离屏/隐藏内容）。
 */
object UiTree {

    /** 窗口纪元：任何内容/窗口变化都 +1。带旧 epoch 的动作一律被拒绝执行。 */
    val epoch = AtomicLong(1L)

    fun bump() {
        epoch.incrementAndGet()
    }

    /** 一扇窗：root 节点 + 它所在的显示器 id（多屏暂只按单屏处理，见 COMPATIBILITY.md） */
    class WinRoot(val node: AccessibilityNodeInfo, val displayId: Int = 0)

    /** 一个节点在序列化时的可追溯信息，供 provenance 与合法性判定用。 */
    data class Meta(
        val selfText: String,
        val desc: String,
        val id: String,
        val clickable: Boolean,
        val longClickable: Boolean,
        val scrollable: Boolean,
        val editable: Boolean,
        val checkable: Boolean,
        val checked: Boolean,
        val enabled: Boolean,
        val visible: Boolean,
        val className: String,
        val textFromSubtree: String,   // 自身或子树里的可见文字（实测：可点项常是容器、文字在子节点）
        val depth: Int,
        val hint: String,
    )

    /** 序列化时跨窗口延续的计数器：[0]=同一父下的 index，[1]=全局 drawing-order */
    private class Counters {
        var index = 0
        var drawingOrder = 0
    }

    /**
     * 入口（多窗版）：返回完整 XML 字符串；森林为空时返回 null
     * （调用方必须显式区分"读不到"与"空页面"，见 PROTOCOL.md 的 SECURE_WINDOW / NO_ROOT）。
     *
     * includeInvisible = false（默认）：丢掉 visible-to-user=false 的节点，等价 uiautomator/u2 的树。
     * includeInvisible = true：连不可见节点一起吐（离屏列表项、隐藏容器），给 /ui?full=1 用。
     */
    fun toXml(roots: List<WinRoot>, includeInvisible: Boolean = false): String? {
        if (roots.isEmpty()) return null
        val sb = StringBuilder()
        sb.append("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>")
        sb.append("<hierarchy rotation=\"0\">")
        val c = Counters()
        for (w in roots) {
            c.index = 0
            walk(w.node, sb, 0, c, w.displayId, includeInvisible)
        }
        sb.append("</hierarchy>")
        return sb.toString()
    }

    /** 兼容旧签名：只喂活动窗口时等价于单窗森林（selftest 等旁路用） */
    fun toXml(root: AccessibilityNodeInfo?, pkgHint: String): String? =
        if (root == null) null else toXml(listOf(WinRoot(root)))

    /**
     * 与 toXml **完全同一套遍历顺序**的节点序列。
     * `/tap` 按序号寻址必须走这里，否则 XML 里的第 N 个节点和动作打到的第 N 个节点
     * 不是同一个（上一版 /ui 是单窗、nthNode 也是单窗，改成森林后必须一起改）。
     *
     * 过滤条件必须与默认 /ui 逐字一致（含"丢不可见"）—— 否则序号整体错位，
     * 就是本项目最怕的"静默点错"。要全量序列就显式传 includeInvisible = true。
     */
    fun sequence(roots: List<WinRoot>, includeInvisible: Boolean = false): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        for (w in roots) pre(w.node, out, includeInvisible)
        return out
    }

    /** ROM 偶尔在节点回收后抛异常，读不到可见性时按"可见"处理（宁可多吐不可漏吐） */
    private fun visible(n: AccessibilityNodeInfo): Boolean =
        try { n.isVisibleToUser } catch (e: Exception) { true }

    private fun pre(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, includeInvisible: Boolean) {
        if (n == null) return
        if (!includeInvisible && !visible(n)) return
        out.add(n)
        for (i in 0 until n.childCount) pre(n.getChild(i), out, includeInvisible)
    }

    /** index = 同一父下的序号；drawing-order = 整棵树的全局前序序号（与 uiautomator 一致） */
    private fun walk(n: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int, c: Counters,
                     displayId: Int, includeInvisible: Boolean) {
        if (n == null) return
        // 不可见节点整棵子树一起丢：u2 的树里 visible-to-user=false 的节点数为 0（4 份 dump 实测）
        if (!includeInvisible && !visible(n)) return
        val m = meta(n, depth)
        val r = Rect()
        n.getBoundsInScreen(r)

        // NAF 必须是第一个属性：参照树里就是 `NAF="true" index=...` 这个顺序
        sb.append("<node ")
        if (nafOf(n, m)) sb.append("NAF=\"true\" ")
        sb.append("index=\"").append(c.index++).append("\" ")
        val myOrder = c.drawingOrder++
        attrs(sb, m, r, n, displayId, myOrder)
        val kids = n.childCount
        if (kids > 0) {
            sb.append(">")
            val saved = c.index
            c.index = 0
            for (i in 0 until kids) {
                walk(n.getChild(i), sb, depth + 1, c, displayId, includeInvisible)
            }
            c.index = saved
            sb.append("</node>")
        } else {
            sb.append("/>")
        }
    }

    private fun attrs(sb: StringBuilder, m: Meta, r: Rect, n: AccessibilityNodeInfo,
                      displayId: Int, drawingOrder: Int) {
        fun b(name: String, v: Boolean) {
            sb.append(name).append("=\"").append(if (v) "true" else "false").append("\" ")
        }
        fun s(name: String, v: String?) {
            sb.append(name).append("=\"").append(esc(v ?: "")).append("\" ")
        }
        s("text", m.selfText)
        s("resource-id", m.id)
        s("class", m.className)
        s("package", n.packageName?.toString() ?: "")
        s("content-desc", m.desc)
        b("checkable", m.checkable)
        b("checked", m.checked)
        b("clickable", m.clickable)
        b("enabled", m.enabled)
        b("focusable", n.isFocusable)
        b("focused", n.isFocused)
        b("scrollable", m.scrollable)
        b("long-clickable", m.longClickable)
        b("password", n.isPassword)
        b("selected", n.isSelected)
        b("visible-to-user", m.visible)
        // bounds 必须是 [x1,y1][x2,y2]，phone.py 的 center() 正则按这个格式解析
        sb.append("bounds=\"[").append(r.left).append(",").append(r.top)
            .append("][").append(r.right).append(",").append(r.bottom).append("]\" ")
        sb.append("drawing-order=\"").append(drawingOrder).append("\" ")
        s("hint", m.hint)
        sb.append("display-id=\"").append(displayId).append("\"")
    }

    private fun hintOf(n: AccessibilityNodeInfo): String =
        if (Build.VERSION.SDK_INT >= 26) (n.hintText?.toString()?.trim() ?: "") else ""

    /**
     * NAF = "Not Accessibility Friendly"（uiautomator 的标注，不是我们发明的字段）。
     *
     * 规则是从 4 份真实参照 dump 反推 + 逐节点回验出来的，不是照文档抄的：
     *   条件 = clickable=true ∧ text 空 ∧ content-desc 空 ∧ **整棵子树里也没有任何 text/desc**
     * 回验（2026-11-02，21 个 NAF 节点全中、0 误报）：
     *   xml_douyin.xml 344 节点/9 NAF → 9/9；u2_dump.xml 156/4 → 4/4；
     *   sys_dump.xml 130/4 → 4/4；diff_ref.xml 156/4 → 4/4。
     *
     * 注意：交接文档里那条"叶子节点"判据是**错的**（只中 4/9，抖音那棵树漏 5 个）——
     * 漏掉的是"可点容器、自身无字、子节点也全是无字图标"这一类，正是 NAF 想标记的典型。
     * 改成"子树无字"后两份大样本全中。样本仍只有 21 个，别声称对所有 App 100% 通用，
     * 这是 best-effort 标注，不参与任何动作决策（phone.py 不读它）。
     *
     * 只对"可点 + 自身无字"的少数节点做子树扫描（每棵树个位数），所以开销可忽略；
     * guard 防 ROM 给出环状/超深子树时打爆主线程。
     */
    private fun nafOf(n: AccessibilityNodeInfo, m: Meta): Boolean =
        m.clickable && m.selfText.isEmpty() && m.desc.isEmpty() && !hasLabelInSubtree(n)

    private fun hasLabelInSubtree(n: AccessibilityNodeInfo): Boolean {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it) }
        var guard = 0
        while (stack.isNotEmpty() && guard < 256) {
            val c = stack.removeFirst()
            guard++
            if (!c.text.isNullOrBlank() || !c.contentDescription.isNullOrBlank()) return true
            for (i in 0 until c.childCount) c.getChild(i)?.let { stack.addLast(it) }
        }
        return false
    }

    private fun meta(n: AccessibilityNodeInfo, depth: Int): Meta {
        val selfText = n.text?.toString()?.trim() ?: ""
        val desc = n.contentDescription?.toString()?.trim() ?: ""
        val editable = n.isEditable || n.className?.toString()?.let {
            it.contains("EditText", true)
        } == true
        // 子树文字：实测闲鱼/抖音的可点项 71%/60% 是"容器可点、文字在子节点"，
        // 只取自身文字会把大量正常按钮误判成无文字（E6 第一版就栽在这儿）。
        var sub = selfText
        if (sub.isEmpty()) {
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it) }
            var guard = 0
            while (stack.isNotEmpty() && guard < 64) {
                val c = stack.removeFirst()
                guard++
                val t = c.text?.toString()?.trim()
                if (!t.isNullOrEmpty()) {
                    sub = t
                    break
                }
                for (i in 0 until c.childCount) c.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return Meta(
            selfText = selfText,
            desc = desc,
            id = n.viewIdResourceName ?: "",
            clickable = n.isClickable,
            longClickable = n.isLongClickable,
            scrollable = n.isScrollable,
            editable = editable,
            checkable = n.isCheckable,
            checked = n.isChecked,
            enabled = n.isEnabled,
            visible = n.isVisibleToUser,
            className = n.className?.toString() ?: "",
            textFromSubtree = sub,
            depth = depth,
            hint = hintOf(n),
        )
    }

    /**
     * 属性值转义。规则来自实测：uiautomator 把 text 里的换行写成 `&#10;`（不是空格）。
     * 上一版把它替换成空格 ⇒ 多行节点（日志、简介、评论）文字对不上，按整段文字找不到。
     * 注意不能直接吐裸换行：XML 属性值里的裸换行会被解析器**规范化成空格**，等于白写。
     */
    private fun esc(v: String): String {
        val sb = StringBuilder(v.length + 8)
        for (c in v) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                '\n' -> sb.append("&#10;")
                '\t' -> sb.append("&#9;")
                '\r' -> { /* 丢掉：CRLF 只保留一个换行，与 ref 观测一致 */ }
                else -> if (c.code < 0x20) sb.append(' ') else sb.append(c)
            }
        }
        return sb.toString()
    }
}
