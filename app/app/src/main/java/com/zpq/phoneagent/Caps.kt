package com.zpq.phoneagent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/**
 * 顺手的「手脚」扩展包：文件互传、装 App、开任意入口、读应用清单、读写系统设置、剪贴板。
 *
 * 为什么单独一个文件：AgentService 已经 50KB，全是读树/点按的细节；这些能力跟"读屏"无关，
 * 混进去会把那边越搞越难读。这里只放纯函数，HTTP 路由在 AgentService 里包一层 Resp。
 *
 * 硬边界（系统给的，不是没做）：
 *   · 静默安装/静默卸载 —— 无障碍服务没有 INSTALL_PACKAGES，只能拉起安装器让人点确认
 *   · Android 10 以下没有 MediaStore.Downloads，安装器拿不到 content:// URI，只能报 INSTALL_UNSUPPORTED
 *   · 剪贴板读取在 Android 10+ 要求 App 在前台/持有焦点，后台会被系统拒绝，如实回报
 */
object Caps {

    private val MIME = mapOf(
        "apk" to "application/vnd.android.package-archive",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "mp4" to "video/mp4",
        "txt" to "text/plain",
        "json" to "application/json",
        "pdf" to "application/pdf",
        "zip" to "application/zip",
        "mp3" to "audio/mpeg"
    )

