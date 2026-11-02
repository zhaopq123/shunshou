#!/usr/bin/env python3
"""phone.py - Android 手机控制：UI 树 + 按文字点击 + 动作即验证。

设计原则（这是省 token 的全部原因）
  1. 读 UI 树，不读截图。一次 dump ≈ 150~400 token，一张截图 ≈ 1500~2000 token。
  2. 只按文字/描述点，绝不按截图目测坐标点。
  3. 一次调用 = 一个动作 + 结果验证（act / batch），把「做-看-再决定」压成一次往返。
  4. 输出一律短行，成功一行，失败一行原因。

用法
  phone.py setup [--save] [--local]       探环境、写配置（首次跑这个；--local 把 adb 拷到本地盘）
  phone.py connect 192.168.1.23:5555      连无线设备并记住地址（可给多个）
  phone.py dev                           当前设备
  phone.py ime status | install [apk] | set <id>   输入法：查/装 ADBKeyboard/切换
  phone.py ui [--filter 词] [--limit 30] [--summary] [--clickable]
                                         紧凑元素表；--summary 一行看屏
  phone.py tap 开发者选项 [--by auto|id] [--re] [--nth 1] [--list] [--ms 800]
                                         按文字/描述/id 点；--list 先列同名候选
  phone.py tapn 5                        按上次 ui 的序号点（用缓存，不重新 dump）
  phone.py find 电池 [--by text] [--tap] [--max 6]  自动下滑找到再点
  phone.py act "tap-text:x" --expect-pkg pkg       动作 + 验证一次完成
  phone.py batch steps.txt               多步一次跑完（最省 token）
  phone.py wait 生成完成 30
  phone.py key back|home|recents|power|wakeup|enter|delete
  phone.py type "中文" / "abc123"         ASCII 走 input text，中文走 ADBKeyboard
  phone.py scroll down 3 / scroll up 1    按屏幕比例滑，任意分辨率通用
  phone.py drag 360,1100 360,700 --ms 300 拖拽/滑动手势
  phone.py shot a.png --crop 0,1450,720,1640 --scale 0.6
  phone.py install app.apk               装 APK（厂商确认框自动点）
  phone.py stop 包名 / app 包名           强制停止 / 启动
  phone.py open URL                      默认应用打开链接
  phone.py focus / sh "任意 shell"

多设备时用 -d 指定：-d 序列号、-d 别名、-d 型号子串
一切坐标都由 UI 树或屏幕尺寸算出来，脚本内没有写死的机型/分辨率/IP。

batch 文件每行一条指令，顺序执行，首个失败即停（--keep-going 继续）
  tap:设置
  wait:更多设置 5
  expect-pkg:com.android.settings
  key:back
  scroll:down 2
  type:你好
  shot:a.png
  app:com.lemon.lv
  sh:getprop ro.product.model

配置优先级：命令行 > 环境变量 > ~/.phone/config.json > 自动探测
  PHONE_ADB / PHONE_KEYDIR / PHONE_ADB_PORT / PHONE_SERIAL

通道（--transport，默认 auto）
  auto   配了 agent_host 且端侧服务连得上就走 HTTP，否则走 adb/u2（老行为）
  agent  强制走端侧 HTTP：手机上的 com.zpq.phoneagent 常驻服务，不依赖 adb 与开发者选项
  adb    强制走 adb/u2（与旧版完全一致）
  agent 地址：PHONE_AGENT_HOST / PHONE_AGENT_PORT，或 config.json 的 agent_host / agent_port
  token：~/.phone/agent_token 或 AGENT_TOKEN 环境变量（不进命令行、不打印）
"""
import argparse
import hashlib
import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
CFG_PATH = os.path.join(os.path.expanduser("~"), ".phone", "config.json")
OCR_PS1 = os.path.join(HERE, "ocr.ps1")


def cache_path(ctx):
    """每个设备一份 UI 缓存：多机并发时不会互相顶掉。"""
    serial = re.sub(r"[^A-Za-z0-9_.-]", "_", ctx.serial or "unknown")
    return os.path.join(os.path.expanduser("~"), ".phone", "last_ui_%s.json" % serial)


_U2_CACHE = {}
_U2_FAILED = set()


def u2_device(ctx):
    """可选快路：装了 uiautomator2 就走常驻服务（dump ~0.4s）；没装/失败则返回 None。
    关闭方式：PHONE_NO_U2=1。首次连接自动往手机推 u2.jar 并起服务（3.x 不再用 atx-agent）。"""
    if os.environ.get("PHONE_NO_U2") or not ctx.serial:
        return None
    if ctx.serial in _U2_CACHE:
        return _U2_CACHE[ctx.serial]
    if ctx.serial in _U2_FAILED or importlib.util.find_spec("uiautomator2") is None:
        return None
    try:
        os.environ["ADBUTILS_ADB_PATH"] = ctx.adb_bin or ""
        os.environ["ANDROID_ADB_SERVER_HOST"] = "127.0.0.1"
        os.environ["ANDROID_ADB_SERVER_PORT"] = str(ctx.port)
        if ctx.keydir:
            home = os.path.dirname(os.path.abspath(ctx.keydir))
            os.environ["HOME"] = home
            os.environ["USERPROFILE"] = home
        import uiautomator2 as u2
        d = u2.connect(ctx.serial)
        _U2_CACHE[ctx.serial] = d
        return d
    except Exception:
        _U2_FAILED.add(ctx.serial)
        return None

ADB_CANDIDATES = [
    os.path.expanduser("~/.phone/platform-tools/adb.exe"),
    os.path.expanduser("~/.phone/platform-tools/platform-tools/adb"),
    r"C:\platform-tools\adb.exe",
    r"C:\platform-tools\adb",
    "/usr/local/bin/adb",
    "/usr/bin/adb",
    os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"),
    os.path.join(HERE, "platform-tools", "adb.exe"),
    os.path.join(HERE, "platform-tools", "adb"),
]
# 不写死任何 IP：联网地址从 config.json 的 connect 或 PHONE_CONNECT 环境变量来
DEFAULT_CONNECT = []
# 别名只是提示，--device 也接受序列号 / 型号子串
MODELS = {}  # 可选别名表，按自己设备填，如 {"pixel": ["Pixel 7"]}

# ---------------------------------------------------------------- 端侧 agent 通道
# 手机上的 com.zpq.phoneagent 起了一个局域网 HTTP（默认 8712），读树/动作全走它，
# 不经过 adb、也不经过 u2 的 instrumentation。协议见 TASK-20261101-PHONEAGENT/docs/PROTOCOL.md。
# 为什么要有第三条路：抖音那类页面一次性 `uiautomator dump` 实测 0/5 全败（每次白等 144s），
# 常驻无障碍服务却 5/5 成功——这不是"更快"，是"另一条路根本读得到"。
AGENT_PORT_DEFAULT = 8712
AGENT_TOKEN_FILE = os.path.join(os.path.expanduser("~"), ".phone", "agent_token")
AGENTS_PATH = os.path.join(os.path.expanduser("~"), ".phone", "agents.json")


def load_agents():
    """已登记的手机列表（多机时用 -d 别名/型号/IP 选）。文件不存在就当空表。"""
    try:
        with open(AGENTS_PATH, encoding="utf-8") as f:
            v = json.load(f)
        return v if isinstance(v, list) else []
    except Exception:
        return []


def save_agents(items):
    os.makedirs(os.path.dirname(AGENTS_PATH), exist_ok=True)
    with open(AGENTS_PATH, "w", encoding="utf-8") as f:
        json.dump(items, f, ensure_ascii=False, indent=2)


def pick_agent(ctx):
    """选一台手机，返回 (host, port) 或 None。
    优先级：PHONE_AGENT_HOST > -d 命中登记表 > config.agent_host > 登记表里只有一台。
    **-d 明确点名但登记表里没有 → 返回 None（退回 adb），绝不"随便挑一台"** ——
    十台同型号手机时，猜错就是对着别人家的手机点。"""
    env_host = (os.environ.get("PHONE_AGENT_HOST") or "").strip()
    if env_host:
        return env_host, int(os.environ.get("PHONE_AGENT_PORT") or AGENT_PORT_DEFAULT)
    items = load_agents()
    want = (getattr(ctx, "want_device", None) or "").strip().lower()
    if want and items:
        for a in items:
            keys = {str(a.get("alias", "")).lower(), str(a.get("model", "")).lower(),
                    str(a.get("host", "")).lower()}
            aid = str(a.get("android_id", "")).lower()
            if want in keys or (aid and aid.startswith(want)):
                return a.get("host"), int(a.get("port") or AGENT_PORT_DEFAULT)
        return None
    cfg_host = (ctx.cfg.get("agent_host") or "").strip()
    if cfg_host:
        return cfg_host, int(ctx.cfg.get("agent_port") or AGENT_PORT_DEFAULT)
    if len(items) == 1:
        return items[0].get("host"), int(items[0].get("port") or AGENT_PORT_DEFAULT)
    return None


def agent_base(ctx):
    """agent 的 base URL。地址由 pick_agent 决定（多机时按 -d 选），不写死任何 IP。"""
    if getattr(ctx, "_agent_addr", None) is None:
        ctx._agent_addr = pick_agent(ctx) or ("", 0)
    host, port = ctx._agent_addr
    if not host:
        return None
    if ":" in str(host) and not str(host).rsplit(":", 1)[1].isdigit():
        host = str(host).rsplit(":", 1)[0]
    return "http://%s:%s" % (host, port)


def agent_token():
    """token 只从文件或环境变量读：不进命令行（会留在 shell history / 进程列表）、不打印。"""
    t = (os.environ.get("AGENT_TOKEN") or "").strip()
    if t:
        return t
    try:
        with open(AGENT_TOKEN_FILE, encoding="utf-8") as f:
            return f.read().strip()
    except Exception:
        return ""


def agent_call(ctx, method, path, obj=None, timeout=25):
    """返回 (status, text)。连不上返回 (0, "AGENT_OFFLINE: ...")，不抛。"""
    base = agent_base(ctx)
    if not base:
        return 0, ("AGENT_OFFLINE: 没配 agent_host（config.json 里加 \"agent_host\": \"192.168.x.x\"，"
                   "或设 PHONE_AGENT_HOST）")
    h = {"X-Agent-Token": agent_token()} if agent_token() else {}
    data = None
    if obj is not None:
        data = json.dumps(obj).encode()
        h["Content-Type"] = "application/json"
    req = urllib.request.Request(base + path, data=data, headers=h,
                                 method="POST" if data is not None else "GET")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:
        return 0, "AGENT_OFFLINE: %s" % e


