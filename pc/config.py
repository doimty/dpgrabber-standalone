"""DPGrabber PC - local configuration.

The config file lives next to the program (``dpgrabber_config.json``) and is
created from defaults on first run. It contains the login account (phone +
password), listening rules and the AES key/IV pairs, so keep it private: on
POSIX systems the file is written with mode 0600.

No real account, token or password is ever shipped in code or in the
template: ``phone`` / ``password`` default to empty strings and the user
fills them in through the UI or by editing the JSON file.

Key/IV defaults follow PROTOCOL.md section 4 and 10.6: the primary pair is
DPGrabber's AES-256 set, the alternate pair is the PC reference tool's
AES-128 set. The API client tries the primary pair first and falls back to
the alternate pair when decryption fails ("decrypt with whichever works").
Both pairs are editable in the config file / the Advanced settings dialog.
"""

from __future__ import annotations

import json
import os
import stat
import sys
from decimal import Decimal

from themes import THEMES

CONFIG_FILENAME = "dpgrabber_config.json"

# DPGrabber AES-256 set (DPGAPIClient.m:12-13). Primary.
DEFAULT_AES_KEY_HEX = "faf6bcb4eeaa8aa62154ed3b8c14cf5bce08cdf83086b9188abdca6f2c8bcbb1"
DEFAULT_AES_IV_HEX = "d45df726d59d21fac023cdad3dfcea86"
# PC reference tool AES-128 set (DP.py:305-306). Alternate / fallback.
# NOTE: in the reference tool's config the key named "key" holds 81b120ef...
# and the key named "iv" holds e6a4cc05...; the AES key is 81b120ef... and
# the AES IV is e6a4cc05... (PROTOCOL.md section 4.3).
ALT_AES_KEY_HEX = "81b120ef00216c33b266763abb02e6d1"
ALT_AES_IV_HEX = "e6a4cc0507dfe344b042289eeb945dce"