    fun mimeOf(name: String): String =
        MIME[name.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"

    /** 文件名消毒：手机和电脑两边的路径语义不同，这里只留最后一段并替换非法字符 */
    fun safeName(raw: String): String {
        val base = raw.replace('\\', '/').substringAfterLast('/')
        val clean = base.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(120)
        return clean.ifEmpty { "file" }
    }

    /** 私有副本目录：电脑 pull 从这里读，不依赖 MediaStore 查询，29 以下也能用 */
    fun shareDir(ctx: Context): File =
        (ctx.getExternalFilesDir("share") ?: File(ctx.filesDir, "share")).apply { mkdirs() }

    /**
     * 保存上传流。两条腿同时走：
     *   1) 私有目录留副本 —— `GET /file?name=` 从这里读，稳定、不受媒体库索引延迟影响
     *   2) Android 10+ 再插一份到「下载/顺手」—— 用户能在文件管理看到，安装器也能拿到 content:// URI
     */
    fun save(ctx: Context, name: String, ins: InputStream, declared: Long): JSONObject {
        val f = File(shareDir(ctx), name)
        var size = 0L
        // 有 Content-Length 就按字节数读满，别等 EOF：客户端发完 body 后会等响应，
        // 不会主动关写端，读 EOF 只会白等 soTimeout（实测 PowerShell 上传就卡这一步）。
        var remaining = if (declared > 0) declared else -1L
        f.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val want = if (remaining > 0) minOf(buf.size.toLong(), remaining).toInt() else buf.size
                val r = ins.read(buf, 0, want)
                if (r <= 0) break
                out.write(buf, 0, r)
                size += r
                if (remaining > 0) {
                    remaining -= r
                    if (remaining == 0L) break
                }
            }
        }
        val o = JSONObject()
            .put("name", name)
            .put("size", size)
            .put("path", f.absolutePath)
        if (declared > 0 && size != declared) o.put("warning", "short_read")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(name))
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/顺手")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    ctx.contentResolver.openOutputStream(uri)?.use { out ->
                        f.inputStream().use { it.copyTo(out) }
                    }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    ctx.contentResolver.update(uri, values, null, null)
                    o.put("public_uri", uri.toString())
                }
            } catch (e: Exception) {
                o.put("public_error", e.message ?: "media store failed")
            }
        }
        return o
    }

    fun listFiles(ctx: Context): JSONArray {
        val arr = JSONArray()
        shareDir(ctx).listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.forEach { f ->
                arr.put(
                    JSONObject()
                        .put("name", f.name)
                        .put("size", f.length())
                        .put("mtime", f.lastModified())
                )
            }
        return arr
    }

    fun fileOf(ctx: Context, name: String): File? {
        val f = File(shareDir(ctx), safeName(name))
        return if (f.isFile) f else null
    }

    /**
     * 拉起系统安装器。只到"安装页"，最后一下确认必须人点 —— 这是 Android 的安装模型，
     * 无障碍服务没有 INSTALL_PACKAGES 权限，任何 App 都做不到静默装。
     */
    fun install(ctx: Context, name: String): JSONObject {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return JSONObject().put("ok", false).put("error", "INSTALL_UNSUPPORTED")
                .put("hint", "Android 10 以下安装器拿不到 content:// URI，请用 adb install")
        }
        var uri: Uri? = null
        try {
            ctx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                arrayOf(name),
                MediaStore.MediaColumns._ID + " DESC"
            )?.use { c ->
                if (c.moveToFirst()) {
                    uri = ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        c.getLong(0)
                    )
                }
            }
        } catch (e: Exception) {
            return JSONObject().put("ok", false).put("error", "QUERY_FAILED")
                .put("detail", e.message ?: "")
        }
        val u = uri ?: return JSONObject().put("ok", false)
            .put("error", "NOT_IN_DOWNLOADS")
            .put("hint", "这个文件不在「下载/顺手」里，先 push 一次（走 MediaStore 的那条）")
        return try {
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(u, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(i)
            JSONObject().put("ok", true).put("uri", u.toString())
                .put("hint", "安装页已拉起；若小米提示「已拒绝安装未知应用」，去应用详情里允许「安装未知应用」")
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", "START_FAILED")
                .put("detail", e.message ?: "")
        }
    }

    /** 开任意入口：应用包名 / URL / 系统动作别名 */
    fun open(ctx: Context, pkg: String?, url: String?, action: String?): JSONObject {
        val i: Intent? = when {
            !pkg.isNullOrEmpty() -> ctx.packageManager.getLaunchIntentForPackage(pkg)
            !url.isNullOrEmpty() -> Intent(Intent.ACTION_VIEW, Uri.parse(url))
            !action.isNullOrEmpty() -> intentOf(action)
            else -> null
        }
        if (i == null) {
            return JSONObject().put("ok", false).put("error", "NO_TARGET")
                .put("hint", "给 pkg / url / action 三选一；pkg 必须是真的装了且可启动")
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(i)
            JSONObject().put("ok", true).put("intent", (i.action ?: ""))
                .put("pkg", i.`package` ?: "")
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", "START_FAILED").put("detail", e.message ?: "")
        }
    }

    /**
     * 别名 → 系统 Intent。常用入口写死，其余允许传完整 action（如 android.settings.WIFI_SETTINGS）。
     * 注意：这些都是"跳设置页"，不是"替你把开关拨了"——开关本身要人点或写 Settings。
     */
    private fun intentOf(action: String): Intent = when (action) {
        "settings" -> Intent(Settings.ACTION_SETTINGS)
        "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
        "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        "data" -> Intent(Settings.ACTION_DATA_ROAMING_SETTINGS)
        "flight" -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
        "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
        "sound" -> Intent(Settings.ACTION_SOUND_SETTINGS)
        "battery" -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        "apps" -> Intent(Settings.ACTION_APPLICATION_SETTINGS)
        "storage" -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
        "locale" -> Intent(Settings.ACTION_LOCALE_SETTINGS)
        "camera" -> Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        "dial" -> Intent(Intent.ACTION_DIAL)
        "browser" -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.baidu.com"))
        "market" -> Intent(Intent.ACTION_VIEW, Uri.parse("market://search"))
        "clock" -> try {
            Intent(Intent.ACTION_MAIN).setClassName(
                "com.android.deskclock", "com.android.deskclock.DeskClock"
            )
        } catch (e: Exception) { Intent("android.intent.action.SHOW_ALARMS") }
        else -> if (action.startsWith("android.")) Intent(action) else Intent(Settings.ACTION_SETTINGS)
    }

    fun apps(ctx: Context, keyword: String): JSONArray {
        val pm = ctx.packageManager
        val arr = JSONArray()
        try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA).forEach { ai ->
                val label = try { pm.getApplicationLabel(ai).toString() } catch (e: Exception) { ai.packageName }
                if (!keyword.isEmpty() &&
                    !label.contains(keyword, ignoreCase = true) &&
                    !ai.packageName.contains(keyword, ignoreCase = true)
                ) return@forEach
                val launch = try { pm.getLaunchIntentForPackage(ai.packageName) != null }
                catch (e: Exception) { false }
                arr.put(
                    JSONObject()
                        .put("label", label)
                        .put("pkg", ai.packageName)
                        .put("launchable", launch)
                        .put("system", (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
                )
            }
        } catch (e: Exception) {
            // Android 11+ 没有 QUERY_ALL_PACKAGES 时只能看到一部分，这里把事实带回去
            return JSONArray().put(
                JSONObject().put("error", "LIST_PARTIAL")
                    .put("detail", e.message ?: "").put("hint", "缺少 QUERY_ALL_PACKAGES")
            )
        }
        return arr
    }

    fun getSetting(ctx: Context, ns: String, key: String): String? = when (ns) {
        "system" -> Settings.System.getString(ctx.contentResolver, key)
        "secure" -> Settings.Secure.getString(ctx.contentResolver, key)
        "global" -> Settings.Global.getString(ctx.contentResolver, key)
        else -> null
    }

    fun putSetting(ctx: Context, ns: String, key: String, value: String): Boolean = try {
        when (ns) {
            "system" -> Settings.System.putString(ctx.contentResolver, key, value)
            "secure" -> Settings.Secure.putString(ctx.contentResolver, key, value)
            "global" -> Settings.Global.putString(ctx.contentResolver, key, value)
            else -> false
        }
    } catch (e: SecurityException) {
        false
    }

    fun clipGet(ctx: Context): JSONObject {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = try {
            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: ""
        } catch (e: Exception) { "" }
        return JSONObject().put("text", text)
            .put("hint", if (text.isEmpty()) "Android 10+ 后台读剪贴板会被拒，尽力而为" else "")
    }

    fun clipSet(ctx: Context, text: String): JSONObject {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("agent", text))
        return JSONObject().put("ok", true).put("len", text.length)
    }

    /** 音量：AudioManager 免权限，任何 App 都能调（这是少数"真的能替用户拨开关"的系统项） */
    fun volume(ctx: Context, stream: String, delta: Int?, percent: Int?): JSONObject {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val s = when (stream) {
            "ring" -> android.media.AudioManager.STREAM_RING
            "alarm" -> android.media.AudioManager.STREAM_ALARM
            "notification" -> android.media.AudioManager.STREAM_NOTIFICATION
            "call" -> android.media.AudioManager.STREAM_VOICE_CALL
            else -> android.media.AudioManager.STREAM_MUSIC
        }
        if (delta != null && delta != 0) {
            am.adjustStreamVolume(
                s,
                if (delta > 0) android.media.AudioManager.ADJUST_RAISE
                else android.media.AudioManager.ADJUST_LOWER,
                android.media.AudioManager.FLAG_SHOW_UI
            )
        }
        if (percent != null) {
            val max = am.getStreamMaxVolume(s)
            val v = (max * percent / 100).coerceIn(0, max)
            am.setStreamVolume(s, v, android.media.AudioManager.FLAG_SHOW_UI)
        }
        return JSONObject()
            .put("stream", stream)
            .put("volume", am.getStreamVolume(s))
            .put("max", am.getStreamMaxVolume(s))
    }
}
