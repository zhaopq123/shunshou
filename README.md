# 顺手 · ShunShou

让电脑通过局域网操控安卓手机：**读屏、点击、输入、滑动、截图**。
不用插数据线，不用开开发者模式，不用装 Appium。

```
电脑  ──HTTP──▶  手机上的「顺手」App（无障碍服务 + 内置 HTTP 服务端）
```

---

## 为什么不用 adb / uiautomator

实测（Redmi 2409BRN2CC / Android 16）：

| 路径 | 抖音信息流页面 | 说明 |
|---|---|---|
| 一次性 `uiautomator dump` | **0/5 成功**，每次白等 144 秒 | 页面永远不"空闲"，等不到 dump 时机 |
| uiautomator2 常驻服务 | 5/5 成功，344 节点 | 需要 adb 推服务，稳态仍依赖开发者选项 |
| **本项目常驻无障碍服务** | **5/5 成功，同屏节点数与 u2 逐节点一致** | 不需要 adb |

不是"更快"，是**另一条路根本读得到**。

---

## 组成

| 目录 | 是什么 | 技术栈 |
|---|---|---|
| `app/` | 手机端「顺手」：无障碍服务 + 前台保活 + 局域网 HTTP | Kotlin，零第三方依赖，minSdk 24 |
| `skill/手机控制/` | 电脑端命令行 `ph`：读屏、点击、批量、截图、OCR | Python 3.9+，可选 uiautomator2 |
| `docs/` | 协议契约、接入指引、机型能力阶梯 | — |

两端靠 `docs/PROTOCOL.md` 里的 HTTP 契约耦合，**必须同版本使用**。

---

## 快速开始

**1. 装 App**

```bash
adb install -r 顺手.apk          # 或把 APK 传到手机点开安装
```

**2. 打开「顺手」，记下屏幕上显示的地址**（形如 `192.168.3.48:8712`），点「生成 token」并把那串字符存到电脑：

```bash
mkdir -p ~/.phone && printf '%s' '<token>' > ~/.phone/agent_token
```

**3. 开启无障碍**：设置 → 无障碍 → 已下载的应用 → 顺手 → 打开。
HyperOS/MIUI 若提示"受限设置"，见 [`docs/INSTALL.md`](docs/INSTALL.md) 第 4 步。

**4. 电脑端找手机**

```bash
cd skill/手机控制
pip install uiautomator2          # 可选：装了会更快；不装也能用
python scripts/phone.py agent-find --save
```

**5. 用起来**

```bash
python scripts/phone.py ui --summary
python scripts/phone.py tap 设置
python scripts/phone.py type 你好
python scripts/phone.py shot a.png --scale 0.6
```

---

## 常用命令

```
ph ui [--summary] [--filter 词] [--clickable]   看屏幕上有什么
ph tap <文字> [--nth N] [--list]                按文字/描述/id 点
ph type <文字>                                   输入（中文也行，不用切输入法）
ph scroll down|up [次数]                         滑动
ph key back|home|recents                         按键
ph shot a.png [--scale 0.6] [--crop x1,y1,x2,y2] 截图
ph fp [--wait 8]                                 页面指纹；--wait 等页面变化
ph batch steps.txt                               多步一次跑完，失败即停
ph agent-find [--save]                           扫描局域网找手机
ph agents                                        列出已登记的手机
```

完整命令与选择器规则见 [`skill/手机控制/SKILL.md`](skill/手机控制/SKILL.md)。

---

## 多台手机

每台手机可以在 App 里起别名（如「客厅机」）。电脑端：

```bash
ph agent-find --save          # 扫一遍，全部登记到 ~/.phone/agents.json
ph agents                     # 看已登记的有哪些
ph -d 客厅机 ui --summary      # 点名用哪台
ph -d 0a11ec36 ui --summary   # 也可以用机器 ID 前缀
```

`-d` 点名但没找到时直接报 `NO_DEVICE`，**绝不会自己改用另一台手机**。

---

## 能力与边界

**能做到**
- 读界面节点树，schema 与 uiautomator 一致（`bounds`、`visible-to-user`、`NAF` 等字段对齐）
- 控件级动作 `performAction`，未确认时降级为坐标手势，仍不确认就明确报 `TAP_UNCONFIRMED`
- 写文本走 `ACTION_SET_TEXT`，写完回读比对，不一致报 `TYPE_UNCONFIRMED`
- 截图走 `AccessibilityService.takeScreenshot()`（API 30+），不需要 MediaProjection 的"共享屏幕"同意框
- 页面指纹（32 字节），用来廉价判断"页面变没变"

**做不到（写在这里，不临场解释）**
- `FLAG_SECURE` 页面（支付、密码）截不到图，这是系统限制
- 纯血鸿蒙 HarmonyOS NEXT 与 iOS 不支持
- Android 7.0 以下没有手势注入，只能控件级点击
- 功能键（音量/电源）、启动 App、装 APK 仍需要 adb —— 端侧只实现了 `back/home/recents/notifications`

---

## 安全与隐私

- token 只存在手机本地和 `~/.phone/agent_token`，**不进命令行、不打印、不入库**
- 设计用途是**同一个可信局域网内、操控自己的设备**；不要把端口映射到公网
- 无障碍服务的存在对已安装应用可见（`getEnabledAccessibilityServiceList()` 能枚举到），
  这是产品边界，本项目不承诺"不被识别"，也不做任何检测规避

---

## 目录结构

```
app/                  Android 工程（Gradle）
  app/src/main/java/com/zpq/phoneagent/
    AgentService.kt   无障碍服务 + HTTP 服务端 + 动作执行
    UiTree.kt         节点树 → 与 uiautomator 同 schema 的 XML
    MainActivity.kt   界面（显示本机地址、状态、别名）
skill/手机控制/       电脑端技能包
  scripts/phone.py    全部 CLI
  SKILL.md            命令表与踩坑记录
docs/
  PROTOCOL.md         两端共同的 HTTP 契约
  INSTALL.md          8 步接入一台新手机
  COMPATIBILITY.md    机型能力阶梯
```

---

## 文档

- [协议契约](docs/PROTOCOL.md)
- [接入新手机](docs/INSTALL.md)
- [机型兼容性](docs/COMPATIBILITY.md)
- [命令表与踩坑](skill/手机控制/SKILL.md)

---

## License

MIT
