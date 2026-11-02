---
name: 手机控制
description: 用 ADB + UI 元素树控制任意 Android 手机：按文字点按钮、输入中英文、滑动、截图、一次调用跑完多步流程并自带验证。不截图猜坐标，不看图也能点准。支持 uiautomator2 常驻快路、Windows OCR 视觉兜底、多机并发、batch 变量/条件/循环。触发场景：用户让你操作手机/点某个按钮/在手机上发内容、装应用、改设置、跑 App 流程、自动化安卓界面、看手机屏幕上有什么，或提到 p.py、phone.py、adb 点屏、手机自动化、UI 树、uiautomator、OCR。
agent_created: true
---

# 手机控制（ADB + UI 树）

一条命令 = 找到一个元素 + 点下去 + 确认结果。全程不截图、不目测坐标。

## 为什么这套比「截图 + 看图点坐标」强

| | 截图派 | 这套 |
|---|---|---|
| 一次看屏 | 一张图 1500~2000 token | 10~20 行文字 ≈ 200 token |
| 定位 | 人/模型从缩放图里估坐标，必偏 | 元素 bounds 直接算中心点 |
| 验证 | 再截一张图确认 | 命令自带 `--expect-pkg` / `--expect-text` |
| 多步流程 | 每步一次往返 | `batch` 一次调用跑完 N 步 |

已实测对照：打开「设置 → 更多设置 → 开发者选项」，老办法 3 张截图 + 猜坐标 ≈ 6000 token / 8 次调用；
现在 `find 开发者选项 --tap` 一条 ≈ 50 token。

## 三条铁律

1. **能读文字就不看图**。UI 树打不出来（Canvas / 游戏 / Flutter / 搜索页）时先自动走 OCR；
   只有连文字都没有的纯图形界面，才 `shot --crop` 人眼看。
2. **不按坐标点，按文字点**。`tap <文字>` / `find <文字> --tap`。坐标只作兜底（`tap: x,y`），且必须来自 `ui` 的输出，不许估。
3. **一次调用带验证**。动作和结果确认放在同一条命令里（`act` / `batch`），不要「点了 → 再看一眼 → 再决定」。

## 首次接入（三步，任何手机通用）

```bash
ph setup --save            # 探 adb / 密钥 / 设备，写配置
ph connect 192.168.1.23:5555   # 无线设备：连一次，地址记进配置
ph dev                     # 确认当前设备
```

adb 不在 PATH 时用全局参数指定：`ph --adb D:/platform-tools/adb.exe setup --save`；
`--adb` / `--keydir` / `--port` / `-d` 都是**全局参数，必须写在子命令前面**（写成 `setup --adb` 会报错）。

`ph` = `python "<技能目录>/scripts/phone.py"`（技能目录名是中文，shell 里**要加引号**）。
Windows 上把 adb 放本地盘（`setup --local`），每条命令能省 1.5s——网络盘/UNC 上跑 adb.exe
每次调用都要重新加载 8MB 可执行文件。

可选快路：`pip install uiautomator2`。装了自动启用（dump 0.3~0.6s），不装也能用，只是慢。
`ph setup` 会显示快路 / OCR 是否就绪。

手机侧只要满足一条：**USB 调试已开、电脑已被授权**（弹窗点「允许」）。
连接方式按系统版本选，细节见 `references/android-variants.md`：

| 场景 | 做法 |
|---|---|
| USB 直连 | 插线即可，最稳；`-d 序列号` 指定多台中的一台 |
| Android 11+ 无线 | 开发者选项 → 无线调试 → 配对码配对：`adb pair ip:port`，再 `adb connect ip:port` |
| Android 10 及以下无线 | 先 USB：`adb tcpip 5555`，再 `adb connect ip:5555` |
| 同一 WiFi 找不到 | `adb mdns services` 看它广播的地址（端口每次可能变，别用缓存值） |

## 命令表