DEFAULT_CONFIG = {
    # --- login account (manual mode, PROTOCOL.md section 3.2) ---
    "phone": "",
    "password": "",
    # Preferred shop to switch to after login (DPGrabber default 92).
    "shop_id": 0,
    # --- listening rules (PROTOCOL.md sections 6-7) ---
    "min_amount": 10.0,        # yuan; orders below this are dropped
    "max_amount": 0.0,         # yuan; 0 means no upper limit
    # --- confirm task (DPGConfirmationTask, DPGEngine.m) ---
    # Hall-order confirm window (seconds) from the task start; used when
    # the order has a createTime, otherwise the fallback window applies.
    "random_confirm_window_sec": 20,
    "random_confirm_fallback_sec": 20,
    # Delay between confirm retries inside the window (seconds).
    "confirm_retry_delay_sec": 0.5,
    "poll_interval_sec": 0.6,
        "poll_jitter_sec": 0.2,
        # 连续失败时的退避上限（秒）：1 → 1.7 → 2.9 → … 封顶
        "poll_failure_max_sec": 20.0,
        # 店铺接单名额占满（如 92 店上限 2 单）后的冷却秒数
        "busy_cooldown_sec": 60.0,  # delay between empty polls (reference tool ListenDelay default)
    "memo_mode": 0,            # 0 = ignore memos, 1 = drop orders that have any memo
    # 备注关键词拆成两份：排除词与白名单互不影响
    "memo_keywords": [],           # 旧字段，仅用于兼容迁移
    # 排除词（memo_mode=2）：备注命中任一就不抢
    "memo_exclude_keywords": [],
    # 白名单（memo_mode=3）：备注必须命中其一才抢
    "memo_include_keywords": [],
    "blacklist": [],           # WeChat ids; drop orders from these customers
    # --- confirm wait (autoduopei shop-92 rule; /grab/start) ---
    # Random wait before the FIRST confirm attempt of a grab (seconds).
    # 0/0 = confirm immediately (current behavior). When max > 0, min must
    # be >= 3 and max - min >= 2 (enforced in validate()).
    # 每个店铺的倒计时不同（竞品实测 92 店 5-7 秒，50/105/1391 是 3 秒），
    # 等待时间按店铺覆盖；表里没有的店用 confirm_wait_min/max。
    "confirm_wait_by_shop": {"92": 5.0, "50": 3.0, "105": 3.0, "1391": 3.0},
        # 多账号顺序轮换：每个账号跑 rotate_minutes 分钟后自动换下一个
        "rotate_enabled": False,
        "rotate_minutes": 30,
        "confirm_wait_min": 0.0,
    "confirm_wait_max": 0.0,
    # --- auto-wait offset (autoduopei /user/auto_wait_offset) ---
    # Added to the confirm wait above, but ONLY for shops 50 / 105 / 1391.
    # Range -0.5..+0.5, at most 3 decimal places; result clamped at >= 0.
    "auto_wait_offset": 0.0,
    # --- risk run/rest rhythm (autoduopei /grab/start risk_run/rest) ---
    # When enabled, the poll loop runs for a random run_min..run_max
    # minutes, then rests (no polling, no confirms) for a random
    # rest_min..rest_max minutes, then resumes. Disabled = poll forever.
    "run_rest_enabled": True,
    "risk_run_min": 50,   # minutes
    "risk_run_max": 60,   # minutes
    "risk_rest_min": 10,  # minutes
    "risk_rest_max": 20,  # minutes
    # --- crypto (PROTOCOL.md section 4) ---
    "aes_key_hex": DEFAULT_AES_KEY_HEX,
    "aes_iv_hex": DEFAULT_AES_IV_HEX,
    "aes_key_hex_alt": ALT_AES_KEY_HEX,
    "aes_iv_hex_alt": ALT_AES_IV_HEX,
    # --- transport / misc ---
    "request_timeout_sec": 15,
    "verify_tls": True,        # verify the server TLS certificate (recommended)
    "voice_enabled": True,
        "auto_stop_after_grab": True,
        "notify_enabled": False,
        "notify_provider": "serverchan",
        "notify_bark_server": "https://api.day.app",
        "notify_key": "",
        "notify_webhook": "",     # spoken announcements; silently off if pyttsx3 is missing
    "seen_ttl_sec": 60,        # de-dup window for already-seen order ids (seconds)
    "theme": "day",
    # 界面缩放："auto" 按屏幕分辨率自动推算，或 0.8–1.6 的固定倍数（重启生效）
    "ui_scale": "auto",
    # 接单类型 ↔ 订单条目名称的匹配词（switchId -> 关键词列表）
    # 实测订单名形如「默认-语音连麦-60分钟」「新客首单体验-30分钟文语体验单」
    # 只放"名称里字面就有"的词；猜出来的一律不进默认值（认不出会放行）
    # 465 连麦：实测订单名「默认-语音连麦-60分钟」含"连麦"
    # 「文字语音条」「新客首单体验-30分钟文语体验单」没有证据归到任何类型，留空
    # 平台返回的接单类型名缓存（id -> 名字），离线时也能显示
    "item_switch_names": {},
    "item_name_map": {
        "465": ["连麦"],
        "2666": ["视频"],
        "2667": ["游戏"],
    },            # UI theme id (see themes.py); unknown values fall back to "day"
}


class ConfigError(ValueError):
    """Raised when the config file is missing required values or malformed."""


def config_dir() -> str:
    """Directory the config file lives in: next to the exe when frozen,
    next to this source file otherwise."""
    if getattr(sys, "frozen", False):
        return os.path.dirname(os.path.abspath(sys.executable))
    return os.path.dirname(os.path.abspath(__file__))


def config_path() -> str:
    return os.path.join(config_dir(), CONFIG_FILENAME)


def _hex_bytes(value, name):
    """Decode a hex string to bytes; raise ConfigError on bad input."""
    if not isinstance(value, str):
        raise ConfigError("%s must be a hex string" % name)
    text = value.strip().lower()
    if len(text) % 2 != 0 or not text:
        raise ConfigError("%s has an odd length or is empty" % name)
    try:
        return bytes.fromhex(text)
    except ValueError:
        raise ConfigError("%s is not valid hex" % name)


