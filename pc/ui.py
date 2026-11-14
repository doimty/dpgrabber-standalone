"""DPGrabber PC - PySide6 (Qt Widgets) user interface.

Replaces the CustomTkinter UI (preserved as ``ui_tk.py`` for reference).
Same dashboard layout and fields: light blue sidebar (#D9E8F6) with
账号登录 + 监听控制 + 高级设置; light main area with 接单条件 + large
运行日志 + 接单历史 below the log; top status strip with 今日抢单 N +
listening state + login status. Blue accent #1F6AA5.

Styling is custom QSS (no QFluentWidgets: it is GPLv3 and this is a
closed-source app). Real Windows 11 Mica is applied with win32mica when
available, with a ctypes DWM fallback; on Linux/macOS (and under Xvfb)
both are no-ops guarded by a platform check, so the app still runs and
screenshots fine there.

Threading: the engine runs on worker threads and never touches widgets
directly. Worker threads call ``App.log`` / the grab callbacks, which
emit Qt signals; the slots run on the UI thread. The log is a
QPlainTextEdit (retained-mode widget: its content survives window
maximize/restore, unlike the old CTkTextbox).

New-feature contract (parallel work adds these to config/engine/history;
every read below is defensive with .get() + defaults so the UI also
works if a key is missing):
- ``confirm_wait_min`` / ``confirm_wait_max`` (seconds, default 0):
  抢单前等待 (秒) in the 接单条件 card; when enabled (either > 0),
  min >= 3 and max - min >= 2.
- ``auto_wait_offset`` (default 0.0, -0.5..+0.5): 自动等待偏移 (秒) in
  Advanced Settings; only applies to shops 50/105/1391.
- ``run_rest_enabled`` (default True), ``risk_run_min`` (50),
  ``risk_run_max`` (60), ``risk_rest_min`` (10), ``risk_rest_max`` (20)
  (minutes): visible 刷新与休息 toggle + four fields in 接单条件.
- Engine ``on_outcome(order, outcome, reason)``: outcome is one of
  success/failed/risk/unknown/skipped. on_grab records the confirmed
  order (as before); on_outcome records non-success outcomes too.
  History entries carry ``outcome`` and ``reason``; old entries without
  them display as success / no reason.
- Blacklist overwrite: saving a blacklist that would overwrite a
  non-empty existing one asks first ("检测到可能误覆盖已有黑名单"),
  using ``config.blacklist_would_overwrite`` when present.
"""

from __future__ import annotations

import ctypes
import inspect
import os
import sys
import threading
import time

from PySide6.QtCore import Qt, QTimer, Signal
from PySide6.QtGui import QColor, QPalette, QTextCharFormat, QTextCursor
from PySide6.QtWidgets import (
    QApplication,
    QCheckBox,
    QComboBox,
    QDialog,
    QDoubleSpinBox,
    QFrame,
    QGridLayout,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QListWidget,
    QListWidgetItem,
    QMainWindow,
    QMessageBox,
    QPlainTextEdit,
    QScrollArea,
    QPushButton,
    QScrollArea,
    QSpinBox,
    QVBoxLayout,
    QWidget,
)

import config as cfgmod
from dpapi import ApiClient, ApiError, amount_yuan_from_cents, extract_clerk
from engine import Engine
import history as history_mod
from themes import DEFAULT_THEME, THEMES, palette_for
from voice import Voice

try:
    from PIL import Image, ImageDraw
    _PIL_AVAILABLE = True
except Exception:
    Image = ImageDraw = None
    _PIL_AVAILABLE = False

APP_TITLE = "DPGrabber PC"
WINDOW_SIZE = (1180, 800)

# Day-palette aliases, kept so older references keep working. The live
# colours come from the active theme's palette (themes.py) via
# build_stylesheet(); these are only the day defaults.
_DAY = THEMES["day"]["palette"]
BLUE = _DAY["accent"]
BLUE_DARK = _DAY["accent_dark"]
BLUE_DISABLED = _DAY["btn_disabled_text"]
WINDOW_BG = _DAY["window_bg"]
CARD_BG = _DAY["card_bg"]
CARD_BORDER = _DAY["card_border"]
TEXT_DARK = _DAY["text_dark"]
HINT = _DAY["hint"]
PLACEHOLDER = "#9AA8B8"
SIDEBAR_BG = _DAY["sidebar_bg"]
SIDEBAR_CARD_BG = _DAY["sidebar_card_bg"]
SIDEBAR_CARD_BORDER = _DAY["sidebar_card_border"]
SIDEBAR_TEXT = _DAY["sidebar_text"]
SIDEBAR_HINT = _DAY["sidebar_hint"]

OUTCOME_LABELS = {
    "success": ("成功", _DAY["outcome_success"]),
    "failed": ("失败", _DAY["outcome_failed"]),
    "risk": ("风控", _DAY["outcome_risk"]),
    "unknown": ("未知", _DAY["outcome_unknown"]),
    "skipped": ("跳过", _DAY["outcome_skipped"]),
}


def _asset_dir():
    """Writable folder for the generated arrow/check PNGs, or None.

    tempfile.gettempdir() raises FileNotFoundError when every candidate is
    missing or unwritable, and build_stylesheet() runs at import time, so an
    unusable TEMP took the whole app down before a window could exist.
    Fall back to a per-user folder, then to the directory of this file.
    """
    import tempfile
    from pathlib import Path as _Path

    candidates = []
    try:
        candidates.append(_Path(tempfile.gettempdir()))
    except Exception:
        pass
    candidates.append(_Path.home() / ".dpgrabber")
    candidates.append(_Path(__file__).resolve().parent)
    for folder in candidates:
        try:
            folder.mkdir(parents=True, exist_ok=True)
            probe = folder / ".dpg_asset_probe"
            probe.write_bytes(b"1")
            probe.unlink()
            return folder
        except Exception:
            continue
    return None


def _check_png(color_hex, theme_id=DEFAULT_THEME):
    """Tick for a checked box; Qt stops drawing its own once the indicator
    gets a custom fill."""
    if not _PIL_AVAILABLE:
        return ""
    folder = _asset_dir()
    if folder is None:
        return ""
    key = ("check", theme_id)
    path = folder / ("dpgrabber_check_%s.png" % theme_id)
    if _CHEVRON_CACHE.get(key) == color_hex and path.exists():
        return path.as_posix()
    w = h = 12
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    r = int(color_hex[1:3], 16)
    g = int(color_hex[3:5], 16)
    b = int(color_hex[5:7], 16)
    draw.line([(2, 6), (5, 9), (10, 3)], fill=(r, g, b, 255), width=2, joint="curve")
    try:
        img.save(str(path), "PNG")
    except OSError:
        return ""
    _CHEVRON_CACHE[key] = color_hex
    return path.as_posix()