def agent_get_bytes(ctx, path, timeout=30):
    """二进制取回（截图）。返回 (status, bytes, headers)。"""
    base = agent_base(ctx)
    if not base:
        return 0, b"", {}
    h = {"X-Agent-Token": agent_token()} if agent_token() else {}
    req = urllib.request.Request(base + path, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read(), dict(r.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read(), dict(e.headers or {})
    except Exception as e:
        return 0, ("AGENT_OFFLINE: %s" % e).encode(), {}


def agent_ready(ctx):
    """agent 通道可用性：/caps 能通且 service_running=true。"""
    st, body = agent_call(ctx, "GET", "/caps", timeout=8)
    if st != 200:
        return False, body[:160]
    try:
        caps = json.loads(body)
    except Exception:
        return False, "CAPS_BAD_JSON"
    if not caps.get("service_running"):
        return False, "无障碍服务没在跑（手机上看一眼开关）"
    return True, caps


def use_agent(ctx):
    """本进程是否走 agent 通道。transport=agent 强制；auto 时只要配了 host 且连得上就走。"""
    t = getattr(ctx, "transport", "auto")
    if t == "adb":
        return False
    if t == "agent":
        return True
    return bool(agent_base(ctx)) and agent_call(ctx, "GET", "/caps", timeout=3)[0] == 200


KEYS = {
    "back": 4, "home": 3, "recents": 187, "power": 26, "menu": 82,
    "enter": 66, "delete": 67, "tab": 61, "space": 62,
    "up": 19, "down": 20, "left": 21, "right": 22,
    "wakeup": 224, "sleep": 223, "volume_up": 24, "volume_down": 25,
}

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


# ---------------------------------------------------------------- 配置

def load_cfg():
    try:
        with open(CFG_PATH, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def save_cfg(cfg):
    os.makedirs(os.path.dirname(CFG_PATH), exist_ok=True)
    with open(CFG_PATH, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)


class Ctx:
    def __init__(self, args):
        cfg = load_cfg()
        self.cfg = cfg
        self.adb_bin = (getattr(args, "adb", None) or os.environ.get("PHONE_ADB")
                        or cfg.get("adb") or shutil.which("adb"))
        if not self.adb_bin or not os.path.exists(self.adb_bin):
            for c in ADB_CANDIDATES:
                if os.path.exists(c):
                    self.adb_bin = c
                    break
        keydir = (getattr(args, "keydir", None) or os.environ.get("PHONE_KEYDIR")
                  or cfg.get("keydir"))
        if not keydir:
            # 注意：adb 固定读 <HOME>/.android/adbkey，所以 keydir 必须是 .android 这一层，
            # 它的父目录才是要顶给 USERPROFILE 的假 HOME
            d = os.path.join(os.path.expanduser("~"), ".phone", "home", ".android")
            if os.path.exists(os.path.join(d, "adbkey")):
                keydir = d
        self.keydir = keydir
        port = (getattr(args, "port", None) or os.environ.get("PHONE_ADB_PORT")
                or cfg.get("port"))
        # 自定义密钥目录时默认起独立 server（5041），避免与用户自己的 adb 抢密钥
        self.port = str(port or (5041 if keydir else 5037))
        self.want_device = (getattr(args, "device", None) or os.environ.get("PHONE_SERIAL")
                            or cfg.get("device"))
        self.serial = None
        self.model = "?"
        self._ime_before = None
        # 通道：auto（默认，配了 agent 就优先走它）/ adb / agent。
        # 命令面不变，只是"怎么读树、怎么点"换了条路。
        self.transport = (getattr(args, "transport", None)
                          or os.environ.get("PHONE_TRANSPORT") or "auto").lower()
        self._agent = None      # 惰性求值：只有真正要读/动的时候才探测
        self._agent_addr = None  # (host, port)：多机时由 -d 解析出来

    # adb 环境：Windows 下 adb 从 USERPROFILE\.android 取密钥，改它即可切换身份
    def env(self):
        if not self.keydir:
            return None
        e = dict(os.environ)
        home = os.path.dirname(os.path.abspath(self.keydir))  # keydir = <home>/.android|keys
        e["USERPROFILE"] = home
        e["HOME"] = home
        e["ANDROID_USER_HOME"] = home
        return e

    def raw(self, args, timeout=90, binary=False):
        if not self.adb_bin:
            raise SystemExit("NO_ADB：找不到 adb。装 platform-tools，或 phone.py setup --adb <路径>")
        cmd = [self.adb_bin, "-P", self.port] + list(args)
        p = subprocess.run(cmd, capture_output=True, timeout=timeout, env=self.env())
        if binary:
            return p.stdout
        return p.stdout.decode("utf-8", "replace")

    def sh(self, cmd, timeout=90):
        return self.raw(["-s", self.serial, "shell", cmd], timeout=timeout)


# ---------------------------------------------------------------- 设备

def connect_list(ctx):
    """联网地址来源：PHONE_CONNECT 环境变量 > config.json 的 connect > 空。"""
    out = []
    env = os.environ.get("PHONE_CONNECT", "")
    for s in re.split(r"[,;\s]+", env):
        if s:
            out.append(s)
    for s in (ctx.cfg.get("connect") or DEFAULT_CONNECT):
        if s and s not in out:
            out.append(s)
    return out


def screen_size(ctx):
    """返回 (w, h)，按 Override 优先。任何分辨率都适用，不写死 720x1544。"""
    if getattr(ctx, "_agent", None) is None:
        ctx._agent = use_agent(ctx)
    if ctx._agent:
        st, body = agent_call(ctx, "GET", "/caps", timeout=10)
        if st == 200:
            try:
                caps = json.loads(body)
                if caps.get("ui_w") and caps.get("ui_h"):
                    return (int(caps["ui_w"]), int(caps["ui_h"]))
            except Exception:
                pass
    key = "size_" + (ctx.serial or "")
    st = load_state()
    if st.get(key):
        return tuple(st[key])
    out = ctx.sh("wm size", timeout=20)
    nums = re.findall(r"(\d+)x(\d+)", out)
    if not nums:
        return (1080, 1920)
    w, h = int(nums[-1][0]), int(nums[-1][1])
    st[key] = [w, h]
    save_state(st)
    return (w, h)

def list_devices(ctx):
    out = ctx.raw(["devices", "-l"], timeout=60)
    rows = []
    for line in out.splitlines()[1:]:
        line = line.strip()
        if not line or line.startswith("*"):
            continue
        parts = line.split()
        if len(parts) < 2:
            continue
        info = {"serial": parts[0], "state": parts[1], "model": "", "product": ""}
        for kv in parts[2:]:
            if ":" in kv:
                k, v = kv.split(":", 1)
                info[k] = v
        rows.append(info)
    return rows


STATE_PATH = os.path.join(os.path.expanduser("~"), ".phone", "state.json")


def load_state():
    try:
        with open(STATE_PATH, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def save_state(d):
    try:
        os.makedirs(os.path.dirname(STATE_PATH), exist_ok=True)
        with open(STATE_PATH, "w", encoding="utf-8") as f:
            json.dump(d, f, ensure_ascii=False)
    except Exception:
        pass


def dev_state(ctx, serial):
    """快路：只问 server 认不认这个设备（~0.2s），不触发 mDNS 扫描。"""
    return (ctx.raw(["-s", serial, "get-state"], timeout=20) or "").strip()


def remember(ctx):
    st = load_state()
    st["last"] = ctx.serial
    known = st.get("known") or {}
    known[ctx.serial] = ctx.model
    st["known"] = known
    save_state(st)


def matches_want(ctx, serial, model=""):
    """-d 一旦给了就必须守住：序列号 / 型号子串 / 别名任意命中才算这台。"""
    want = (ctx.want_device or "").lower().strip()
    if not want:
        return True
    hay = ("%s %s" % (serial or "", model or "")).lower()
    if want in hay:
        return True
    alias = MODELS.get(want)
    return bool(alias and any(a.lower() in hay for a in alias))


def pick(ctx, quiet=False):
    want = ctx.want_device
    st = load_state()
    cands = []

    def add(s):
        if s and s not in cands:
            cands.append(s)

    if want:
        if ":" in want or re.fullmatch(r"[A-Za-z0-9]{8,}", want):
            add(want)
        else:
            alias = MODELS.get(want.lower())
            for s, m in (st.get("known") or {}).items():
                if alias and any(a.lower() in (m or "").lower() for a in alias):
                    add(s)
    add(st.get("last"))

    # ① 快路：已知序列号直接问状态（-d 不匹配的不许顶上来）
    for s in list(cands):
        if dev_state(ctx, s) == "device":
            ctx.serial = s
            model = (ctx.sh("getprop ro.product.model", timeout=20) or "").strip() or "?"
            if matches_want(ctx, s, model):
                ctx.model = model
                remember(ctx)
                return True
            ctx.serial = None

    # ② connect 候选地址后再问（连上了也要过 -d）
    for cand in connect_list(ctx):
        if dev_state(ctx, cand) != "device":
            try:
                ctx.raw(["connect", cand], timeout=25)
            except Exception:
                pass
        if dev_state(ctx, cand) != "device":
            continue
        ctx.serial = cand
        model = (ctx.sh("getprop ro.product.model", timeout=20) or "").strip() or "?"
        if matches_want(ctx, cand, model):
            ctx.model = model
            remember(ctx)
            return True
        ctx.serial = None

    # ③ 慢路：列设备（会触发 mDNS 扫描，约 10s）+ mDNS 兜底；match_device 已按 -d 过滤
    r = match_device(ctx, list_devices(ctx))
    if r is None:
        try:
            mdns = ctx.raw(["mdns", "services"], timeout=20)
            for line in mdns.splitlines():
                if "_adb-tls-connect._tcp" in line or "_adb._tcp" in line:
                    m = re.search(r"(\d+\.\d+\.\d+\.\d+):(\d+)", line)
                    if m:
                        ctx.raw(["connect", m.group(0)], timeout=25)
        except Exception:
            pass
        r = match_device(ctx, list_devices(ctx))
    if r is None:
        if not quiet:
            if want:
                print("NO_DEVICE：没找到 -d %s 对应的设备（不自动改用别的手机）" % want)
            else:
                print("NO_DEVICE：手机没连上（插 USB，或先 adb connect <ip>:5555）")
        return False
    ctx.serial = r["serial"]
    ctx.model = r.get("model") or r.get("product") or "?"
    remember(ctx)
    return True


def match_device(ctx, rows):
    want = (ctx.want_device or "").lower()
    ok = [r for r in rows if r["state"] == "device"]
    if want:
        for r in ok:
            if want in (r["serial"] + " " + r.get("model", "") + " " + r.get("product", "")).lower():
                return r
        for alias, models in MODELS.items():
            if want == alias:
                for r in ok:
                    if any(m.lower() in (r.get("model", "") + r.get("product", "")).lower()
                           for m in models):
                        return r
        return None
    ok.sort(key=lambda r: (":" in r["serial"],))   # 优先 USB
    return ok[0] if ok else None


def need(ctx):
    # agent 通道不需要 adb 设备：它的前提是"手机上的服务活着"，不是"adb 连得上"。
    # 这正是端侧通道的意义——开发者选项关掉、无线调试掉线，它照样工作。
    if getattr(ctx, "_agent", None) is None:
        ctx._agent = use_agent(ctx)
    if ctx._agent:
        ok, why = agent_ready(ctx)
        if not ok:
            print("AGENT_OFFLINE: %s" % why)
            sys.exit(2)
        ctx.serial = ctx.serial or "agent"   # 仅用于缓存文件名，不当设备用
        return True
    if ctx.serial:
        return True
    if pick(ctx):
        return True
    sys.exit(2)


# ---------------------------------------------------------------- UI 树

def screen_asleep(ctx):
    out = ctx.sh("dumpsys power | grep -m1 mWakefulness=", timeout=25)
    return "Asleep" in out


def dump_ui(ctx, tries=4, path="/sdcard/_ui.xml", rounds=3):
    """UI 树。这条链上有三个真坑，都在这儿处理掉：

    ① `ERROR: null root node returned by UiTestAutomationBridge` 是**抛硬币级抖动**：
       实测同一屏连测 6 次是 3 成 3 败，跟时间间隔无关。所以重试必须做，且在**手机端循环**做
       （一次 adb 往返跑多次），比在电脑端来回跑快得多。默认 3 轮 × 4 次 = 最多 12 次。
    ② 旧文件必须先删，否则 dump 失败时会读到上一次的界面，据此点下去就是错的。
    ③ 息屏时 dump 100% 失败（报的也是 null root node，很容易误判成页面问题）。
       所以彻底失败时先看是不是睡了，叫醒再来一轮；否则重连 adb（掉线也是同样报错）。
    """
    time.sleep(0.25)
    if os.environ.get("PHONE_FORCE_OCR"):     # 调试/验证视觉兜底用：强制走 OCR 分支
        return None
    # 端侧通道优先：一次 HTTP 往返拿到常驻服务遍历的树，没有 instrumentation 的"null root"抖动。
    # 默认口径已与 u2 对齐（flag 开 + 只留 visible-to-user=true），所以上层 select_nodes 不用改。
    if getattr(ctx, "_agent", None) is None:
        ctx._agent = use_agent(ctx)
    if ctx._agent:
        st, body = agent_call(ctx, "GET", "/ui", timeout=30)
        if st == 200 and "<hierarchy" in body:
            try:
                return ET.fromstring(body)
            except ET.ParseError:
                pass
        # 读不到就是读不到：不偷偷退回 adb（那会变成"看起来走了新通道其实没有"）
        return None
    if not os.environ.get("PHONE_NO_U2"):
        d = u2_device(ctx)
        if d is not None:
            for _ in range(2):
                try:
                    xml = d.dump_hierarchy()
                    if xml and "<hierarchy" in xml:
                        return ET.fromstring(xml)
                except Exception:
                    time.sleep(0.3)
            _U2_FAILED.add(ctx.serial)
    loop = ("rm -f {p}; n=0; while [ $n -lt {n} ]; do "
            "uiautomator dump {p} 2>&1 | grep -q 'dumped to' && break; n=$((n+1)); done")
    for _ in range(rounds):
        ctx.sh(loop.format(p=path, n=tries), timeout=180)
        raw = ctx.raw(["-s", ctx.serial, "exec-out", "cat " + path], binary=True)
        if b"<hierarchy" in raw:
            try:
                return ET.fromstring(raw.decode("utf-8", "replace"))
            except ET.ParseError:
                pass
        if screen_asleep(ctx):
            ctx.sh("input keyevent 224", timeout=20)     # WAKEUP
            time.sleep(1.0)
        else:
            try:
                ctx.raw(["connect", ctx.serial], timeout=25)   # adb 掉线
            except Exception:
                pass
            time.sleep(0.5)
    return None


def center(bounds):
    m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", bounds or "")
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    if x2 - x1 <= 0 or y2 - y1 <= 0:
        return None
    return (x1 + x2) // 2, (y1 + y2) // 2


def nodes(root):
    out = []
    for n in root.iter("node"):
        t = (n.get("text") or "").strip()
        d = (n.get("content-desc") or "").strip()
        rid = (n.get("resource-id") or "").strip()
        clickable = n.get("clickable") == "true"
        c = center(n.get("bounds"))
        if not c:
            continue
        if not (t or d or (rid and clickable)):
            continue
        out.append({"i": len(out), "text": t, "desc": d, "id": rid,
                    "cls": (n.get("class") or "").split(".")[-1],
                    "clickable": clickable, "x": c[0], "y": c[1]})
    return out


def fmt(n):
    label = n["text"] or n["desc"] or n["id"].split("/")[-1]
    kind = "t" if n["text"] else ("d" if n["desc"] else "i")
    flag = "*" if n["clickable"] else " "
    return "%2d%s[%s] %-40s @%d,%d" % (n["i"], flag, kind, label[:40], n["x"], n["y"])


def find_node(ns, needle):
    nl = needle.lower().strip()
    for key in ("text", "desc", "id"):
        for n in ns:
            if n[key] and n[key].lower() == nl:
                return n
    for key in ("text", "desc", "id"):          # 包含匹配，优先可点
        hits = [n for n in ns if n[key] and nl in n[key].lower()]
        for n in hits:
            if n["clickable"]:
                return n
        if hits:
            return hits[0]
    return None


SELECTOR_KEYS = ("auto", "text", "desc", "id", "class")


def select_nodes(ns, needle, by="auto", regex=False, clickable_only=False):
    """统一选择器：默认 auto=文字/描述/id 一起找，先精确、退化到包含；regex=True 按正则。
    可点节点排前面，其余保持树序。返回全部命中，由调用方决定点第几个（--nth）或列出来（--list）。"""
    key = by if by in SELECTOR_KEYS else "auto"
    needle = (needle or "").strip()
    if not needle:
        return []
    pat = None
    if regex:
        try:
            pat = re.compile(needle, re.I)
        except re.error:
            return []

    def fields(n):
        if key == "auto":
            return [n["text"], n["desc"], n["id"]]
        return [n.get(key) or ""]

    def match(n):
        vals = [v for v in fields(n) if v]
        if pat is not None:
            return any(pat.search(v) for v in vals)
        return any(needle.lower() in v.lower() for v in vals)

    hits = [n for n in ns if match(n) and (not clickable_only or n["clickable"])]
    if not regex:
        exact = [n for n in hits
                 if any((v or "").strip().lower() == needle.lower() for v in fields(n))]
        if exact:
            hits = exact
    hits.sort(key=lambda n: (not n["clickable"], n["y"], n["x"]))
    return hits


def save_cache(ctx, ns):
    try:
        path = cache_path(ctx)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            json.dump({"serial": ctx.serial, "ts": time.time(), "nodes": ns}, f,
                      ensure_ascii=False)
    except Exception:
        pass


def load_cache(ctx, max_age=120):
    try:
        with open(cache_path(ctx), encoding="utf-8") as f:
            c = json.load(f)
        if c.get("serial") == ctx.serial and time.time() - c.get("ts", 0) < max_age:
            return c["nodes"]
    except Exception:
        pass
    return None


# ---------------------------------------------------------------- 动作

def press(ctx, x, y, ms=80):
    """真正能点的按压：WebView / 自绘控件会忽略零时长的 input tap，
    同点 swipe 80ms 是真实按压。有 u2 快路时走 d.click / d.long_click。
    agent 通道走 /tap（dispatchGesture，坐标空间就是 UI 像素，不需要换算）。"""
    if getattr(ctx, "_agent", None) is None:
        ctx._agent = use_agent(ctx)
    if ctx._agent:
        st, body = agent_call(ctx, "POST", "/tap", {"x": int(x), "y": int(y), "ms": int(ms)},
                              timeout=30)
        if st == 200:
            return
        # TAP_UNCONFIRMED 也**不**降级去 adb：两套动作混着打会把界面搞成半截状态，
        # 而且掩盖了真正的问题（该节点不可交互 / 这一屏需要真实触摸序列）。
        raise SystemExit("AGENT_TAP_FAILED %s: %s" % (st, body[:160]))
    d = u2_device(ctx)
    if d is not None:
        try:
            if ms > 200:
                d.long_click(x, y, ms / 1000.0)
            else:
                d.click(x, y)
            return
        except Exception:
            _U2_FAILED.add(ctx.serial)
    ctx.sh("input swipe %d %d %d %d %d" % (x, y, x, y, ms), timeout=30)


def send_key(ctx, name):
    """按键统一入口。agent 只实现了 back/home/recents/notifications（performGlobalAction），
    其余键（音量/电源/enter…）退回 adb；adb 也没有就明确报不支持，不假装按过。"""
    if getattr(ctx, "_agent", None) is None:
        ctx._agent = use_agent(ctx)
    if ctx._agent and name in ("back", "home", "recents", "notifications"):
        st, body = agent_call(ctx, "POST", "/key", {"key": name}, timeout=20)
        if st == 200:
            return True
        print("AGENT_KEY_FAILED %s: %s" % (st, body[:160]))
        return False
    code = KEYS.get(name, name if name.isdigit() else None)
    if code is None:
        return False
    if not ctx.adb_bin:
        print("TRANSPORT_UNSUPPORTED: agent 通道只支持 back/home/recents/notifications，"
              "按键 %s 需要 adb（本机没配）" % name)
        return False
    ctx.sh("input keyevent %s" % code, timeout=20)
    return True


def _pkg_from(out):
    for line in (out or "").splitlines():
        m = (re.search(r"([A-Za-z][\w.]*)/[A-Za-z0-9_.$]+", line)
             or re.search(r"u0\s+([A-Za-z][\w.]*)/", line))
        if m:
            pkg = m.group(1)
            if "." in pkg:
                return pkg
    return ""


def fore(ctx):
    """当前前台包名。Android 9~16 输出格式有差异，三条命令依次兜。
    agent 通道直接读 /state 的 pkg（事件流维护的，不用起 shell）。"""
    if getattr(ctx, "_agent", None) is None:
        ctx._agent = use_agent(ctx)
    if ctx._agent:
        st, body = agent_call(ctx, "GET", "/state", timeout=15)
        if st == 200:
            try:
                return json.loads(body).get("pkg") or "?"
            except Exception:
                return "?"
        return "?"
    pkg = _pkg_from(ctx.sh(
        "dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp'", timeout=30))
    if pkg:
        return pkg
    pkg = _pkg_from(ctx.sh(
        "dumpsys activity activities 2>/dev/null | grep -m2 -E 'mResumedActivity|topResumedActivity'",
        timeout=30))
    return pkg or "?"


# ---------------------------------------------------------------- 视觉兜底（Windows 自带 OCR）

def norm_text(s):
    return re.sub(r"\s+", "", s or "").lower()


def ocr_available():
    return sys.platform.startswith("win") and os.path.exists(OCR_PS1)


def ocr_screen(ctx):
    """截图 + 本机 OCR（Windows.Media.Ocr，离线，中文）。返回 {width,height,lines} 或 None。
    用于 Canvas / 游戏 / Flutter 这类 uiautomator 打不出节点的界面。"""
    if not ocr_available():
        return None
    base = os.path.join(tempfile.gettempdir(), "phone_ocr_%d" % os.getpid())
    png, jout = base + ".png", base + ".json"
    try:
        shot = False
        if getattr(ctx, "_agent", None) is None:
            ctx._agent = use_agent(ctx)
        if ctx._agent:
            # 端侧 takeScreenshot：不依赖 adb，也不依赖 u2 的 instrumentation
            st, data, _h = agent_get_bytes(ctx, "/frame", timeout=30)
            if st == 200 and data[:8] == b"\x89PNG\r\n\x1a\n":
                with open(png, "wb") as f:
                    f.write(data)
                shot = True
        d = None if shot else u2_device(ctx)
        if d is not None:
            try:
                d.screenshot().save(png)
                shot = True
            except Exception:
                _U2_FAILED.add(ctx.serial)
        if not shot:
            raw = ctx.raw(["-s", ctx.serial, "exec-out", "screencap -p"], binary=True, timeout=60)
            if not raw:
                return None
            with open(png, "wb") as f:
                f.write(raw)
        subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                        "-File", OCR_PS1, "-Path", png, "-Out", jout],
                       capture_output=True, timeout=90)
        with open(jout, encoding="utf-8-sig") as f:
            return json.load(f)
    except Exception:
        return None
    finally:
        for p in (png, jout):
            try:
                os.remove(p)
            except OSError:
                pass


def ocr_matches(data, needle):
    """OCR 行匹配：去掉所有空白再比（中文 OCR 常在字间插空格）。"""
    if not data:
        return []
    n = norm_text(needle)
    if not n:
        return []
    lines = [l for l in (data.get("lines") or []) if l.get("text")]
    exact = [l for l in lines if norm_text(l["text"]) == n]
    return exact or [l for l in lines if n in norm_text(l["text"])]


def cur_ime(ctx):
    return (ctx.sh("settings get secure default_input_method", timeout=20) or "").strip()


ADB_IME = "com.android.adbkeyboard/.AdbIME"


def is_ascii(s):
    try:
        s.encode("ascii")
        return True
    except Exception:
        return False


def type_text(ctx, text):
    """ASCII 走 input text（快）；中文/emoji 只能走 ADBKeyboard 广播注入。
    用完把输入法切回原来那个——不写死任何厂商输入法包名。
    agent 通道走 ACTION_SET_TEXT（不用输入法，中文/emoji 一视同仁，也不切换 IME）。"""
    if getattr(ctx, "_agent", None) is None:
        ctx._agent = use_agent(ctx)
    if ctx._agent:
        st, body = agent_call(ctx, "POST", "/text", {"text": text}, timeout=30)
        if st == 200:
            return True
        print("AGENT_TYPE_FAILED %s: %s" % (st, body[:160]))
        return False
    if is_ascii(text) and text.strip():
        esc = text.replace(" ", "%s").replace("'", "\\'")
        ctx.sh("input text '%s'" % esc, timeout=40)
        return True
    have = ctx.sh("pm list packages com.android.adbkeyboard", timeout=25)
    if "adbkeyboard" not in have:
        print("NEED_IME：中文/emoji 只能靠 ADBKeyboard 注入，本机还没装。跑：")
        print("  phone.py ime install                 # 用 ~/.phone/ADBKeyboard.apk")
        print("  phone.py ime install D:/x/ADBKeyboard.apk")
        return False
    before = cur_ime(ctx)
    esc = text.replace("\\", "\\\\").replace("'", "\\'")
    ctx.sh("ime enable %s; ime set %s; sleep 0.35; "
           "am broadcast -a ADB_INPUT_TEXT --es msg '%s' >/dev/null; sleep 0.35"
           % (ADB_IME, ADB_IME, esc), timeout=90)
    if before and before != ADB_IME:
        ctx.sh("ime set %s" % before, timeout=25)
    return True


def scroll(ctx, direction="down", times=1):
    """按屏幕高度的比例滑，任何分辨率都适用（不写死 720x1544 的坐标）。"""
    w, h = screen_size(ctx)
    x = w // 2
    if getattr(ctx, "_agent", None) is None:
        ctx._agent = use_agent(ctx)
    d = None if ctx._agent else u2_device(ctx)
    for _ in range(max(1, times)):
        if direction == "up":
            y1, y2 = int(h * 0.32), int(h * 0.82)
        else:
            y1, y2 = int(h * 0.82), int(h * 0.32)
        if ctx._agent:
            ms = int(200 + h * 0.06)
            st, body = agent_call(ctx, "POST", "/swipe",
                                  {"x1": x, "y1": y1, "x2": x, "y2": y2, "ms": ms}, timeout=30)
            if st != 200:
                print("AGENT_SWIPE_FAILED %s: %s" % (st, body[:160]))
            time.sleep(0.5)
            continue
        if d is not None:
            try:
                d.swipe(x, y1, x, y2, (200 + h * 0.06) / 1000.0)
                time.sleep(0.3)
                continue
            except Exception:
                _U2_FAILED.add(ctx.serial)
                d = None
        ctx.sh("input swipe %d %d %d %d %d" % (x, y1, x, y2, int(200 + h * 0.06)), timeout=30)
        time.sleep(0.5)


def act(ctx, target, expect_pkg=None, expect_text=None, timeout=10.0):
    """一次调用 = 动作 + 验证。返回 (ok, 说明)。"""
    before = fore(ctx)
    ok = True
    note = ""
    coord = re.fullmatch(r"\s*(-?\d+)\s*,\s*(-?\d+)\s*", target.split(":", 1)[1]) \
        if ":" in target else None
    if target.startswith("tap-text:") or (target.startswith("tap:") and not coord):
        needle = target.split(":", 1)[1].strip()
        if not needle:
            return False, "BAD_TARGET: tap: 后面要写坐标 x,y 或文字"
        root = dump_ui(ctx)
        if root is None:
            ocr_hits = ocr_matches(ocr_screen(ctx), needle)
            if not ocr_hits:
                return False, "UI_DUMP_FAILED: 这一屏打不出节点（当前 %s），先 key back/home 换屏" % before
            l = ocr_hits[0]
            press(ctx, l["cx"], l["cy"])
            note = "tap-ocr '%s' @%d,%d" % (l["text"][:20], l["cx"], l["cy"])
        else:
            hits = select_nodes(nodes(root), needle)
            if not hits:
                return False, "NOT_FOUND: %s (当前 %s)" % (needle, before)
            node = hits[0]
            press(ctx, node["x"], node["y"])
            note = "tap '%s' @%d,%d" % ((node["text"] or node["desc"] or node["id"])[:20],
                                        node["x"], node["y"])
    elif target.startswith("tap:"):
        x, y = int(coord.group(1)), int(coord.group(2))
        press(ctx, x, y)
        note = "tap %d,%d" % (x, y)
    elif target.startswith("key:"):
        name = target[4:]
        if KEYS.get(name, name if name.isdigit() else None) is None:
            return False, "BAD_KEY: %s" % name
        if not send_key(ctx, name):
            return False, "KEY_FAILED: %s" % name
        note = "key %s" % name
    elif target.startswith("app:"):
        if not ctx.adb_bin:
            return False, "TRANSPORT_UNSUPPORTED: app: 需要 adb（agent 通道没有启动 App 的路由）"
        ctx.sh("monkey -p %s -c android.intent.category.LAUNCHER 1" % target[4:], timeout=30)
        note = "launch %s" % target[4:]
    elif target.startswith("type:"):
        if not type_text(ctx, target[5:]):
            return False, "TYPE_FAILED"
        note = "type %s" % target[5:][:16]
    elif target.startswith("scroll:"):
        d, _, n = target[7:].partition(" ")
        scroll(ctx, d or "down", int(n) if n.strip().isdigit() else 1)
        note = "scroll %s" % (d or "down")
    else:
        return False, "BAD_TARGET: %s" % target[:30]

    # ---- 验证 ----
    if expect_pkg:
        deadline = time.time() + timeout
        cur = fore(ctx)
        while cur != expect_pkg and time.time() < deadline:
            time.sleep(0.6)
            cur = fore(ctx)
        if cur != expect_pkg:
            return False, "%s  但前台=%s，期望 %s" % (note, cur, expect_pkg)
        note += "  前台=%s" % cur
    elif expect_text:
        deadline = time.time() + timeout
        seen = False
        while time.time() < deadline:
            root = dump_ui(ctx, tries=2, rounds=2)
            if root is not None and find_node(nodes(root), expect_text):
                seen = True
                break
            if root is None and ocr_matches(ocr_screen(ctx), expect_text):
                seen = True
                break
            time.sleep(0.5)
        if not seen:
            return False, "%s  但没看到 '%s'" % (note, expect_text)
        note += "  见到 '%s'" % expect_text
    else:
        time.sleep(0.35 if target.startswith("key:") else 1.0)
        cur = fore(ctx)
        note += "  前台=%s%s" % (cur, "" if cur != before else " (没变，未验证)")
        ok = True                      # 没给 --expect-* 就不该判失败：页内跳转本来就同包名
    return ok, note


# ---------------------------------------------------------------- 命令

def cmd_setup(ctx, args):
    if args.local and ctx.adb_bin:
        src = os.path.dirname(ctx.adb_bin)
        dst = os.path.join(os.path.expanduser("~"), ".phone", "platform-tools")
        try:
            if os.path.abspath(src) != os.path.abspath(dst):
                shutil.copytree(src, dst, dirs_exist_ok=True)
                print("已把 platform-tools 复制到 %s（本地盘比网络盘快约 10 倍）" % dst)
                ctx.adb_bin = os.path.join(dst, "adb.exe")
        except Exception as e:
            print("复制失败：%s" % e)
    print("adb      : %s" % (ctx.adb_bin or "未找到"))
    if ctx.adb_bin and re.match(r"^[A-Za-z]:", ctx.adb_bin) is None:
        print("           ⚠ 不在本地盘，每次调用多 1.5s；跑 setup --local")
    print("密钥目录 : %s" % (ctx.keydir or "默认 ~/.android"))
    print("adb 端口 : %s" % ctx.port)
    if ctx.adb_bin:
        try:
            print("版本     : %s" % ctx.raw(["version"]).splitlines()[0])
        except Exception:
            pass
    try:
        has_u2 = importlib.util.find_spec("uiautomator2") is not None
    except Exception:
        has_u2 = False
    print("快路     : %s" % ("uiautomator2 已装（自动启用）"
                            if has_u2 else "未装（可选：pip install uiautomator2）"))
    print("视觉兜底 : %s" % ("Windows OCR 可用" if ocr_available() else "OCR 不可用"))
    rows = list_devices(ctx)
    if not rows:
        for cand in connect_list(ctx):
            ctx.raw(["connect", cand], timeout=25)
        rows = list_devices(ctx)
    print("设备     :")
    for r in rows:
        print("  %-24s %-12s %s" % (r["serial"], r["state"], r.get("model", "")))
    if args.save:
        cfg = load_cfg()
        if ctx.adb_bin:
            cfg["adb"] = ctx.adb_bin
        if ctx.keydir:
            cfg["keydir"] = ctx.keydir
        if args.port:
            cfg["port"] = args.port
        if args.device:
            cfg["device"] = args.device
        save_cfg(cfg)
        print("已写入 %s" % CFG_PATH)
    return 0


def cmd_wake(ctx, args):
    need(ctx)
    if screen_asleep(ctx):
        ctx.sh("input keyevent 224", timeout=20)
        print("WAKE")
    else:
        print("已亮屏")
    return 0


INSTALLER_HINTS = ("packageinstaller", "installer", "permissioncontroller")
INSTALL_CONFIRM = ("继续安装", "安装", "允许", "确定")


def _fore_is_installer(ctx):
    pkg = fore(ctx).lower()
    return any(h in pkg for h in INSTALLER_HINTS)


def install_apk(ctx, apk, timeout=120):
    """pm install 在 vivo / 部分 ROM 上会停在「继续安装」确认框。
    这里让安装进程在后台跑，脚本自己发现确认框并点掉，不再要人肉按手机。
    返回 (是否成功, 输出摘要)。"""
    remote = "/data/local/tmp/" + os.path.basename(apk)
    ime_before = cur_ime(ctx)
    ctx.raw(["push", apk, remote], timeout=180)
    p = subprocess.Popen(
        [ctx.adb_bin, "-P", ctx.port, "-s", ctx.serial, "shell", "pm install -r " + remote],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=ctx.env())
    deadline = time.time() + timeout
    tapped = 0
    while time.time() < deadline and p.poll() is None:
        if _fore_is_installer(ctx):
            root = dump_ui(ctx, tries=2, rounds=1)
            if root is not None:
                ns = nodes(root)
                hit = None
                for label in INSTALL_CONFIRM:
                    hit = find_node(ns, label)
                    if hit and hit["clickable"]:
                        break
                    hit = None
                if hit is not None:
                    press(ctx, hit["x"], hit["y"])
                    tapped += 1
                    time.sleep(1.0)
        time.sleep(1.0)
    if p.poll() is None:
        p.terminate()
    try:
        out = (p.communicate(timeout=10)[0] or b"").decode("utf-8", "replace").strip()
    except Exception:
        p.kill()
        out = ""
    if tapped:
        out += "（自动点了 %d 次安装确认）" % tapped
    # 实测：重装 ADBKeyboard 会把默认输入法顶成它（没有可见键盘，等于手机打不了字）。
    # 装完把用户原来的输入法还原，别留坑。
    if ime_before and ime_before != "null":
        try:
            if cur_ime(ctx) != ime_before:
                ctx.sh("ime enable %s; ime set %s" % (ime_before, ime_before), timeout=25)
                out += "（已还原输入法）"
        except Exception:
            pass
    return p.returncode == 0, out[:200]


def cmd_ime(ctx, args):
    need(ctx)
    if args.action == "status":
        print("当前输入法: %s" % (cur_ime(ctx) or "?"))
        pk = ctx.sh("pm list packages com.android.adbkeyboard", timeout=25).strip()
        print("ADBKeyboard: %s" % ("已装" if "adbkeyboard" in pk else "未装（中文输入不可用）"))
    elif args.action == "install":
        apk = args.apk or os.path.join(os.path.expanduser("~"), ".phone", "ADBKeyboard.apk")
        if not os.path.exists(apk):
            print("找不到 APK: %s（先下载 ADBKeyboard.apk 放到 ~/.phone/）" % apk)
            return 1
        ok, out = install_apk(ctx, apk)
        print(out or "（pm install 无输出）")
        pk = ctx.sh("pm list packages com.android.adbkeyboard", timeout=25).strip()
        installed = "adbkeyboard" in pk
        print("ADBKeyboard: %s" % ("已装" if installed else "未装"))
        return 0 if installed else 1
    elif args.action == "list":
        out = ctx.sh("ime list -s", timeout=25)
        for line in (out or "").splitlines():
            if line.strip():
                print(line.strip())
    elif args.action == "fix":
        cur = cur_ime(ctx)
        if "adbkeyboard" not in (cur or "").lower():
            print("当前输入法正常：%s" % (cur or "?"))
            return 0
        out = ctx.sh("ime list -s", timeout=25)
        cands = [x.strip() for x in (out or "").splitlines()
                 if x.strip() and "adbkeyboard" not in x.lower()]
        if not cands:
            print("NO_IME：手机上没有其他输入法，先装一个（搜狗/百度/Gboard）")
            return 1
        pref = ("sogou", "baidu", "iflytek", "gboard", "google", "swiftkey")
        target = next((c for c in cands if any(p in c.lower() for p in pref)), cands[0])
        ctx.sh("ime enable %s; ime set %s" % (target, target), timeout=30)
        print("已还原输入法：%s" % target)
    elif args.action == "set":
        if not args.apk:
            print("用法：phone.py ime set <完整 IME id>")
            return 1
        ctx.sh("ime enable %s; ime set %s" % (args.apk, args.apk), timeout=30)
        print("已切到 %s" % args.apk)
    return 0


def cmd_connect(ctx, args):
    """连一台无线设备并记住地址，下次免参数。"""
    for a in args.addr:
        print((ctx.raw(["connect", a], timeout=35) or "").strip())
        if dev_state(ctx, a) == "device":
            cfg = load_cfg()
            cl = cfg.get("connect") or []
            if a not in cl:
                cl.append(a)
            cfg["connect"] = cl
            save_cfg(cfg)
            print("已记住 %s（以后自动连）" % a)
    for r in list_devices(ctx):
        print("  %-24s %-12s %s" % (r["serial"], r["state"], r.get("model", "")))
    return 0


def cmd_dev(ctx, args):
    need(ctx)
    if getattr(ctx, "_agent", False):
        # agent 通道下 serial 是占位值，真正该看的是"哪台机、哪个 build"
        st, body = agent_call(ctx, "GET", "/caps", timeout=10)
        try:
            caps = json.loads(body)
            print("agent@%s  %s %s  build=%s  %sx%s"
                  % (agent_base(ctx).replace("http://", ""), caps.get("brand"), caps.get("model"),
                     caps.get("build"), caps.get("ui_w"), caps.get("ui_h")))
            return 0
        except Exception:
            pass
    print("%s  %s" % (ctx.serial, ctx.model))
    return 0


def cmd_devices(ctx, args):
    """列出当前所有已连接设备（多机时先看这个，不会只挑一台）。"""
    rows = list_devices(ctx)
    if not rows:
        for cand in connect_list(ctx):
            ctx.raw(["connect", cand], timeout=25)
        rows = list_devices(ctx)
    if not rows:
        print("NO_DEVICE：一台都没连上")
        return 2
    for r in rows:
        print("%-24s %-10s %s" % (r["serial"], r["state"], r.get("model", "")))
    return 0


def cmd_ui(ctx, args):
    need(ctx)
    root = dump_ui(ctx)
    if root is None:
        print("UI_DUMP_FAILED：已重试 3 轮。设置搜索页/键盘弹出/视频播放这类界面常打不出节点，"
              "先 phone.py key back 或 key home 换一屏再试。")
        return 3
    ns = nodes(root)
    save_cache(ctx, ns)
    if args.summary:
        labels = []
        for n in ns:
            if not n["clickable"]:
                continue
            label = (n["text"] or n["desc"] or n["id"].split("/")[-1])[:14]
            if label and label not in labels:
                labels.append(label)
        print("前台=%s | 节点=%d | 可点: %s"
              % (fore(ctx), len(ns), " / ".join(labels[:12]) or "无"))
        return 0
    flt = (args.filter or "").lower()
    shown = 0
    for n in ns:
        if args.clickable and not n["clickable"]:
            continue
        if flt and flt not in (n["text"] + n["desc"] + n["id"]).lower():
            continue
        print(fmt(n))
        shown += 1
        if shown >= args.limit:
            print("# 还有更多，加 --limit")
            break
    print("# %d/%d 节点  前台=%s" % (shown, len(ns), fore(ctx)))
    return 0


def cmd_tap(ctx, args):
    need(ctx)
    text = " ".join(args.text)
    root = dump_ui(ctx)
    if root is None:
        if args.by in ("auto", "text"):
            data = ocr_screen(ctx)
            ocr_hits = ocr_matches(data, text)
            if ocr_hits:
                if args.list:
                    for l in ocr_hits[:8]:
                        print("  [t] %-30s @%d,%d" % (l["text"][:30], l["cx"], l["cy"]))
                    print("# %d 个 OCR 匹配（--nth 选第几个）" % len(ocr_hits))
                    return 0
                if args.nth >= len(ocr_hits):
                    print("BAD_INDEX (OCR 只找到 %d 个)" % len(ocr_hits))
                    return 4
                l = ocr_hits[args.nth]
                ms = max(1, int(args.ms or 80))
                press(ctx, l["cx"], l["cy"], ms)
                print("TAPPED(OCR) %s @%d,%d" % (l["text"][:24], l["cx"], l["cy"]))
                return 0
        print("UI_DUMP_FAILED：已重试 3 轮，这一屏打不出节点（键盘弹出/视频/自绘界面），"
              "先 key back 或 key home 换屏再试")
        return 3
    hits = select_nodes(nodes(root), text, args.by, args.re, args.clickable)
    if not hits:
        print("NOT_FOUND: %s" % text)
        return 4
    if args.list:
        for n in hits[:8]:
            print(fmt(n))
        print("# %d 个匹配（--nth 选第几个）" % len(hits))
        return 0
    if args.nth >= len(hits):
        print("BAD_INDEX (有 %d 个)" % len(hits))
        return 4
    n = hits[args.nth]
    ms = max(1, int(args.ms or 80))
    press(ctx, n["x"], n["y"], ms)
    print("TAPPED %s @%d,%d%s" % ((n["text"] or n["desc"] or n["id"])[:24], n["x"], n["y"],
                                  ("  长按 %d ms" % ms) if ms > 200 else ""))
    if len(hits) > 1 and args.nth == 0:
        print("# 另有 %d 个同名，--list 看候选 / --nth 指定" % (len(hits) - 1))
    return 0


def cmd_ocr(ctx, args):
    need(ctx)
    data = ocr_screen(ctx)
    if not data:
        print("OCR_UNAVAILABLE：Windows 自带 OCR 不可用（需要 Windows + 中文语言包）")
        return 7
    if args.json:
        print(json.dumps(data, ensure_ascii=False))
        return 0
    flt = norm_text(args.filter) if args.filter else ""
    lines = data.get("lines") or []
    shown = 0
    for i, l in enumerate(lines):
        if flt and flt not in norm_text(l["text"]):
            continue
        print("%2d [t] %-30s @%d,%d" % (i, l["text"][:30], l["cx"], l["cy"]))
        shown += 1
        if shown >= args.limit:
            print("# 还有更多，加 --limit")
            break
    print("# %d/%d 行  OCR %dx%d  前台=%s"
          % (shown, len(lines), data.get("width", 0), data.get("height", 0), fore(ctx)))
    return 0


def cmd_tap_ocr(ctx, args):
    need(ctx)
    text = " ".join(args.text)
    data = ocr_screen(ctx)
    if not data:
        print("OCR_UNAVAILABLE：Windows 自带 OCR 不可用（需要 Windows + 中文语言包）")
        return 7
    hits = ocr_matches(data, text)
    if not hits:
        print("OCR_NOT_FOUND: %s" % text)
        return 4
    if args.list:
        for l in hits[:8]:
            print("  [t] %-30s @%d,%d" % (l["text"][:30], l["cx"], l["cy"]))
        print("# %d 个匹配（--nth 选第几个）" % len(hits))
        return 0
    if args.nth >= len(hits):
        print("BAD_INDEX (有 %d 个)" % len(hits))
        return 4
    l = hits[args.nth]
    ms = max(1, int(args.ms or 80))
    press(ctx, l["cx"], l["cy"], ms)
    print("TAPPED-OCR %s @%d,%d%s" % (l["text"][:24], l["cx"], l["cy"],
                                      ("  长按 %d ms" % ms) if ms > 200 else ""))
    if len(hits) > 1 and args.nth == 0:
        print("# 另有 %d 个同名，--list 看候选 / --nth 指定" % (len(hits) - 1))
    return 0


def cmd_tapn(ctx, args):
    need(ctx)
    ns = None if args.fresh else load_cache(ctx)
    if ns is None:
        root = dump_ui(ctx)
        ns = nodes(root) if root is not None else []
        save_cache(ctx, ns)
    if args.index >= len(ns):
        print("BAD_INDEX (有 %d 个)" % len(ns))
        return 4
    n = ns[args.index]
    press(ctx, n["x"], n["y"])
    print("TAPPED %s @%d,%d" % ((n["text"] or n["desc"] or n["id"])[:24], n["x"], n["y"]))
    return 0


def find_and_tap(ctx, needle, max_steps=5, direction="down", tap=False,
                 by="text", regex=False, clickable_only=False, nth=0):
    """跨屏找元素（按屏滑，不写死坐标）；返回 (ok, 说明)。"""
    for step in range(max_steps + 1):
        root = dump_ui(ctx)
        if root is not None:
            hits = select_nodes(nodes(root), needle, by, regex, clickable_only)
            if len(hits) > nth:
                n = hits[nth]
                label = (n["text"] or n["desc"] or n["id"])[:20]
                if tap:
                    press(ctx, n["x"], n["y"])
                return True, "%s @%d,%d（%s %d 次）" % (
                    label, n["x"], n["y"], "上滑" if direction == "up" else "下滑", step)
        if step < max_steps:
            scroll(ctx, "up" if direction == "up" else "down")
    return False, "NOT_FOUND: %s（已滑 %d 屏）" % (needle, max_steps)


def cmd_find(ctx, args):
    need(ctx)
    ok, note = find_and_tap(ctx, " ".join(args.text), args.max, args.dir, args.tap,
                            args.by, args.re, args.clickable, args.nth)
    if ok:
        print(("TAPPED " if args.tap else "FOUND ") + note)
    else:
        print(note)
    return 0 if ok else 4


def cmd_act(ctx, args):
    need(ctx)
    ok, note = act(ctx, args.target, args.expect_pkg, args.expect_text, args.timeout)
    print(("OK   " if ok else "FAIL ") + note)
    return 0 if ok else 1


def _subst(val, variables):
    """$name 变量替换；没定义的保持原样。"""
    return re.sub(r"\$(\w+)", lambda m: str(variables.get(m.group(1), m.group(0))), val)


def parse_batch(lines, i=0, stops=()):
    """把步骤行解析成树：if/else/endif、repeat/end、retry/end，可嵌套。
    返回 (nodes, 下一个未消费行号)。"""
    nodes = []
    while i < len(lines):
        raw = lines[i]
        kind, _, val = raw.partition(":")
        kind = kind.strip().lower()
        if any(kind == s for s in stops):
            return nodes, i
        val = val.strip()
        if kind == "if" or kind.startswith("if-"):
            then, i = parse_batch(lines, i + 1, ("else", "endif"))
            els = []
            if i < len(lines) and lines[i].partition(":")[0].strip().lower() == "else":
                els, i = parse_batch(lines, i + 1, ("endif",))
            if i >= len(lines) or lines[i].partition(":")[0].strip().lower() != "endif":
                raise ValueError("if 缺少 endif：%s" % raw)
            nodes.append({"type": "if", "cond": (kind, val), "then": then, "else": els})
            i += 1
        elif kind in ("repeat", "retry"):
            body, i = parse_batch(lines, i + 1, ("end",))
            if i >= len(lines):
                raise ValueError("%s 缺少 end：%s" % (kind, raw))
            try:
                n = int(val)
            except ValueError:
                n = 1
            nodes.append({"type": kind, "n": max(1, n), "body": body})
            i += 1
        else:
            nodes.append({"type": "step", "raw": raw})
            i += 1
    if stops:
        raise ValueError("块没有闭合（缺少 %s）" % "/".join(stops))
    return nodes, i


class BatchRunner:
    """batch 解释器：变量、条件、循环、重试；硬失败才算失败，WARN 不算。"""

    def __init__(self, ctx, args):
        self.ctx = ctx
        self.args = args
        self.vars = {}
        self.step_no = 0
        self.stopped = False
        self.stop_on_fail = True

    def run(self, tree):
        fails = 0
        for node in tree:
            if self.stopped:
                break
            if node["type"] == "step":
                self.step_no += 1
                hard = self.step(node["raw"])
                if hard:
                    fails += 1
                    if self.stop_on_fail:
                        print("首个失败即停（--keep-going 可继续）")
                        self.stopped = True
                        break
            elif node["type"] == "if":
                self.step_no += 1
                cond = self.cond(*node["cond"])
                print("%2d %s if %s"
                      % (self.step_no, "OK  " if cond else "SKIP", node["cond"][1]))
                fails += self.run(node["then"] if cond else node["else"])
            elif node["type"] == "repeat":
                self.step_no += 1
                print("%2d .... repeat %d" % (self.step_no, node["n"]))
                for _ in range(node["n"]):
                    fails += self.run(node["body"])
                    if self.stopped:
                        break
            elif node["type"] == "retry":
                self.step_no += 1
                print("%2d .... retry %d" % (self.step_no, node["n"]))
                ok = False
                for attempt in range(1, node["n"] + 1):
                    self.stop_on_fail = False
                    f = self.run(node["body"])
                    self.stop_on_fail = True
                    if f == 0:
                        ok = True
                        break
                    if attempt < node["n"]:
                        print("%2d RETRY %d/%d" % (self.step_no, attempt + 1, node["n"]))
                if not ok:
                    fails += 1
                    if not self.args.keep_going:
                        print("首个失败即停（--keep-going 可继续）")
                        self.stopped = True
                        break
        return fails

    def cond(self, kind, val):
        val = _subst(val, self.vars)
        if kind == "if-pkg":
            return fore(self.ctx) == val
        if kind == "if-text":
            root = dump_ui(self.ctx)
            if root is not None:
                return find_node(nodes(root), val) is not None
            return bool(ocr_matches(ocr_screen(self.ctx), val))
        m = re.match(r"^\$?([A-Za-z_]\w*)\s*(==|!=)\s*(.*)$", val)
        if not m:
            return False
        left = self.vars.get(m.group(1), "")
        return (left == m.group(3)) if m.group(2) == "==" else (left != m.group(3))

    def step(self, raw):
        kind, _, val = raw.partition(":")
        kind = kind.strip().lower()
        val = _subst(val.strip(), self.vars)
        n = self.step_no
        if kind == "set":
            k, _, v = val.partition("=")
            if not k.strip():
                print("%2d FAIL set 需要 name=value" % n)
                return True
            self.vars[k.strip()] = v.strip()
            print("%2d OK   set %s=%s" % (n, k.strip(), v.strip()))
            return False
        if kind == "echo":
            print("%2d .... %s" % (n, val))
            return False
        if kind == "expect-pkg":
            cur = fore(self.ctx)
            ok = cur == val
            print("%2d %s expect-pkg %s (前台=%s)" % (n, "OK  " if ok else "FAIL", val, cur))
            return not ok
        if kind == "wait":
            txt, _, secs = val.partition(" ")
            ok = cmd_wait_impl(self.ctx, txt, float(secs) if secs.strip() else 10)
            print("%2d %s wait %s" % (n, "OK  " if ok else "FAIL", txt))
            return not ok
        if kind in ("tap", "key", "app", "type", "scroll", "tap-text"):
            target = ("tap-text:" + val) if kind == "tap-text" else (kind + ":" + val)
            ok, note = act(self.ctx, target)
            hard = (not ok) and note.startswith(
                ("NOT_FOUND", "UI_DUMP_FAILED", "BAD_TARGET", "TYPE_FAILED", "BAD_KEY"))
            print("%2d %s %s %s" % (n, "FAIL" if hard else ("OK  " if ok else "WARN"),
                                    "tap" if kind == "tap-text" else kind, note))
            return hard
        if kind == "find":
            txt, _, mx = val.partition(" ")
            ok, note = find_and_tap(self.ctx, txt, int(mx) if mx.strip().isdigit() else 5,
                                    "down", True)
            print("%2d %s find %s" % (n, "OK  " if ok else "FAIL", note))
            return not ok
        if kind == "shot":
            cmd_shot(self.ctx, argparse.Namespace(path=val, crop=None, scale=None))
            print("%2d OK   shot %s" % (n, val))
            return False
        if kind == "sh":
            print("%2d OK   sh %s" % (n, (self.ctx.sh(val, timeout=120) or "").strip()[:200]))
            return False
        print("%2d SKIP 未知指令 %s" % (n, raw))
        return False


def cmd_batch(ctx, args):
    if getattr(args, "devices", None):
        devs = [d.strip() for d in args.devices.split(",") if d.strip()]
        if not devs:
            print("BAD_DEVICES: --devices 为空")
            return 4
        procs = []
        for d in devs:
            cmd = [sys.executable, os.path.abspath(__file__), "-d", d, "batch", args.file]
            if args.keep_going:
                cmd.append("--keep-going")
            p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                 text=True, encoding="utf-8", errors="replace")
            procs.append((d, p))
        rc = 0
        for d, p in procs:
            out = p.communicate()[0] or ""
            for line in out.splitlines():
                print("[%s] %s" % (d, line))
            if p.returncode:
                rc = 1
        return rc
    need(ctx)
    if args.file == "-":
        src = sys.stdin.read()
    else:
        try:
            with open(args.file, encoding="utf-8") as f:
                src = f.read()
        except OSError as e:
            print("BAD_FILE: %s (%s)" % (args.file, e))
            return 4
    steps = [s.strip() for s in src.splitlines() if s.strip() and not s.strip().startswith("#")]
    try:
        tree, _ = parse_batch(steps)
    except ValueError as e:
        print("BAD_STEPS: %s" % e)
        return 4
    runner = BatchRunner(ctx, args)
    fails = runner.run(tree)
    return 0 if not fails else 1


def cmd_wait_impl(ctx, text, secs):
    deadline = time.time() + secs
    while time.time() < deadline:
        root = dump_ui(ctx, tries=2, rounds=1)
        if root is not None and find_node(nodes(root), text):
            return True
        if root is None and ocr_matches(ocr_screen(ctx), text):
            return True
        time.sleep(0.8)
    return False


def cmd_wait(ctx, args):
    need(ctx)
    t0 = time.time()
    if cmd_wait_impl(ctx, " ".join(args.text), args.timeout):
        print("FOUND %.1fs 后出现" % (time.time() - t0))
        return 0
    print("TIMEOUT: %s" % " ".join(args.text))
    return 5


def cmd_key(ctx, args):
    need(ctx)
    name = args.name.lower()
    code = KEYS.get(name, name if name.isdigit() else None)
    if code is None:
        print("UNKNOWN_KEY: %s" % name)
        return 6
    if not send_key(ctx, name):
        print("KEY_FAILED %s" % name)
        return 7
    print("KEY %s" % name)
    return 0


def cmd_type(ctx, args):
    need(ctx)
    ok = type_text(ctx, " ".join(args.text))
    print("TYPED %s" % " ".join(args.text) if ok else "TYPE_FAILED")
    return 0 if ok else 1


def cmd_scroll(ctx, args):
    need(ctx)
    scroll(ctx, args.dir, args.times)
    print("SCROLLED %s x%d" % (args.dir, args.times))
    return 0


def cmd_shot(ctx, args):
    need(ctx)
    out = args.path or os.path.join(os.path.expanduser("~"), ".phone", "shot.png")
    os.makedirs(os.path.dirname(os.path.abspath(out)) or ".", exist_ok=True)
    im = None
    if getattr(ctx, "_agent", None) is None:
        ctx._agent = use_agent(ctx)
    if ctx._agent:
        # 走端侧 takeScreenshot：不需要 adb、不需要 MediaProjection 同意框。
        # 有 crop 时按原尺寸取，裁剪在本地做（否则 crop 坐标会落在缩放后的图上，差一个 scale）。
        q = "/frame" if args.crop else ("/frame?scale=%s" % args.scale if args.scale else "/frame")
        st, data, hdr = agent_get_bytes(ctx, q, timeout=30)
        if st != 200:
            print("FRAME_FAILED %s: %s" % (st, data[:160].decode("utf-8", "replace")))
            return 3
        with open(out, "wb") as f:
            f.write(data)
        info = out + "  %sx%s" % (hdr.get("X-Img-W"), hdr.get("X-Img-H"))
        try:
            from PIL import Image
            if args.crop or (args.scale and args.scale != 1.0):
                im = Image.open(out)
                if args.crop:
                    im = im.crop(tuple(int(v) for v in args.crop.split(",")))
                if args.scale and args.scale != 1.0:
                    im = im.resize((int(im.width * args.scale), int(im.height * args.scale)))
                im.save(out)
                info = out + "  %dx%d" % (im.width, im.height)
        except Exception as e:
            info += "  (未裁剪：%s)" % e
        print(info)
        return 0
    d = u2_device(ctx)
    if d is not None:
        try:
            im = d.screenshot()
        except Exception:
            _U2_FAILED.add(ctx.serial)
            im = None
    if im is None:
        raw = ctx.raw(["-s", ctx.serial, "exec-out", "screencap -p"], binary=True)
        with open(out, "wb") as f:
            f.write(raw)
    info = out
    try:
        from PIL import Image
        if im is None:
            im = Image.open(out)
        if args.crop:
            im = im.crop(tuple(int(v) for v in args.crop.split(",")))
        if args.scale and args.scale != 1.0:
            im = im.resize((int(im.width * args.scale), int(im.height * args.scale)))
        im.save(out)
        info += "  %dx%d" % (im.width, im.height)
    except Exception as e:
        info += "  (未裁剪：%s)" % e
    print(info)
    return 0


def fingerprint_of(root):
    """页面指纹。**必须与端侧 /fingerprint 逐字节同构**：class|id|text|desc|l,t,r,b 换行拼接，取 md5。
    两边算出来不一样，"页面变了"就会和"树变了"对不上（所以有 A/B 断言盯着它）。"""
    h = hashlib.md5()
    n = 0
    for node in root.iter("node"):
        m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds") or "")
        b = "%s,%s,%s,%s" % m.groups() if m else (node.get("bounds") or "")
        h.update(("%s|%s|%s|%s|%s\n" % (
            node.get("class") or "", node.get("resource-id") or "",
            node.get("text") or "", node.get("content-desc") or "", b)).encode("utf-8"))
        n += 1
    return h.hexdigest(), n


def page_fp(ctx):
    """(fp, nodes, pkg)。agent 通道走 /fingerprint（只回 32 字节，不传整棵树）；adb 本地算。"""
    if getattr(ctx, "_agent", None) is None:
        ctx._agent = use_agent(ctx)
    if ctx._agent:
        st, body = agent_call(ctx, "GET", "/fingerprint", timeout=20)
        if st == 200:
            try:
                o = json.loads(body)
                return o.get("fp"), o.get("nodes"), o.get("pkg")
            except Exception:
                pass
    root = dump_ui(ctx)
    if root is None:
        return None, 0, fore(ctx)
    fp, n = fingerprint_of(root)
    return fp, n, fore(ctx)


def cmd_fp(ctx, args):
    need(ctx)
    fp, n, pkg = page_fp(ctx)
    if not fp:
        print("UI_DUMP_FAILED：读不到窗口树")
        return 3
    if not args.wait:
        print("FP %s  nodes=%d  前台=%s" % (fp, n, pkg))
        return 0
    t0 = time.time()
    while time.time() - t0 < args.wait:
        time.sleep(0.4)
        fp2, n2, pkg2 = page_fp(ctx)
        if fp2 and fp2 != fp:
            print("UI_CHANGED %s -> %s  %.1fs  前台=%s" % (fp[:8], fp2[:8], time.time() - t0, pkg2))
            return 0
    print("UI_UNCHANGED %.1fs  %s  前台=%s" % (time.time() - t0, fp[:8], pkg))
    return 5


def http_get_json(base, path, timeout=5):
    """对任意 base 发一次 GET（扫描/探测用，不经过 ctx 的通道选择）。"""
    h = {"X-Agent-Token": agent_token()} if agent_token() else {}
    req = urllib.request.Request(base + path, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:
        return 0, str(e)


def cmd_agent_find(ctx, args):
    """在局域网里找开着端侧服务的手机：扫本机所在 /24 网段的 8712 端口。
    为什么要有：新手机接进来时"手机 IP 是多少"是最容易卡住的一步，不该让人去设置里翻。
    找到后用 --save 写进 agents.json，多机时用 `-d 别名` 选。"""
    import socket
    from concurrent.futures import ThreadPoolExecutor

    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))          # 不会真的发包，只为让内核选出口网卡
        me = s.getsockname()[0]
        s.close()
    except Exception:
        me = "127.0.0.1"
    if me.startswith("127."):
        print("找不到本机局域网地址（没连 WiFi/网线？）")
        return 2
    prefix = me.rsplit(".", 1)[0]
    port = args.port or AGENT_PORT_DEFAULT

    def probe(i):
        host = "%s.%d" % (prefix, i)
        try:
            with socket.create_connection((host, port), timeout=0.35):
                return host
        except Exception:
            return None

    print("扫描 %s.1-254 的 %d 端口（本机 %s）…" % (prefix, port, me))
    with ThreadPoolExecutor(max_workers=96) as ex:
        hosts = [h for h in ex.map(probe, range(1, 255)) if h]

    if not hosts:
        print("没找到。检查：手机和电脑是不是同一个 WiFi / App 是不是还在跑（看手机通知栏）")
        return 4

    found = []
    for host in hosts:
        st, body = http_get_json("http://%s:%d" % (host, port), "/caps", timeout=5)
        if st != 200:
            print("%-16s 端口开着，但不是我们的服务（HTTP %s）" % (host, st))
            continue
        try:
            caps = json.loads(body)
        except Exception:
            continue
        found.append({
            "alias": caps.get("alias") or caps.get("model") or host,
            "host": host, "port": port,
            "brand": caps.get("brand", ""), "model": caps.get("model", ""),
            "android_id": caps.get("android_id", ""), "build": caps.get("build", ""),
            "last_seen": time.strftime("%Y-%m-%d %H:%M:%S"),
        })

    if not found:
        print("扫到开着的端口，但都不是 phoneagent（token 不对也会是这个结果）")
        return 4

    print("\n%-22s %-24s %-16s %s" % ("别名", "型号", "地址", "build"))
    for a in found:
        print("%-22s %-24s %-16s %s" % (a["alias"], ("%s %s" % (a["brand"], a["model"]))[:24],
                                        "%s:%s" % (a["host"], a["port"]), a["build"]))
    print("\n共 %d 台。用 `ph -d <别名>` 选一台；`--save` 记住它们。" % len(found))

    if args.save:
        old = load_agents()
        by_id = {str(a.get("android_id") or a.get("host")): a for a in old}
        for a in found:
            by_id[str(a.get("android_id") or a.get("host"))] = a
        save_agents(list(by_id.values()))
        print("已写入 %s（%d 台）" % (AGENTS_PATH, len(by_id)))
    return 0