def confirm_wait_error(cfg: dict) -> str | None:
    """Autoduopei shop-92 wait rule, shared by validate() and the engine.

    Returns an error message when the confirm-wait config is invalid,
    else None.

    Rule: min >= 0, max >= 0, max >= min, both <= 120. Setting min == max is
    allowed and means a fixed wait — the earlier "min >= 3 and max - min >= 2"
    rule only existed to keep a random window usable, and it blocked a plain
    "wait 5 s" configuration."""
    try:
        min_w = float((cfg or {}).get("confirm_wait_min", 0) or 0)
        max_w = float((cfg or {}).get("confirm_wait_max", 0) or 0)
    except (TypeError, ValueError):
        return "confirm_wait_min/max must be numbers"
    if min_w < 0:
        return "confirm_wait_min must be >= 0"
    if max_w < 0:
        return "confirm_wait_max must be >= 0"
    if max_w < min_w:
        return "confirm_wait_max must be >= confirm_wait_min"
    if min_w > 120 or max_w > 120:
        return "confirm_wait_min/max must be <= 120 seconds"
    table = (cfg or {}).get("confirm_wait_by_shop")
    if table is not None and not isinstance(table, dict):
        return "confirm_wait_by_shop must be a mapping of shop id -> seconds"
    if isinstance(table, dict):
        for key, value in table.items():
            try:
                seconds = float(value)
            except (TypeError, ValueError):
                return "confirm_wait_by_shop[%s] must be a number" % key
            if not (0 <= seconds <= 120):
                return "confirm_wait_by_shop[%s] must be between 0 and 120" % key
    return None


def blacklist_would_overwrite(old_list, new_list) -> bool:
    """True when saving ``new_list`` would remove or change entries of a
    non-empty ``old_list``: old is non-empty and new != old as sets.
    Pure helper for the UI's "检测到可能误覆盖已有黑名单" confirmation;
    the UI only saves on confirm. Blank/whitespace entries are ignored."""
    old = {str(x).strip() for x in (old_list or []) if str(x).strip()}
    if not old:
        return False
    new = {str(x).strip() for x in (new_list or []) if str(x).strip()}
    return new != old


