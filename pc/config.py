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
    "shop_id": 92,
    # --- listening rules (PROTOCOL.md sections 6-7) ---
    "min_amount": 10.0,        # yuan; orders below this are dropped
    "max_amount": 0.0,         # yuan; 0 means no upper limit
    "user_delay": 0,           # extra seconds to wait before submitting, after the precise wait
    "poll_interval_sec": 0.5,  # delay between empty polls (reference tool ListenDelay default)
    "memo_mode": 0,            # 0 = ignore memos, 1 = drop orders that have any memo
    "memo_keywords": [],       # drop an order if its memo contains any of these (case-insensitive)
    "blacklist": [],           # WeChat ids; drop orders from these customers
    # --- crypto (PROTOCOL.md section 4) ---
    "aes_key_hex": DEFAULT_AES_KEY_HEX,
    "aes_iv_hex": DEFAULT_AES_IV_HEX,
    "aes_key_hex_alt": ALT_AES_KEY_HEX,
    "aes_iv_hex_alt": ALT_AES_IV_HEX,
    # --- transport / misc ---
    "request_timeout_sec": 15,
    "verify_tls": True,        # verify the server TLS certificate (recommended)
    "voice_enabled": True,     # spoken announcements; silently off if pyttsx3 is missing
    "seen_ttl_sec": 60,        # de-dup window for already-seen order ids (seconds)
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


def validate(cfg: dict) -> dict:
    """Validate a loaded config dict in place (fills defaults for missing
    keys) and return it. Raises ConfigError on invalid values."""
    merged = dict(DEFAULT_CONFIG)
    merged.update(cfg or {})

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

    if not isinstance(merged["shop_id"], int) or merged["shop_id"] <= 0:
        raise ConfigError("shop_id must be a positive integer")
    if float(merged["min_amount"]) < 0 or float(merged["max_amount"]) < 0:
        raise ConfigError("amounts must not be negative")
    if float(merged["max_amount"]) > 0 and float(merged["max_amount"]) < float(
        merged["min_amount"]
    ):
        raise ConfigError("max_amount must be 0 (unlimited) or >= min_amount")
    if int(merged["user_delay"]) < 0:
        raise ConfigError("user_delay must not be negative")
    if float(merged["poll_interval_sec"]) <= 0:
        raise ConfigError("poll_interval_sec must be positive")
    if int(merged["memo_mode"]) not in (0, 1):
        raise ConfigError("memo_mode must be 0 (ignore memos) or 1 (drop memo orders)")
    if float(merged["request_timeout_sec"]) <= 0:
        raise ConfigError("request_timeout_sec must be positive")
    if not isinstance(merged["memo_keywords"], list) or not isinstance(
        merged["blacklist"], list
    ):
        raise ConfigError("memo_keywords and blacklist must be lists")
    merged["memo_keywords"] = [str(x).strip() for x in merged["memo_keywords"] if str(x).strip()]
    merged["blacklist"] = [str(x).strip() for x in merged["blacklist"] if str(x).strip()]
    merged["phone"] = str(merged.get("phone") or "").strip()
    merged["password"] = str(merged.get("password") or "")
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


def mask_phone(phone: str) -> str:
    """Mask a phone number for logs: keep first 3 and last 4 digits."""
    phone = str(phone or "")
    if len(phone) <= 7:
        return "***"
    return phone[:3] + "****" + phone[-4:]
