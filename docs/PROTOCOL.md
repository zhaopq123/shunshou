# 端侧 Agent 协议 v0.1（M1）

被控端：手机上的 `com.zpq.phoneagent`（无障碍服务 + 前台服务 + 局域网 HTTP）
控制端：PC 的 `phone.py --transport agent`
端口：**8712**（`AgentService.PORT`）

## 0. 这个协议要解决的四件事（都是实测逼出来的，不是设计偏好）

| 实测事实 | 协议里的对应约束 |
|---|---|
| 一次性 `uiautomator dump` 在抖音页 **0/5 全败、每次白等 144s**；常驻服务 **5/5 成功、344 节点** | 读树只走常驻服务的 `/ui`，不再有"等 idle"这条失败路径 |
| 缩放帧回算误差 p95 ≤2px，但**漏除一次 scale 就偏 320px**，形态像"机型不支持" | `/frame` 必须同时回 `img_*`/`ui_*`/`scale`；PC 侧回算**只准走 `to_ui_px()`**；不变式违反即 `SCALE_UNVERIFIED` 拒绝执行 |
| 一屏 141 行里 **12~13% 与其他行同名**，按文字匹配会点到另一行（偏 844~930px） | 禁止用文字串作唯一匹配键；同名命中 >1 → `AMBIGUOUS_MATCH`，不许"取第一个" |
| 闲鱼 71%、抖音 60% 的可点项**只有 content-desc 或文字在子节点** | 选择器 desc 优先；`textFromSubtree` 聚合子树文字（E6 第一版因只看自身文字把闲鱼误判成"100% 无文字"） |

## 1. 鉴权

请求头 `X-Agent-Token: <token>`（或 `Authorization: Bearer <token>`）。
token 为空时**不鉴权**——这是 M1 期为局域网自测做的刻意选择，`/selftest` 会把它列为警告；M2 向导强制设置。

## 2. 读

### `GET /ui`
成功：`Content-Type: text/xml`，正文是**与 uiautomator dump 同 schema 的 XML**：
```
<hierarchy rotation="0">
  <node NAF="true" index="0" text="" resource-id="..." class="android.widget.FrameLayout"
        package="com.taobao.idlefish" content-desc="我想要发布"
        checkable="false" checked="false" clickable="true" enabled="true"
        focusable="true" focused="false" scrollable="false" long-clickable="false"
        password="false" selected="false" visible-to-user="true"
        bounds="[40,1520][200,1600]" drawing-order="3" hint="" display-id="0" />
</hierarchy>
```
响应头恒带 `X-Epoch`、`X-Pkg`。

> **契约红线**：字段名、`bounds="[x1,y1][x2,y2]"` 格式、节点遍历顺序必须与 uiautomator 一致。
> phone.py 的 `center()` 按这个正则解析，`nodes()/fmt()/select_nodes()/act()/BatchRunner` 和上层输出格式
> （`%2d%s[%s] label @x,y` 与尾行 `# shown/total 节点 前台=pkg`）都**不许变**——变了就要重写业务侧。

#### 2.1 内容口径（v5 用三方实测重定，上一版口径是错的）

| 请求 | flag `INCLUDE_NOT_IMPORTANT_VIEWS` | 不可见节点（`visible-to-user=false`） | 实测节点数（小米内容中心页） |
|---|---|---|---|
| `GET /ui`（默认） | 开（常驻） | **丢掉** | **144** ＝ u2 参照 144 |
| `GET /ui?full=1` | 开 | 保留 | 209 |
| v4 及以前（flag 关） | 关 | 保留 | 87（容器被框架扁平化） |

判定依据（2026-11-02，同屏背靠背取数）：
- u2 参照树的 `visible-to-user=false` 节点数恒为 **0**（4 份 dump：156/130/344/144 节点全部如此）⇒ u2 = **flag 开 + 只留可见**。
- 默认树与 u2 逐节点对比：144 vs 144，仅差新闻滚动条的 2 条时效文本（时机，不是漏读）。
- `index` / `drawing-order` 的**编号方式**尚未与 u2 对齐（u2 的子节点 index 是 `i+1`、drawing-order 是父内序号；
  我们是父内从 0 起的稠密计数）。**phone.py 不读这两个字段**（只按文档顺序 + 自己的 `i` 枚举），
  所以这是已知的外观性偏差，不是契约破洞；改它没有可验证的收益，暂不动。

#### 2.2 `NAF` 属性

uiautomator 的标注（Not Accessibility Friendly），**不参与任何动作决策**，但属 schema 契约。

判定规则（从 4 份参照 dump 的 21 个 NAF 节点反推并全量回验，命中 21/21、误报 0）：
```
clickable=true ∧ text 为空 ∧ content-desc 为空 ∧ 整棵子树里也没有任何 text / content-desc
```
交接文档里"只标叶子节点"的说法**是错的**：那条在抖音那棵树只中 4/9，漏掉的正是
"可点容器、自身无字、子节点也全是无字图标"这一类——恰恰是 NAF 想标记的典型。

