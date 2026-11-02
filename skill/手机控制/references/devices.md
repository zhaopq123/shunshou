# 设备档案（模板）

本文件是给你自己填的：把每台手机的实测信息记在这里，换机、多机并跑时对照用。
动手前先 `ph dev` 确认当前连的是哪台，别串。

## 档案模板（每台一份）

| 项 | 值 |
|---|---|
| 型号 / ROM | `<型号>` / `<系统版本>` |
| adb 序列号（USB） | `<序列号>` |
| 无线 | `<ip>:<port>`（连一次后写进 `~/.phone/config.json` 的 connect） |
| 屏幕 | `<宽>×<高> @<dpi>`（`ph sh "wm size; wm density"` 现查） |
| 用途 | `<干什么用>` |

> 序列号、IP、SSH 别名都是**本机私有信息**，填在自己机器上就行，别提交进公开仓库。

## 本机配置（通用说明）

| 项 | 默认位置 / 值 |
|---|---|
| adb | `~/.phone/platform-tools/adb.exe`（`ph setup --local` 会把 adb 拷到本地盘） |
| 密钥 | `~/.phone/home/.android/adbkey`（假 HOME，不污染系统 `~/.android`） |
| adb 端口 | 5041（独立 server，不碰系统默认 5037） |
| 配置 | `~/.phone/config.json`（adb 路径 / 密钥目录 / 端口 / 记住的无线地址） |
| UI 缓存 | `~/.phone/last_ui_<序列号>.json`（每台一份，多机并发不串） |
| 快路 | `pip install uiautomator2` 后自动启用，首次连接会往手机推 `u2.jar` |
| OCR | `scripts/ocr.ps1` 走 Windows 自带 OCR（zh-Hans，离线） |

## 跨机型通用踩坑（实测过，别再犯）

- **无线 adb 睡一觉就掉**：Android 10 及以下重启后必须插线 `adb tcpip 5555` 才有。
- **`adb devices` 显示 `offline` 但端口在 LISTEN**：本机 adb 僵尸，`disconnect` 再 `connect`，不是手机掉线。
- **视频播放页 dump 会等 idle 超时**（8~16s）；**设置里的搜索页**（键盘弹起、光标闪）会持续
  `null root node`，退回桌面即恢复。这两类不是脚本坏了。
- **卸载系统应用先查路径**：`pm path <包名>` 结果含 `/system/priv-app/` 就地停手。
  卸掉桌面/最近任务相关组件会导致按键静默失效（logcat 零报错），恢复要 `cmd package install-existing` **再重启**。
- **输入法**：注入完必须切回用户原来的输入法；别写死包名，读 `settings get secure default_input_method` 现取现还原。
- **MIUI / HyperOS** 重启会把 `adb_wifi_enabled` 重置为 0，需重新开；`pm uninstall --user 0` 是绕拦截的常用路子。
- **判负载别看 load average**：MTK 平台 14~29 可能全是 D 状态内核线程，CPU 实际 600%+ idle，
  只信 `dumpsys cpuinfo`（`top -o %CPU` 在 toybox 上排序不生效）。
- **重启后 5~10 分钟 dex2oat 更卡**，别误判「清理无效」。
- **判「卡」按 PSS 算**，别把 Total RSS 相加。
- **恢复清单别增量修补**：手改必漂移，用 `pm list packages -e` 实测差集重建。
