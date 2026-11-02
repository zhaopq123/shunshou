# 各厂商 / 各系统版本的差异与坑

目标：同一套 `phone.py` 命令在任何 Android 手机上都能跑。这里记的是「哪些地方不一样、怎么绕」。

## 1. 连接方式（按系统版本）

| 系统 | 无线调试怎么做 | 备注 |
|---|---|---|
| Android 12+ / 14 / 15 / 16 | 开发者选项 → **无线调试** → 用配对码配对：`adb pair <ip>:<配对端口>` 输 6 位码，再 `adb connect <ip>:<调试端口>` | 两个端口不一样，配对端口只在那一次有效 |
| Android 11 | 同上（无线调试首次出现在 11） | |
| Android 8~10 | 必须先用 USB：`adb tcpip 5555` → `adb connect <ip>:5555` | 重启后失效，要重来 |
| 任何版本 USB | 插线 → 手机弹「允许 USB 调试」→ 勾「一律允许」 | 最稳，批量操作优先 USB |

`adb mdns services` 能看到同网段的设备广播，但**端口会变**（`_adb-tls-connect._tcp` 尤其），
每次现查现连，别把上一次的端口写死进脚本。

## 2. 厂商特有的开关与拦截

| 厂商/ROM | 必须先开 | 典型拦截 |
|---|---|---|
| 小米 / 红米（MIUI、HyperOS） | 「USB 调试」+「USB 安装」+「USB 调试（安全设置）」 | `adb install` 报 `INSTALL_FAILED_USER_RESTRICTED`：`adb push` 到 `/data/local/tmp/` 再 `adb shell pm install -r`；`pm disable-user` 报 SecurityException，用 `pm uninstall --user 0` 绕过，`cmd package install-existing` 恢复 |
| 华为 / 荣耀（EMUI、HarmonyOS） | 「仅充电」要改成「传输文件」，否则 adb 不识别 | 部分机型需关「HDB 调试」相关限制；系统应用无法卸载 |
| OPPO / 一加 / realme（ColorOS、OxygenOS） | 「USB 调试」+「禁止权限监控」（否则装 APK 被拦） | 安装时需在手机上点确认 |
| vivo / iQOO（OriginOS、Funtouch） | 「USB 调试」 | 卸载系统应用前必查 `pm path`，`/system/priv-app/` 下的动不得（见 `references/devices.md` 的卸载红线） |
| 三星（One UI） | 「USB 调试」 | 部分机型需装 Samsung USB driver（Windows） |
| 原生 Android / Pixel | 只需「USB 调试」 | `adb root` 仅 userdebug 版本可用 |

## 3. 密钥与授权（换电脑时最常卡这里）

- 手机只认它记过的公钥（`/data/misc/adb/adb_keys`）。换电脑 = 新密钥 = `unauthorized`。
- 无 root 改不了 `adb_keys`，只有两条路：
  1. 手机上「撤销 USB 调试授权」→ 重插 → 弹窗点「允许」；
  2. 把原来那台电脑的 `~/.android/adbkey` + `adbkey.pub` **整对**拷过来用（密钥即控制权，别外传）。
- adb 读密钥的位置是 `%USERPROFILE%\.android\adbkey`（Windows）/ `$HOME/.android/adbkey`（mac/Linux）。
  `phone.py` 用「假 HOME」把密钥目录顶进去，所以能用独立密钥而不污染你本机的 `~/.android`。
- 用了独立密钥时**必须换端口起自己的 adb server**（脚本默认 5041），否则会和你平时的 adb server 抢同一套密钥。

## 4. 输入法

- `input text` 只吃 ASCII，中文/emoji 会 `NullPointerException`。
- 通用解 = **ADBKeyboard**（一个输入法 APK，靠 `am broadcast -a ADB_INPUT_TEXT --es msg '中文'` 注入）。
  `ph ime install` 装、`ph ime status` 查。
- 注入完必须切回用户原来的输入法，且**不许写死包名**——每家 ROM 的默认输入法都不一样
  （搜狗小米版、搜狗 vivo 版、百度、Gboard、讯飞…）。脚本的做法：读 `settings get secure default_input_method`，用完 set 回去。
- 个别 ROM 切输入法需要先 `ime enable` 再 `ime set`，脚本已串在一起。

## 5. UI 树读不出来的时候

`uiautomator dump` 返回 `ERROR: null root node returned by UiTestAutomationBridge`。两类：

- **抖动**：重试即好。脚本最多 3 轮 × 4 次（每轮在手机端一次往返跑完）。
- **持久失败**：目前确定会踩的页面——设置里的搜索页（键盘弹起 + 光标闪）、部分视频播放页。
  退回上一屏就恢复。**别在这一页死磕**，也不要因此判断「手机不支持」。
- 完全没有文字节点的：游戏、Unity、Flutter 自绘、WebView canvas。这类只能截图人眼看。
- Android 11+ 多了 `--compressed` 选项（跳过不可见节点，输出更小），但对 null root 无效。

## 6. 点击点不动

- `input tap` 是零时长事件，**WebView、Flutter、自绘控件会直接忽略**。
  通用解：`input swipe x y x y 80`——同点短滑，产生真实按压，只慢 80ms。脚本里所有点击都走这条。
- 仍不行：`input motionevent DOWN x y` → sleep → `input motionevent UP x y`（能精确控长按时长）。
- `sendevent` 能更底层，但单条 600ms+，不如 motionevent。

## 7. 权限边界（别承诺做不到的事）

- 无 root 时只能通过 **adb shell（uid 2000 shell）** 能动系统设置；Termux 里 `settings`/`pm`/`cmd` 会报
  `SecurityException: MANAGE_USERS`（Android 9 起用户解析路径限制），这是平台限制不是配置错。
- `pm grant` 运行权限会报 `Neither user ... nor current process has GRANT_RUNTIME_PERMISSIONS`——正常，必须由 adb shell 执行。
- 国产 ROM 基本没有官方 bootloader 解锁通道，**别把「root 一下」当方案**。

## 8. 主机侧（Windows / macOS / Linux）

- Windows + Git Bash 调 adb：`MSYS_NO_PATHCONV=1`，否则 `/sdcard/x` 会被转成 `C:/Program Files/Git/sdcard/x`。
- `adb pull` 的目标路径要写 **Windows 格式**（`C:/x/a.mp4`），混着写必报错。
- `.bat` 里全用 ASCII；延时用 `%SystemRoot%\System32\ping.exe -n N 127.0.0.1 >nul`，别用 `timeout`（会被 Git 的同名程序顶掉）。
- adb 版本：`1.0.41` 起够用；控 Android 14+ 建议用最新 platform-tools，老 adb 会遇到协议层报错。
- **adb 别放网络盘/UNC 路径**：每次调用都要重新加载 8MB exe，实测每次多 1.5s。`ph setup --local` 会拷到本地。
- 每台机器的 adb server 是独立的，`--port` 不同互不干扰。