def validate(cfg: dict) -> dict:
    """Validate a loaded config dict in place (fills defaults for missing
    keys) and return it. Raises ConfigError on invalid values."""
    merged = dict(DEFAULT_CONFIG)
    merged.update(cfg or {})
    # user_delay was removed with the precise-wait flow; drop it from
    # configs written by older versions.
    merged.pop("user_delay", None)

    for name in ("aes_key_hex", "aes_key_hex_alt"):
        raw = _hex_bytes(merged[name], name)
        if len(raw) not in (16, 32):
            raise ConfigError(
                "%s must decode to 16 bytes (AES-128) or 32 bytes (AES-256)" % name
            )
    for name in ("aes_iv_hex", "aes_iv_hex_alt"):
        raw = _hex_bytes(merged[name], name)
        if len(raw) != 16:
            raise ConfigError("%s must decode to 16 bytes" % name)

    if merged.get("notify_provider") not in ("bark", "serverchan", "pushplus", "webhook"):
        merged["notify_provider"] = "serverchan"
    merged["auto_stop_after_grab"] = bool(merged.get("auto_stop_after_grab", True))
    merged["rotate_enabled"] = bool(merged.get("rotate_enabled", False))
    try:
        minutes = int(float(merged.get("rotate_minutes") or 30))
    except (TypeError, ValueError):
        raise ConfigError("rotate_minutes must be a number")
    if not (5 <= minutes <= 600):
        raise ConfigError("rotate_minutes must be between 5 and 600")
    merged["rotate_minutes"] = minutes
    merged["notify_enabled"] = bool(merged.get("notify_enabled", False))
    merged["notify_key"] = str(merged.get("notify_key") or "")
    merged["notify_webhook"] = str(merged.get("notify_webhook") or "")
    merged["notify_bark_server"] = str(
        merged.get("notify_bark_server") or "https://api.day.app"
    )
    if not isinstance(merged["shop_id"], int) or merged["shop_id"] < 0:
        raise ConfigError("shop_id must be 0 (auto) or a positive shop id")
    if float(merged["min_amount"]) < 0 or float(merged["max_amount"]) < 0:
        raise ConfigError("amounts must not be negative")
    if float(merged["max_amount"]) > 0 and float(merged["max_amount"]) < float(
        merged["min_amount"]
    ):
        raise ConfigError("max_amount must be 0 (unlimited) or >= min_amount")
    if float(merged["poll_interval_sec"]) <= 0:
        raise ConfigError("poll_interval_sec must be positive")
    # 0 不检测 / 1 有备注不抢 / 2 排除关键词 / 3 只抢关键词
    if int(merged["memo_mode"]) not in (0, 1, 2, 3):
        raise ConfigError("memo_mode must be 0..3 (ignore / drop-memo / exclude-keywords / include-keywords)")
    for name in ("random_confirm_window_sec", "random_confirm_fallback_sec"):
        value = float(merged[name])
        if not 0.1 <= value <= 86400:
            raise ConfigError("%s must be between 0.1 and 86400" % name)
    retry = float(merged["confirm_retry_delay_sec"])
    if not 0 <= retry <= 60:
        raise ConfigError("confirm_retry_delay_sec must be between 0 and 60")
    # --- confirm wait (autoduopei shop-92 rule) ---
    wait_error = confirm_wait_error(merged)
    if wait_error is not None:
        raise ConfigError(wait_error)
    merged["confirm_wait_min"] = float(merged.get("confirm_wait_min") or 0)
    merged["confirm_wait_max"] = float(merged.get("confirm_wait_max") or 0)
    # --- auto-wait offset: -0.5..+0.5, at most 3 decimal places ---
    try:
        offset = float(merged.get("auto_wait_offset") or 0)
    except (TypeError, ValueError):
        raise ConfigError("auto_wait_offset must be a number")
    if not -0.5 <= offset <= 0.5:
        raise ConfigError("auto_wait_offset must be between -0.5 and +0.5")
    if -Decimal(str(offset)).as_tuple().exponent > 3:
        raise ConfigError("auto_wait_offset must have at most 3 decimal places")
    merged["auto_wait_offset"] = offset
    # --- risk run/rest rhythm (minutes, ints) ---
    if not isinstance(merged.get("run_rest_enabled"), bool):
        raise ConfigError("run_rest_enabled must be true or false")
    for name in ("risk_run_min", "risk_run_max", "risk_rest_min", "risk_rest_max"):
        value = merged.get(name)
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise ConfigError("%s must be an integer number of minutes" % name)
        if float(value) != int(float(value)):
            raise ConfigError("%s must be an integer number of minutes" % name)
        merged[name] = int(value)
    if merged["risk_run_min"] < 1:
        raise ConfigError("risk_run_min must be >= 1")
    if merged["risk_run_max"] < merged["risk_run_min"]:
        raise ConfigError("risk_run_max must be >= risk_run_min")
    if merged["risk_rest_min"] < 0:
        raise ConfigError("risk_rest_min must be >= 0")
    if merged["risk_rest_max"] < merged["risk_rest_min"]:
        raise ConfigError("risk_rest_max must be >= risk_rest_min")
    if float(merged["request_timeout_sec"]) <= 0:
        raise ConfigError("request_timeout_sec must be positive")
    for name in ("memo_keywords", "memo_exclude_keywords",
                 "memo_include_keywords", "blacklist"):
        if not isinstance(merged.get(name), list):
            raise ConfigError("%s must be a list" % name)
        merged[name] = [str(x).strip() for x in merged[name] if str(x).strip()]
    # 旧配置只有一个 memo_keywords：按模式迁到对应列表
    if merged["memo_keywords"]:
        target = ("memo_include_keywords" if int(merged["memo_mode"]) == 3
                  else "memo_exclude_keywords")
        if not merged[target]:
            merged[target] = list(merged["memo_keywords"])
    merged["blacklist"] = [str(x).strip() for x in merged["blacklist"] if str(x).strip()]
    merged["phone"] = str(merged.get("phone") or "").strip()
    merged["password"] = str(merged.get("password") or "")
    name_map = merged.get("item_name_map")
    if not isinstance(name_map, dict):
        raise ConfigError("item_name_map must be an object")
    cleaned = {}
    for key, words in name_map.items():
        if isinstance(words, str):
            words = [w for w in words.replace("，", ",").split(",")]
        if not isinstance(words, list):
            raise ConfigError("item_name_map values must be lists")
        cleaned[str(key).strip()] = [str(w).strip() for w in words if str(w).strip()]
    merged["item_name_map"] = cleaned
    scale = merged.get("ui_scale", "auto")
    if isinstance(scale, str) and scale.strip().lower() == "auto":
        merged["ui_scale"] = "auto"
    else:
        try:
            value = float(scale)
        except (TypeError, ValueError):
            raise ConfigError("ui_scale must be 'auto' or a number")
        if not (0.8 <= value <= 1.6):
            raise ConfigError("ui_scale must be between 0.8 and 1.6")
        merged["ui_scale"] = round(value, 2)
    theme = str(merged.get("theme") or "day").strip()
    merged["theme"] = theme if theme in THEMES else "day"
    return merged