def _chevron_png(direction: str, color_hex: str, theme_id: str = DEFAULT_THEME) -> str:
    """Draw a small chevron (up/down) in the theme's accent colour and
    save it as a PNG.

    Used as the custom arrow for QComboBox / QSpinBox so they don't show
    Qt's default grey triangle — the arrow always matches the active
    theme's material. One file per (direction, theme), cached in a
    module dict; the file is only rewritten when the colour for that
    theme changes. Returns the absolute file path, or "" when Pillow is
    unavailable (then the QSS falls back to the default arrow).
    """
    if not _PIL_AVAILABLE:
        return ""
    folder = _asset_dir()
    if folder is None:
        return ""

    key = (direction, theme_id)
    path = folder / ("dpgrabber_chevron_%s_%s.png" % (direction, theme_id))
    if _CHEVRON_CACHE.get(key) == color_hex and path.exists():
        return path.as_posix()

    w, h = 14, 10
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    r = int(color_hex[1:3], 16)
    g = int(color_hex[3:5], 16)
    b = int(color_hex[5:7], 16)
    if direction == "down":
        draw.line([(2, 2), (w // 2, h - 2), (w - 2, 2)],
                  fill=(r, g, b, 255), width=2, joint="curve")
    else:
        draw.line([(2, h - 2), (w // 2, 2), (w - 2, h - 2)],
                  fill=(r, g, b, 255), width=2, joint="curve")
    try:
        img.save(str(path), "PNG")
    except OSError:
        return ""
    _CHEVRON_CACHE[key] = color_hex
    # as_posix: forward slashes so url() works inside QSS on Windows too.
    return path.as_posix()


# (direction, theme_id) -> colour the cached PNG was drawn with.
_CHEVRON_CACHE: dict = {}

_STYLESHEET_TEMPLATE = """
QMainWindow, QWidget#central { background: %(WINDOW_BG)s; }
/* The body sits in a scroll area since 24ca8cde; without these rules the
   viewport keeps the system's light background and the dark themes get a
   white band behind the section titles. */
QScrollArea#bodyScroll { background: %(WINDOW_BG)s; border: none; }
QScrollArea#bodyScroll > QWidget > QWidget { background: %(WINDOW_BG)s; }
QScrollArea#bodyScroll QScrollBar:vertical { background: transparent; }
QDialog { background: %(WINDOW_BG)s; }
/* The dialogs scroll since 43762ee5; the viewport otherwise keeps the
   system palette, which paints label text white on a light dialog. */
QDialog QScrollArea > QWidget > QWidget { background: %(WINDOW_BG)s; }
QDialog QLabel { color: %(TEXT_DARK)s; background: transparent; }
QDialog QCheckBox { color: %(TEXT_DARK)s; }
QDialog QGroupBox { color: %(TEXT_DARK)s; }
QFrame#card {
    background: %(CARD_BG)s;
    border: 1px solid %(CARD_BORDER)s;
    border-radius: 14px;
}
QFrame#sidebar {
    background: %(SIDEBAR_BG)s;
    border-radius: 14px;
}
QFrame#sidebarCard {
    background: %(SIDEBAR_CARD_BG)s;
    border: 1px solid %(SIDEBAR_CARD_BORDER)s;
    border-radius: 10px;
}
QFrame#hero {
    background: qlineargradient(x1:0, y1:0, x2:0, y2:1,
        stop:0 %(HERO_TOP)s, stop:1 %(HERO_BOTTOM)s);
    border-bottom: 2px solid %(HERO_BORDER)s;
}
QLabel#heroTitle { color: white; font-size: 20px; font-weight: bold; }
QLabel#heroStat { color: white; font-size: 13px; font-weight: bold; }
QLabel#heroStatDim { color: %(HERO_STAT_DIM)s; font-size: 13px; }
QLabel#sectionTitle { color: %(BLUE)s; font-size: 14px; font-weight: bold; }
QLabel#sidebarTitle { color: %(SIDEBAR_TEXT)s; font-size: 14px; font-weight: bold; }
QLabel#fieldLabel { color: %(TEXT_DARK)s; font-size: 13px; }
QLabel#hint { color: %(HINT)s; font-size: 12px; }
QLabel#sidebarHint { color: %(SIDEBAR_HINT)s; font-size: 12px; }
QLabel#sidebarLabel { color: %(SIDEBAR_TEXT)s; font-size: 13px; }
QLabel#sidebarState { color: %(SIDEBAR_HINT)s; font-size: 12px; }
QLabel#historyLine1 { color: %(TEXT_DARK)s; font-size: 13px; font-weight: bold; }
QLabel#historyLine2 { color: %(HINT)s; font-size: 12px; }
QLabel#historyReason { color: %(HISTORY_REASON)s; font-size: 12px; }
QLineEdit {
    background: %(ENTRY_BG)s; color: %(TEXT_DARK)s; font-size: 13px;
    border: 1px solid %(ENTRY_BORDER)s; border-radius: 10px; padding: 7px 10px;
}
QLineEdit:focus { border: 1px solid %(BLUE)s; }
QComboBox {
    background: %(ENTRY_BG)s; color: %(TEXT_DARK)s; font-size: 13px;
    border: 1px solid %(ENTRY_BORDER)s; border-radius: 10px; padding: 7px 10px;
    /* Draw the popup ourselves: the native Windows popup is a separate
       window that ignores every rule below and renders as a white slab. */
    combobox-popup: 0;
}
QComboBox:focus { border: 1px solid %(BLUE)s; }
QComboBox::drop-down {
    subcontrol-origin: padding; subcontrol-position: top right;
    width: 30px; border: none; background: transparent;
}
QComboBox::down-arrow {
    %(CHEVRON_DOWN_CSS)s width: 14px; height: 10px;
}
QComboBox QAbstractItemView {
    background-color: %(CARD_BG)s; color: %(TEXT_DARK)s;
    border: 1px solid %(CARD_BORDER)s; border-radius: 10px;
    selection-background-color: %(SELECTION_BG)s; selection-color: %(TEXT_DARK)s;
    outline: none; padding: 4px;
}
QComboBox QAbstractItemView::item {
    color: %(TEXT_DARK)s; min-height: 26px; padding: 2px 8px; border-radius: 6px;
}
QComboBox QAbstractItemView::item:selected {
    background: %(SELECTION_BG)s; color: %(TEXT_DARK)s;
}
QSpinBox, QDoubleSpinBox {
    background: %(ENTRY_BG)s; color: %(TEXT_DARK)s; font-size: 13px;
    border: 1px solid %(ENTRY_BORDER)s; border-radius: 10px; padding: 7px 10px;
}
QSpinBox:focus, QDoubleSpinBox:focus { border: 1px solid %(BLUE)s; }
QSpinBox::up-button, QDoubleSpinBox::up-button {
    subcontrol-origin: border; subcontrol-position: top right;
    width: 26px; border: none; background: transparent;
    border-top-right-radius: 10px;
}
QSpinBox::down-button, QDoubleSpinBox::down-button {
    subcontrol-origin: border; subcontrol-position: bottom right;
    width: 26px; border: none; background: transparent;
    border-bottom-right-radius: 10px;
}
/* No hover fill on the spin buttons: a filled hover rect has hard
   square edges (only one corner is rounded) and reads as a square
   block stuck on the rounded field. The chevron arrows are the
   affordance; the buttons stay transparent in every state. */
QSpinBox::up-arrow { %(CHEVRON_UP_CSS)s width: 14px; height: 10px; }
QSpinBox::down-arrow { %(CHEVRON_DOWN_CSS)s width: 14px; height: 10px; }
QPlainTextEdit {
    background: %(ENTRY_BG)s; color: %(TEXT_DARK)s; font-size: 13px;
    border: 1px solid %(ENTRY_BORDER)s; border-radius: 8px; padding: 6px;
}
QPlainTextEdit#logView { border: none; border-radius: 8px; padding: 2px; }
QPlainTextEdit#listEdit { border: 1px solid %(ENTRY_BORDER)s; border-radius: 8px; }
QPushButton#primary {
    background: qlineargradient(x1:0, y1:0, x2:0, y2:1,
        stop:0 %(BTN_TOP)s, stop:1 %(BTN_BOTTOM)s);
    color: white; font-size: 13px; font-weight: bold;
    border: none; border-radius: 10px; padding: 7px 16px;
}
QPushButton#primary:hover {
    background: qlineargradient(x1:0, y1:0, x2:0, y2:1,
        stop:0 %(BTN_HOVER_TOP)s, stop:1 %(BTN_HOVER_BOTTOM)s);
}
QPushButton#primary:disabled {
    background: %(BTN_DISABLED_BG)s; color: %(BTN_DISABLED_TEXT)s;
}
QPushButton#secondary {
    background: %(ENTRY_BG)s; color: %(BLUE)s; font-size: 13px; font-weight: bold;
    border: 1px solid %(BLUE)s; border-radius: 10px; padding: 7px 16px;
}
QPushButton#secondary:hover { background: %(SELECTION_BG)s; }
QPushButton#secondary:disabled { background: %(BTN_DISABLED_BG)s; color: %(BTN_DISABLED_TEXT)s; border-color: %(ENTRY_BORDER)s; }
QCheckBox { color: %(TEXT_DARK)s; font-size: 13px; spacing: 6px; }
QCheckBox#sidebarCheck { color: %(SIDEBAR_TEXT)s; }
QCheckBox::indicator { width: 16px; height: 16px; }
QCheckBox::indicator:unchecked {
    border: 1px solid %(ENTRY_BORDER)s; border-radius: 6px; background: %(ENTRY_BG)s;
}
QCheckBox::indicator:checked {
    border: 1px solid %(BLUE)s; border-radius: 6px;
    background: %(BLUE)s; %(CHECK_CSS)s
}
QListWidget { background: transparent; border: none; outline: none; }
QListWidget::item { border: none; }
QListWidget::item:selected { background: %(SELECTION_BG)s; }
QScrollBar:vertical {
    background: transparent; width: 12px; margin: 0px;
}
QScrollBar::handle:vertical {
    background: %(BLUE)s; min-height: 20px; border-radius: 6px;
}
QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical { height: 0px; }
QGroupBox {
    color: %(BLUE)s; font-size: 13px; font-weight: bold;
    border: 1px solid %(CARD_BORDER)s; border-radius: 10px;
    margin-top: 10px; padding-top: 8px;
}
QGroupBox::title { subcontrol-origin: margin; left: 10px; padding: 0 4px; }
"""


def build_stylesheet(palette: dict, theme_id: str = DEFAULT_THEME) -> str:
    """Build the full QSS for a palette.

    Every colour comes from the palette — nothing is hardcoded in the
    template. The combo/spin chevron arrows are (re)generated in the
    palette's accent colour, so they always match the active theme's
    material. Unknown/missing palette keys fall back to the day theme.
    """
    p = dict(THEMES[DEFAULT_THEME]["palette"])
    p.update(palette or {})
    check_mark = _check_png("#FFFFFF", theme_id)
    chevron_down = _chevron_png("down", p["accent"], theme_id)
    chevron_up = _chevron_png("up", p["accent"], theme_id)
    subs = {
        "WINDOW_BG": p["window_bg"],
        "CARD_BG": p["card_bg"],
        "CARD_BORDER": p["card_border"],
        "BLUE": p["accent"],
        "BLUE_DARK": p["accent_dark"],
        "TEXT_DARK": p["text_dark"],
        "HINT": p["hint"],
        "SIDEBAR_BG": p["sidebar_bg"],
        "SIDEBAR_CARD_BG": p["sidebar_card_bg"],
        "SIDEBAR_CARD_BORDER": p["sidebar_card_border"],
        "SIDEBAR_TEXT": p["sidebar_text"],
        "SIDEBAR_HINT": p["sidebar_hint"],
        "HERO_TOP": p["hero_top"],
        "HERO_BOTTOM": p["hero_bottom"],
        "HERO_BORDER": p["hero_border"],
        "HERO_STAT_DIM": p["hero_stat_dim"],
        "BTN_TOP": p["btn_top"],
        "BTN_BOTTOM": p["btn_bottom"],
        "BTN_HOVER_TOP": p["btn_hover_top"],
        "BTN_HOVER_BOTTOM": p["btn_hover_bottom"],
        "BTN_DISABLED_BG": p["btn_disabled_bg"],
        "BTN_DISABLED_TEXT": p["btn_disabled_text"],
        "ENTRY_BG": p["entry_bg"],
        "ENTRY_BORDER": p["entry_border"],
        "SELECTION_BG": p["selection_bg"],
        "HISTORY_REASON": p["history_reason"],
        "CHEVRON_DOWN_CSS": ('image: url("%s");' % chevron_down) if chevron_down else "",
        "CHEVRON_UP_CSS": ('image: url("%s");' % chevron_up) if chevron_up else "",
        "CHECK_CSS": ('image: url("%s");' % check_mark) if check_mark else "",
    }
    return _STYLESHEET_TEMPLATE % subs


# Default stylesheet (day theme) so existing imports keep working.
STYLESHEET = build_stylesheet(_DAY, DEFAULT_THEME)


def apply_theme(qapp, palette: dict, theme_id: str = DEFAULT_THEME) -> None:
    """Apply a theme to the application: QSS plus placeholder colour.

    QSS cannot set placeholder-text colour, so the palette's hint colour
    is applied to QPalette.PlaceholderText here. Without it the "..."
    placeholders are nearly invisible in the night theme. Only the
    placeholder role is touched; every other palette colour is left
    alone.
    """
    qapp.setStyleSheet(build_stylesheet(palette, theme_id))
    pal = qapp.palette()
    pal.setColor(QPalette.PlaceholderText, QColor(palette.get("hint", "#5B7A99")))
    qapp.setPalette(pal)


def _apply_mica(window: QMainWindow) -> bool:
    """Apply real Windows 11 Mica to the window.

    Returns True when Mica was applied. On non-Windows platforms (Linux
    under Xvfb included) this is a no-op returning False; every Windows
    call is guarded so a failure never stops the app from launching.
    win32mica itself imports ``winreg`` at module level, so it can only
    be imported on Windows at all — import it lazily inside the check.
    """
    if sys.platform != "win32":
        return False
    try:
        hwnd = int(window.winId())
    except Exception:
        return False
    # Preferred route: win32mica (thin DWM wrapper).
    try:
        from win32mica import ApplyMica, MicaTheme

        ApplyMica(hwnd, MicaTheme.AUTO)
        return True
    except Exception:
        pass
    # Fallback: direct DWM call via ctypes
    # (DWMWA_SYSTEMBACKDROP_TYPE = 38, DWMSBT_MAINWINDOW / Mica = 2).
    try:
        dwm = ctypes.windll.dwmapi
        value = ctypes.c_int(2)
        hr = dwm.DwmSetWindowAttribute(
            ctypes.c_void_p(hwnd), 38,
            ctypes.byref(value), ctypes.sizeof(value),
        )
        return hr == 0
    except Exception:
        return False


def _lines(edit: QPlainTextEdit) -> list[str]:
    return [ln.strip() for ln in edit.toPlainText().splitlines() if ln.strip()]


def _set_lines(edit: QPlainTextEdit, lines):
    edit.setPlainText("\n".join(ln for ln in lines if str(ln).strip()))


def _blacklist_would_overwrite(old_list, new_list) -> bool:
    """True when saving `new_list` would clobber a non-empty `old_list`.

    Prefers config.blacklist_would_overwrite when the parallel feature
    work has landed it; otherwise the same check inline.
    """
    helper = getattr(cfgmod, "blacklist_would_overwrite", None)
    if callable(helper):
        try:
            return bool(helper(old_list, new_list))
        except Exception:
            pass
    old = [str(x).strip() for x in (old_list or []) if str(x).strip()]
    new = [str(x).strip() for x in (new_list or []) if str(x).strip()]
    return bool(old) and set(new) != set(old)


def _outcome_label(outcome: str, palette: dict | None = None):
    """(label, colour) for an outcome, coloured from the given palette
    (day palette when none is given) so the chip matches the theme."""
    key = str(outcome or "unknown")
    label = {
        "success": "成功", "failed": "失败", "risk": "风控",
        "unknown": "未知", "skipped": "跳过",
    }.get(key, "未知")
    p = palette or _DAY
    color = p.get("outcome_" + key, p.get("outcome_unknown", "#666666"))
    return label, color


def _prevent_sleep(enable: bool) -> None:
    """Hold off Windows idle sleep while the listener is running.

    Losing the poll loop to system sleep means not grabbing at all, so we
    take ES_CONTINUOUS + ES_SYSTEM_REQUIRED for the duration of a listen
    run and release it on stop/close. No-op off Windows; best effort.
    """
    if sys.platform != "win32":
        return
    try:
        import ctypes

        ES_CONTINUOUS = 0x80000000
        ES_SYSTEM_REQUIRED = 0x00000001
        ctypes.windll.kernel32.SetThreadExecutionState(
            ES_CONTINUOUS | (ES_SYSTEM_REQUIRED if enable else 0)
        )
    except Exception:
        pass

class App(QMainWindow):
    # Signals: worker threads emit these; slots run on the UI thread.
    log_signal = Signal(str, str)
    status_signal = Signal(str)
    login_state_signal = Signal(str)
    listen_state_signal = Signal(str)
    history_entry_signal = Signal(dict)
    refresh_history_signal = Signal()

    def __init__(self, cfg: dict):
        super().__init__()
        self.setWindowTitle(APP_TITLE)
        # WINDOW_SIZE is taller than a 1080p screen, so the bottom of the
        # sidebar (高级设置) opened off-screen. Fit the available desktop and
        # centre, keeping the declared size as an upper bound.
        screen = QApplication.primaryScreen()
        width, height = WINDOW_SIZE
        if screen is not None:
            area = screen.availableGeometry()
            width = min(width, max(1024, area.width() - 80))
            height = min(height, max(700, area.height() - 80))
        self.resize(width, height)
        if screen is not None:
            area = screen.availableGeometry()
            self.move(area.center().x() - width // 2, area.center().y() - height // 2)
        self.cfg = cfg
        # Active theme: palette + id, kept in sync by _on_theme_changed.
        self._theme_id = str(cfg.get("theme") or DEFAULT_THEME)
        if self._theme_id not in THEMES:
            self._theme_id = DEFAULT_THEME
        self._palette = palette_for(self._theme_id)
        self.client = self._make_client()
        self._log_history: list = []
        self.voice = Voice(enabled=bool(cfg.get("voice_enabled", True)))
        from notify import Notifier
        self.notifier = Notifier(cfg)
        self.engine: Engine | None = None
        self._listening = False
        self._login_thread: threading.Thread | None = None
        self.grab_count = 0
        self._recorded_order_keys: set = set()
        self._mica_applied = False

        self.log_signal.connect(self._on_log_line)
        self.status_signal.connect(self._on_status)
        self.login_state_signal.connect(self._on_login_state)
        self.listen_state_signal.connect(self._on_listen_state)
        self.history_entry_signal.connect(self._on_history_entry)
        self.refresh_history_signal.connect(self._refresh_history)

        self._build_widgets()
        self._load_settings_into_widgets()
        self._refresh_timer = QTimer(self)
        self._refresh_timer.timeout.connect(self._refresh_buttons)
        self._refresh_timer.start(500)
        # Canary: proves on launch that the log path is alive.
        self.log("info", "DPGrabber PC 已启动")
        try:
            from build_stamp import SHA as _build_sha
            self.log("info", "构建 %s" % _build_sha)
        except Exception:
            pass

    # -- construction -----------------------------------------------------

    def _make_client(self) -> ApiClient:
        return ApiClient(
            aes_key_hex=self.cfg["aes_key_hex"],
            aes_iv_hex=self.cfg["aes_iv_hex"],
            aes_key_hex_alt=self.cfg.get("aes_key_hex_alt", ""),
            aes_iv_hex_alt=self.cfg.get("aes_iv_hex_alt", ""),
            verify_tls=bool(self.cfg.get("verify_tls", True)),
        )

    def showEvent(self, event):
        super().showEvent(event)
        if not self._mica_applied:
            self._mica_applied = True
            _apply_mica(self)

    def _primary_button(self, text, slot):
        btn = QPushButton(text)
        btn.setObjectName("primary")
        btn.clicked.connect(slot)
        return btn

    def _secondary_button(self, text, slot):
        btn = QPushButton(text)
        btn.setObjectName("secondary")
        btn.clicked.connect(slot)
        return btn

    def _section_title(self, text):
        label = QLabel(text)
        label.setObjectName("sectionTitle")
        return label

    def _card(self):
        frame = QFrame()
        frame.setObjectName("card")
        return frame

    def _hint(self, text):
        label = QLabel(text)
        label.setObjectName("hint")
        label.setWordWrap(True)
        # Cap the hint column: without it a long hint wraps to four lines and
        # drags its row tall, so the form stops looking like a table.
        label.setMaximumWidth(440)
        return label

    def _build_widgets(self):
        central = QWidget()
        central.setObjectName("central")
        self.setCentralWidget(central)
        root = QVBoxLayout(central)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)

        self._build_hero(root)

        body = QWidget()
        body_layout = QHBoxLayout(body)
        body_layout.setContentsMargins(14, 10, 14, 10)
        body_layout.setSpacing(12)
        # The body's minimum size is larger than the desktop at 125% scaling,
        # so the window could not shrink and its bottom fell off-screen.
        # A scroll area lets the window stay at WINDOW_SIZE and scroll instead.
        scroll = QScrollArea()
        scroll.setObjectName("bodyScroll")
        scroll.setWidgetResizable(True)
        scroll.setFrameShape(QFrame.NoFrame)
        scroll.setWidget(body)
        root.addWidget(scroll, 1)

        sidebar = QFrame()
        sidebar.setObjectName("sidebar")
        sidebar.setFixedWidth(280)
        sidebar_layout = QVBoxLayout(sidebar)
        sidebar_layout.setContentsMargins(0, 0, 0, 0)
        sidebar_layout.setSpacing(0)
        self._build_login_section(sidebar_layout)
        self._build_control_section(sidebar_layout)
        self._build_theme_section(sidebar_layout)
        sidebar_layout.addStretch(1)
        self._build_advanced_button(sidebar_layout)
        sidebar_layout.addStretch(1)
        body_layout.addWidget(sidebar)

        main_area = QWidget()
        main_layout = QVBoxLayout(main_area)
        main_layout.setContentsMargins(0, 0, 0, 0)
        main_layout.setSpacing(0)
        self._build_conditions_section(main_layout)
        self._build_log_section(main_layout)
        self._build_history_section(main_layout)
        body_layout.addWidget(main_area, 1)

    def _build_hero(self, root):
        hero = QFrame()
        hero.setObjectName("hero")
        hero.setFixedHeight(56)
        layout = QHBoxLayout(hero)
        layout.setContentsMargins(20, 0, 20, 0)
        title = QLabel(APP_TITLE)
        title.setObjectName("heroTitle")
        layout.addWidget(title)
        layout.addStretch(1)
        self.grab_count_label = QLabel("今日抢单 0")
        self.grab_count_label.setObjectName("heroStat")
        layout.addWidget(self.grab_count_label)
        self.listen_state_label = QLabel("未开始监听")
        self.listen_state_label.setObjectName("heroStatDim")
        layout.addWidget(self.listen_state_label)
        self.status_label = QLabel("未登录")
        self.status_label.setObjectName("heroStatDim")
        layout.addWidget(self.status_label)
        root.addWidget(hero)

    def _sidebar_card(self, parent_layout, title):
        title_label = QLabel(title)
        title_label.setObjectName("sidebarTitle")
        parent_layout.addWidget(title_label)
        # Indent the title like the old UI (padx=14).
        title_label.setContentsMargins(14, 12, 14, 2)
        card = QFrame()
        card.setObjectName("sidebarCard")
        card_layout = QVBoxLayout(card)
        card_layout.setContentsMargins(12, 10, 12, 10)
        card_layout.setSpacing(4)
        wrapper = QVBoxLayout()
        wrapper.setContentsMargins(12, 0, 12, 12)
        wrapper.addWidget(card)
        parent_layout.addLayout(wrapper)
        return card, card_layout

    def _build_login_section(self, sidebar_layout):
        card, layout = self._sidebar_card(sidebar_layout, "账号登录")
        phone_label = QLabel("手机号")
        phone_label.setObjectName("sidebarLabel")
        layout.addWidget(phone_label)
        self.phone_edit = QLineEdit()
        self.phone_edit.setPlaceholderText("11 位手机号")
        layout.addWidget(self.phone_edit)
        hint = QLabel("登录用的手机号，11 位数字")
        hint.setObjectName("sidebarHint")
        layout.addWidget(hint)
        layout.addSpacing(4)
        pw_label = QLabel("密码")
        pw_label.setObjectName("sidebarLabel")
        layout.addWidget(pw_label)
        self.password_edit = QLineEdit()
        self.password_edit.setEchoMode(QLineEdit.Password)
        layout.addWidget(self.password_edit)
        self.show_pw_check = QCheckBox("显示密码")
        self.show_pw_check.setObjectName("sidebarCheck")
        self.show_pw_check.toggled.connect(self._toggle_show_password)
        layout.addWidget(self.show_pw_check)
        btn_row = QHBoxLayout()
        btn_row.addWidget(self._primary_button("登录", self._on_login))
        btn_row.addWidget(self._secondary_button("注销", self._on_logout))
        btn_row.addStretch(1)
        layout.addLayout(btn_row)
        self.login_state_label = QLabel("请输入手机号和密码后登录")
        self.login_state_label.setObjectName("sidebarState")
        self.login_state_label.setWordWrap(True)
        layout.addWidget(self.login_state_label)

    def _form_row(self, grid, row, label, widget, hint):
        lab = QLabel(label)
        lab.setObjectName("fieldLabel")
        lab.setAlignment(Qt.AlignRight | Qt.AlignVCenter)
        grid.addWidget(lab, row, 0)
        grid.addWidget(widget, row, 1)
        grid.addWidget(self._hint(hint), row, 2)

    def _build_conditions_section(self, main_layout):
        header = QHBoxLayout()
        header.addWidget(self._section_title("接单条件"))
        header.addStretch(1)
        header.addWidget(self._primary_button("保存设置", self._on_save_settings))
        main_layout.addLayout(header)
        card = self._card()
        card_layout = QVBoxLayout(card)
        card_layout.setContentsMargins(12, 8, 12, 8)
        inner = QWidget()
        grid = QGridLayout(inner)
        grid.setContentsMargins(0, 0, 0, 0)
        grid.setColumnStretch(2, 1)
        self.min_amount_edit = QLineEdit()
        self._form_row(grid, 0, "最小金额（元）", self.min_amount_edit,
                       "不低于此价")
        self.max_amount_edit = QLineEdit()
        self._form_row(grid, 1, "最大金额（元）", self.max_amount_edit,
                       "填 0 不限制")
        self.poll_interval_edit = QLineEdit()
        self._form_row(grid, 2, "轮询间隔（秒）", self.poll_interval_edit,
                       "列表刷新节奏")
        for widget in (self.min_amount_edit, self.max_amount_edit,
                       self.poll_interval_edit):
            widget.setFixedWidth(190)
        self.memo_mode_box = QComboBox()
        self.memo_mode_box.addItems(["不检测备注", "有备注不抢"])
        self._form_row(grid, 3, "备注检测", self.memo_mode_box,
                       "有备注即跳过")
        self.confirm_wait_min_spin = QSpinBox()
        self.confirm_wait_min_spin.setRange(0, 120)
        self.confirm_wait_min_spin.setSuffix(" 秒")
        self._form_row(grid, 4, "抢单前等待最小（秒）", self.confirm_wait_min_spin,
                       "0 不等待；可与最大值相同")
        self.confirm_wait_max_spin = QSpinBox()
        self.confirm_wait_max_spin.setRange(0, 120)
        self.confirm_wait_max_spin.setSuffix(" 秒")
        self._form_row(grid, 5, "抢单前等待最大（秒）", self.confirm_wait_max_spin,
                       "与最小值相同即固定等待")
        # A stretched form leaves a huge empty band beside every hint.
        inner.setMaximumWidth(980)
        for widget in (self.memo_mode_box, self.confirm_wait_min_spin,
                       self.confirm_wait_max_spin):
            widget.setFixedWidth(190)
        card_layout.addWidget(inner, 0, Qt.AlignLeft)

        # 刷新与休息 (run/rest): visible toggle + four minute fields.
        rest_group = QGroupBox("刷新与休息")
        rest_grid = QGridLayout(rest_group)
        self.run_rest_check = QCheckBox("开启刷新与休息（风控店铺轮换休息）")
        self.run_rest_check.setChecked(True)
        rest_grid.addWidget(self.run_rest_check, 0, 0, 1, 4)
        self.risk_run_min_spin = QSpinBox()
        self.risk_run_min_spin.setRange(1, 600)
        self.risk_run_min_spin.setSuffix(" 分钟")
        self.risk_run_max_spin = QSpinBox()
        self.risk_run_max_spin.setRange(1, 600)
        self.risk_run_max_spin.setSuffix(" 分钟")
        self.risk_rest_min_spin = QSpinBox()
        self.risk_rest_min_spin.setRange(0, 600)
        self.risk_rest_min_spin.setSuffix(" 分钟")
        self.risk_rest_max_spin = QSpinBox()
        self.risk_rest_max_spin.setRange(0, 600)
        self.risk_rest_max_spin.setSuffix(" 分钟")
        for col, (lab_text, spin) in enumerate([
            ("刷新最短", self.risk_run_min_spin),
            ("刷新最长", self.risk_run_max_spin),
            ("休息最短", self.risk_rest_min_spin),
            ("休息最长", self.risk_rest_max_spin),
        ]):
            lab = QLabel(lab_text)
            lab.setObjectName("fieldLabel")
            rest_grid.addWidget(lab, 1, col * 2)
            rest_grid.addWidget(spin, 1, col * 2 + 1)
        hint = self._hint("每轮随机刷新时长后休随机时长")
        rest_grid.addWidget(hint, 2, 0, 1, 8)
        card_layout.addWidget(rest_group)

        main_layout.addWidget(card)
        main_layout.addSpacing(10)

    def _build_control_section(self, sidebar_layout):
        card, layout = self._sidebar_card(sidebar_layout, "监听控制")
        btn_row = QHBoxLayout()
        self.start_btn = self._primary_button("开始监听", self._on_start_listen)
        self.stop_btn = self._secondary_button("停止监听", self._on_stop_listen)
        self.stop_btn.setEnabled(False)
        btn_row.addWidget(self.start_btn)
        btn_row.addWidget(self.stop_btn)
        btn_row.addStretch(1)
        layout.addLayout(btn_row)
        self.listen_state_sidebar_label = QLabel("未开始监听")
        self.listen_state_sidebar_label.setObjectName("sidebarState")
        self.listen_state_sidebar_label.setWordWrap(True)
        layout.addWidget(self.listen_state_sidebar_label)

    def _build_theme_section(self, sidebar_layout):
        card, layout = self._sidebar_card(sidebar_layout, "主题")
        self.theme_box = QComboBox()
        # Six items must show as six rows: without a cap Qt sizes the popup
        # from the screen, which is how it ended up as a tall white slab.
        self.theme_box.setMaxVisibleItems(10)
        for theme_id, theme in THEMES.items():
            self.theme_box.addItem(theme["name"], theme_id)
        idx = list(THEMES.keys()).index(self._theme_id)
        self.theme_box.setCurrentIndex(idx)
        self.theme_box.currentIndexChanged.connect(self._on_theme_changed)
        layout.addWidget(self.theme_box)
        hint = QLabel("切换后立即生效，并记住选择")
        hint.setObjectName("sidebarHint")
        hint.setWordWrap(True)
        layout.addWidget(hint)

    def _on_theme_changed(self, index: int):
        theme_id = str(self.theme_box.itemData(index) or DEFAULT_THEME)
        if theme_id not in THEMES or theme_id == self._theme_id:
            return
        self._theme_id = theme_id
        self._palette = palette_for(theme_id)
        app = QApplication.instance()
        if app is not None:
            apply_theme(app, self._palette, theme_id)
        self._rebuild_log()
        self.cfg["theme"] = theme_id
        try:
            cfgmod.save(self.cfg)
        except cfgmod.ConfigError:
            pass  # theme still applies this session; the save retries later
        # Rebuild history rows so outcome chips use the new palette.
        self._refresh_history()
        self.log("info", "主题已切换为%s" % THEMES[theme_id]["name"])

    def _build_advanced_button(self, sidebar_layout):
        row = QHBoxLayout()
        row.setContentsMargins(12, 0, 12, 0)
        row.addWidget(self._secondary_button("高级设置", self._open_advanced))
        row.addStretch(1)
        sidebar_layout.addLayout(row)

    def _build_log_section(self, main_layout):
        main_layout.addWidget(self._section_title("运行日志"))
        card = self._card()
        card_layout = QVBoxLayout(card)
        card_layout.setContentsMargins(12, 10, 12, 10)
        # QPlainTextEdit: retained-mode, so the log survives maximize /
        # restore (the old CTkTextbox lost its content on maximize).
        self.log_text = QPlainTextEdit()
        self.log_text.setObjectName("logView")
        self.log_text.setReadOnly(True)
        self.log_text.setLineWrapMode(QPlainTextEdit.WidgetWidth)
        # Cap retained lines: a 0.5 s poll loop otherwise grows the log
        # without bound and every repaint gets slower.
        self.log_text.setMaximumBlockCount(2000)
        card_layout.addWidget(self.log_text, 1)
        clear_row = QHBoxLayout()
        clear_row.addWidget(self._secondary_button("清空日志", self._clear_log))
        clear_row.addStretch(1)
        card_layout.addLayout(clear_row)
        main_layout.addWidget(card, 1)
        main_layout.addSpacing(10)

    def _build_history_section(self, main_layout):
        header = QHBoxLayout()
        header.addWidget(self._section_title("接单历史"))
        header.addStretch(1)
        header.addWidget(self._secondary_button("清空历史", self._on_clear_history))
        main_layout.addLayout(header)
        card = self._card()
        card_layout = QVBoxLayout(card)
        card_layout.setContentsMargins(12, 10, 12, 10)
        self.history_list = QListWidget()
        self.history_list.setContextMenuPolicy(Qt.CustomContextMenu)
        self.history_list.customContextMenuRequested.connect(self._on_history_context_menu)
        self.history_list.setFixedHeight(180)
        card_layout.addWidget(self.history_list)
        main_layout.addWidget(card)
        self._refresh_history()

    # -- history ------------------------------------------------------------

    def _history_entry_for_order(self, order: dict, order_id: str,
                                 outcome: str, reason: str) -> dict:
        return {
            "grabbed_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
            "no": str(order.get("no") or order_id),
            "id": str(order_id),
            "amount": round(amount_yuan_from_cents(order.get("totalAmount")), 2),
            "userWechatId": str(order.get("userWechatId") or ""),
            "userMemo": str(order.get("userMemo") or ""),
            "createTime": str(order.get("createTime") or ""),
            "outcome": outcome,
            "reason": reason or "",
        }

    def _on_history_entry(self, entry: dict):
        """UI-thread slot: persist one history entry and refresh the list."""
        try:
            history_mod.append_history(entry)
        except Exception:
            pass
        self._refresh_history()

    def _refresh_history(self):
        """Rebuild the history rows from the stored file, newest first."""
        self.history_list.clear()
        entries = history_mod.load_history()
        if not entries:
            item = QListWidgetItem("暂无接单记录，抢到单后会自动出现在这里")
            item.setTextAlignment(Qt.AlignCenter)
            item.setForeground(QColor(HINT))
            self.history_list.addItem(item)
            return
        for entry in reversed(entries):
            self._add_history_row(entry)

    def _add_history_row(self, entry: dict):
        grabbed = str(entry.get("grabbed_at") or "").replace("T", " ")
        try:
            amount = float(entry.get("amount") or 0)
        except (TypeError, ValueError):
            amount = 0.0
        no = str(entry.get("no") or entry.get("id") or "")
        wechat = str(entry.get("userWechatId") or "未知")
        memo = str(entry.get("userMemo") or "")
        # Old entries have no outcome/reason: display as success / no reason.
        outcome = str(entry.get("outcome") or "success")
        reason = str(entry.get("reason") or "")
        label, color = _outcome_label(outcome, self._palette)
        widget = QWidget()
        layout = QVBoxLayout(widget)
        layout.setContentsMargins(2, 4, 2, 4)
        layout.setSpacing(1)
        line1 = QLabel("%s   ¥%.2f   订单号 %s   [%s]" % (grabbed, amount, no, label))
        line1.setObjectName("historyLine1")
        layout.addWidget(line1)
        line2 = QLabel("微信 %s   ·   备注：%s" % (wechat, memo if memo else "无备注"))
        line2.setObjectName("historyLine2")
        layout.addWidget(line2)
        if outcome != "success" and reason:
            line3 = QLabel("原因：%s" % reason)
            line3.setObjectName("historyReason")
            layout.addWidget(line3)
        elif outcome != "success":
            line3 = QLabel("结果：%s" % label)
            line3.setObjectName("historyReason")
            layout.addWidget(line3)
        # Outcome colour chip on line 1 via a coloured status word.
        line1.setText(
            "%s   ¥%.2f   订单号 %s   " % (grabbed, amount, no)
            + "<span style='color:%s'>[%s]</span>" % (color, label)
        )
        line1.setTextFormat(Qt.RichText)
        item = QListWidgetItem()
        item.setData(Qt.UserRole, dict(entry))
        item.setSizeHint(widget.sizeHint())
        self.history_list.addItem(item)
        self.history_list.setItemWidget(item, widget)

    def _on_history_context_menu(self, pos):
        """Right-click a row: blacklist its WeChat id or add its memo keyword."""
        item = self.history_list.itemAt(pos)
        if item is None:
            return
        entry = item.data(Qt.UserRole) or {}
        wechat = str(entry.get("userWechatId") or "").strip()
        memo = str(entry.get("userMemo") or "").strip()
        menu = QMenu(self)
        black_action = None
        keyword_action = None
        if wechat:
            black_action = menu.addAction("拉黑微信号 %s" % wechat)
        else:
            menu.addAction("该订单没有微信号").setEnabled(False)
        if memo:
            short = memo if len(memo) <= 18 else memo[:18] + "…"
            keyword_action = menu.addAction("把备注加入关键词「%s」" % short)
        else:
            menu.addAction("该订单没有备注").setEnabled(False)
        menu.addSeparator()
        copy_action = menu.addAction("复制订单号")
        chosen = menu.exec(self.history_list.mapToGlobal(pos))
        if chosen is None:
            return
        if chosen is black_action:
            self._append_to_config_list("blacklist", wechat, "已拉黑微信号")
        elif chosen is keyword_action:
            self._append_to_config_list("memo_keywords", memo, "已加入备注关键词")
        elif chosen is copy_action:
            from PySide6.QtGui import QGuiApplication
            QGuiApplication.clipboard().setText(str(entry.get("no") or ""))
            self.log("info", "订单号已复制到剪贴板")

    def _append_to_config_list(self, key: str, value: str, message: str):
        """Append to blacklist / memo_keywords, persist, and say so."""
        value = (value or "").strip()
        if not value:
            return
        values = [str(v) for v in (self.cfg.get(key) or [])]
        if value not in values:
            values.append(value)
            self.cfg[key] = values
            try:
                cfgmod.save(self.cfg)
            except Exception as exc:
                self.log("warning", "保存配置失败：%s" % exc)
                return
        self.log("success", "%s：%s" % (message, value))

    def _on_clear_history(self):
        history_mod.clear_history()
        self._refresh_history()
        self.log("info", "接单历史已清空")

    def _on_grab(self, order: dict, order_id: str, confirm_calls: int):
        """Engine callback on a confirmed grab (worker thread): record it."""
        entry = self._history_entry_for_order(order, order_id, "success", "")
        self._recorded_order_keys.add((entry["no"], entry["id"]))
        self.history_entry_signal.emit(entry)
        self._push_grab(entry)

    def _push_grab(self, entry: dict):
        """Push the successful grab on a worker thread; never blocks or raises."""
        if not getattr(self, "notifier", None) or not self.notifier.enabled:
            return
        title = "抢单成功 %s" % entry.get("no", "")
        body = "金额 %s 元\n订单 %s\n时间 %s" % (
            entry.get("amount", "?"), entry.get("no", ""), entry.get("grabbed_at", ""),
        )

        def worker():
            ok = self.notifier.send(title, body)
            self.log("info" if ok else "warning",
                     "推送(%s)%s" % (self.notifier.describe(), "已发送" if ok else "失败"))

        threading.Thread(target=worker, daemon=True).start()

    def _on_outcome(self, order: dict, outcome: str, reason: str):
        """Engine callback once per order leaving the confirm loop.

        Success is already recorded by on_grab, so skip it here (unless
        on_grab never fired for this order). Non-success outcomes are
        recorded so 接单历史 shows failed / risk / unknown / skipped too.
        """
        outcome = str(outcome or "unknown")
        order_id = str(order.get("id") or order.get("no") or "")
        no = str(order.get("no") or order_id)
        if outcome == "success" and (no, order_id) in self._recorded_order_keys:
            return
        entry = self._history_entry_for_order(order, order_id, outcome, reason or "")
        self._recorded_order_keys.add((entry["no"], entry["id"]))
        self.history_entry_signal.emit(entry)

    # -- settings -----------------------------------------------------------

    def _load_settings_into_widgets(self):
        self.phone_edit.setText(self.cfg.get("phone", ""))
        self.password_edit.setText(self.cfg.get("password", ""))
        self.min_amount_edit.setText(str(self.cfg.get("min_amount", 10.0)))
        self.max_amount_edit.setText(str(self.cfg.get("max_amount", 0.0)))
        self.poll_interval_edit.setText(str(self.cfg.get("poll_interval_sec", 0.5)))
        self.memo_mode_box.setCurrentIndex(1 if int(self.cfg.get("memo_mode", 0)) == 1 else 0)
        self.confirm_wait_min_spin.setValue(int(self.cfg.get("confirm_wait_min", 0) or 0))
        self.confirm_wait_max_spin.setValue(int(self.cfg.get("confirm_wait_max", 0) or 0))
        self.run_rest_check.setChecked(bool(self.cfg.get("run_rest_enabled", True)))
        self.risk_run_min_spin.setValue(int(self.cfg.get("risk_run_min", 50) or 50))
        self.risk_run_max_spin.setValue(int(self.cfg.get("risk_run_max", 60) or 60))
        self.risk_rest_min_spin.setValue(int(self.cfg.get("risk_rest_min", 10)))
        self.risk_rest_max_spin.setValue(int(self.cfg.get("risk_rest_max", 20)))

    def _read_settings_from_widgets(self) -> dict:
        try:
            min_amount = float(self.min_amount_edit.text())
            max_amount = float(self.max_amount_edit.text())
            poll_interval = float(self.poll_interval_edit.text())
        except ValueError:
            raise cfgmod.ConfigError("金额和轮询间隔必须是数字")
        if min_amount < 0 or max_amount < 0:
            raise cfgmod.ConfigError("金额不能小于 0")
        if max_amount > 0 and max_amount < min_amount:
            raise cfgmod.ConfigError("最大金额不能小于最小金额；填 0 表示不限制")
        if poll_interval <= 0:
            raise cfgmod.ConfigError("轮询间隔必须大于 0")
        confirm_min = int(self.confirm_wait_min_spin.value())
        confirm_max = int(self.confirm_wait_max_spin.value())
        if confirm_min > 0 or confirm_max > 0:
            # A fixed wait (min == max) is a valid configuration; only an
            # inverted or absurd window is refused.
            if confirm_max < confirm_min:
                raise cfgmod.ConfigError("最大等待不能小于最小等待")
            if confirm_max > 120:
                raise cfgmod.ConfigError("等待秒数上限 120 秒")
        run_enabled = self.run_rest_check.isChecked()
        run_min = int(self.risk_run_min_spin.value())
        run_max = int(self.risk_run_max_spin.value())
        rest_min = int(self.risk_rest_min_spin.value())
        rest_max = int(self.risk_rest_max_spin.value())
        if run_enabled:
            if run_max < run_min:
                raise cfgmod.ConfigError("刷新最长分钟数不能小于刷新最短")
            if rest_max < rest_min:
                raise cfgmod.ConfigError("休息最长分钟数不能小于休息最短")
        return {
            "phone": self.phone_edit.text().strip(),
            "password": self.password_edit.text(),
            "min_amount": min_amount,
            "max_amount": max_amount,
            "poll_interval_sec": poll_interval,
            "memo_mode": 1 if self.memo_mode_box.currentIndex() == 1 else 0,
            "confirm_wait_min": confirm_min,
            "confirm_wait_max": confirm_max,
            "run_rest_enabled": run_enabled,
            "risk_run_min": run_min,
            "risk_run_max": run_max,
            "risk_rest_min": rest_min,
            "risk_rest_max": rest_max,
        }

    def _on_save_settings(self):
        try:
            updates = self._read_settings_from_widgets()
        except cfgmod.ConfigError as exc:
            QMessageBox.critical(self, "设置有误", str(exc))
            return
        self.cfg.update(updates)
        try:
            cfgmod.save(self.cfg)
        except cfgmod.ConfigError as exc:
            QMessageBox.critical(self, "保存失败", str(exc))
            return
        self.log("success", "设置已保存")

    def _open_advanced(self):
        dialog = AdvancedDialog(self, self.cfg, on_saved=self._on_advanced_saved)
        dialog.exec()

    def _on_advanced_saved(self, new_cfg: dict):
        self.cfg = new_cfg
        self.client = self._make_client()
        self.voice.enabled = bool(new_cfg.get("voice_enabled", True))
        from notify import Notifier
        self.notifier = Notifier(new_cfg)
        self.log("success", "高级设置已保存")

    # -- logging (signal-driven; worker threads only emit) ------------------

    def log(self, level: str, message: str):
        self.log_signal.emit(level, message)

    def _on_log_line(self, level: str, message: str):
        stamp = time.strftime("%H:%M:%S")
        text = "[%s] %s" % (stamp, message)
        # Per-line colours are baked in when the line is written, so a theme
        # switch has to re-render the whole document or old lines keep the old
        # theme's ink (dark text on a dark background read as "still broken").
        self._log_history.append((level, text))
        # Colours come from the active theme's palette so the log stays
        # readable on dark and light themes alike.
        p = self._palette
        colors = {
            "info": p.get("log_info", TEXT_DARK),
            "success": p.get("log_success", "#1A7F37"),
            "warning": p.get("log_warning", "#9A6700"),
            "error": p.get("log_error", "#CF222E"),
            "debug": p.get("log_debug", "#666666"),
        }
        try:
            cursor = self.log_text.textCursor()
            cursor.movePosition(QTextCursor.End)
            fmt = QTextCharFormat()
            fmt.setForeground(QColor(colors.get(level, TEXT_DARK)))
            cursor.setCharFormat(fmt)
            cursor.insertText(text + "\n")
            self.log_text.setTextCursor(cursor)
            self.log_text.ensureCursorVisible()
        except Exception:
            try:
                self.log_text.appendPlainText(text)
            except Exception:
                pass
        self._log_history_trim()
        self._mirror_log_line(text + "\n")
        if level == "success" and "抢单成功" in message:
            self.grab_count += 1
            self.grab_count_label.setText("今日抢单 %d" % self.grab_count)
        if level in ("success", "error", "warning"):
            try:
                self.voice.speak(message)
            except Exception:
                pass

    def _mirror_log_line(self, text: str):
        # Best-effort copy of the run log next to the config file, so a
        # display problem never destroys the evidence. Never raises.
        try:
            path = os.path.join(cfgmod.config_dir(), "dpgrabber_pc.log")
            if os.path.exists(path) and os.path.getsize(path) > 512 * 1024:
                open(path, "w", encoding="utf-8").close()
            with open(path, "a", encoding="utf-8") as f:
                f.write(text)
        except Exception:
            pass

    _LOG_HISTORY_MAX = 2000

    def _log_history_trim(self):
        history = getattr(self, "_log_history", None)
        if history is not None and len(history) > self._LOG_HISTORY_MAX:
            del history[0:len(history) - self._LOG_HISTORY_MAX]

    def _rebuild_log(self):
        """Re-colour every retained line with the active palette."""
        history = list(getattr(self, "_log_history", []))
        if not history:
            return
        p = self._palette
        colors = {
            "info": p.get("log_info", TEXT_DARK),
            "success": p.get("log_success", "#1A7F37"),
            "warning": p.get("log_warning", "#9A6700"),
            "error": p.get("log_error", "#CF222E"),
            "debug": p.get("log_debug", "#666666"),
        }
        try:
            self.log_text.clear()
            cursor = self.log_text.textCursor()
            for level, text in history:
                cursor.movePosition(QTextCursor.End)
                fmt = QTextCharFormat()
                fmt.setForeground(QColor(colors.get(level, TEXT_DARK)))
                cursor.setCharFormat(fmt)
                cursor.insertText(text + "\n")
            self.log_text.setTextCursor(cursor)
            self.log_text.ensureCursorVisible()
        except Exception:
            pass

    def _clear_log(self):
        self.log_text.clear()

    def _on_status(self, text: str):
        self.status_label.setText(text)

    def _on_login_state(self, text: str):
        self.login_state_label.setText(text)

    def _on_listen_state(self, text: str):
        self.listen_state_label.setText(text)
        self.listen_state_sidebar_label.setText(text)

    # -- login --------------------------------------------------------------

    def _toggle_show_password(self, checked: bool):
        self.password_edit.setEchoMode(
            QLineEdit.Normal if checked else QLineEdit.Password
        )

    def _on_login(self):
        phone = self.phone_edit.text().strip()
        password = self.password_edit.text()
        if not phone or not password:
            QMessageBox.warning(self, "缺少信息", "请先填写手机号和密码")
            return
        if len(phone) != 11 or not phone.isdigit():
            QMessageBox.warning(self, "手机号格式", "手机号应为 11 位数字")
            return
        if self._login_thread is not None and self._login_thread.is_alive():
            return
        self.login_state_signal.emit("正在登录，请稍后…")
        self._login_thread = threading.Thread(
            target=self._login_worker, args=(phone, password), daemon=True
        )
        self._login_thread.start()

    def _login_worker(self, phone: str, password: str):
        try:
            session = self.client.session_for_account(
                phone, password, int(self.cfg.get("shop_id", 0)),
            on_note=lambda note: self.log("info", note)
            )
            clerk = None
            try:
                clerk = extract_clerk(self.client.clerk_get(session))
            except ApiError:
                clerk = None
            if clerk:
                label = "已登录：%s（ID：%s），店铺 sid=%s" % (
                    clerk.get("name") or "", clerk.get("id") or "", session.sid,
                )
            else:
                label = "已登录，店铺 sid=%s" % session.sid
            self.log("success", label)
            self.status_signal.emit(label)
            self.login_state_signal.emit(label)
        except ApiError as exc:
            self.log("error", "登录失败：%s" % exc.message)
            self.status_signal.emit("未登录")
            self.login_state_signal.emit("登录失败：%s" % exc.message)

    def _on_logout(self):
        if self.engine is not None and self.engine.running:
            self._on_stop_listen()
        self.client.session = None
        self.status_signal.emit("未登录")
        self.login_state_signal.emit("已注销，请重新登录")
        self.log("info", "已注销")

    # -- listening ----------------------------------------------------------

    def _make_engine(self) -> Engine:
        kwargs = {"on_grab": self._on_grab}
        # on_outcome lands with the parallel feature work; only pass it
        # when this Engine build accepts it, so the UI works either way.
        try:
            params = inspect.signature(Engine.__init__).parameters
            if "on_outcome" in params:
                kwargs["on_outcome"] = self._on_outcome
        except (TypeError, ValueError):
            pass
        return Engine(
            self.client, self.cfg, log=self.log, speak=self.voice.speak,
            **kwargs,
        )

    def _on_start_listen(self):
        if self.engine is not None and self.engine.running:
            return
        try:
            updates = self._read_settings_from_widgets()
        except cfgmod.ConfigError as exc:
            QMessageBox.critical(self, "设置有误", str(exc))
            return
        phone = updates["phone"]
        password = updates["password"]
        if not phone or not password:
            QMessageBox.warning(self, "缺少信息", "请先填写手机号和密码")
            return
        self.cfg.update(updates)
        self.engine = self._make_engine()
        if self.engine.start(phone, password):
            self._listening = True
            _prevent_sleep(True)
            self.listen_state_signal.emit("正在监听")
            self._set_listen_buttons(True)

    def _set_listen_buttons(self, listening: bool):
        self.start_btn.setEnabled(not listening)
        self.stop_btn.setEnabled(listening)

    def _on_stop_listen(self):
        self._listening = False
        if self.engine is not None:
            self.engine.stop()
        self.listen_state_signal.emit("已停止监听")
        self._set_listen_buttons(False)
        _prevent_sleep(False)

    def _refresh_buttons(self):
        # Engine stopped on its own (e.g. grab succeeded).
        if self._listening and self.engine is not None and not self.engine.running:
            self._listening = False
            self.listen_state_signal.emit("监听已停止")
            self._set_listen_buttons(False)
            _prevent_sleep(False)

    def closeEvent(self, event):
        if self.engine is not None:
            self.engine.stop()
        self.voice.stop()
        _prevent_sleep(False)
        event.accept()


class AdvancedDialog(QDialog):
    """Advanced settings: memo keywords, blacklist, shop id, timeout,
    TLS, voice, auto-wait offset. Decryption keys are NOT shown here
    (customers never see them); they live in the config file / code
    defaults only."""

    test_result = Signal(bool, str)

    def __init__(self, parent: App, cfg: dict, on_saved):
        super().__init__(parent)
        self.setWindowTitle("高级设置")
        self.setFixedSize(800, 700)
        self.cfg = dict(cfg)
        self._on_saved = on_saved

        outer = QVBoxLayout(self)
        outer.setContentsMargins(16, 14, 16, 14)
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setFrameShape(QFrame.NoFrame)
        content = QWidget()
        root = QVBoxLayout(content)
        root.setContentsMargins(0, 0, 0, 0)
        scroll.setWidget(content)
        outer.addWidget(scroll, 1)
        title = QLabel("高级设置")
        title.setObjectName("sectionTitle")
        title.setStyleSheet("font-size: 15px;")
        root.addWidget(title)

        form = QWidget()
        grid = QGridLayout(form)
        grid.setContentsMargins(0, 4, 0, 0)
        grid.setColumnStretch(2, 1)
        self.entries: dict[str, QLineEdit] = {}
        rows = [
            ("shop_id", "优先店铺（0=自动）",
         "0 = 自动挑选账号有店员权限的店铺；填数字则优先该店铺，无权限时自动改选"),
            ("request_timeout_sec", "请求超时（秒）", "单次请求最长等待时间"),
            ("notify_key", "推送密钥", "Bark 设备 Key / Server酱 SendKey / PushPlus token"),
            ("notify_webhook", "推送 Webhook", "自定义地址，收到 {title, body} JSON；非空时优先于密钥"),
            ("notify_bark_server", "Bark 服务端", "默认 https://api.day.app；自建填自己的域名"),
        ]
        for i, (key, label, hint) in enumerate(rows):
            lab = QLabel(label)
            lab.setObjectName("fieldLabel")
            lab.setAlignment(Qt.AlignRight | Qt.AlignVCenter)
            grid.addWidget(lab, i, 0)
            edit = QLineEdit(str(self.cfg.get(key, "")))
            edit.setFixedWidth(210)
            edit.setCursorPosition(0)
            self.entries[key] = edit
            grid.addWidget(edit, i, 1)
            h = QLabel(hint)
            h.setObjectName("hint")
            grid.addWidget(h, i, 2)
        # 自动等待偏移 (秒): only applies to shops 50/105/1391.
        auto_row = len(rows)
        lab = QLabel("自动等待偏移（秒）")
        lab.setObjectName("fieldLabel")
        lab.setAlignment(Qt.AlignRight | Qt.AlignVCenter)
        grid.addWidget(lab, auto_row, 0)
        self.auto_wait_offset_spin = QDoubleSpinBox()
        self.auto_wait_offset_spin.setRange(-0.5, 0.5)
        self.auto_wait_offset_spin.setSingleStep(0.05)
        self.auto_wait_offset_spin.setDecimals(3)
        try:
            self.auto_wait_offset_spin.setValue(float(self.cfg.get("auto_wait_offset", 0.0) or 0.0))
        except (TypeError, ValueError):
            self.auto_wait_offset_spin.setValue(0.0)
        grid.addWidget(self.auto_wait_offset_spin, auto_row, 1)
        h = QLabel("仅适用于店铺 50 / 105 / 1391；正值延后、负值提前")
        h.setObjectName("hint")
        grid.addWidget(h, auto_row, 2)
        root.addWidget(form)

        lists_row = QHBoxLayout()
        kw_box = QVBoxLayout()
        kw_title = QLabel("备注关键词（一行一个）")
        kw_title.setObjectName("fieldLabel")
        kw_box.addSpacing(8)
        kw_box.addWidget(kw_title)
        kw_hint = QLabel("命中任意一个就不抢")
        kw_hint.setObjectName("hint")
        kw_box.addWidget(kw_hint)
        self.keyword_text = QPlainTextEdit()
        self.keyword_text.setObjectName("listEdit")
        self.keyword_text.setPlaceholderText("例如：加急")
        self.keyword_text.setFixedHeight(70)
        _set_lines(self.keyword_text, self.cfg.get("memo_keywords", []))
        kw_box.addWidget(self.keyword_text)
        lists_row.addLayout(kw_box, 1)
        bl_box = QVBoxLayout()
        bl_title = QLabel("黑名单微信号（一行一个）")
        bl_title.setObjectName("fieldLabel")
        bl_box.addSpacing(8)
        bl_box.addWidget(bl_title)
        bl_hint = QLabel("每行一个微信号，命中就不抢")
        bl_hint.setObjectName("hint")
        bl_box.addWidget(bl_hint)
        self.blacklist_text = QPlainTextEdit()
        self.blacklist_text.setObjectName("listEdit")
        self.blacklist_text.setPlaceholderText("例如：wechat123")
        self.blacklist_text.setFixedHeight(70)
        _set_lines(self.blacklist_text, self.cfg.get("blacklist", []))
        bl_box.addWidget(self.blacklist_text)
        lists_row.addLayout(bl_box, 1)
        root.addLayout(lists_row)

        self.verify_check = QCheckBox("验证服务器 TLS 证书（推荐开启）")
        self.verify_check.setChecked(bool(self.cfg.get("verify_tls", True)))
        root.addWidget(self.verify_check)
        self.notify_row = QHBoxLayout()
        self.notify_check = QCheckBox("开启抢单推送")
        self.notify_check.setChecked(bool(self.cfg.get("notify_enabled", False)))
        self.notify_provider_box = QComboBox()
        from notify import PROVIDERS, PROVIDER_NAMES
        for key in PROVIDERS:
            self.notify_provider_box.addItem(PROVIDER_NAMES[key], key)
        current = str(self.cfg.get("notify_provider") or "serverchan")
        index = [self.notify_provider_box.itemData(i) for i in range(self.notify_provider_box.count())].index(current)          if current in [self.notify_provider_box.itemData(i) for i in range(self.notify_provider_box.count())] else 0
        self.notify_provider_box.setCurrentIndex(index)
        self.test_button = QPushButton("发送测试")
        self.test_button.setObjectName("secondary")
        self.test_button.clicked.connect(self._send_test_push)
        self.notify_row.addWidget(self.notify_check)
        self.notify_row.addWidget(self.notify_provider_box)
        self.notify_row.addWidget(self.test_button)
        self.notify_row.addStretch(1)
        root.addLayout(self.notify_row)
        self.test_label = QLabel("")
        self.test_label.setObjectName("hint")
        root.addWidget(self.test_label)
        self.test_result.connect(self._show_test_result)
        self.voice_check = QCheckBox("语音播报（需要本机语音支持）")
        self.voice_check.setChecked(bool(self.cfg.get("voice_enabled", True)))
        root.addWidget(self.voice_check)
        path_label = QLabel("配置文件：%s" % cfgmod.config_path())
        path_label.setObjectName("hint")
        path_label.setWordWrap(True)
        root.addWidget(path_label)
        root.addStretch(1)

        btns = QHBoxLayout()
        btns.addStretch(1)
        cancel_btn = QPushButton("取消")
        cancel_btn.setObjectName("secondary")
        cancel_btn.clicked.connect(self.reject)
        save_btn = QPushButton("保存")
        save_btn.setObjectName("primary")
        save_btn.clicked.connect(self._save)
        btns.addWidget(cancel_btn)
        btns.addWidget(save_btn)
        root.addLayout(btns)

    def _notify_cfg_from_widgets(self) -> dict:
        cfg = dict(self.cfg)
        cfg["notify_enabled"] = True
        cfg["notify_provider"] = str(self.notify_provider_box.currentData() or "serverchan")
        for key in ("notify_key", "notify_webhook", "notify_bark_server"):
            edit = self.entries.get(key)
            if edit is not None:
                cfg[key] = edit.text().strip()
        return cfg

    def _send_test_push(self):
        from notify import Notifier

        cfg = self._notify_cfg_from_widgets()
        self.test_button.setEnabled(False)
        self.test_label.setText("发送中…")

        def worker():
            ok = Notifier(cfg).send(
                "DPGrabber 测试推送",
                "如果你看到这条，说明推送通道配置正确。",
            )
            self.test_result.emit(ok, Notifier(cfg).describe())

        threading.Thread(target=worker, daemon=True).start()

    def _show_test_result(self, ok: bool, provider: str):
        self.test_button.setEnabled(True)
        self.test_label.setText(
            "%s 测试推送已发送" % provider if ok else
            "%s 测试推送失败（检查密钥 / 网络 / 渠道）" % provider
        )

    def _save(self):
        try:
            for key, edit in self.entries.items():
                value = edit.text().strip()
                if key == "shop_id":
                    self.cfg[key] = int(value or "0")
                elif key == "request_timeout_sec":
                    self.cfg[key] = float(value)
                else:
                    self.cfg[key] = value
            self.cfg["auto_wait_offset"] = float(self.auto_wait_offset_spin.value())
            self.cfg["notify_enabled"] = self.notify_check.isChecked()
            self.cfg["notify_provider"] = str(self.notify_provider_box.currentData() or "serverchan")
            self.cfg["verify_tls"] = self.verify_check.isChecked()
            self.cfg["voice_enabled"] = self.voice_check.isChecked()
            self.cfg["memo_keywords"] = _lines(self.keyword_text)
            new_blacklist = _lines(self.blacklist_text)
            old_blacklist = self.cfg.get("blacklist", [])
            if _blacklist_would_overwrite(old_blacklist, new_blacklist):
                answer = QMessageBox.question(
                    self, "黑名单可能被覆盖",
                    "检测到可能误覆盖已有黑名单（当前 %d 条，将改为 %d 条）。"
                    "确定要保存新的黑名单吗？" % (len(old_blacklist or []), len(new_blacklist)),
                    QMessageBox.Yes | QMessageBox.No, QMessageBox.No,
                )
                if answer != QMessageBox.Yes:
                    return
            self.cfg["blacklist"] = new_blacklist
            cfgmod.save(self.cfg)
        except (cfgmod.ConfigError, ValueError) as exc:
            QMessageBox.critical(self, "保存失败", str(exc))
            return
        self._on_saved(self.cfg)
        self.accept()
