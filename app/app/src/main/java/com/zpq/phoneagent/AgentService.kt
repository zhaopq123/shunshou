package com.zpq.phoneagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 端侧执行器：一个类同时是三样东西
 *   1) AccessibilityService  —— 读节点树 / 执行动作（也是风控能看到的信号来源，已知边界）
 *   2) 前台服务              —— 常驻不被杀
 *   3) 局域网 HTTP 服务端    —— 不依赖第三方库（ServerSocket + 内置 org.json）
 *
 * 关键设计（都是实测逼出来的，见 PLAN.md）：
 *   · /ui 输出与 uiautomator **同 schema** 的 XML，这样 phone.py 上层零改动
 *   · 坐标一律是**显示像素**，与 dispatchGesture 同一坐标空间；截图帧若缩放，
 *     换算只准在 PC 侧一个函数里做（PROTOCOL.md 第 4 节）
 *   · epoch：窗口/内容变化即递增；带旧 epoch 的动作**拒绝执行**（STALE_TREE），
 *     绝不点旧坐标 —— 保证"零次静默点错"，不保证"百分百点准"
 *   · 三段式点击：performAction → 回读 → 降级 gesture → 回读 → TAP_UNCONFIRMED
 *     写动作**没有回读确认就不算成功**
 */
class AgentService : AccessibilityService() {

    companion object {
        const val PORT = 8712
        private const val CHANNEL = "agent_fg"
        @Volatile var instance: AgentService? = null
            private set

        /** 前台包名：由事件流维护，不再像 adb 路径那样每次起一条 shell 去查 */
        @Volatile var lastPkg: String = ""
        @Volatile var lastActivity: String = ""

        fun isRunning() = instance != null

        /** 本机 WLAN 的 IPv4。手机自己要能把地址显示出来——否则"手机 IP 是多少"就得去设置里翻。
         *  放 companion 里是因为 MainActivity 也要用（实例方法没法用类名调）。 */
        fun localIp(): String = try {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress ?: ""
        } catch (e: Exception) { "" }
    }

    private val serverTag = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private val pool = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val reqId = AtomicInteger(0)
    @Volatile private var lastEventAt = 0L

