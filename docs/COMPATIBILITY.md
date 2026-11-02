# 兼容层规格：覆盖"所有安卓机型、系统"的现实做法

## 0. 一句话结论

**不做机型清单，做能力探测。** 每台设备接入时跑一次 `agent-doctor`（约 20 分钟，零代码改动），产出该机能力档案；执行器按档案**分支到能力，不分支到型号**。这样"新机型"= 跑一次标定，不是改一遍代码。

诚实边界：**做不到 100% 覆盖**。能承诺的是下面这个阶梯；阶梯以下与纯血鸿蒙明确不支持，写进档案而不是靠临场解释。

## 1. 能力阶梯（按 Android API 级别，这是硬地板，不由我们决定）

| 能力 | 最低 API | 系统版本 | 拿不到时怎么降级 |
|---|---|---|---|
| 读界面语义树（`rootInActiveWindow` / 节点遍历） | 16 | Android 4.1 | 读不到 → 走截图 + OCR（需下面两项） |
| 控件级动作 `performAction(ACTION_CLICK/LONG_CLICK/SCROLL_*/FOCUS)` | 16 | Android 4.1 | 退 `dispatchGesture`；再不行报 `ACTION_UNSUPPORTED` |
| 截图（MediaProjection） | 21 | Android 5.0 | 无视觉兜底；`/frame` 报 `UNSUPPORTED` |
| 手势注入 `dispatchGesture` | **24** | **Android 7.0** | 只能控件级动作；自绘区（剪映/醒图那类）不可点 |
| 写文本 `ACTION_SET_TEXT` | **24** | Android 7.0 | 退输入法/剪贴板路径；A 类页面仍可点 |
| 前台服务通知渠道（常驻不被杀的常见前提） | 26 | Android 8.0 | 老系统无渠道概念，代码分支 |
| 分区存储（影响文件互传的可见范围） | 29/30 | Android 10/11 | 一律走"公共目录 + MediaStore 注册"，跨版本行为一致 |
| `MANAGE_EXTERNAL_STORAGE`（跨任意路径读写） | 30 | Android 11 | 只用自身目录 + 媒体库；文件互传范围缩小 |
| 通知运行时权限 `POST_NOTIFICATIONS` | **33** | **Android 13** | 必须动态申请，否则前台服务无通知、更易被杀 |
| 前台服务类型 + `FOREGROUND_SERVICE_MEDIA_PROJECTION`；投影每次会话需确认 | **34** | **Android 14** | 会话重连需人点一次同意 → 待实测 **T4** |
| 侧载 APK 的无障碍"受限设置"拦截 | 13/14+（**版本待实测**） | Android 13 起普遍出现 | 向导给图文步骤；AutoJs6 在 ColorOS 15 的同类问题是靠 adb/Shizuku 绕的，我们不绕 |

**结论性口径**：**Android 7.0 及以上 = 全功能**（读、控件动作、手势、写文本、截图）。**Android 5/6 = 可读可点但自绘区点不动、写文本可能不行。** **Android 4.x = 只读 + 控件级点击。** **Android 4.1 以下 = 不支持。**

## 2. 明确不支持（写进档案，不临场解释）

- **纯血鸿蒙 HarmonyOS NEXT（5.0+，去 AOSP）**：不能安装运行 Android APK，也没有 Android 无障碍接口 → **不支持**。EMUI 与 HarmonyOS 2/3/4 兼容 AOSP，按 Android 处理（**待实测**，手上没有设备）。
- **iOS**：Windows 上没有真机自动化通道（`SKILL.md` L245 原话），本方案不提供假支持。
- **已 root/改机的设备**：不做适配，也不借用 root 提升能力。
- **无前台服务权限/用户拒绝无障碍**：直接报能力缺失，不绕过系统弹窗。

## 3. ROM 差异（按现象探测，不按厂商名分支）

代码里**不允许出现 `if brand == "xiaomi"` 这种分支**。改为探测下列现象，各写一条可判定的检查项：