def _restrict_permissions(path: str) -> None:
    """Best effort: make the config file owner-only readable (0600)."""
    try:
        os.chmod(path, stat.S_IRUSR | stat.S_IWUSR)
    except OSError:
        pass  # e.g. Windows: no POSIX permission bits; nothing to do


def save(cfg: dict, path: str | None = None) -> str:
    """Write the config (validated first) and return the path used."""
    cfg = validate(cfg)
    path = path or config_path()
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(cfg, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    os.replace(tmp, path)
    _restrict_permissions(path)
    return path


def load(path: str | None = None) -> dict:
    """Load the config; create the default template (no credentials) when
    the file does not exist. Raises ConfigError on invalid content."""
    path = path or config_path()
    if not os.path.exists(path):
        cfg = validate(dict(DEFAULT_CONFIG))
        save(cfg, path)
        return cfg
    with open(path, "r", encoding="utf-8") as fh:
        try:
            raw = json.load(fh)
        except json.JSONDecodeError as exc:
            raise ConfigError("config file is not valid JSON: %s" % exc)
    if not isinstance(raw, dict):
        raise ConfigError("config file must contain a JSON object")
    cfg = validate(raw)
    # Write back so newly added default keys are persisted.
    if set(raw.keys()) != set(cfg.keys()):
        save(cfg, path)
    return cfg


def resolve_ui_scale(cfg: dict) -> float:
    """要传给 QT_SCALE_FACTOR 的倍数。

    "auto" 时按主屏物理分辨率推算（1920x1080 = 1.0），夹在 0.85–1.35；
    固定值直接用。返回值四舍五入到 0.05，避免奇怪的缩放比。
    """
    value = (cfg or {}).get("ui_scale", "auto")
    if not (isinstance(value, str) and value.strip().lower() == "auto"):
        try:
            return max(0.8, min(1.6, round(float(value), 2)))
        except (TypeError, ValueError):
            pass
    width = height = 0
    try:
        import ctypes
        user32 = ctypes.windll.user32
        user32.SetProcessDPIAware()
        width = int(user32.GetSystemMetrics(0))
        height = int(user32.GetSystemMetrics(1))
    except Exception:
        width = height = 0
    if width <= 0 or height <= 0:
        return 1.0
    ratio = (width * height / (1920.0 * 1080.0)) ** 0.5
    ratio = max(0.85, min(1.35, ratio))
    return round(ratio * 20) / 20.0


def mask_phone(phone: str) -> str:
    """Mask a phone number for logs: keep first 3 and last 4 digits."""
    phone = str(phone or "")
    if len(phone) <= 7:
        return "***"
    return phone[:3] + "****" + phone[-4:]