def cmd_agents(ctx, args):
    """列出已登记的手机。"""
    items = load_agents()
    if not items:
        print("还没有登记的手机。先跑：ph agent-find --save")
        return 0
    print("%-22s %-24s %-16s %s" % ("别名", "型号", "地址", "最后见到"))
    for a in items:
        print("%-22s %-24s %-16s %s" % (
            a.get("alias", ""), ("%s %s" % (a.get("brand", ""), a.get("model", "")))[:24],
            "%s:%s" % (a.get("host", ""), a.get("port", "")), a.get("last_seen", "")))
    return 0


def cmd_agent_rename(ctx, args):
    """给手机起别名（多机时靠它区分）。手机上也会立刻显示新名字。"""
    need(ctx)
    st, body = agent_call(ctx, "POST", "/alias", {"alias": args.alias}, timeout=10)
    if st != 200:
        print("RENAME_FAILED %s: %s" % (st, body[:160]))
        return 3
    print("已改名：%s" % body)
    # 同步到本地登记表
    items = load_agents()
    addr = agent_base(ctx) or ""
    host = addr.replace("http://", "").rsplit(":", 1)[0]
    for a in items:
        if a.get("host") == host:
            a["alias"] = args.alias
    if items:
        save_agents(items)
    return 0


def cmd_focus(ctx, args):
    need(ctx)
    print(fore(ctx))
    return 0