| 现象 | 探测方式 | 缺失时的动作 |
|---|---|---|
| 无障碍服务被系统静默秒关（已记录：ColorOS 15） | 开启后连续 60 分钟轮询 `isEnabled`，含一次重启后复检 | 向导提示对应设置项；档案标 `accessibility_unstable`，写动作默认禁用 |
| 后台被杀 | 杀掉自己 UI 后 30 分钟看服务是否存活 | 引导电池优化白名单 + 自启动；档案标 `fgs_needs_whitelist` |
| "继续安装"厂商弹窗 | 安装时轮询前台包名是否 installer | 复用 `install_apk()` L877 已有逻辑自动点掉 |
| 侧载应用受限设置 | 检查无障碍入口是否可点 | 向导图文步骤 |
| 投影每次要确认 | 断开重连计数 | 档案标 `projection_needs_consent`，改走 adb 截图路径（若在 adb 可用环境） |
| 边缘手势吞点击 | 标定四边带宽（E8 实做） | 执行器自动把目标点平移出带（**校正不是伪造**） |
| 无线调试端口每次变 | 每次现查 `adb mdns services` | 已有约定（`android-variants.md` §1），不缓存端口 |
| ROM 忽略 `FLAG_INCLUDE_NOT_IMPORTANT_VIEWS`（运行时设了也不重枚举） | `/ui?full=1` 尾注 `toggled=0`；或默认树节点数 ≈ 扁平化口径（容器被吃掉） | 档案标 `no_not_important_views`；此时默认树与 u2 **不等价**，业务脚本按"少容器"口径重标，不能假装对齐 |
| 状态栏/导航条窗口读不到（`windows` 只回 1 扇） | `/selftest` 的 `windows_enum` 报"只有 1 扇窗" | 档案标 `single_window`；跨包弹窗（授权框）需改走截图 + OCR |

## 4. 屏幕形态差异（全部走坐标契约，不走特例）

- **一律用 UI 逻辑像素**，且 `img_size / ui_size == scale` 这条不变式在每次取帧时断言，不满足直接报 `SCALE_UNVERIFIED`，**拒绝执行**（这是"看起来像机型不支持"的真根因，见主计划第五节）。
- 分辨率、密度、`font_scale`、显示大小、旋转 90/270、分屏/自由窗口/折叠态 → **任一变化则 `epoch` 递增**，带旧 epoch 的动作一律 `STALE_TREE` 拒绝。
- 不做"按比例缩放坐标"的兼容：比例缩放对折屏/分屏必错，只有重新读树才是对的。
- 平板/折叠屏/多窗口 = **待实测 T5**，没有设备前不宣称支持。

## 5. 手上两台机的覆盖度（这是现有证据，不是承诺）

| 设备 | 系统 | 定位 | 备注 |
|---|---|---|---|
| 红米 2409BRN2CC | **Android 16 / SDK 36**，720×1640，320dpi，font_scale 1.0，无 override | 全功能档 | 实测已读：`wm size/density`、`font_scale`、`dumpsys window init=720x1640` |
| vivo V1901A | **Android 9 / SDK 28**，720×1544 | 7 年跨度对照 + 兼 PG 服务器 | 本轮 adb 未连上，接入后补档案 |

### 5.1 红米（pond）已实测的能力档案（2026-11-02，v5-visible）

| 项 | 实测值 | 判据来源 |
|---|---|---|
| 读树 | 3 扇窗（活动窗 + 系统状态栏 + 导航条） | `/selftest`.windows_enum |
| `FLAG_INCLUDE_NOT_IMPORTANT_VIEWS` | **可用**（`toggled=1`，默认 144 / full 209 节点） | A5 + 节点数差 |
| 默认树 vs u2 | **等价**（内容中心页 144=144、候选 37=37；桌面 19=19） | A3 / A6 |
| 手势与写文本 | `support_gesture=true`、`support_set_text=true` | `/caps` |
| 无障碍服务存活 | 重装 `-r` 后自动重绑，token 与开关保留 | A2 实测 |
| 已知弱项 | 新闻滚动条（`com.miui.newhome:id/tv`）文字每 1~2s 变一次，逐节点对比会稳定差 1 条 | A3 三轮观察 |

**这两台合起来覆盖 Android 9→16 与两个不同 ROM（OriginOS/HyperOS），是很好的跨度对照，但不等于"覆盖了所有机型"**——尤其缺 Android 7/8 老档、缺高密度大屏/平板档、缺华为系。兼容性矩阵要靠接入更多设备填，不靠推理填。

## 6. 接入一台新设备的标准流程（傻瓜式，我全包技术细节）

1. `ph setup` / `ph connect`（USB 最稳；无线现查端口）
2. 浏览器或 `ph agent-setup` 装 APK（HyperOS 安装框自动点掉）
3. 跟首启向导逐项授权（无障碍→通知→电池→悬浮窗→投影→按需文件）
4. `ph agent-doctor`：跑能力阶梯探测 + 边缘带宽标定 + `performAction` 命中率基线 → 结果写 PG 的 device profile
5. `/selftest` 全绿 → 该机可用；任一未过 → 打印"卡在第几项、影响什么、下一步"，**不猜、不静默降级**

验收口径：**新机型接入 ≤20 分钟、不改一行代码**（V15）；**两台已有机型互换跑同一份脚本对跑通过**（V16）。做不到这两条就是设计缺陷，不是机型问题。