诚实边界：样本只有 21 个 NAF 节点、4 棵树，**不声称对所有 App 100% 通用**；它是 best-effort 标注。

失败（`Content-Type: application/json`，HTTP 400）：
| code | 含义 | 必须怎么读 |
|---|---|---|
| `NO_ROOT` | 读不到当前窗口树 | **不等于"页面没内容"** |
| `SECURE_WINDOW` | `FLAG_SECURE` 窗口（支付/密码页） | 明确区分于空页面；此页两种通道截图都是黑的 |
| `PARTIAL_TREE` | 只读到部分（带 `coverage`） | 覆盖率不达标时上层应改走视觉或人眼 |
| `AGENT_OFFLINE` | 服务没跑（HTTP 连不上） | 检查无障碍开关与前台服务 |

### `GET /state`
`{pkg, activity, epoch, last_event_ms, screen:{w,h,density,font_scale}}` —— 事件驱动维护，
**不需要**像 adb 路径那样每次起一条 `dumpsys window | grep`（那在无线 adb 上是 1~2s/次）。

⚠️ `pkg` 的语义（v6 起）：**应用窗口**的包名，不是输入法/状态栏的包名。
取法是从 `windows` 里挑 `type=TYPE_APPLICATION && isActive` 的那扇窗。
上一版直接用事件流维护的 `lastPkg`，输入法一弹出来就变成 `com.baidu.input_mi`，
`expect-pkg` 这类断言全部误判（实测）。拿不到应用窗口时才退回 `lastPkg`。

### `GET /caps`
该机能力档案（api/release/model/brand/ui 尺寸/density/font_scale/是否支持 gesture/set_text/http_error/fg_error，
v5 起加 `flag_include_not_important`、`default_tree`）。
新设备接入跑 `ph agent-doctor` 时以它为准，**按能力分支、不按机型分支**。
`build` 字段是"装的到底是哪一版"的凭据——**每次改源码必须 bump**，否则分不清新包旧包。

### `GET /selftest`
```
{"pass":false,"port":8712,"checks":[
  {"name":"service_enabled","pass":false,"detail":"设置里没开启（或被 ROM 关闭）"},
  {"name":"root_readable","pass":true,"detail":"读到一棵树"},
  ... ]}
```
断言项（v5）：`service_enabled`、`windows_enum`、`root_readable`、`foreground_known`、`fg_service`、
`http_bound`、`ui_size_known`、`event_types_configured`、`important_flag_toggle`。
M2 追加：投影帧非纯黑、缩放帧回算自洽（误差 >1px 即红）、页面指纹比对能变红。

两条判据的**语义边界**（上一版在这里出过假红，别再改回去）：
- `event_types_configured` 只断言 `serviceInfo.eventTypes` 含 `TYPE_WINDOW_STATE_CHANGED | TYPE_WINDOW_CONTENT_CHANGED | TYPE_WINDOWS_CHANGED`。
  这条**连假红两版**才定下来：`epoch > 1` 在静止屏幕恒红；`last_event_ms > 0` 同样恒红
  （重装后实测 7 秒仍为 0 —— 静止屏幕根本不产生事件）。只读自检不能依赖"恰好有人碰过屏幕"。
  "事件在**持续**推"必须造事件才能验 → 放主机侧 A4（swipe 触发后 `e2 > e0`）。
  `last_event_ms` 仍会回在 detail 里，但**不作为判据**，只作诊断参考。
- `important_flag_toggle` 只回读 `serviceInfo.flags`，能证明"字段被接受、没抛异常"，
  **不能证明 ROM 真的按新 flag 重枚举了视图**。后者看节点数（默认 144 vs full 209 这类差值）。
  detail 里已如实写明这条边界。

**跑不起来的闸门等于没有闸门**：`/selftest` 必须能自己变红。

## 3. 写（三段式，写动作没有回读确认就不算成功）

### `POST /act {index, action, expect_epoch?}`
`index` = **`GET /ui`（默认口径）里的文档顺序**，0 起。服务端的 `nthNode` 与 `/ui` 共用
`UiTree.sequence(forest, includeInvisible=false)`，**同一个过滤条件**——v5 起两边都丢不可见节点，
所以序号对齐的是"默认树"，不是 `full=1` 树。**拿 `full=1` 的序号来调 `/act` 会点错**，这是已知陷阱。
`action ∈ click | long_click | scroll_forward | scroll_backward | focus`
1. `performAction(...)` —— **不经过坐标**，因此没有坐标误差这一项
2. 回读确认（epoch 变化 / 前台变化 / 节点失效）
3. 未确认 → 降级 `dispatchGesture` @中心（80ms 按压，`Path` 带 0.5px 位移避开"零时长被 WebView 忽略"）→ 再回读
4. 仍未确认 → **`TAP_UNCONFIRMED`**，附 `index/x/y/pkg/epoch/hint`，**绝不返回 OK**
其他返回：`STALE_TREE`（epoch 不符，拒绝执行）、`NO_SUCH_NODE`、`UNACTIONABLE`（节点面积 0，不吐中心点）、`ACTION_UNSUPPORTED`