```
ph setup [--save] [--local]      探环境/写配置/把 adb 拷到本地盘
ph connect ip:port ...           连无线设备并记住
ph dev | ph devices | ph wake | ph ime status|list|fix|install|set
                                 当前设备 / 列出所有在线设备 / 输入法（fix 还原被顶掉的键盘）
ph ui [--filter 词] [--limit 40] [--clickable] [--summary]
                                 元素表；--summary 一行看屏，--clickable 只看能点的
ph tap <文字> [--by auto|text|desc|id|class] [--re] [--nth N] [--list] [--clickable] [--ms 800]
                                 按文字/描述/id 点；--list 列同名候选，--ms 长按
ph tap-ocr <文字> [--nth N] [--list] [--ms 800]
                                 OCR 兜底点击（Canvas/游戏/Flutter 或 UI 树失败）
ph ocr [--filter 词] [--limit 30] [--json]
                                 OCR 看屏：每行文字 + 坐标
ph tapn <序号>                    按上次 ui 的序号点（走缓存，不重新 dump）
ph find <文字> [同 tap 选择器] [--tap] [--max 6] [--dir up]
                                 自动滑动找，可跨屏
ph act "tap-text:文字" --expect-pkg 包名 --timeout 8
ph act "key:back" --expect-text 文字
ph batch 步骤文件 [--devices 别名A,别名B]
                                 多步一次跑完；支持变量/if/repeat/retry，可多机并发
ph key back|home|recents|power|wakeup|enter|delete
ph type "中文"|"abc"              ASCII 走 input text，中文走 ADBKeyboard，用完切回原输入法
ph scroll down|up [次数]          按屏幕高度比例滑，任意分辨率通用
ph drag x1,y1 x2,y2 [--ms 400]    拖拽/滑动手势
ph shot a.png --crop 0,1450,720,1640 --scale 0.6
                                 截图；agent 通道走无障碍 takeScreenshot（不需要 adb / 共享屏幕同意框）
ph fp [--wait 8]                 页面指纹（32 字节）。--wait 等页面变化：变了打 UI_CHANGED(rc=0)，
                                 没变打 UI_UNCHANGED(rc=5)——比每轮拉整棵树便宜得多
ph install app.apk                装 APK；厂商「继续安装」框自动点掉
ph stop 包名 | ph app 包名         强制停止 / 启动
ph open URL                       用默认应用打开链接
ph focus | ph sh "任意 shell"
```

多设备：`-d 序列号` / `-d 型号子串` / `-d 别名`。所有坐标要么来自 UI 树，要么按 `wm size` 算，
脚本里没有写死的机型、分辨率、序列号、IP、输入法包名。

## 选择器（点得准，点不到就换姿势）

- **同名歧义**：`ph tap 设置 --list` 先列候选（可点、靠上的排前），再 `--nth 1` 指定第几个。
  默认 `--nth 0` 点第一个，并在有同名时打一行提示，不会静默点错。
- **非文字定位**：`--by text` / `--by id`（resource-id 子串）/ `--by desc`（无障碍描述）/ `--by class`；
  默认 `auto` 三处一起找。
- **正则/模糊**：`ph tap "WLAN|无线" --re`。
- **只要可点节点**：加 `--clickable`，避开纯文字标签。
- **长按**：`ph tap 设置 --ms 800`（默认 80ms，即普通点击）。

## 快路与视觉兜底（自动，不用选）

- **uiautomator2 快路**：装了 `uiautomator2` 就自动走手机上的常驻服务——dump 0.3~0.6s、
  截图 0.8s、点击 0.25s（原路 dump 4~21s）。首次连接会自动把 `u2.jar` 推到手机并起服务
  （u2 3.x 不再用 atx-agent）。关闭：`PHONE_NO_U2=1`；`ph setup` 会显示快路是否启用。
  没装也不影响，自动回退原路。
- **Windows OCR 兜底**：UI 树失败时，`tap` / `tap-text` / `wait` / `--expect-text` 会自动
  截图 + 本机离线 OCR（zh-Hans）再按文字坐标点。手动入口：`ph ocr`、`ph tap-ocr 文字`。
  只认文字，纯图标/无字画面仍失明——这类才截图人眼看。

## 第三条通道：端侧 agent（不依赖 adb）