def cmd_app(ctx, args):
    need(ctx)
    ctx.sh("monkey -p %s -c android.intent.category.LAUNCHER 1" % args.package, timeout=30)
    print("LAUNCHED %s" % args.package)
    return 0


def cmd_drag(ctx, args):
    need(ctx)
    try:
        x1, y1 = [int(v) for v in str(args.start).split(",")]
        x2, y2 = [int(v) for v in str(args.end).split(",")]
    except ValueError:
        print("BAD_TARGET: drag 要写 x1,y1 x2,y2")
        return 4
    ms = max(1, int(args.ms))
    ctx.sh("input swipe %d %d %d %d %d" % (x1, y1, x2, y2, ms), timeout=40)
    print("DRAGGED %d,%d -> %d,%d (%d ms)" % (x1, y1, x2, y2, ms))
    return 0


def cmd_install(ctx, args):
    need(ctx)
    ok, out = install_apk(ctx, args.apk, args.timeout)
    print(out or "（pm install 无输出）")
    return 0 if ok else 1


def cmd_stop(ctx, args):
    need(ctx)
    ctx.sh("am force-stop %s" % args.package, timeout=30)
    print("STOPPED %s" % args.package)
    return 0


def cmd_open(ctx, args):
    need(ctx)
    out = (ctx.sh("am start -a android.intent.action.VIEW -d '%s'" % args.url, timeout=40)
           or "").strip()
    print(out[:160] or ("OPENED %s" % args.url))
    return 0