    // ---------------------------------------------------------------- 生命周期

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            // FLAG_INCLUDE_NOT_IMPORTANT_VIEWS 必须常驻开：关掉时框架会把 App 标成"无障碍不重要"的
            // 中间容器**扁平化**掉（小米内容中心实测 87 节点 vs 开着的 209 节点），
            // 而 u2/uiautomator 的树是带容器的（144）。默认口径要等价 u2，就得开着这个 flag，
            // 再靠 UiTree 的 visible-to-user 过滤把不可见节点丢掉。见 UiTree 头部 v5 注释。
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 80
        }
        becomeForeground()
        // 主动读一次当前窗口：lastPkg 若只靠窗口变化事件维护，服务启动时屏幕静止 ⇒ 一直是空，
        // 会让 /selftest 的 foreground_known 误报（上一版实测就是这样）
        try {
            rootInActiveWindow?.packageName?.toString()?.let { if (it.isNotEmpty()) lastPkg = it }
        } catch (_: Exception) {
        }
        startHttp()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                val pkg = event.packageName?.toString() ?: ""
                if (pkg.isNotEmpty() && pkg != lastPkg) {
                    lastPkg = pkg
                    lastActivity = event.className?.toString() ?: lastActivity
                }
                UiTree.bump()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> UiTree.bump()
            else -> {}
        }
        lastEventAt = System.currentTimeMillis()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        serverTag.set(false)
        try { server?.close() } catch (_: Exception) {}
        pool.shutdownNow()
        instance = null
        super.onDestroy()
    }

    private fun becomeForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.agent_channel),
                    NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("端口 $PORT · 前台 $lastPkg")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("端口 $PORT")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .build()
        }
        try {
            // SPECIAL_USE 常量在 ServiceInfo 上（不是 Service），且只有 Android 14+ 才是有效的前台服务类型
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1, n)
            }
        } catch (e: Exception) {
            // 不静默：前台服务起不来 = 一定会被 ROM 杀，这条必须让 /selftest 报出来
            fgError = e.message ?: "startForeground failed"
        }
    }

    @Volatile var fgError: String? = null

    // ---------------------------------------------------------------- HTTP

    private fun startHttp() {
        if (!serverTag.compareAndSet(false, true)) return
        pool.execute {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(PORT))
                server = s
                while (serverTag.get()) {
                    val sock = try { s.accept() } catch (e: Exception) { break }
                    try { handle(sock) } catch (_: Exception) {} finally {
                        try { sock.close() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                httpError = e.message ?: "server failed"
            }
        }
    }

    @Volatile var httpError: String? = null

    private fun handle(sock: Socket) {
        sock.soTimeout = 20000
        val ins: InputStream = sock.getInputStream()
        val reader = BufferedReader(ins.reader())
        val reqLine = reader.readLine() ?: return
        val parts = reqLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val pathQ = parts[1]
        val path = pathQ.substringBefore("?")
        val headers = HashMap<String, String>()
        while (true) {
            val ln = reader.readLine() ?: break
            if (ln.isEmpty()) break
            val i = ln.indexOf(':')
            if (i > 0) headers[ln.substring(0, i).trim().lowercase()] = ln.substring(i + 1).trim()
        }
        val token = headers["x-agent-token"] ?: headers["authorization"]?.removePrefix("Bearer ")
        val needAuth = AgentPrefs.token(this).isNotEmpty()
        if (needAuth && token != AgentPrefs.token(this)) {
            write(sock, 401, "application/json", json("error" to "BAD_TOKEN").toString())
            return
        }
        var body = ""
        val cl = headers["content-length"]?.toIntOrNull() ?: 0
        if (method == "POST" && cl > 0) {
            val buf = CharArray(cl)
            var got = 0
            while (got < cl) {
                val r = reader.read(buf, got, cl - got)
                if (r < 0) break
                got += r
            }
            body = String(buf, 0, got)
        }
        val t0 = System.currentTimeMillis()
        val resp = route(method, path, body, pathQ.substringAfter("?", ""))
        // 只有 OK 算 200；FRAME_FAILED / NO_ROOT / UNSUPPORTED 这些都必须是非 2xx，
        // 否则客户端会把错误 JSON 当图片存下来（第一版就这样把 96 字节的错误存成了 .png）
        write(sock, if (resp.code == "OK") 200 else 400, resp.type, resp.text,
              resp.bytes, resp.extra)
        resp.log(reqId.incrementAndGet(), method, path, System.currentTimeMillis() - t0)
    }

    private data class Resp(val code: String, val type: String, val text: String,
                            val note: String = "", val bytes: ByteArray? = null,
                            val extra: Map<String, String> = emptyMap())
    private fun Resp.log(id: Int, m: String, p: String, ms: Long) {
        AgentLog.line(this@AgentService, "#$id $m $p -> $code ${ms}ms $note")
    }

    private fun write(sock: Socket, status: Int, type: String, text: String,
                      bytesIn: ByteArray? = null, extra: Map<String, String> = emptyMap()) {
        val bytes = bytesIn ?: text.toByteArray()
        val out: OutputStream = sock.getOutputStream()
        val head = StringBuilder("HTTP/1.1 $status OK\r\n")
            .append("Content-Type: $type")
            .append(if (bytesIn == null) "; charset=utf-8\r\n" else "\r\n")
            .append("Content-Length: ${bytes.size}\r\n")
            .append("X-Epoch: ").append(UiTree.epoch.get()).append("\r\n")
            .append("X-Pkg: ").append(lastPkg).append("\r\n")
        for ((k, v) in extra) head.append(k).append(": ").append(v).append("\r\n")
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray())
        out.write(bytes)
        out.flush()
    }

    // ---------------------------------------------------------------- 路由

    private fun route(method: String, path: String, body: String, query: String = ""): Resp {
        return when {
            method == "GET" && path == "/ui" -> getUi(query.contains("full=1"))
            method == "GET" && path == "/frame" -> getFrame(query)
            method == "GET" && path == "/fingerprint" -> getFingerprint()
            method == "GET" && path == "/caps" -> jsonResp(json(*capsPairs()))
            method == "GET" && path == "/state" -> jsonResp(json(
                // pkg 必须是"应用窗口"的包，不是输入法/状态栏的包 —— 见 appWindow() 注释
                "pkg" to (onMain(3000) { foregroundPkg() } as? String ?: lastPkg),
                "activity" to lastActivity,
                "epoch" to UiTree.epoch.get(), "last_event_ms" to lastEventAt,
                "screen" to uiSizeJson()))
            method == "GET" && path == "/selftest" -> jsonResp(selftestJson())
            method == "POST" && path == "/act" -> postAct(body)
            method == "POST" && path == "/tap" -> postTap(body)
            method == "POST" && path == "/swipe" -> postSwipe(body)
            method == "POST" && path == "/key" -> postKey(body)
            method == "POST" && path == "/text" -> postText(body)
            method == "POST" && path == "/alias" -> postAlias(body)
            else -> Resp("ERR", "application/json", json("error" to "NO_ROUTE", "path" to path).toString())
        }
    }

    /**
     * 读屏。内容口径（2026-11-02 v5 用三方实测重新定的，上一版的口径是错的）：
     *   默认 = flag 开（含非重要容器）+ **丢掉 visible-to-user=false** ⇒ 等价 u2/`ph xml` 的树。
     *     实测小米内容中心页：agent 144 节点 / u2 144 节点，逐节点只差新闻滚动的 2 条时效文本。
     *   ?full=1 = 在上面基础上**再保留不可见节点**（离屏列表项、隐藏容器），实测 209 节点。
     *     给剪映/抖音那类自绘界面兜底；尾注 `toggled=N` 自证 flag 状态，N=0 说明这台 ROM
     *     忽略了 FLAG_INCLUDE_NOT_IMPORTANT_VIEWS（此时默认树会是"扁平化"的 87 节点，
     *     必须在 COMPATIBILITY.md 记为该机能力缺失，不能假装拿到了 u2 等价树）。
     * 为什么默认要过滤不可见：16 个待迁移业务脚本全按 u2 那棵树调过，`ph tap <序号>` 的序号语义
     * 必须一致；多吐 65 个不可见节点会让序号整体错位。
     */
    private fun getUi(full: Boolean): Resp {
        val xml = onMain(12000) {
            // flag 常驻开、用完**不**恢复（v5）：见 onServiceConnected 里的注释
            val toggled = setImportantFlag(true) || flagOn()
            val forest = forestOnMain()
            if (forest.isEmpty()) return@onMain null
            val body = UiTree.toXml(forest, includeInvisible = full) ?: return@onMain null
            if (full) body + "<!-- full=1 toggled=" + (if (toggled) 1 else 0) + " -->" else body
        }
        if (xml !is String) return Resp(
            "NO_ROOT", "application/json",
            json("error" to "NO_ROOT",
                 "hint" to "无障碍未连接或当前窗口不可读（安全窗口返回 null，不是空页面）").toString())
        return Resp("OK", "text/xml", xml)
    }

    // ---------------------------------------------------------------- 视觉与指纹（M2）

    /**
     * 截图走无障碍服务自带的 `takeScreenshot`（API 30+），**不需要 MediaProjection**，
     * 也就不需要每次弹"开始录制/共享屏幕"的同意框，更不需要 adb。
     * M2 原计划写的是 MediaProjection，那条路每次会话都要人点一次同意，成本高得多。
     */
    private fun getFrame(query: String): Resp {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return Resp("UNSUPPORTED", "application/json",
                json("error" to "UNSUPPORTED", "api" to Build.VERSION.SDK_INT,
                     "hint" to "takeScreenshot 需要 API 30+；低版本继续用 adb screencap").toString())
        }
        val scale = Regex("(?:^|&)scale=([0-9.]+)").find(query)?.groupValues?.get(1)
            ?.toFloatOrNull()?.coerceIn(0.2f, 1f) ?: 1f
        val latch = java.util.concurrent.CountDownLatch(1)
        var png: ByteArray? = null
        var err = ""
        var iw = 0
        var ih = 0
        val t0 = System.currentTimeMillis()
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        try {
                            val hb = result.hardwareBuffer
                            val hard = android.graphics.Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                            val soft = hard?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                            hb.close()
                            if (soft != null) {
                                val out = if (scale < 0.999f)
                                    android.graphics.Bitmap.createScaledBitmap(
                                        soft, (soft.width * scale).toInt().coerceAtLeast(1),
                                        (soft.height * scale).toInt().coerceAtLeast(1), true)
                                else soft
                                iw = out.width
                                ih = out.height
                                val bos = java.io.ByteArrayOutputStream(256 * 1024)
                                out.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos)
                                png = bos.toByteArray()
                            }
                        } catch (e: Exception) {
                            err = "${e.javaClass.simpleName}: ${e.message}"
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        err = "ERR_$errorCode"
                        latch.countDown()
                    }
                })
        } catch (e: Exception) {
            return Resp("FRAME_FAILED", "application/json",
                json("error" to "FRAME_FAILED", "detail" to (e.message ?: "")).toString())
        }
        if (!latch.await(8, java.util.concurrent.TimeUnit.SECONDS)) err = "TIMEOUT"
        val bytes = png
        if (bytes == null) {
            return Resp("FRAME_FAILED", "application/json",
                json("error" to "FRAME_FAILED", "detail" to err,
                     "hint" to "FLAG_SECURE 页面（支付/密码）会被系统拒绝；这是能力边界，不是 bug").toString())
        }
        val (uw, uh) = uiSize()
        return Resp("OK", "image/png", "",
            note = "${bytes.size}B ${iw}x$ih scale=$scale ${System.currentTimeMillis() - t0}ms",
            bytes = bytes,
            extra = mapOf("X-Ui-W" to "$uw", "X-Ui-H" to "$uh",
                          "X-Img-W" to "$iw", "X-Img-H" to "$ih", "X-Scale" to "$scale"))
    }

    /**
     * 页面指纹：把可见树的 (class|id|text|desc|bounds) 串起来取 MD5。
     * 用途：`wait` 不必每 0.5s 拉一整棵树，比 32 字节就知道页面变没变。
     * 口径必须与 `/ui` 完全一致（同一套可见性过滤），否则"页面变了"和"树变了"对不上。
     */
    private fun getFingerprint(): Resp {
        val r = onMain(8000) {
            val forest = forestOnMain()
            if (forest.isEmpty()) return@onMain null
            val sb = StringBuilder()
            var n = 0
            for (w in forest) {
                for (node in UiTree.sequence(listOf(w))) {
                    n++
                    sb.append(node.className).append('|')
                        .append(node.viewIdResourceName ?: "").append('|')
                        .append(node.text ?: "").append('|')
                        .append(node.contentDescription ?: "").append('|')
                    val rc = Rect()
                    node.getBoundsInScreen(rc)
                    sb.append(rc.left).append(',').append(rc.top).append(',')
                        .append(rc.right).append(',').append(rc.bottom).append('\n')
                }
            }
            if (n == 0) null else arrayOf(md5(sb.toString()), n.toString(), foregroundPkg())
        }
        if (r !is Array<*>) return Resp("NO_ROOT", "application/json",
            json("error" to "NO_ROOT", "hint" to "读不到窗口树").toString())
        return jsonResp(json("fp" to r[0], "nodes" to (r[1] as String).toInt(),
                             "pkg" to r[2], "epoch" to UiTree.epoch.get()))
    }

    private fun md5(s: String): String {
        val d = java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray())
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    /** 把 FLAG_INCLUDE_NOT_IMPORTANT_VIEWS 置为 on（v5 起只用于「确保开着」，不再有临时关掉的用法）。
     *  返回是否真的改动过（ROM 可能忽略，改完要看 flagOn() 回读）。 */
    private fun setImportantFlag(on: Boolean): Boolean {
        return try {
            val si = serviceInfo ?: return false
            val mask = android.accessibilityservice.AccessibilityServiceInfo
                .FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            val want = if (on) si.flags or mask else si.flags and mask.inv()
            if (si.flags == want) return false
            si.flags = want
            serviceInfo = si
            importantErr = ""
            true
        } catch (e: Exception) {
            importantErr = "${e.javaClass.simpleName}: ${e.message}"
            false
        }
    }

    @Volatile var importantErr = ""

    /**
     * 当前所有可读窗口（状态栏 / 导航条 / 别的包的授权弹窗 / 输入法都在别的窗口里）。
     * 顺序按 windows 原样，与 uiautomator dump 的窗口顺序一致（实测：活动窗→导航条→状态栏）。
     * 上一版只取 rootInActiveWindow，被 ui_diff.py 测出稳定漏读 6 个状态栏节点——见 UiTree 头部注释。
     */
    private fun forestOnMain(): List<UiTree.WinRoot> {
        val out = ArrayList<UiTree.WinRoot>()
        val seen = HashSet<Int>()
        try {
            for (w in windows) {
                if (w == null) continue
                val root = try { w.root } catch (e: Exception) { null } ?: continue
                val id = try { w.id } catch (e: Exception) { out.size }
                if (!seen.add(id)) continue
                val did = try { if (android.os.Build.VERSION.SDK_INT >= 28) w.displayId else 0 } catch (e: Throwable) { 0 }
                out.add(UiTree.WinRoot(root, did))
            }
        } catch (e: Exception) { /* ROM 不给枚举窗口时退回单窗 */ }
        if (out.isEmpty()) rootInActiveWindow?.let { out.add(UiTree.WinRoot(it)) }
        return out
    }

    private fun currentForest(): List<UiTree.WinRoot> {
        val r = onMain { forestOnMain() }
        @Suppress("UNCHECKED_CAST")
        return (r as? List<UiTree.WinRoot>) ?: emptyList()
    }

    /** currentRoot 为 null 有两种完全不同的原因，不能都当"页面没内容"：见 PROTOCOL 错误码表 */
    private fun currentRoot(): AccessibilityNodeInfo? {
        return try {
            val r = rootInActiveWindow
            if (r == null) {
                secureSuspect = true
                null
            } else { secureSuspect = false; r }
        } catch (e: Exception) {
            null
        }
    }

    @Volatile var secureSuspect = false

    /**
     * 真正的"前台 App 窗口"。
     *
     * 为什么不能只看事件流里的 lastPkg：输入法弹出来时会发 TYPE_WINDOW_STATE_CHANGED，
     * 包名是 `com.baidu.input_mi` 这类 IME，于是 lastPkg 被写成输入法 ——
     * 实测 `ph --transport agent ui --summary` 打印"前台=com.baidu.input_mi"，
     * 而真实焦点是 `com.android.settings`。`expect-pkg` 这类断言会因此全错。
     * 正解：从 windows 里挑 type=APPLICATION 且 isActive 的那扇窗（IME 的 type 是 INPUT_METHOD）。
     */
    private fun appWindow(): android.view.accessibility.AccessibilityWindowInfo? = try {
        val ws = windows
        (ws.firstOrNull {
            it != null && it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive
        } ?: ws.firstOrNull {
            it != null && it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
        })
    } catch (e: Exception) { null }

    /** 前台包名（必须在主线程调）。取不到应用窗口时退回事件流维护的 lastPkg。 */
    private fun foregroundPkg(): String = try {
        appWindow()?.root?.packageName?.toString()?.takeIf { it.isNotEmpty() } ?: lastPkg
    } catch (e: Exception) { lastPkg }

    private fun onMain(timeoutMs: Long = 8000, block: () -> Any?): Any? {
        // 无障碍 API 必须在主线程调；HTTP 线程在别处，所以统一走这个包装
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var res: Any? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        mainHandler.post {
            try { res = block() } catch (e: Exception) { res = e } finally { latch.countDown() }
        }
        if (!latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) return "TIMEOUT"
        return res
    }

    // ---------- 三段式动作 ----------

    private fun postAct(body: String): Resp {
        val req = try { JSONObject(body) } catch (e: Exception) {
            return Resp("ERR", "application/json", json("error" to "BAD_JSON").toString())
        }
        val index = req.optInt("index", -1)
        val action = req.optString("action", "click")
        val expectEpoch = if (req.has("expect_epoch")) req.getLong("expect_epoch") else null
        val e0 = UiTree.epoch.get()
        if (expectEpoch != null && expectEpoch != e0) {
            return Resp("STALE_TREE", "application/json",
                json("error" to "STALE_TREE", "expect" to expectEpoch, "now" to e0).toString())
        }
        if (index < 0) return Resp("ERR", "application/json", json("error" to "BAD_INDEX").toString())

        val node = nthNode(index)
            ?: return Resp("NO_SUCH_NODE", "application/json",
                json("error" to "NO_SUCH_NODE", "index" to index, "epoch" to e0).toString())

        // 段 1：performAction（不需要坐标 ⇒ 没有坐标误差这一项）
        val performed = try {
            performActionOn(node, action)
        } catch (e: Exception) { "EX:" + e.message }

        if (confirm(action, e0, node)) {
            return Resp("OK", "application/json",
                json("stage" to "performAction", "performed" to performed.toString(),
                     "epoch_before" to e0, "epoch_after" to UiTree.epoch.get(),
                     "provenance" to "perform_action").toString())
        }

        // 段 2：降级坐标 gesture（自绘控件/未声明 clickable 时才有意义）
        val r = Rect(); node.getBoundsInScreen(r)
        if (r.width() <= 0 || r.height() <= 0) {
            return Resp("ERR", "application/json",
                json("error" to "UNACTIONABLE", "index" to index,
                     "hint" to "节点面积为 0，不吐中心点").toString())
        }
        val cx = (r.left + r.right) / 2
        val cy = (r.top + r.bottom) / 2
        val e1 = UiTree.epoch.get()
        // 原来这里写的是 `lastPkg != pkgAt(e1)`，而 pkgAt 直接 return lastPkg ⇒ 恒为 false，
        // 等于这一项从来没生效过（死代码）。改成真的取一次前后台包名。
        val pkgBefore = (onMain(2000) { foregroundPkg() } as? String) ?: lastPkg
        dispatchGesturePoint(cx, cy, 80L)
        val e2 = UiTree.epoch.get()
        val pkgAfter = (onMain(2000) { foregroundPkg() } as? String) ?: lastPkg
        val confirmedByGeometry = e2 != e1 || pkgAfter != pkgBefore
        if (confirmedByGeometry) {
            return Resp("OK", "application/json",
                json("stage" to "gesture_fallback", "x" to cx, "y" to cy,
                     "provenance" to "node_center",
                     "epoch_before" to e1, "epoch_after" to e2).toString())
        }
        // 段 3：两段都没确认 ⇒ 明确失败，**绝不返回成功**（V12 静默点错必须为 0）
        return Resp("TAP_UNCONFIRMED", "application/json",
            json("error" to "TAP_UNCONFIRMED", "index" to index, "x" to cx, "y" to cy,
                 "pkg" to lastPkg, "epoch" to e2,
                 "hint" to "performAction 与 gesture 都没让界面发生变化；可能点到了不可交互节点，或该页需要真实触摸序列").toString())
    }

    private fun performActionOn(n: AccessibilityNodeInfo, action: String): Boolean = when (action) {
        "click" -> n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        "long_click" -> n.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        "scroll_forward" -> n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        "scroll_backward" -> n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        "focus" -> n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        else -> false
    }

    private fun confirm(action: String, e0: Long, n: AccessibilityNodeInfo): Boolean {
        val deadline = System.currentTimeMillis() + 1200
        while (System.currentTimeMillis() < deadline) {
            if (UiTree.epoch.get() != e0) return true
            if (lastPkg != "" && !sameWindow(n)) return true
            Thread.sleep(60)
        }
        return false
    }

    private fun sameWindow(n: AccessibilityNodeInfo): Boolean = try {
        n.parent != null || n.window == rootInActiveWindow?.window
    } catch (e: Exception) { false }

    /** 按 /ui 的遍历顺序取第 index 个节点（与 phone.py 的序号语义一致） */
    private fun nthNode(index: Int): AccessibilityNodeInfo? {
        val res = onMain {
            val forest = forestOnMain()
            if (forest.isEmpty()) return@onMain null
            val seq = UiTree.sequence(forest)
            if (index < 0 || index >= seq.size) return@onMain null
            seq[index]
        }
        return if (res is AccessibilityNodeInfo) res else null
    }

    // ---------- 坐标动作（只用于自绘区与拖拽）----------

    private fun postTap(body: String): Resp {
        val req = try { JSONObject(body) } catch (e: Exception) {
            return Resp("ERR", "application/json", json("error" to "BAD_JSON").toString())
        }
        val x = req.optInt("x", -1); val y = req.optInt("y", -1)
        val ms = req.optLong("ms", 80L)
        val expectEpoch = if (req.has("expect_epoch")) req.getLong("expect_epoch") else null
        if (x < 0 || y < 0) return Resp("ERR", "application/json", json("error" to "BAD_COORD").toString())
        val (uw, uh) = uiSize()
        if (x >= uw || y >= uh) {
            return Resp("OUT_OF_SCREEN", "application/json",
                json("error" to "OUT_OF_SCREEN", "x" to x, "y" to y, "ui" to "$uw x $uh",
                     "hint" to "坐标必须是 UI 像素；若来自缩放帧，请在 PC 侧用 to_ui_px() 换算后重试").toString())
        }
        if (expectEpoch != null && expectEpoch != UiTree.epoch.get()) {
            return Resp("STALE_TREE", "application/json",
                json("error" to "STALE_TREE", "expect" to expectEpoch, "now" to UiTree.epoch.get()).toString())
        }
        val e1 = UiTree.epoch.get()
        dispatchGesturePoint(x, y, ms)
        // 必须等：dispatchGesture 是异步的，手势要 80ms 才走完、界面再反应。
        // 上一版这里**立刻**读 epoch，于是正常点中了也几乎必然 e2==e1 ⇒ 假报 TAP_UNCONFIRMED
        // （实测：点「已下载的应用」明明跳页了，却返回 TAP_UNCONFIRMED）。
        val e2 = awaitEpochChange(e1, 1200)
        return if (e2 != e1) Resp("OK", "application/json",
            json("x" to x, "y" to y, "epoch_before" to e1, "epoch_after" to e2,
                 "provenance" to req.optString("provenance", "node_center")).toString())
        else Resp("TAP_UNCONFIRMED", "application/json",
            json("error" to "TAP_UNCONFIRMED", "x" to x, "y" to y, "epoch" to e2).toString())
    }

    /** 等 epoch 变化（最多 timeoutMs），返回最终 epoch。手势是异步的，不等就会假报失败。 */
    private fun awaitEpochChange(e0: Long, timeoutMs: Long): Long {
        val deadline = System.currentTimeMillis() + timeoutMs
        var e = UiTree.epoch.get()
        while (e == e0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            e = UiTree.epoch.get()
        }
        return e
    }

    /** 给界面用：当前前台应用包名（主线程调）。 */
    fun foregroundNow(): String = try { foregroundPkg() } catch (e: Exception) { lastPkg }

    private fun dispatchGesturePoint(x: Int, y: Int, ms: Long): Any? = onMain(12000) {
        val p = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            lineTo(x.toFloat() + 0.5f, y.toFloat() + 0.5f)   // 极小位移，避开"零时长被 WebView 忽略"
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0L, ms.coerceIn(40L, 1500L)))
            .build()
        dispatchGesture(g, null, null)
    }

    private fun postSwipe(body: String): Resp {
        val req = try { JSONObject(body) } catch (e: Exception) {
            return Resp("ERR", "application/json", json("error" to "BAD_JSON").toString())
        }
        val x1 = req.optInt("x1"); val y1 = req.optInt("y1")
        val x2 = req.optInt("x2"); val y2 = req.optInt("y2")
        val ms = req.optLong("ms", 300L)
        val e1 = UiTree.epoch.get()
        onMain(15000) {
            val p = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
            dispatchGesture(GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, 0L, ms.coerceIn(80L, 3000L)))
                .build(), null, null)
        }
        val e2 = awaitEpochChange(e1, 1200)
        return Resp(if (e2 != e1) "OK" else "TAP_UNCONFIRMED", "application/json",
            json("epoch_before" to e1, "epoch_after" to e2).toString())
    }

    private fun postKey(body: String): Resp {
        val name = try { JSONObject(body).optString("key", "back") } catch (e: Exception) { "back" }
        val g = when (name) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> -1
        }
        if (g < 0) return Resp("ERR", "application/json", json("error" to "BAD_KEY", "key" to name).toString())
        val pkg0 = (onMain(2000) { foregroundPkg() } as? String) ?: lastPkg
        val ok = try { onMain(4000) { performGlobalAction(g) } as? Boolean ?: false } catch (e: Exception) { false }
        // 等前台真的变了再回。不等的话上层紧接着读 /state 会读到**旧**包名：
        // 实测 `batch` 里 `key:back` + `expect-pkg:com.miui.home` 因此误判失败
        // （adb 路径碰巧没事，因为 `input keyevent` 自己就要 300~500ms，把这段延迟掩盖掉了）。
        val t0 = System.currentTimeMillis()
        var pkg1 = pkg0
        while (System.currentTimeMillis() - t0 < 900) {
            Thread.sleep(60)
            pkg1 = (onMain(2000) { foregroundPkg() } as? String) ?: lastPkg
            if (pkg1 != pkg0) break
        }
        return Resp(if (ok) "OK" else "ERR", "application/json",
            json("key" to name, "performed" to ok, "pkg_before" to pkg0, "pkg_after" to pkg1,
                 "waited_ms" to (System.currentTimeMillis() - t0)).toString())
    }

    private fun postText(body: String): Resp {
        val req = try { JSONObject(body) } catch (e: Exception) {
            return Resp("ERR", "application/json", json("error" to "BAD_JSON").toString())
        }
        val text = req.optString("text")
        val index = req.optInt("index", -1)
        val target = if (index >= 0) nthNode(index) else findEditable()
        if (target == null) {
            return Resp("NO_EDITABLE", "application/json",
                json("error" to "NO_EDITABLE", "hint" to "当前窗口的聚焦节点不可编辑；先 /act focus 或 /tap 点输入框").toString())
        }
        val b = android.os.Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val ok = try { target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b) } catch (e: Exception) { false }
        if (!ok) return Resp("TYPE_FAILED", "application/json",
            json("error" to "TYPE_FAILED", "index" to index).toString())
        // 回读：写了不等于写进去了
        val now = try { target.text?.toString() ?: "" } catch (e: Exception) { "" }
        return if (now == text) Resp("OK", "application/json", json("len" to text.length).toString())
        else Resp("TYPE_UNCONFIRMED", "application/json",
            json("error" to "TYPE_UNCONFIRMED", "want" to text.length, "got" to now.length).toString())
    }

    /** 改设备别名（多机时用 `ph -d 客厅机` 区分）。空别名 = 恢复默认（型号+id后4位）。 */
    private fun postAlias(body: String): Resp {
        val a = try { JSONObject(body).optString("alias", "") } catch (e: Exception) { "" }
        AgentPrefs.setAlias(this, a)
        return jsonResp(json("alias" to AgentPrefs.displayAlias(this),
                             "custom" to AgentPrefs.alias(this)))
    }

    private fun findEditable(): AccessibilityNodeInfo? {
        val res = onMain {
            val forest = forestOnMain()
            // 必须先看**应用窗口**：forest 的第一扇窗可能是输入法，往输入法自己的输入框里写
            // 会返回"写入成功、回读一致"，但 App 的搜索框其实一个字都没进（实测踩过）。
            val appRoot = appWindow()?.root ?: forest.firstOrNull()?.node
            val first = appRoot ?: return@onMain null
            first.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?.takeIf { it.isEditable || it.className?.toString()?.contains("EditText", true) == true }
                ?: run {
                    // 焦点不在应用窗（授权弹窗/别的包抢了焦点）时，扫整个窗口森林，但**跳过输入法窗**
                    val stack = ArrayDeque<AccessibilityNodeInfo>()
                    for (w in forest) {
                        val isIme = try {
                            w.node.packageName?.toString()?.contains("input", true) == true
                        } catch (e: Exception) { false }
                        if (!isIme) stack.addLast(w.node)
                    }
                    var hit: AccessibilityNodeInfo? = null
                    var guard = 0
                    while (stack.isNotEmpty() && hit == null && guard < 400) {
                        guard++
                        val c = stack.removeFirst()
                        // 不可见节点不能当输入目标（/text 打进去等于写进了看不见的框，回读还可能"成功"）
                        val vis = try { c.isVisibleToUser } catch (e: Exception) { true }
                        if (!vis) continue
                        if (c.isEditable || c.className?.toString()?.contains("EditText", true) == true) hit = c
                        else for (i in 0 until c.childCount) c.getChild(i)?.let { stack.addLast(it) }
                    }
                    hit
                }
        }
        return res as? AccessibilityNodeInfo
    }

    // ---------- 能力与自检 ----------

    private fun uiSize(): Pair<Int, Int> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val b = wm.currentWindowMetrics.bounds
                Pair(b.width(), b.height())
            } else {
                @Suppress("DEPRECATION")
                val dm = DisplayMetrics()
                @Suppress("DEPRECATION")
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
                Pair(dm.widthPixels, dm.heightPixels)
            }
        } catch (e: Exception) {
            val dm = resources.displayMetrics
            Pair(dm.widthPixels, dm.heightPixels)
        }
    }

    private fun uiSizeJson(): JSONObject {
        val (w, h) = uiSize()
        return JSONObject().put("w", w).put("h", h)
            .put("density", resources.displayMetrics.densityDpi)
            .put("font_scale", resources.configuration.fontScale)
    }

    fun androidId(): String = try {
        android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: ""
    } catch (e: Exception) { "" }

    private fun capsPairs(): Array<Pair<String, Any?>> {
        val (w, h) = uiSize()
        return arrayOf(
            "api" to Build.VERSION.SDK_INT,
            "build" to "v12-final",           // 装了哪一版的凭据（免得以为跑的是新包其实是旧的）
            "release" to Build.VERSION.RELEASE,
            "model" to Build.MODEL,
            "brand" to Build.BRAND,
            "ui_w" to w, "ui_h" to h,
            "density" to resources.displayMetrics.densityDpi,
            "font_scale" to resources.configuration.fontScale,
            "support_gesture" to true,          // minSdk 24 已保证
            "support_set_text" to true,
            "support_screenshot" to (Build.VERSION.SDK_INT >= 30),   // takeScreenshot，不需要 MediaProjection
            // 多机区分三件套：别名（人起的）/ android_id（机器唯一）/ ip（当前网段地址）
            "alias" to AgentPrefs.displayAlias(this),
            "alias_custom" to AgentPrefs.alias(this),
            "android_id" to androidId(),
            "ip" to localIp(),
            // 默认口径的两根柱子：flag 开着（含非重要容器）+ 丢不可见节点 = u2 等价树
            "flag_include_not_important" to flagOn(),
            "default_tree" to "visible-only",
            "service_running" to (instance != null),
            "http_error" to (httpError ?: ""),
            "fg_error" to (fgError ?: ""),
        )
    }

    private fun selftestJson(): JSONObject {
        val (w, h) = uiSize()
        val root = currentRoot()
        val forest = currentForest()
        val winDesc = forest.mapIndexed { i, r ->
            val cnt = UiTree.sequence(listOf(r)).size
            "#${i + 1} ${r.node.packageName} ${cnt}节点"
        }.joinToString(" | ")
        val checks = JSONArray()
        fun add(name: String, pass: Boolean, detail: String) {
            checks.put(JSONObject().put("name", name).put("pass", pass).put("detail", detail))
        }
        add("service_enabled", enabledBySystem(),
            if (enabledBySystem()) "系统已启用本无障碍服务" else "设置里没开启（或被 ROM 关闭）")
        add("windows_enum", forest.isNotEmpty(),
            if (forest.size > 1) "枚举到 ${forest.size} 扇窗：$winDesc"
            else "只有 1 扇窗（flagRetrieveInteractiveWindows 可能被 ROM 忽略，或处于沉浸全屏）：$winDesc")
        add("root_readable", root != null,
            if (root == null) "rootInActiveWindow=null（可能是安全窗口/刚切窗口）" else "读到一棵树")
        add("foreground_known", lastPkg.isNotEmpty(), "lastPkg=$lastPkg")
        add("fg_service", fgError == null, fgError ?: "startForeground 成功")
        add("http_bound", httpError == null, httpError ?: "端口 $PORT")
        add("ui_size_known", w > 0 && h > 0, "${w}x$h")
        // 事件通道怎么判，试过两版都不对，第三版才对：
        //   ① 原版 `epoch > 1`：重装后屏幕静止 ⇒ epoch 恒为 1 ⇒ 假红（交接文档已记）。
        //   ② `lastEventAt > 0`：**仍然假红** —— v5 重装后 7 秒内 lastEventAt 一直是 0，
        //      因为静止屏幕根本不会产生事件（实测：等再久也是 0，直到有人滑一下）。
        //   ③ 现在这版：只读地核对"我们向系统要了哪些事件类型"（配置事实，确定性，能自己红），
        //      "事件是否真的在推"必须用**写操作**造一个事件才能验 —— 那是主机侧 A4 的事。
        //      只读自检不该依赖"恰好有人碰过屏幕"，否则同一台机同一份代码会时红时绿。
        val needEvents = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
        val gotEvents = serviceInfo?.eventTypes ?: 0
        val ageMs = if (lastEventAt > 0L) System.currentTimeMillis() - lastEventAt else -1L
        add("event_types_configured", (gotEvents and needEvents) == needEvents,
            "eventTypes=0x%x 需要=0x%x；last_event_ms=%d age=%dms（仅供参考，不作为判据）"
                .format(gotEvents, needEvents, lastEventAt, ageMs))
        val flagDetail = probeImportantFlag()
        add("important_flag_toggle", importantFlagOk, flagDetail)
        val all = (0 until checks.length()).all { checks.getJSONObject(it).getBoolean("pass") }
        return JSONObject().put("pass", all).put("checks", checks).put("port", PORT)
    }

    @Volatile private var importantFlagOk = false

    private fun flagOn(): Boolean = try {
        ((serviceInfo?.flags ?: 0) and
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS) != 0
    } catch (e: Exception) { false }

    /**
     * 确认 FLAG_INCLUDE_NOT_IMPORTANT_VIEWS 处于开（v5 起它是常驻口径，不是临时开关）。
     *
     * 能力边界必须说清：回读的是 `serviceInfo.flags`（我们刚写进去的值），能证明"本进程没抛异常、
     * 字段被接受"，**不能证明 ROM 真的按新 flag 重新枚举了视图**。后者只能看节点数
     * （主机侧 /ui 与 /ui?full=1 的节点数差，以及 A6 与 u2 的逐节点对比）。
     * 所以 pass 只代表"开关可写且处于开"，detail 里如实写明，不冒充"已生效"。
     */
    private fun probeImportantFlag(): String {
        val before = flagOn()
        val wrote = setImportantFlag(true)
        val onRead = flagOn()
        importantFlagOk = onRead
        return "before=$before wrote=$wrote on_readback=$onRead" +
                (if (importantErr.isNotEmpty()) " err=$importantErr" else "") +
                "（只证明开关可写，不证明 ROM 已按新 flag 重枚举）"
    }

    private fun enabledBySystem(): Boolean {
        // 不能用 getEnabledAccessibilityServiceList() 判：HyperOS 对侧载应用有"受限设置"，
        // 那个枚举会漏掉我们（实测：服务已绑定、能读树，枚举里却查不到 ⇒ 上一版误报 FAIL）。
        // 正解：直接读系统设置里的那串已启用服务列表（普通 App 可读）。
        return try {
            val want = "$packageName/${AgentService::class.java.name}"
            val listed = android.provider.Settings.Secure.getString(
                contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            listed.split(':').any { it.trim() == want } || instance != null
        } catch (e: Exception) {
            instance != null
        }
    }

    private fun jsonResp(o: JSONObject) = Resp("OK", "application/json", o.toString())

    private fun json(vararg kv: Pair<String, Any?>): JSONObject {
        val o = JSONObject()
        for ((k, v) in kv) o.put(k, v ?: JSONObject.NULL)
        return o
    }
}
