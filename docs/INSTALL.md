# 8 步接入一台新手机（「顺手」）

目标：装完 `com.zpq.phoneagent` 后，`ph --transport agent` 能读树、能点、能截图，**不依赖 adb**。
全程约 10 分钟；第 4 步是唯一容易卡住的地方，卡住了看文末对照表。

> 前置：手机和电脑在**同一网段**（能互相 ping 通）。不需要 root，不需要开发者选项，不需要数据线。

---

## 1 · 拿到手机 IP

设置 → WLAN → 当前网络 → 查看 IP（形如 `192.168.3.48`）。
路由器里给这台机绑个固定 IP，否则换网段就得改配置。

## 2 · 装 APK

```bash
adb -s <序列号> install -r agent-v8.apk
```
没有 adb 就把 APK 传到手机，用文件管理器点开安装。
HyperOS/MIUI 会拦"未知来源"：按提示允许**文件管理器**安装未知应用。
装完**先别急着开无障碍**，先做第 3 步。

## 3 · 生成访问 token，落到电脑上

打开「顺手」→ 点「生成/清除 token」→ 屏幕上会出现一串字符。

- 手机端：这串就是唯一凭据，**别截图发人**。
- 电脑端：写进文件，**不要写进命令行**（会留在 shell history 和进程列表里）：

```bash
mkdir -p ~/.phone && printf '%s' '<那串token>' > ~/.phone/agent_token
```

> 也可以让 App 不设 token（M1 期局域网自测的刻意选择），但那样 `/selftest` 会标警告，且同网段任何人都能读你的屏幕。

## 4 · 开无障碍（唯一容易卡的一步）

设置 → 无障碍 → 已下载的应用 → **顺手** → 打开。

**HyperOS / MIUI 的"受限设置"分支**：直接打开会被拦，提示"此应用受到限制"。

1. 回到「已下载的服务」列表，点右上角 ⋮ → 「允许受限设置」（不同版本叫法略有差异）
2. 按提示验证锁屏密码
3. 再回去打开「顺手」的开关
4. 弹「允许此应用读取屏幕内容？」→ **允许**

> 判断"到底开没开"**不要**用 `getEnabledAccessibilityServiceList()`：HyperOS 对侧载应用会漏报，
> 服务明明已经绑定、能读树，枚举里却查不到（本项目实测踩过，会误报成没开）。
> 正确做法是读系统设置里那串已启用服务：
> ```bash
> adb shell settings get secure enabled_accessibility_services
> ```
> 输出里应该含 `com.zpq.phoneagent/com.zpq.phoneagent.AgentService`（包名没改，界面上显示的名字是「顺手」）。
> App 自己的 `/selftest` 就是这么判的。

## 5 · 通知权限 + 前台服务

首次打开 App 会请求通知权限 → 允许。没有通知，前台服务在多数 ROM 上活不过几分钟。

## 6 · 电池与自启动白名单

- 设置 → 应用设置 → 顺手 → 省电策略 → **无限制**
- 安全中心 → 自启动管理 → 允许「顺手」
- 最近任务里给 App **加锁**（HyperOS 上滑清后台会连服务一起清）

## 7 · 验证

```bash
export PHONE_AGENT_HOST=192.168.3.48
python phone.py --transport agent dev        # 应打印 agent@192.168.3.48:8712  Redmi ... build=v8-frame
python phone.py --transport agent ui --summary
```

App 内也有自检：浏览器打开 `http://<手机IP>:8712/selftest`（带 token 头），或直接看 App 首页。
9 项全绿才算可用：`service_enabled / windows_enum / root_readable / foreground_known / fg_service /
http_bound / ui_size_known / event_types_configured / important_flag_toggle`。

## 8 · 标定该机能力（可选但建议）

```bash
python phone.py --transport agent ui --summary   # 看枚举到几扇窗
python phone.py --transport agent shot /tmp/a.png --scale 0.6
python phone.py --transport agent fp --wait 3    # 静止屏应打 UI_UNCHANGED
```
结果记进 `docs/COMPATIBILITY.md` 的机型表：读树扇窗数、`support_screenshot`、`toggled` 是否为 1。

---

## 卡住时的对照表

| 现象 | 原因 | 处置 |
|---|---|---|
| `AGENT_OFFLINE` | 不同网段 / App 被杀 / IP 变了 | 手机浏览器开 `http://<IP>:8712/caps` 自测；先解决网络再怀疑代码 |
| `401 BAD_TOKEN` | token 文件不对，或 App 里重新生成过 | 重新取一次，覆盖 `~/.phone/agent_token`（注意别留换行） |
| 无障碍开关打不开、提示"受到限制" | HyperOS 受限设置 | 第 4 步的分支流程 |
| 开关是开的，`/selftest` 仍报 `service_enabled=false` | 枚举 API 漏报侧载应用 | 用 `settings get secure enabled_accessibility_services` 复核，别信枚举 |
| `windows_enum` 只有 1 扇窗 | ROM 忽略了 `flagRetrieveInteractiveWindows` | 记进 COMPATIBILITY 当能力缺失；跨包弹窗要改走 OCR |
| `/frame` 报 `capability` 缺失 | 配置里漏了 `canTakeScreenshot="true"` | 重新打包（这是编译期声明，运行期改不了） |
| 截图全黑 | `FLAG_SECURE` 页面（支付/密码） | 能力边界，无解；这类页面两种通道都截不到 |
| 服务几分钟后自己没了 | 没进电池/自启动白名单 | 第 5、6 步 |