手机上装 `com.zpq.phoneagent`（无障碍服务 + 局域网 HTTP，默认 8712）后，`ph` 可以完全不碰 adb：

```bash
ph agent-find --save                         # 扫局域网自动找到手机，写进配置（不用去设置里翻 IP）
ph ui --summary                              # 之后所有命令默认就走端侧
ph --transport agent ui --summary            # 想强制指定通道时加这个
ph --transport agent tap 设置                 # 动作走 dispatchGesture
ph --transport agent batch steps.txt         # batch 照跑，命令面与输出格式一字不变
```

- `--transport auto`（默认）只在配了 `agent_host` 且 `/caps` 连得上时才走它，否则回落到 adb/u2——
  **不配就与旧版行为完全一致**。
- **新手机接入不用知道 IP**：`ph agent-find` 扫本机所在 /24 网段的 8712 端口，直接列出局域网里的手机
  （实测 3.7s 找到一台）；`--save` 写进 `~/.phone/config.json` 后就不用再管。
- token 从 `~/.phone/agent_token` 或 `AGENT_TOKEN` 读，不进命令行、不打印。
- 为什么要它：抖音那类页面一次性 `uiautomator dump` 实测 0/5 全败（每次白等 144s），
  常驻无障碍服务 5/5 成功——这不是"更快"，是"另一条路根本读得到"。
- **截图也走端侧**：`ph --transport agent shot a.png --scale 0.6` 用无障碍服务的 `takeScreenshot`
  （API 30+），不需要 MediaProjection 的"共享屏幕"同意框，也不需要 adb。实测 720×1640 全尺寸 1.24MB/2.3s，
  0.6 档 487KB/0.59s；两条通道截出来的 PNG **字节数完全相同**（同一帧）。
- **`ph fp`**：页面指纹，agent 通道走 `/fingerprint`（只回 32 字节），adb 通道本地算同一套哈希
  （实测同屏同值）。`--wait N` 就是廉价的"等页面变"。
- 能力边界（agent 通道）：`back/home/recents/notifications` 之外的功能键、`app:`、`install`
  仍需要 adb；没有 adb 时明确报 `TRANSPORT_UNSUPPORTED`，不假装成功。
- 通道不影响语义：默认树的节点集已与 u2 逐节点对齐（桌面 19=19、小米内容中心 41=41），
  所以 `ui/tap/type/batch` 的输出在两条通道下**逐字节相同**（实测 diff 为空）。

## 多机

```bash
ph devices                          # 列出所有在线设备
ph -d 别名 ui --summary             # 指定一台
ph batch steps.txt --devices 别名A,别名B   # 多机并发跑同一份步骤
```
`-d` 支持序列号 / 型号子串 / 别名（别名表见 `scripts/phone.py` 的 `MODELS`）。每台设备独立 UI 缓存，
并发进程不会互相顶掉；`-d` 找不到就报 `NO_DEVICE`，绝不自动改用别的手机。

## 标准动作

**① 探索一屏**
```bash
ph ui --limit 20          # 看有哪些可点元素
ph ui --filter 设置        # 只在关心的词上过滤
```

**② 点它并确认**
```bash
ph act "tap-text:设置" --expect-pkg com.android.settings   # 跨应用跳转：认包名最稳
ph act "tap-text:更多设置" --expect-text 开发者选项          # 页内跳转：认文字
```
`--expect-pkg` 最快（只查前台窗口，~0.2s/次）。`--expect-text` 要 dump，慢但通用。
两个都不带时只报前台、不做判定——页内跳转（同包名）不会因此报 FAIL。

**③ 跨屏找东西**
```bash
ph find 开发者选项 --tap --max 6      # 最多下滑 6 屏
```

**④ 多步流程（最省 token）**
```bash
ph batch steps.txt
```
```
# steps.txt：一行一条，按顺序执行，失败即停
key:home
tap-text:设置
expect-pkg:com.android.settings
find:开发者选项          # 会下滑找
type:wifi
wait:WLAN 3
sh:dumpsys activity recents | grep -o "mRecentsComponent=[^ ]*"
```
指令：`tap:` `tap-text:` `key:` `app:` `type:` `scroll:` `find:` `wait:` `expect-pkg:` `shot:` `sh:`。