### `POST /tap {x, y, ms?, expect_epoch?, provenance?}`
坐标**必须是 UI 像素**。越界 → `OUT_OF_SCREEN`（附 `ui` 尺寸与"请用 to_ui_px() 换算"提示）。
成功返回 `provenance`（`node_center`/`ocr_text`/`template_match`/`vlm_guess`）。

### `POST /swipe {x1,y1,x2,y2,ms}` · `POST /key {back|home|recents|notifications}` · `POST /text {text, index?}`
`/text` 用 `ACTION_SET_TEXT` 后**回读节点文本**比对，不一致 → `TYPE_UNCONFIRMED`；无可编辑焦点 → `NO_EDITABLE`。
目标节点优先取**应用窗口**里 `FOCUS_INPUT` 的可编辑节点，并且扫森林时跳过输入法窗口——
上一版会写进输入法自己的输入框，回读还"一致"，但 App 的搜索框一个字都没进（实测）。

`/key`（v7 起）**等前台真的变了再返回**（最多 900ms），响应带
`{key, performed, pkg_before, pkg_after, waited_ms}`。
不等的话，客户端紧接着读 `/state` 会拿到旧包名：实测 `batch` 里 `key:back` 后紧跟
`expect-pkg:com.miui.home` 直接误判失败（adb 路径碰巧没事，因为 `input keyevent` 自己就要 300~500ms）。

## 4. 坐标系与帧（v8 已实现）

### `GET /frame[?scale=0.6]`

成功：`Content-Type: image/png`，正文是 PNG 字节。响应头：
`X-Ui-W / X-Ui-H`（UI 逻辑像素）、`X-Img-W / X-Img-H`（图片像素）、`X-Scale`。
不变式 `img == ui × scale` 由服务端保证（它就是按 scale 缩的）。

实现用的是 **`AccessibilityService.takeScreenshot()`（API 30+）**，不是 MediaProjection：
- 不需要每次弹"开始录制/共享屏幕"的同意框，也不需要 adb；
- 前提是配置里写了 `android:canTakeScreenshot="true"`——**漏了这一行运行时会报
  `Services don't have the capability of taking the screenshot.`**（实测踩过）；
- API < 30 返回 `UNSUPPORTED`，客户端继续走 adb `screencap`；
- `FLAG_SECURE` 页面（支付/密码）会被系统拒绝，返回 `FRAME_FAILED`，这是能力边界不是 bug。

实测（红米 pond，720×1640）：全尺寸 1.24MB / 2.3s；`scale=0.6` 487KB / 0.59s。
PC 侧所有视觉坐标回算只准走一个函数 `to_ui_px()`。
实用下限：**scale ≥ 0.6**（0.5 档实测出现 274px 离群，OCR 已开始合并/拆分行）。

### `GET /fingerprint`

`{fp, nodes, pkg, epoch}`，`fp` = 可见树逐节点 `class|id|text|desc|l,t,r,b` 拼接后的 MD5。
口径与 `/ui` **完全一致**（同一套可见性过滤），否则"页面变了"会和"树变了"对不上。
用途：`wait` 不必每 0.4s 拉整棵树，比 32 字节就知道页面变没变。
客户端（`ph fp`）在 adb 通道下本地算同一套指纹，两条通道实测同屏同值（`53662fea…`，77 节点）。

## 5. epoch 语义

以下任一事件都使 epoch +1：`TYPE_WINDOW_STATE_CHANGED`、`TYPE_WINDOW_CONTENT_CHANGED`、`TYPE_WINDOWS_CHANGED`。
M2 追加覆盖：屏幕旋转、字体/显示大小变更、分屏与折叠态切换。
**任何带 `expect_epoch` 且与当前不符的请求，一律 `STALE_TREE` 拒绝**——这是"零次静默点错"的执行点。

## 6. 审计日志

`/data/.../files/agent_calls.log`，每次请求一行 `#id METHOD path -> CODE ms note`，超 2MB 滚动。
**只记路径与耗时，不记请求体**（里面常有聊天正文与客户信息）。诊断由向导一键导出纯文本。

## 7. 明确不做

不做检测规避（不隐藏 `adb_enabled`、不伪造设备信息、不 root/改机、不抹除无障碍痕迹）。
本 App 的存在**本身对已安装应用可见**（`getEnabledAccessibilityServiceList()` 能枚举到我们的服务 ID 与 capabilities），
这是产品边界，不是缺陷——所以本协议不承诺"不被识别"。