def cmd_sh(ctx, args):
    need(ctx)
    print((ctx.sh(" ".join(args.cmd), timeout=300) or "").rstrip())
    return 0


def main():
    p = argparse.ArgumentParser(add_help=True, description="Android 手机控制（省 token 版）")
    p.add_argument("--adb")
    p.add_argument("--keydir")
    p.add_argument("--port")
    p.add_argument("-d", "--device", help="序列号 / 别名 / 型号子串")
    p.add_argument("--transport", choices=["auto", "agent", "adb"], default=None,
                   help="通道：auto=配了 agent_host 且连得上就优先走端侧 HTTP（默认）；"
                        "agent=强制走 HTTP；adb=强制 adb/u2。命令与输出格式不受影响，只换底层路径")
    sub = p.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("setup"); s.add_argument("--save", action="store_true"); s.add_argument("--local", action="store_true"); s.set_defaults(fn=cmd_setup)
    sub.add_parser("dev").set_defaults(fn=cmd_dev)
    sub.add_parser("devices").set_defaults(fn=cmd_devices)
    sub.add_parser("wake").set_defaults(fn=cmd_wake)
    s = sub.add_parser("ime")
    s.add_argument("action", choices=["status", "list", "fix", "install", "set"])
    s.add_argument("apk", nargs="?")
    s.set_defaults(fn=cmd_ime)
    s = sub.add_parser("connect")
    s.add_argument("addr", nargs="+")
    s.set_defaults(fn=cmd_connect)
    s = sub.add_parser("ui")
    s.add_argument("--filter"); s.add_argument("--limit", type=int, default=40)
    s.add_argument("--clickable", action="store_true")
    s.add_argument("--summary", action="store_true")
    s.set_defaults(fn=cmd_ui)
    s = sub.add_parser("tap")
    s.add_argument("text", nargs="+")
    s.add_argument("--by", default="auto", choices=SELECTOR_KEYS)
    s.add_argument("--re", action="store_true")
    s.add_argument("--nth", type=int, default=0)
    s.add_argument("--list", action="store_true")
    s.add_argument("--clickable", action="store_true")
    s.add_argument("--ms", type=int, default=80)
    s.set_defaults(fn=cmd_tap)
    s = sub.add_parser("tapn"); s.add_argument("index", type=int); s.add_argument("--fresh", action="store_true"); s.set_defaults(fn=cmd_tapn)
    s = sub.add_parser("find")
    s.add_argument("text", nargs="+"); s.add_argument("--tap", action="store_true")
    s.add_argument("--max", type=int, default=5); s.add_argument("--dir", default="down")
    s.add_argument("--by", default="auto", choices=SELECTOR_KEYS)
    s.add_argument("--re", action="store_true"); s.add_argument("--nth", type=int, default=0)
    s.add_argument("--clickable", action="store_true")
    s.set_defaults(fn=cmd_find)
    s = sub.add_parser("ocr")
    s.add_argument("--filter"); s.add_argument("--limit", type=int, default=30)
    s.add_argument("--json", action="store_true")
    s.set_defaults(fn=cmd_ocr)
    s = sub.add_parser("tap-ocr")
    s.add_argument("text", nargs="+")
    s.add_argument("--nth", type=int, default=0)
    s.add_argument("--list", action="store_true")
    s.add_argument("--ms", type=int, default=80)
    s.set_defaults(fn=cmd_tap_ocr)
    s = sub.add_parser("act"); s.add_argument("target"); s.add_argument("--expect-pkg"); s.add_argument("--expect-text"); s.add_argument("--timeout", type=float, default=10); s.set_defaults(fn=cmd_act)
    s = sub.add_parser("batch"); s.add_argument("file"); s.add_argument("--keep-going", action="store_true"); s.add_argument("--devices", help="逗号分隔，多机并发跑同一份步骤"); s.set_defaults(fn=cmd_batch)
    s = sub.add_parser("wait"); s.add_argument("text", nargs="+"); s.add_argument("timeout", nargs="?", type=float, default=10); s.set_defaults(fn=cmd_wait)
    s = sub.add_parser("key"); s.add_argument("name"); s.set_defaults(fn=cmd_key)
    s = sub.add_parser("type"); s.add_argument("text", nargs="+"); s.set_defaults(fn=cmd_type)
    s = sub.add_parser("scroll"); s.add_argument("dir", nargs="?", default="down", choices=["up", "down"]); s.add_argument("times", nargs="?", type=int, default=1); s.set_defaults(fn=cmd_scroll)
    s = sub.add_parser("shot"); s.add_argument("path", nargs="?"); s.add_argument("--crop"); s.add_argument("--scale", type=float); s.set_defaults(fn=cmd_shot)
    s = sub.add_parser("fp"); s.add_argument("--wait", type=float, default=0,
                                             help="等页面变化，最多这么多秒；变了打 UI_CHANGED，没变打 UI_UNCHANGED（rc=5）")
    s.set_defaults(fn=cmd_fp)
    s = sub.add_parser("agent-find"); s.add_argument("--save", action="store_true"); s.add_argument("--port", type=int)
    s.set_defaults(fn=cmd_agent_find)
    sub.add_parser("agents").set_defaults(fn=cmd_agents)
    s = sub.add_parser("agent-rename"); s.add_argument("alias"); s.set_defaults(fn=cmd_agent_rename)
    sub.add_parser("focus").set_defaults(fn=cmd_focus)
    s = sub.add_parser("app"); s.add_argument("package"); s.set_defaults(fn=cmd_app)
    s = sub.add_parser("drag"); s.add_argument("start"); s.add_argument("end"); s.add_argument("--ms", type=int, default=400); s.set_defaults(fn=cmd_drag)
    s = sub.add_parser("install"); s.add_argument("apk"); s.add_argument("--timeout", type=int, default=120); s.set_defaults(fn=cmd_install)
    s = sub.add_parser("stop"); s.add_argument("package"); s.set_defaults(fn=cmd_stop)
    s = sub.add_parser("open"); s.add_argument("url"); s.set_defaults(fn=cmd_open)
    s = sub.add_parser("sh"); s.add_argument("cmd", nargs="+"); s.set_defaults(fn=cmd_sh)

    args = p.parse_args()
    ctx = Ctx(args)
    sys.exit(args.fn(ctx, args))


if __name__ == "__main__":
    main()
