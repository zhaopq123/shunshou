# 手机控制 · phone-control-skill

> 给 AI Agent 用的 Android 手机控制技能：读 UI 元素树、按文字点按钮，**一条命令 = 定位 + 动作 + 验证**。
> 不截图、不目测坐标，一次调用跑完多步流程。

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![Python](https://img.shields.io/badge/python-3.8%2B-blue.svg)
![Platform](https://img.shields.io/badge/platform-Windows%20%7C%20macOS%20%7C%20Linux-lightgrey.svg)

---

## 为什么不走「截图 + 看图点坐标」

| | 截图派 | 这套 |
|---|---|---|
| 一次看屏 | 一张图 1500~2000 token | 10~20 行文字 ≈ 200 token |
| 定位 | 从缩放图里估坐标，必偏 | 元素 bounds 直接算中心点 |
| 验证 | 再截一张图确认 | 命令自带 `--expect-pkg` / `--expect-text` |
| 多步流程 | 每步一次往返 | `batch` 一次调用跑完 N 步 |

实测对照：打开「设置 → 更多设置 → 开发者选项」，截图猜坐标 ≈ 6000 token / 8 次调用；
`find 开发者选项 --tap` 一条 ≈ 50 token。

## 特性

| 特性 | 说明 |
|---|---|
| 按文字点 | `tap 设置` / `find 开发者选项 --tap`，默认同时查 text / desc / resource-id |
| 选择器 | `--by` / `--re` 正则 / `--nth` 同名候选 / `--list` 先列后点 / `--clickable` |
| 动作即验证 | `act` 一条命令带 `--expect-pkg` / `--expect-text`，失败给明确 exit code |
| batch 编排 | 一行一条指令，支持变量 `$NAME`、`if/else`、`repeat`、`retry`、跨屏 `find` |
| uiautomator2 快路 | 装了自动启用：dump 0.3~0.6s（原路 2~20s），点击 0.25s，截图 0.8s |
| 视觉兜底 | UI 树打不出来时自动截图 + Windows 离线 OCR（zh-Hans）按文字点击 |
| 多机 | `-d 序列号/别名/型号子串`，`--devices A,B` 并发；每台独立缓存，不串机 |
| 跨分辨率 | 一切坐标来自 UI 树或 `wm size`，脚本里没有写死机型 / 分辨率 / IP / 输入法包名 |
| 输入法安全 | 中文走 ADBKeyboard 注入，用完自动还原原来的输入法；装 APK 后也会还原 |

## 环境要求

- **Python 3.8+**（只用标准库；可选 `uiautomator2`）
- **Android platform-tools（adb）**：放进 PATH，或跑一次 `ph setup --local` 让它自己拷到本地盘
- **手机**：开发者选项里开「USB 调试」，插线时在手机上点「允许」
- 可选：`pip install uiautomator2` —— 常驻快路，dump 快 5~30 倍
- 可选：**Windows 10/11 + 中文(简体) OCR 语言包** —— 视觉兜底；非 Windows 自动只用 UI 树
- 中文输入：自行下载 **ADBKeyboard.apk**（仓库不含该二进制），`ph ime install <apk路径>`

## 安装

技能就是一个目录，放进你所用 Agent 的 skills 目录即可：

```bash
git clone https://github.com/zhaopq123/phone-control-skill.git
cp -r phone-control-skill ~/.workbuddy/skills/手机控制      # WorkBuddy
cp -r phone-control-skill ~/.trae-cn/skills/手机控制         # Trae
cp -r phone-control-skill ~/.qoder-cn/skills/手机控制        # Qoder
cp -r phone-control-skill ~/.agents/skills/手机控制          # 通用 Agent
cp -r phone-control-skill ~/.claude/skills/手机控制          # Claude Code
```

也可以不装成技能、直接当命令行工具用（见下）。

## 快速开始

```bash
# 定义别名（目录名可以是中文，shell 里记得加引号）
alias ph='python "$HOME/.workbuddy/skills/手机控制/scripts/phone.py"'
```

PowerShell：

```powershell
function ph { & python "$env:USERPROFILE\.workbuddy\skills\手机控制\scripts\phone.py" @args }
```

三步接入：

```bash
ph setup --save                 # 探 adb / 密钥 / 设备，写配置
ph connect 192.168.1.23:5555    # 无线设备：连一次，地址记进配置
ph dev                          # 确认当前设备
```

`ph setup` 会显示快路（uiautomator2）和 OCR 是否就绪。手机侧只要满足一条：**USB 调试已开、电脑已被授权**。

| 场景 | 做法 |
|---|---|
| USB 直连 | 插线即可，最稳；多台用 `-d 序列号` 指定 |
| Android 11+ 无线 | 开发者选项 → 无线调试 → 配对码配对：`adb pair ip:port`，再 `adb connect ip:port` |
| Android 10 及以下无线 | 先 USB：`adb tcpip 5555`，再 `adb connect ip:5555` |
| 同一 WiFi 找不到 | `adb mdns services` 现查广播地址（端口可能变，别用缓存值） |

## 命令表

```
ph setup [--save] [--local]      探环境 / 写配置 / 把 adb 拷到本地盘
ph connect ip:port ...           连无线设备并记住
ph dev | ph devices | ph wake    当前设备 / 所有在线设备 / 叫醒
ph ime status|list|fix|install|set
                                 输入法：查 / 列 / 修复被顶掉的键盘 / 装 ADBKeyboard / 切换
ph ui [--filter 词] [--limit 40] [--clickable] [--summary]
                                 元素表；--summary 一行看屏，--clickable 只看能点的
ph tap <文字> [--by auto|text|desc|id|class] [--re] [--nth N] [--list] [--clickable] [--ms 800]
                                 按文字/描述/id 点；--list 列同名候选，--ms 长按
ph tap-ocr <文字> [--nth N] [--list] [--ms 800]
                                 OCR 兜底点击（Canvas / 游戏 / Flutter）
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
ph drag x1,y1 x2,y2 [--ms 400]    拖拽 / 滑动手势
ph shot a.png --crop 0,1450,720,1640 --scale 0.6
ph install app.apk                装 APK；厂商「继续安装」确认框自动点掉
ph stop 包名 | ph app 包名         强制停止 / 启动
ph open URL                       用默认应用打开链接
ph focus | ph sh "任意 shell"
```

## 选择器

- **同名歧义**：`ph tap 设置 --list` 先列候选（可点、靠上的排前），再 `--nth 1` 指定。
  默认点第一个，有同名时打一行提示，不会静默点错。
- **非文字定位**：`--by text|id|desc|class`，默认 `auto` 三处一起找。
- **正则**：`ph tap "WLAN|无线" --re`。
- **只要可点节点**：`--clickable`，避开纯文字标签。
- **长按**：`ph tap 设置 --ms 800`（默认 80ms）。

## batch：一次调用跑完多步

`steps.txt` 一行一条，按顺序执行，硬失败即停：

```
key:home
tap-text:设置
expect-pkg:com.android.settings
find:开发者选项
type:wifi
wait:WLAN 3
sh:dumpsys activity recents | grep -o "mRecentsComponent=[^ ]*"
```

指令集：`tap:` `tap-text:` `key:` `app:` `type:` `scroll:` `find:` `wait:` `expect-pkg:` `shot:` `sh:`。

DSL（可嵌套，向后兼容线性写法）：

```
set:NAME=值              # 定义变量，后面用 $NAME
if:NAME==值              # 也支持 !=
if-pkg:包名              # 条件：前台包名
if-text:文字             # 条件：当前屏有没有这段文字
echo:文字                # 打一行（变量已替换）
repeat:3 ... end:        # 循环
retry:2 ... end:         # 块内硬失败自动重试
else: / endif:           # 条件与循环的闭合
```

## 多机

```bash
ph devices                                  # 列出所有在线设备
ph -d 别名 ui --summary                      # 指定一台
ph batch steps.txt --devices 别名A,别名B      # 多机并发跑同一份
```

`-d` 支持序列号 / 型号子串 / 别名。别名表在 `scripts/phone.py` 的 `MODELS` 里自己填：

```python
MODELS = {"pixel": ["Pixel 7"], "mi": ["Redmi"]}
```

每台设备独立 UI 缓存，并发进程互不顶掉；`-d` 找不到就报 `NO_DEVICE`，绝不自动改用别的手机。

## 视觉兜底（Canvas / 游戏 / Flutter）

`uiautomator dump` 在自绘界面读不到节点。这时 `tap` / `find` / `wait` / `--expect-text`
会自动截图 + 本机离线 OCR（Windows，zh-Hans）再按文字坐标点；手动入口是 `ph ocr`、`ph tap-ocr 文字`。
纯图标、无文字的画面仍然读不到——这是唯一该 `ph shot --crop` 截图人眼看的情况。

## 省 token 三招

1. `ph ui --summary` —— 一行给前台 + 12 个可点项，比整棵元素表省一个数量级。
2. `ph ui --clickable --limit 10` —— 只看能点的。
3. 动作带验证 —— `ph act ... --expect-pkg`，或 `ph batch` 一次跑完，别「点 → 看 → 再决定」。

## 实测耗时（720×1544 旧机型，作最坏基线）

| 操作 | 耗时 |
|---|---|
| 一条 adb shell（本地盘 adb） | 0.2 s |
| 同上，adb 在网络盘 | 1.9 s |
| u2 快路 dump | 0.3~0.6 s |
| u2 首次连接（每个进程） | ~1.7 s |
| `uiautomator dump` 静态页（原路） | 2.4~3.3 s |
| `uiautomator dump` 前台在播视频 | 8~16 s（等 idle 超时，不是卡死） |
| `ph ui --summary` | 3.1 s（u2）/ 14.1 s（原路） |
| 截图 | 0.8 s（u2）/ 1.9 s（screencap） |
| OCR 看一屏 | ~1.5 s |
| 14 步 batch | 24.5 s，**一次调用** |

新机器会更快；有 u2 时瓶颈不在 dump。每次新 shell 里 adb server + u2 连接是冷启动（约 2~3 s），
所以**能用 batch 就别拆成多次调用**。

## 项目结构

```
phone-control-skill/
├── SKILL.md                      # 技能说明（Agent 读这份）
├── references/
│   ├── android-variants.md       # 厂商 / 系统版本差异：连接、安装限制、输入法、UI 树天花板
│   └── devices.md                # 设备档案模板 + 跨机型通用踩坑
└── scripts/
    ├── phone.py                  # 全部 CLI 与执行逻辑
    └── ocr.ps1                   # Windows 自带 OCR（Windows.Media.Ocr，离线）
```

## 已知边界

- **Android only**。iOS 在 Windows 上没有真机自动化通道（需 macOS + WebDriverAgent/Appium），不提供假支持。
- **UI 树天花板**：`null root node` 抖动型靠重试（最多 3 轮 × 4 次），死锁型（设置搜索页、部分视频页）
  退回上一屏再试；纯图形界面只能截图 + 坐标。
- **息屏时 dump 必失败**：脚本会先叫醒再重试，手搓命令时别忘。
- **拖拽 / 画图 / 长按移动**：`input swipe` 只能给直线，复杂手势用 `sh "input motionevent DOWN x y"` 自己控时序。
- **桌面端 UI 自动化不在范围**。

## 排错

| 症状 | 处置 |
|---|---|
| `NO_DEVICE` | `ph connect ip:port`；检查是否「仅充电」；`adb devices` 显示 `unauthorized` = 去手机上点允许 |
| `unauthorized` 反复 | 手机「撤销 USB 调试授权」后重插，或用同一套 `adbkey` |
| `UI_DUMP_FAILED` | 先 `ph key home` 再试，或换一屏；单次失败别下结论 |
| 点了没反应 | WebView/自绘控件忽略零时长事件，脚本已改同点 `swipe 80ms`；仍不行用 `motionevent` |
| `type` 中文没进去 | `ph ime status` 查 ADBKeyboard；确认输入框已聚焦（先 `tap-text` 点输入框） |
| `device offline` | 本机 adb 僵尸：`disconnect` 再 `connect`，不是手机掉线 |
| 多台手机串了 | `ph devices` 先看，`-d` 明确指定，batch 用 `--devices` |
| `OCR_UNAVAILABLE` | 装 Windows「中文(简体)」语言包 / OCR 功能 |
| u2 连不上 | `PHONE_NO_U2=1` 强制走原路；`pip install -U uiautomator2` |
| `BAD_STEPS` | batch 的 `if`/`repeat`/`retry` 缺 `endif:` / `end:` |

## 许可

[MIT](LICENSE)