**batch DSL（可嵌套，向后兼容线性写法）**
```
set:NAME=值              # 定义变量，后面用 $NAME
if:NAME==值              # 也支持 !=
if-pkg:包名              # 条件：前台包名
if-text:文字             # 条件：当前屏有没有这段文字
echo:文字                # 打一行（变量已替换）
repeat:3 ... end:        # 循环
retry:2 ... end:         # 块内硬失败自动重试
else: / endif:           # 条件和循环的闭合
```
`ph batch steps.txt --devices 别名A,别名B` 多机并发跑同一份。

## 省 token 三招

1. 先 `ph ui --summary`：一行给前台 + 12 个可点项，比整棵元素表省一个数量级。
2. 只看能点的：`ph ui --clickable --limit 10`。
3. 动作带验证：`ph act ... --expect-pkg` / `--expect-text`，或 `ph batch` 一次跑完，别「点→看→再决定」。

## 实测耗时（720×1544 旧机型，作最坏基线）

| 操作 | 耗时 |
|---|---|
| 一条 adb shell（本地盘 adb） | 0.2 s |
| 同上，adb 在网络盘 | 1.9 s |
| **u2 快路 dump** | **0.3~0.6 s** |
| **u2 首次连接（每个进程）** | **~1.7 s** |
| `uiautomator dump` 静态页（原路） | 2.4~3.3 s |
| `uiautomator dump` 前台在播视频 | 8~16 s（等 idle 超时，不是卡死） |
| `ph ui --summary` | 3.1 s（u2）/ 14.1 s（原路，抖动时更慢） |
| 截图 | 0.8 s（u2）/ 1.9 s（screencap） |
| OCR 看一屏 | ~1.5 s（u2 截图 + 本机离线 OCR） |
| `ph batch` 14 步 DSL 流程 | 24.5 s / **一次调用** |

换机器会更快；有 u2 时瓶颈不再是 dump。每次新 shell 里 adb server + u2 连接是冷启动的（约 2~3s），
所以**能用 batch 就别拆成多次调用**。

## 打不出 UI 树的界面（必须知道）

`ERROR: null root node returned by UiTestAutomationBridge` 是这套方法唯一的真实天花板，两类：

- **抖动型（最常见）**：纯抛硬币。实测同一屏连测 6 次是 3 成 3 败，跟等待间隔无关。
  脚本最多跑 **3 轮 × 4 次**（每轮在手机端循环，轮间会叫醒/重连）；验收实测单次失败仍约 1~2 成
  （一次 `ph ui` 内部重试到 21 s）。仍失败就换一屏再来，别拿单次失败下结论。
- **死锁型**：设置里的搜索页（键盘弹起、光标闪）、部分视频播放页，多轮重试全失败。
  处置：`ph key back` / `ph key home` 退回上一屏再试；不要在这一页死磕。
- 还有一种假失败：**息屏时 dump 100% 失败，报的也是 null root node**。脚本已做成「先叫醒再重试」，自己手搓命令时别忘了。

完全没有文字节点的界面（游戏、Unity、Flutter 自绘、WebView canvas）读不到元素：
现在会先自动 OCR；OCR 也读不到（纯图标、无字画面）才 `ph shot --crop ...` 截图人眼看，
点的时候用 `tap: x,y`，坐标取自最近一次截图与屏幕尺寸换算。这是唯一该截图的情况。

## 输入

- 纯 ASCII：`ph type abc123`，直接 `input text`，快。
- 中文/emoji：`input text` 会崩（NullPointerException），必须 ADBKeyboard 注入。
  首次：`ph ime install`（用 `~/.phone/ADBKeyboard.apk`，或指定 APK 路径），之后 `ph type 中文` 自动切输入法、发送、再切回原来那个。
  部分国产 ROM 的 `pm install` 会停在「继续安装」确认框——脚本会自动发现并点掉，**不要再手动按手机**（前提：adb 在线、屏幕亮着）。
- 长文本、含引号：避开 `'`，或改用 `sh "am broadcast ..."` 自己转义。

## 别用它的时候

- 拖拽 / 画图 / 长按移动：`input swipe` 只能给直线，用 `sh "input motionevent DOWN 100 200"` 系列自己控时序。
- 需要判断图像内容（这图标是什么意思）：这时才截图。
- 手机没开 USB 调试也不方便开：无解，先让用户开。安卓不给应用层绕过。

## 排错索引

| 症状 | 处置 |
|---|---|
| `NO_DEVICE` | `ph connect ip:port`；USB 看是否被「仅充电」模式挡；`adb devices` 里 `unauthorized` = 去手机上点允许 |
| `unauthorized` 反复出现 | 手机密钥和电脑密钥不匹配：手机「撤销 USB 调试授权」后重插，或用同一套 `adbkey`（见 references） |
| `UI_DUMP_FAILED` | 见上节；先 `key home` 再试 |
| 点了没反应 | 该控件是 WebView/自绘，`input tap` 零时长事件被忽略——脚本已改用同点 `swipe 80ms`；仍不行用 `motionevent DOWN/UP` |
| `type` 中文没进去 | `ph ime status` 看 ADBKeyboard 装没装；装了就检查输入框是否已聚焦（先 `tap-text` 点输入框） |
| 命令报 `device offline` | 本机 adb 僵尸状态：`adb disconnect <目标>` 再 `connect`，不是手机掉线 |
| 多台手机串了 | `ph devices` 先看；`-d` 明确指定；batch 用 `--devices` |
| `OCR_UNAVAILABLE` | Windows 设置里装「中文(简体)」OCR/语言包；或直接 `PHONE_NO_U2=1` 不影响 UI 树路径 |
| u2 连不上 / 卡住 | `PHONE_NO_U2=1` 强制走原路；`pip install -U uiautomator2` 后重试 |
| `BAD_STEPS` | batch 的 `if`/`repeat`/`retry` 缺 `endif:` 或 `end:` |
| `AGENT_OFFLINE` | `--transport agent` 但连不上：确认手机和电脑同网段、手机上看一眼 agent 前台通知是否还在、`PHONE_AGENT_HOST` 有没有配 |
| `AGENT_TAP_FAILED` | 动作被服务端判定"没让界面变化"（`TAP_UNCONFIRMED`）：该节点不可交互，或这页需要真实触摸序列。**不会**偷偷降级去 adb |
| `TRANSPORT_UNSUPPORTED` | agent 通道没有 shell。`sh`/`ime`/`app`/`install`/`stop`/`open` 会自动强制走 adb；其余命令加 `--transport adb` |
| `TAP_UNCONFIRMED` | 点到了但界面没变化，无法确认。脚本会重试一次，仍不确认只警告不中断 |
| 输入框是自绘控件（闲鱼等） | `/text` 写不进去时**自动降级**为 ADBKeyboard 广播，无需手动切通道 |
| 输入法卡在 ADBKeyboard | `ph ime restore` 一键切回原输入法 |
| 关键词匹配不到（闲鱼等） | 商品标题含零宽字符 `​`，`ph ui` 已自动清除；自己解析 XML 时记得也清 |

## 平台边界

- **Android only**（本机实测 Android 9；MIUI/HyperOS 需另开 USB 安装与安全设置）。
- **iOS** 在 Windows 上没有真机自动化通道：必须 macOS + WebDriverAgent/Appium。本技能不提供假支持。
- 桌面端 UI 自动化不在范围（用 computer-use 类工具）。

## 文件地图

- `scripts/phone.py` — 全部 CLI 与执行逻辑（可选依赖：uiautomator2、PIL、Windows OCR）
- `scripts/ocr.ps1` — Windows 自带 OCR（Windows.Media.Ocr，离线，中文）
- `references/android-variants.md` — 厂商/版本差异：连接、安装限制、输入法、无障碍、特殊 ROM
- `references/devices.md` — 设备档案模板 + 通用运维踩坑
