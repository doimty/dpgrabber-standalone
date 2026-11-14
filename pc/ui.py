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
import platform


def _windows_build() -> int:
    import sys as _sys          # this helper sits above the module's own imports
    if _sys.platform != "win32":
        return 0
    try:
        return int(platform.version().split(".")[2])
    except Exception:
        return 0


#: Mica and DWM-drawn rounded corners need Windows 11 (build >= 22000).
WINDOWS_11 = _windows_build() >= 22000
import os
import re
import sys
import threading
import urllib.request
from pathlib import Path
import time

from PySide6.QtCore import QPoint, QRectF, Qt, QTimer, Signal
from PySide6.QtWidgets import QSizeGrip
from PySide6.QtGui import (QColor, QImage, QPainter, QPainterPath, QPalette,
                           QPixmap, QRegion, QTextCharFormat, QTextCursor)
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
    QInputDialog,
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
    QTabWidget,
    QVBoxLayout,
    QWidget,
)

import config as cfgmod
from dpapi import ApiClient, ApiError, amount_yuan_from_cents, extract_clerk
from engine import Engine
import accounts as accounts_mod
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
WINDOW_SIZE = (1180, 729)  # 1180 / 729 = 1.6187 -> 1:phi within 0.04%

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
QScrollArea#bodyScroll {
    background: %(WINDOW_BG)s; border: none;
    border-bottom-left-radius: 13px; border-bottom-right-radius: 13px;
}
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
    border: none;
    border-radius: 13px;
}
QFrame#sidebar {
    background: %(SIDEBAR_BG)s;
    border-radius: 13px;
}
QFrame#sidebarCard {
    background: %(SIDEBAR_CARD_BG)s;
    border: none;
    border-radius: 13px;
}
QFrame#hero {
    background: qlineargradient(x1:0, y1:0, x2:0, y2:1,
        stop:0 %(HERO_TOP)s, stop:1 %(HERO_BOTTOM)s);
    border-bottom: 2px solid %(HERO_BORDER)s;
}
QLabel#heroTitle { color: white; font-size: 21px; font-weight: bold; }
QComboBox#heroCombo {
    background: rgba(255,255,255,0.16); color: white; font-size: 12px;
    border: 1px solid rgba(255,255,255,0.55); border-radius: 8px;
    padding: 3px 8px; min-height: 18px;
}
QComboBox#heroCombo::drop-down { width: 20px; border: none; background: transparent; }
QComboBox#heroCombo QAbstractItemView {
    background-color: %(CARD_BG)s; color: %(TEXT_DARK)s;
    border: 1px solid %(CARD_BORDER)s; border-radius: 8px; padding: 4px; }
QPushButton#heroButton {
    background: rgba(255,255,255,0.16); color: white; font-size: 12px;
    font-weight: bold; border: 1px solid rgba(255,255,255,0.55);
    border-radius: 8px; padding: 4px 12px; min-height: 18px;
}
QPushButton#heroButton:hover { background: rgba(255,255,255,0.30); }
QLabel#heroStat { color: white; font-size: 13px; font-weight: bold; }
QLabel#heroStatDim { color: %(HERO_STAT_DIM)s; font-size: 13px; }
QLabel#sectionTitle { color: %(BLUE)s; font-size: 14px; font-weight: bold; }
QLabel#sidebarTitle { color: %(SIDEBAR_TEXT)s; font-size: 14px; font-weight: bold; }
QLabel#fieldLabel { color: %(TEXT_DARK)s; font-size: 13px; }
QLabel#hint { color: %(HINT)s; font-size: 12px; }
QLabel#sidebarHint { color: %(SIDEBAR_HINT)s; font-size: 12px; }
QLabel#sidebarLabel { color: %(SIDEBAR_TEXT)s; font-size: 13px; }
QLabel#sidebarState { color: %(SIDEBAR_HINT)s; font-size: 12px; }
QPushButton#winMin, QPushButton#winMax, QPushButton#winClose {
    background: transparent; color: white; border: none;
    border-radius: 8px; font-size: 13px;
}
QPushButton#winMin:hover, QPushButton#winMax:hover { background: rgba(255,255,255,0.22); }
QPushButton#winClose:hover { background: #D9453D; }
QScrollArea#sidebarScroll { background: transparent; border: none; }
QScrollArea#sidebarScroll QScrollBar:vertical {
    background: transparent; width: 6px; margin: 0px; border: none;
}
QScrollArea#sidebarScroll QScrollBar::handle:vertical {
    background: %(CARD_BORDER)s; min-height: 32px; border-radius: 3px;
}
QScrollArea#sidebarScroll QScrollBar::handle:vertical:hover { background: %(BLUE)s; }
QScrollArea#sidebarScroll QScrollBar::add-line:vertical,
QScrollArea#sidebarScroll QScrollBar::sub-line:vertical { height: 0px; border: none; }
QScrollArea#sidebarScroll QScrollBar::add-page:vertical,
QScrollArea#sidebarScroll QScrollBar::sub-page:vertical { background: transparent; }
QScrollArea#sidebarScroll > QWidget > QWidget { background: transparent; }
QLabel#dialogTitle { color: %(TEXT_DARK)s; font-size: 16px; font-weight: bold; }
QPushButton#dialogClose {
    background: transparent; color: %(HINT)s; border: none;
    border-radius: 8px; font-size: 14px;
}
QPushButton#dialogClose:hover { background: rgba(217,69,61,0.85); color: white; }
QLabel#avatarBadge {
    background: %(BLUE)s; color: white; font-size: 21px; font-weight: bold;
    border-radius: 22px;
}
QLabel#accountName { color: %(SIDEBAR_TEXT)s; font-size: 13px; font-weight: bold; }
QLabel#statValue { color: %(BLUE)s; font-size: 21px; font-weight: bold; }
QLabel#subTitle { color: %(TEXT_DARK)s; font-size: 13px; font-weight: bold; }
QFrame#divider { background: %(CARD_BORDER)s; border: none; }
QLabel#historyLine1 { color: %(TEXT_DARK)s; font-size: 13px; font-weight: bold; }
QLabel#historyLine2 { color: %(HINT)s; font-size: 12px; }
QLabel#historyReason { color: %(HISTORY_REASON)s; font-size: 12px; }
QLineEdit {
    background: %(ENTRY_BG)s; color: %(TEXT_DARK)s; font-size: 13px;
    border: 1px solid %(ENTRY_BORDER)s; border-radius: 8px; padding: 8px 13px; min-height: 16px;
}
QLineEdit:focus { border: 1px solid %(BLUE)s; }
QComboBox {
    background: %(ENTRY_BG)s; color: %(TEXT_DARK)s; font-size: 13px;
    border: 1px solid %(ENTRY_BORDER)s; border-radius: 8px; padding: 8px 13px; min-height: 16px;
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
    border: 1px solid %(CARD_BORDER)s; border-radius: 8px;
    selection-background-color: %(SELECTION_BG)s; selection-color: %(TEXT_DARK)s;
    outline: none; padding: 4px;
}
QComboBox QAbstractItemView::item {
    color: %(TEXT_DARK)s; min-height: 26px; padding: 2px 8px; border-radius: 5px;
}
QComboBox QAbstractItemView::item:selected {
    background: %(SELECTION_BG)s; color: %(TEXT_DARK)s;
}
QSpinBox, QDoubleSpinBox {
    background: %(ENTRY_BG)s; color: %(TEXT_DARK)s; font-size: 13px;
    border: 1px solid %(ENTRY_BORDER)s; border-radius: 8px; padding: 8px 13px; min-height: 16px;
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
    border: none; border-radius: 8px; padding: 8px 13px; min-height: 18px;
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
    border: 1px solid %(BLUE)s; border-radius: 8px; padding: 8px 13px; min-height: 16px;
}
QPushButton#secondary:hover { background: %(SELECTION_BG)s; }
QPushButton#ghost {
    background: transparent; color: %(BLUE)s; font-size: 12px; font-weight: bold;
    border: 1px solid %(CARD_BORDER)s; border-radius: 8px; padding: 0px 13px;
}
QPushButton#ghost:hover { background: %(SELECTION_BG)s; border-color: %(BLUE)s; }
QPushButton#secondary:disabled { background: %(BTN_DISABLED_BG)s; color: %(BTN_DISABLED_TEXT)s; border-color: %(ENTRY_BORDER)s; }
QCheckBox { color: %(TEXT_DARK)s; font-size: 13px; spacing: 6px; }
QCheckBox#sidebarCheck { color: %(SIDEBAR_TEXT)s; }
QCheckBox::indicator { width: 16px; height: 16px; }
QCheckBox::indicator:unchecked {
    border: 1px solid %(ENTRY_BORDER)s; border-radius: 5px; background: %(ENTRY_BG)s;
}
QCheckBox::indicator:checked {
    border: 1px solid %(BLUE)s; border-radius: 5px;
    background: %(BLUE)s; %(CHECK_CSS)s
}
QTabWidget#bottomTabs::pane { border: none; background: transparent; }
QTabWidget#bottomTabs QTabBar::tab {
    background: transparent; color: %(HINT)s; border: none;
    padding: 8px 21px; margin-right: 5px; font-size: 13px;
}
QTabWidget#bottomTabs QTabBar::tab:selected {
    color: %(BLUE)s; font-weight: bold;
    border-bottom: 2px solid %(BLUE)s;
}
QTabWidget#bottomTabs QTabBar::tab:hover { color: %(TEXT_DARK)s; }
QListWidget { background: transparent; border: none; outline: none; }
QListWidget::item { border: none; }
QListWidget::item:selected { background: %(SELECTION_BG)s; }
QScrollBar:vertical {
    background: transparent; width: 12px; margin: 0px;
}
QScrollBar::handle:vertical {
    background: %(BLUE)s; min-height: 20px; border-radius: 5px;
}
QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical { height: 0px; }
QGroupBox {
    color: %(BLUE)s; font-size: 13px; font-weight: bold;
    border: 1px solid %(CARD_BORDER)s; border-radius: 8px;
    margin-top: 10px; padding-top: 8px;
}
QGroupBox::title { subcontrol-origin: margin; left: 10px; padding: 0 4px; }
"""


def build_stylesheet(palette: dict, theme_id: str = DEFAULT_THEME,
                     mica: bool = False) -> str:
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
    def translucent(value: str, alpha: int) -> str:
        colour = QColor(value)
        return "rgba(%d, %d, %d, %d)" % (colour.red(), colour.green(),
                                         colour.blue(), alpha)

    window_bg = p["window_bg"]
    card_bg, sidebar_card_bg, entry_bg = (p["card_bg"], p["sidebar_card_bg"],
                                          p["entry_bg"])
    if mica:
        # Mica only shows where we do not paint opaque pixels: the window
        # surface, the cards and the inputs each keep a little transparency,
        # while text colours stay untouched so contrast holds.
        window_bg = translucent(window_bg, 186)     # ~73%
        card_bg = translucent(card_bg, 232)
        sidebar_card_bg = translucent(sidebar_card_bg, 224)
        entry_bg = translucent(entry_bg, 214)
    subs = {
        "WINDOW_BG": window_bg,
        "CARD_BG": card_bg,
        "CARD_BORDER": p["card_border"],
        "BLUE": p["accent"],
        "BLUE_DARK": p["accent_dark"],
        "TEXT_DARK": p["text_dark"],
        "HINT": p["hint"],
        "SIDEBAR_BG": p["sidebar_bg"],
        "SIDEBAR_CARD_BG": sidebar_card_bg,
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
        "ENTRY_BG": entry_bg,
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


def apply_theme(qapp, palette: dict, theme_id: str = DEFAULT_THEME,
                mica: bool = False) -> None:
    """Apply a theme to the application: QSS plus placeholder colour.

    QSS cannot set placeholder-text colour, so the palette's hint colour
    is applied to QPalette.PlaceholderText here. Without it the "..."
    placeholders are nearly invisible in the night theme. Only the
    placeholder role is touched; every other palette colour is left
    alone.
    """
    qapp.setStyleSheet(build_stylesheet(palette, theme_id, mica=mica))
    pal = qapp.palette()
    pal.setColor(QPalette.PlaceholderText, QColor(palette.get("hint", "#5B7A99")))
    qapp.setPalette(pal)


def _apply_windows_effects(window: QMainWindow, dark: bool = True) -> bool:
    """Mica backdrop + DWM rounded corners (Windows 11 only).

    Both need an *opaque* top-level: WA_TranslucentBackground and a QRegion
    mask each disable the DWM frame, which is why they are only used when this
    returns False.
    """
    if sys.platform != "win32" or not WINDOWS_11:
        return False
    try:
        hwnd = int(window.winId())
        dwm = ctypes.windll.dwmapi
    except Exception:
        return False

    def set_int(index: int, value: int) -> bool:
        raw = ctypes.c_int(value)
        try:
            return dwm.DwmSetWindowAttribute(
                ctypes.c_void_p(hwnd), index, ctypes.byref(raw),
                ctypes.sizeof(raw)) == 0
        except Exception:
            return False

    ok = set_int(38, 2)            # DWMWA_SYSTEMBACKDROP_TYPE = Mica
    set_int(20, 1 if dark else 0)  # DWMWA_USE_IMMERSIVE_DARK_MODE
    set_int(33, 2)                 # DWMWA_WINDOW_CORNER_PREFERENCE = round
    # DWMWA_BORDER_COLOR: without this DWM draws a 1px frame ring around the
    # frameless window, which reads as a dark outline on the rounded corners.
    raw = ctypes.c_uint(0xFFFFFFFE)   # DWMWA_COLOR_NONE
    try:
        dwm.DwmSetWindowAttribute(ctypes.c_void_p(hwnd), 34,
                                  ctypes.byref(raw), ctypes.sizeof(raw))
    except Exception:
        pass
    return ok


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
    week_orders_signal = Signal(object)   # int, or None when unknown
    clerk_state_signal = Signal(object)    # {"status": int, "items": [...], "enabled": [...]}
    avatar_signal = Signal(str)           # local path, or "" 
    listen_state_signal = Signal(str)
    history_entry_signal = Signal(dict)
    refresh_history_signal = Signal()

    def __init__(self, cfg: dict):
        super().__init__()
        # Native title bar off: it drew the stock Windows strip with a 16px
        # icon above our own hero bar. The hero doubles as the title bar and
        # carries the window buttons below.
        self.setWindowFlag(Qt.FramelessWindowHint, True)
        # A mask alone leaves the corner pixels painted with the platform's
        # opaque backing store, which is the white sliver seen under each
        # rounded corner. Translucency lets the clipped area show the desktop.
        # Mica and DWM rounding both require an opaque window; translucency is
        # only the Windows 10 fallback.
        if not WINDOWS_11:
            self.setAttribute(Qt.WA_TranslucentBackground, True)
        self._drag_offset = None
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
        self.week_orders_signal.connect(self._on_week_orders)
        self.clerk_state_signal.connect(self._on_clerk_state)
        self.avatar_signal.connect(self._on_avatar)
        self.listen_state_signal.connect(self._on_listen_state)
        self.history_entry_signal.connect(self._on_history_entry)
        self.refresh_history_signal.connect(self._refresh_history)

        try:
            accounts_mod.migrate_legacy()
        except Exception:
            pass
        self._build_widgets()
        self._reload_account_picker()
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
        if getattr(self, "_mica_restyled", False):
            return
        self._mica_restyled = True
        app = QApplication.instance()
        if app is not None and getattr(self, "_mica", False):
            apply_theme(app, self._palette, self._theme_id, mica=True)


        super().showEvent(event)
        if not self._mica_applied:
            self._mica_applied = True
            self._mica = _apply_windows_effects(
            self, dark=(self._theme_id or "").lower() in ("night", "violet", "blue")
        )
        if not self._mica:
            _apply_mica(self)

    def _primary_button(self, text, slot):
        btn = QPushButton(text)
        btn.setObjectName("primary")
        # 34 = 21 + 13: pinning the height makes every button land on the
        # golden row measure instead of drifting with padding + border.
        btn.setFixedHeight(34)
        btn.clicked.connect(slot)
        return btn

    def _ghost_button(self, text, slot):
        btn = QPushButton(text)
        btn.setObjectName("ghost")
        btn.setFixedHeight(30)
        btn.clicked.connect(slot)
        return btn

    def _secondary_button(self, text, slot):
        btn = QPushButton(text)
        btn.setObjectName("secondary")
        # 34 = 21 + 13: pinning the height makes every button land on the
        # golden row measure instead of drifting with padding + border.
        btn.setFixedHeight(34)
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
        label.setMaximumWidth(300)
        return label

    GOLDEN = 1.6180339887

    def _apply_golden_split(self):
        """form : right = 1 : phi, recomputed whenever the window resizes.

        Available = window - body margins (14+14) - sidebar gap (12)
                            - sidebar - column gap (12)
        The form takes available/(1+phi); the tabs column takes the rest.
        """
        wrap = getattr(self, "_form_wrap", None)
        sidebar = getattr(self, "_sidebar", None)
        if wrap is None or sidebar is None:
            return
        available = self.width() - 28 - 12 - sidebar.width() - 12
        if available <= 0:
            return
        form_width = int(round(available / (1.0 + self.GOLDEN)))
        # never below what the form actually needs: at 324 the labels and the
        # controls overlapped, because the column was narrower than its own
        # minimumSizeHint (342 after the label shortening)
        needed = wrap.minimumSizeHint().width()
        form_width = max(form_width, needed)
        form_width = min(form_width, available - 260)
        if wrap.width() != form_width:
            wrap.setFixedWidth(form_width)

    WINDOW_RADIUS = 13

    def _apply_window_mask(self):
        """Rounded corners via a QRegion mask.

        Alternatives evaluated on Windows 10 (19044):
          - self.render() into an offscreen image and draw it through a clip
            path: Qt answers "Recursive repaint detected" /
            "paintEngine: Should no longer be called" inside a translucent
            top-level, so it cannot be used here;
          - DWM rounding: Windows 11 only;
          - QSS radii on the outer surfaces: the scroll area's square viewport
            repaints over the hero's rounded corners.
        The mask is binary, so a 1px stair-step remains at 125% scaling; the
        window is translucent so the clipped pixels show the desktop instead of
        a white backing.
        """
        if WINDOWS_11:
            self.clearMask()
            return
        path = QPainterPath()
        path.addRoundedRect(QRectF(0, 0, self.width(), self.height()),
                            self.WINDOW_RADIUS, self.WINDOW_RADIUS)
        self.setMask(QRegion(path.toFillPolygon().toPolygon()))

    def resizeEvent(self, event):
        super().resizeEvent(event)
        self._apply_window_mask()
        self._apply_golden_split()
        self._apply_window_mask()
        grip = getattr(self, "size_grip", None)
        if grip is not None:
            grip.move(self.width() - 18, self.height() - 18)
            grip.raise_()

    def _build_widgets(self):
        central = QWidget()
        central.setObjectName("central")
        self.setCentralWidget(central)
        root = QVBoxLayout(central)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)

        self._build_hero(root)
        # frameless windows have no frame to grab: a grip in the corner restores
        # resize, and resizeEvent keeps it pinned there
        self.size_grip = QSizeGrip(central)
        self.size_grip.setStyleSheet("background: transparent;")

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

        sidebar_host = QScrollArea()
        sidebar_host.setObjectName("sidebarScroll")
        sidebar_host.setWidgetResizable(True)
        sidebar_host.setFrameShape(QFrame.NoFrame)
        sidebar_host.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        sidebar_host.setFixedWidth(279)
        sidebar = QFrame()
        self._sidebar = sidebar
        sidebar.setObjectName("sidebar")
        sidebar.setFixedWidth(279)  # 1180 / 279 = 4.229 = phi^3 within 0.17%
        sidebar_layout = QVBoxLayout(sidebar)
        sidebar_layout.setContentsMargins(0, 0, 0, 0)
        sidebar_layout.setSpacing(0)
        self._build_login_section(sidebar_layout)
        self._build_control_section(sidebar_layout)
        self._build_item_switch_section(sidebar_layout)
        self._build_stats_section(sidebar_layout)
        sidebar_layout.addStretch(1)
        sidebar_layout.addStretch(1)
        sidebar_host.setWidget(sidebar)
        body_layout.addWidget(sidebar_host)

        main_area = QWidget()
        main_layout = QHBoxLayout(main_area)
        main_layout.setContentsMargins(0, 0, 0, 0)
        main_layout.setSpacing(12)
        form_column = QVBoxLayout()
        form_column.setSpacing(0)
        self._build_conditions_section(form_column)
        right_column = QVBoxLayout()
        right_column.setSpacing(0)
        self.bottom_tabs = QTabWidget()
        self.bottom_tabs.setObjectName("bottomTabs")
        log_page = QWidget()
        log_layout = QVBoxLayout(log_page)
        log_layout.setContentsMargins(0, 0, 0, 0)
        self._build_log_section(log_layout, tabbed=True)
        history_page = QWidget()
        history_layout = QVBoxLayout(history_page)
        history_layout.setContentsMargins(0, 0, 0, 0)
        self._build_history_section(history_layout, tabbed=True)
        self.bottom_tabs.addTab(log_page, "运行日志")
        self.bottom_tabs.addTab(history_page, "接单历史")
        right_column.addWidget(self.bottom_tabs, 1)
        # form : right = 1 : 1.618 once the form is capped; without the cap the
        # form's own minimum width (label + field + hint) won the extra space
        # and the split drifted to 2.08 : 1.
        form_wrap = QWidget()
        self._form_wrap = form_wrap
        form_column.setContentsMargins(0, 0, 0, 0)
        form_wrap.setLayout(form_column)
        main_layout.addWidget(form_wrap)
        main_layout.addLayout(right_column, 1)


        body_layout.addWidget(main_area, 1)

    def _build_hero(self, root):
        hero = QFrame()
        hero.setObjectName("hero")
        hero.setFixedHeight(55)  # 34 * phi
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
        self.renewal_chip = QLabel("")
        self.renewal_chip.setObjectName("heroStatDim")
        self.renewal_chip.setVisible(False)
        layout.addWidget(self.renewal_chip)

        # Toolbar controls live in the hero: the sidebar is for the account and
        # the run switch, not for periodic settings.
        layout.addSpacing(18)
        self.theme_box = QComboBox()
        self.theme_box.setObjectName("heroCombo")
        self.theme_box.setFixedWidth(132)
        self.theme_box.setFixedHeight(34)
        for theme_id, theme in THEMES.items():
            self.theme_box.addItem(theme["name"], theme_id)
        self.theme_box.setCurrentIndex(list(THEMES.keys()).index(self._theme_id))
        self.theme_box.currentIndexChanged.connect(self._on_theme_changed)
        layout.addWidget(self.theme_box)
        layout.addSpacing(8)
        advanced = QPushButton("高级设置")
        advanced.setObjectName("heroButton")
        advanced.setFixedHeight(34)
        advanced.clicked.connect(self._open_advanced)
        layout.addWidget(advanced)

        layout.addSpacing(13)
        for text, slot, name in (
            ("–", self.showMinimized, "winMin"),
            ("□", self._toggle_maximize, "winMax"),
            ("✕", self.close, "winClose"),
        ):
            button = QPushButton(text)
            button.setObjectName(name)
            button.setFixedSize(34, 34)
            button.clicked.connect(slot)
            layout.addWidget(button)
        root.addWidget(hero)

    def _toggle_maximize(self):
        """Manual maximise: a frameless showMaximized() can cover the taskbar."""
        if getattr(self, "_maximized_state", False):
            self.setGeometry(self._normal_geometry)
            self._maximized_state = False
            return
        screen = self.screen() or QApplication.primaryScreen()
        if screen is None:
            return
        self._normal_geometry = self.geometry()
        self.setGeometry(screen.availableGeometry())
        self._maximized_state = True

    def mousePressEvent(self, event):
        # Drag anywhere the hero is not covered by a control.
        if event.button() == Qt.LeftButton and event.position().y() <= 55:
            child = self.childAt(event.position().toPoint())
            if child is None or not isinstance(child, QPushButton):
                self._drag_offset = event.globalPosition().toPoint() - self.frameGeometry().topLeft()
                event.accept()
                return
        super().mousePressEvent(event)

    def mouseMoveEvent(self, event):
        if self._drag_offset is not None and event.buttons() & Qt.LeftButton:
            self.move(event.globalPosition().toPoint() - self._drag_offset)
            event.accept()
            return
        super().mouseMoveEvent(event)

    def mouseReleaseEvent(self, event):
        self._drag_offset = None
        super().mouseReleaseEvent(event)

    def mouseDoubleClickEvent(self, event):
        if event.position().y() <= 55:
            self._toggle_maximize()
            event.accept()
            return
        super().mouseDoubleClickEvent(event)

    def _sidebar_card(self, parent_layout, title):
        title_label = QLabel(title)
        title_label.setObjectName("sidebarTitle")
        parent_layout.addWidget(title_label)
        # Indent the title like the old UI (padx=14).
        # 13px above the title, 8px between the title and its card: the old
        # 2px bottom margin made every heading look like it sat on the card.
        title_label.setContentsMargins(14, 10, 14, 0)
        card = QFrame()
        card.setObjectName("sidebarCard")
        card_layout = QVBoxLayout(card)
        card_layout.setContentsMargins(13, 13, 13, 13)
        card_layout.setSpacing(4)
        wrapper = QVBoxLayout()
        wrapper.setContentsMargins(12, 8, 12, 8)
        wrapper.addWidget(card)
        parent_layout.addLayout(wrapper)
        return card, card_layout

    def _build_login_section(self, sidebar_layout):
        card, layout = self._sidebar_card(sidebar_layout, "账号登录")

        # --- account picker: one config file per phone ----------------------
        picker_row = QHBoxLayout()
        picker_row.setSpacing(6)
        self.account_combo = QComboBox()
        self.account_combo.setObjectName("accountCombo")
        self.account_combo.currentIndexChanged.connect(self._on_account_picked)
        picker_row.addWidget(self.account_combo, 1)
        add_button = self._ghost_button("＋", self._on_add_account)
        add_button.setFixedWidth(34)
        add_button.setToolTip("新增账号（每个账号一份配置文件）")
        picker_row.addWidget(add_button)
        layout.addLayout(picker_row)

        # --- signed-out form ------------------------------------------------
        self.login_form = QWidget()
        form = QVBoxLayout(self.login_form)
        form.setContentsMargins(0, 0, 0, 0)
        form.setSpacing(4)
        phone_label = QLabel("手机号")
        phone_label.setObjectName("sidebarLabel")
        form.addWidget(phone_label)
        self.phone_edit = QLineEdit()
        self.phone_edit.setFixedHeight(34)
        self.phone_edit.setPlaceholderText("11 位手机号")
        form.addWidget(self.phone_edit)
        hint = QLabel("登录用的手机号，11 位数字")
        hint.setObjectName("sidebarHint")
        form.addWidget(hint)
        pw_label = QLabel("密码")
        pw_label.setObjectName("sidebarLabel")
        form.addWidget(pw_label)
        self.password_edit = QLineEdit()
        self.password_edit.setFixedHeight(34)
        self.password_edit.setEchoMode(QLineEdit.Password)
        form.addWidget(self.password_edit)
        self.show_pw_check = QCheckBox("显示密码")
        self.show_pw_check.setObjectName("sidebarCheck")
        self.show_pw_check.toggled.connect(self._toggle_show_password)
        form.addWidget(self.show_pw_check)
        self.login_btn = self._primary_button("登录", self._on_login)
        form.addWidget(self.login_btn)
        self.login_state_label = QLabel("请输入手机号和密码后登录")
        self.login_state_label.setObjectName("sidebarState")
        self.login_state_label.setWordWrap(True)
        form.addWidget(self.login_state_label)
        layout.addWidget(self.login_form)

        # --- signed-in identity ---------------------------------------------
        self.account_box = QWidget()
        account = QVBoxLayout(self.account_box)
        account.setContentsMargins(0, 0, 0, 0)
        account.setSpacing(8)
        head = QHBoxLayout()
        head.setSpacing(8)
        self.avatar_label = QLabel("·")
        self.avatar_label.setObjectName("avatarBadge")
        self.avatar_label.setFixedSize(44, 44)
        self.avatar_label.setAlignment(Qt.AlignCenter)
        head.addWidget(self.avatar_label)
        names = QVBoxLayout()
        names.setSpacing(1)
        self.account_name_label = QLabel("—")
        self.account_name_label.setObjectName("accountName")
        self.account_phone_label = QLabel("")
        self.account_phone_label.setObjectName("sidebarHint")
        names.addWidget(self.account_name_label)
        names.addWidget(self.account_phone_label)
        head.addLayout(names, 1)
        account.addLayout(head)

        # 续费率（金额比）决定能不能接随机单；续单率（单数比）影响发工资。
        # 两个都要，所以并排分开显示，各自带标题。
        stats = QHBoxLayout()
        stats.setSpacing(13)
        self.week_count_label = QLabel("0")
        self.week_count_label.setObjectName("statValue")
        self.renewal_label = QLabel("—")
        self.renewal_label.setObjectName("statValue")
        self.order_rate_label = QLabel("—")
        self.order_rate_label.setObjectName("statValue")
        for value, caption in ((self.week_count_label, "本周接单"),
                               (self.renewal_label, "续费率"),
                               (self.order_rate_label, "续单率")):
            cell = QVBoxLayout()
            cell.setSpacing(0)
            cell.addWidget(value)
            cap = QLabel(caption)
            cap.setObjectName("hint")
            cell.addWidget(cap)
            stats.addLayout(cell)
        stats.addStretch(1)
        account.addLayout(stats)

        self.logout_btn = self._secondary_button("注销", self._on_logout)
        account.addWidget(self.logout_btn)
        self.account_box.setVisible(False)
        layout.addWidget(self.account_box)
        return card

    def _fetch_avatar(self, clerk: dict):
        """Download the clerk avatar once and cache a full-resolution PNG.

        The source is up to 1080x1080; shrinking it here and scaling again in
        the label resampled the same image twice, which is why the circle looked
        soft at 125% scaling. The PNG keeps the source resolution (capped at
        512) and the label scales by device pixel ratio.
        """
        url = str(clerk.get("avatarUrl") or "")
        if not url.startswith(("http://", "https://")):
            self.avatar_signal.emit("")
            return
        cache_dir = Path(cfgmod.config_dir()) / "avatars"
        clerk_id = clerk.get("id") or "me"
        try:
            cache_dir.mkdir(parents=True, exist_ok=True)
            source = cache_dir / ("avatar_%s.img" % clerk_id)
            target = cache_dir / ("avatar_%s_v3.png" % clerk_id)

            if not source.exists() or source.stat().st_size < 1024:
                with urllib.request.urlopen(url, timeout=15) as response:
                    data = response.read()
                if len(data) > 512:
                    source.write_bytes(data)

            needs_convert = True
            if target.exists() and target.stat().st_size > 512:
                try:
                    from PIL import Image
                    with Image.open(target) as probe:
                        needs_convert = max(probe.size) < 128
                except Exception:
                    needs_convert = True
            if needs_convert:
                try:
                    from PIL import Image
                    import io
                    raw = source.read_bytes() if source.exists() else b""
                    if raw:
                        image = Image.open(io.BytesIO(raw)).convert("RGBA")
                        side = 176 * 4                     # 4x supersampling
                        scale = side / float(min(image.size))
                        image = image.resize(
                            (max(1, int(image.width * scale)),
                             max(1, int(image.height * scale))), Image.LANCZOS)
                        left = (image.width - side) // 2
                        top = (image.height - side) // 2
                        image = image.crop((left, top, left + side, top + side))
                        mask = Image.new("L", (side, side), 0)
                        from PIL import ImageDraw
                        ImageDraw.Draw(mask).ellipse((0, 0, side - 1, side - 1), fill=255)
                        mask = mask.resize((176, 176), Image.LANCZOS)
                        circle = Image.new("RGBA", (176, 176), (0, 0, 0, 0))
                        circle.paste(image.resize((176, 176), Image.LANCZOS), (0, 0), mask)
                        # write beside the target and swap: never fails on a lock
                        temp = target.with_suffix(".tmp")
                        circle.save(temp, "PNG")
                        temp.replace(target)
                except Exception:
                    pass

            chosen = target if target.exists() else source
            self.avatar_signal.emit(str(chosen))
        except Exception:
            self.avatar_signal.emit("")

    def _fetch_clerk_state(self, session, clerk: dict):
        """Online status (1 online / 2 offline) plus 接单类型 switches."""
        items, enabled = [], []
        try:
            listing = self.client.item_switch_list(session=session)
            items = list(listing.get("itemSwitchList") or [])
            enabled = [int(i) for i in (listing.get("clerkItemSwitchIds") or [])]
        except Exception:
            pass
        try:
            status = int(clerk.get("status") or 0)
        except (TypeError, ValueError):
            status = 0
        self.clerk_state_signal.emit({"status": status, "items": items, "enabled": enabled})

    def _on_clerk_state(self, state):
        status = int(state.get("status") or 0)
        self._set_online_text(status == 1)
        items = state.get("items")
        if items is not None:
            self._rebuild_item_switches(items, state.get("enabled") or [])

    def _rebuild_item_switches(self, items, enabled):
        holder = getattr(self, "item_switch_box", None)
        if holder is None:
            return
        while holder.count():
            child = holder.takeAt(0)
            widget = child.widget()
            if widget is not None:
                widget.setParent(None)   # detach now; deleteLater is deferred
                widget.deleteLater()
        self.item_checks = {}
        for item in items:
            box = QCheckBox(str(item.get("name") or item.get("id")))
            box.setObjectName("sidebarCheck")
            box.setChecked(int(item.get("id")) in enabled)
            box.toggled.connect(
                lambda checked, item_id=int(item.get("id")): self._toggle_item_switch(item_id, checked)
            )
            holder.addWidget(box)
            self.item_checks[int(item.get("id"))] = box
        if not items:
            hint = QLabel("登录后显示")
            hint.setObjectName("sidebarHint")
            holder.addWidget(hint)

    def _reload_account_picker(self):
        combo = getattr(self, "account_combo", None)
        if combo is None:
            return
        combo.blockSignals(True)
        combo.clear()
        self._account_items = accounts_mod.list_accounts()
        for meta in self._account_items:
            combo.addItem(accounts_mod.label_for(meta), meta["phone"])
        if not self._account_items:
            combo.addItem("（暂无账号，点 ＋ 新建）", "")
        current = str(self.cfg.get("phone") or "")
        index = combo.findData(current)
        combo.setCurrentIndex(index if index >= 0 else 0)
        combo.blockSignals(False)

    def _on_account_picked(self, index: int):
        phone = self.account_combo.itemData(index)
        if phone:
            self._activate_account(str(phone))

    def _activate_account(self, phone: str):
        """Switch the whole app to another account's configuration file."""
        if phone == str(self.cfg.get("phone") or ""):
            return
        try:
            cfg = accounts_mod.load_account(phone)
        except Exception as exc:
            self.log("error", "读取账号配置失败：%s" % str(exc)[:80])
            return
        if self.engine is not None and self.engine.running:
            self._on_stop_listen()
        self.cfg = cfg
        self.client.session = None
        self._show_account_card(False)
        self._load_settings_into_widgets()
        self.phone_edit.setText(phone)
        self.login_state_label.setText("请输入手机号和密码后登录")
        self.log("info", "已切换到账号 %s" % cfgmod.mask_phone(phone))
        self._reload_account_picker()

    def _on_add_account(self):
        phone, ok = QInputDialog.getText(self, "新增账号", "手机号（11 位）")
        if not ok:
            return
        digits = "".join(ch for ch in str(phone) if ch.isdigit())
        if len(digits) < 6:
            self.log("error", "手机号无效：%s" % phone)
            return
        try:
            cfg = accounts_mod.load_account(digits)
        except Exception as exc:
            self.log("error", "创建账号配置失败：%s" % str(exc)[:80])
            return
        self.log("success", "已新增账号 %s，配置：%s"
                 % (cfgmod.mask_phone(digits), accounts_mod.path_for(digits)))
        self._activate_account(digits)
        self._reload_account_picker()

    def _set_online_text(self, online: bool):
        box = getattr(self, "online_check", None)
        if box is None:
            return
        box.blockSignals(True)
        box.setText("在线接单（当前在线）" if online else "在线接单（当前离线）")
        box.setChecked(online)
        box.blockSignals(False)

    def _toggle_online(self, checked: bool):
        session = getattr(self.client, "session", None)
        if session is None:
            return
        target = 1 if checked else 2
        # flip the label immediately so the control answers the click
        self._set_online_text(bool(checked))

        def worker():
            try:
                result = self.client.set_online(target, False, session=session)
            except Exception as exc:
                result = {"code": -1, "msg": str(exc)[:80]}
            ok = result.get("code") == 0
            if ok:
                self.log("success", "已上线" if target == 1 else "已下线")
            else:
                self.log("error", "切换在线状态失败：%s" % result.get("msg"))
                self.clerk_state_signal.emit({"status": 2 if target == 1 else 1,
                                              "items": [], "enabled": []})

        threading.Thread(target=worker, daemon=True).start()

    def _toggle_item_switch(self, item_id: int, enabled: bool):
        session = getattr(self.client, "session", None)
        if session is None:
            return
        current = [int(i) for i, box in (getattr(self, "item_checks", {}) or {}).items() if box.isChecked()]

        def worker():
            try:
                result = self.client.set_item_switch(item_id, current, session=session)
            except Exception as exc:
                result = {"code": -1, "msg": str(exc)[:80]}
            ok = result.get("code") == 0
            self.log("success" if ok else "error",
                     "接单类型已更新" if ok else "接单类型更新失败：%s" % result.get("msg"))
            if not ok:
                self.clerk_state_signal.emit({"status": 0, "items": [], "enabled": []})

        threading.Thread(target=worker, daemon=True).start()

    def _fetch_week_orders(self, session):
        """本周接单 + 续单率 from statisticsClerkOrder (Monday -> today)."""
        stats = {}
        try:
            now = time.localtime()
            monday = time.strftime(
                "%Y-%m-%d", time.localtime(time.time() - now.tm_wday * 86400))
            today = time.strftime("%Y-%m-%d")
            stats = self.client.clerk_statistics(monday, today, session=session) or {}
            if not stats:
                fallback = self.client.order_total(
                    monday + " 00:00:00", today + " 23:59:59", session=session)
                self.log("warning", "本周统计为空，改用订单总数：%s" % fallback)
        except Exception as exc:
            stats = {}
            self.log("warning", "本周统计失败：%s" % str(exc)[:80])
        self.week_orders_signal.emit(stats or None)

    RENEWAL_TARGET = 30.0

    def _update_renewal_chip(self, fee_rate):
        chip = getattr(self, "renewal_chip", None)
        if chip is None:
            return
        if fee_rate is None:
            chip.setVisible(False)
            return
        ok = fee_rate >= self.RENEWAL_TARGET
        chip.setText("%s 续费率 %d%% / %d%%" % (
            "●" if ok else "○", round(fee_rate), int(self.RENEWAL_TARGET)))
        colour = "#7BD88F" if ok else "#FFB4A8"
        chip.setStyleSheet("color: %s; font-size: 13px;" % colour)
        chip.setVisible(True)

    def _apply_week_stats(self):
        """Draw 本周接单 / 续费率 from the cached statistics dict.

        Both the login worker (week_orders_signal) and the account-card refresh
        call this; the refresh used to write "—" over the values the worker had
        just fetched.
        """
        self._on_week_orders(getattr(self, "_week_stats", None))

    def _on_week_orders(self, stats):
        self._week_stats = stats
        """Three platform ratios, each with its own formula.

        续费率 = continuedAmount / orderAmount    (money, this is the gate the
                  platform quotes: 6000/24500 = 24% for 加冰)
        续单率 = continuedCount / orderCount
        续客率 = continuedUserCount / totalOrderCount
        """
        week_label = getattr(self, "week_count_label", None)
        rate_label = getattr(self, "renewal_label", None)
        order_rate_label = getattr(self, "order_rate_label", None)
        if not isinstance(stats, dict) or not stats:
            for label in (week_label, rate_label):
                if label is not None:
                    label.setText("—")
            return

        def ratio(numerator, denominator):
            try:
                numerator = float(numerator or 0)
                denominator = float(denominator or 0)
            except (TypeError, ValueError):
                return None
            if denominator <= 0:
                return None
            return 100.0 * numerator / denominator

        orders = int(stats.get("orderCount") or 0)
        continued = int(stats.get("continuedCount") or 0)
        users = int(stats.get("continuedUserCount") or 0)
        total = int(stats.get("totalOrderCount") or 0)
        fee_rate = ratio(stats.get("continuedAmount"), stats.get("orderAmount"))
        order_rate = ratio(continued, orders)
        user_rate = ratio(users, total)

        if week_label is not None:
            week_label.setText(str(orders))
        if rate_label is not None:
            rate_label.setText("—" if fee_rate is None else "%d%%" % round(fee_rate))
            detail = ["续费率（金额比）%.1f%% = 续单金额 %.2f / 订单金额 %.2f" % (
                fee_rate or 0,
                (stats.get("continuedAmount") or 0) / 100.0,
                (stats.get("orderAmount") or 0) / 100.0)]
            if order_rate is not None:
                detail.append("续单率（单数比）%.1f%% = %d/%d 单" % (order_rate, continued, orders))
            if user_rate is not None:
                detail.append("续客率（人数比）%.1f%% = %d/%d 人" % (user_rate, users, total))
            rate_label.setToolTip("\n".join(detail))
            self._renewal_source = detail[0]
            if order_rate_label is not None:
                order_rate_label.setText(
                    "—" if order_rate is None else "%d%%" % round(order_rate))
                order_rate_label.setToolTip("续单率（单数比）%s = %d/%d 单" % (
                    "—" if order_rate is None else "%.1f%%" % order_rate,
                    continued, orders))
            self._update_renewal_chip(fee_rate)

    def _on_avatar(self, path: str):
        label = getattr(self, "avatar_label", None)
        if label is None or not path:
            return
        pixmap = QPixmap(path)
        if pixmap.isNull():
            return
        dpr = label.devicePixelRatioF() or 1.0
        target = int(round(44 * dpr))
        pixmap = pixmap.scaled(target, target, Qt.KeepAspectRatioByExpanding,
                               Qt.SmoothTransformation)
        pixmap.setDevicePixelRatio(dpr)
        # The cached PNG already carries a supersampled circular alpha, so the
        # label only scales it: no mask, no painter, no clipped edges.
        label.setMask(QRegion())
        label.setStyleSheet("background: transparent; border: none;")
        label.setAlignment(Qt.AlignCenter)
        label.setPixmap(pixmap)
        label.setText("")

    def _show_account_card(self, signed_in: bool):
        """Signed in: the form collapses into avatar + identity + logout."""
        self.login_form.setVisible(not signed_in)
        self.account_box.setVisible(signed_in)
        if signed_in:
            self._refresh_account_card()

    def _refresh_account_card(self):
        phone = self.phone_edit.text().strip()
        name = getattr(self, "_clerk_name", "") or ""
        clerk_id = getattr(self, "_clerk_id", "") or ""
        shown = name or (cfgmod.mask_phone(phone) if phone else "已登录")
        self.account_name_label.setText(
            "%s（ID %s）" % (name, clerk_id) if name and clerk_id else shown
        )
        self.account_phone_label.setText(cfgmod.mask_phone(phone) if phone else "")
        # The login worker emits the avatar before the login state, so this
        # refresh used to overwrite the just-loaded photo with a letter.
        existing = self.avatar_label.pixmap()
        if existing is None or existing.isNull():
            # back to the letter badge: restore the QSS circle behind it
            self.avatar_label.setStyleSheet("")
            self.avatar_label.setText(((name or phone or "·")[0]).upper())

        self._apply_week_stats()

    def _note_renewal_rate(self, reason: str):
        """The platform states 续费率 in its refusals; cache it for the card."""
        match = re.search(r"续费率\s*(\d{1,3})\s*%", reason or "")
        if not match:
            return
        try:
            rate = int(match.group(1))
        except ValueError:
            return
        if self.cfg.get("last_renewal_rate") != rate:
            self.cfg["last_renewal_rate"] = rate
            self._refresh_account_card()

    def _form_row(self, grid, row, label, widget, hint):
        """Two columns only: the hint lives in the widget's tooltip."""
        lab = QLabel(label)
        lab.setObjectName("fieldLabel")
        lab.setAlignment(Qt.AlignRight | Qt.AlignVCenter)
        widget.setToolTip(hint)
        grid.addWidget(lab, row, 0)
        grid.addWidget(widget, row, 1)

    def _stats_snapshot(self):
        """success / failed / skipped / unknown counts for today."""
        counts = {"success": 0, "failed": 0, "skipped": 0, "unknown": 0}
        try:
            entries = history_mod.load()
        except Exception:
            entries = []
        today = time.strftime("%Y-%m-%d")
        for entry in entries:
            if not str(entry.get("grabbed_at") or "").startswith(today):
                continue
            outcome = str(entry.get("outcome") or "success")
            counts[outcome if outcome in counts else "failed"] += 1
        return counts

    def _build_item_switch_section(self, sidebar_layout):
        card, column = self._sidebar_card(sidebar_layout, "接单类型")
        self.item_switch_box = QVBoxLayout()
        self.item_switch_box.setSpacing(4)
        self.item_checks = {}
        column.addLayout(self.item_switch_box)
        self._rebuild_item_switches([], [])
        return card

    def _build_stats_section(self, sidebar_layout):
        """今日统计 : sidebar card under 监听控制, where the space was empty."""
        card, column = self._sidebar_card(sidebar_layout, "今日概况")
        grid = QGridLayout()
        grid.setHorizontalSpacing(8)
        self.stat_labels = {}
        for index, (key, caption) in enumerate((
            ("success", "成功"), ("failed", "失败"),
            ("skipped", "跳过"), ("unknown", "未知"),
        )):
            value = QLabel("0")
            value.setObjectName("statValue")
            cap = QLabel(caption)
            cap.setObjectName("hint")
            grid.addWidget(value, 0, index)
            grid.addWidget(cap, 1, index)
            self.stat_labels[key] = value
        column.addLayout(grid)
        divider = QFrame()
        divider.setObjectName("divider")
        divider.setFixedHeight(1)
        column.addWidget(divider)
        self.last_order_main = QLabel("暂无记录")
        self.last_order_main.setObjectName("sidebarState")
        self.last_order_main.setWordWrap(True)
        column.addWidget(self.last_order_main)
        self.last_order_note = QLabel("抢到单后会显示在这里")
        self.last_order_note.setObjectName("sidebarHint")
        self.last_order_note.setWordWrap(True)
        column.addWidget(self.last_order_note)
        self._refresh_stats()
        self._refresh_last_order()
        return card

    def _build_last_order_section(self, sidebar_layout):
        card, column = self._sidebar_card(sidebar_layout, "最近一单")
        self.last_order_main = QLabel("暂无记录")
        self.last_order_main.setObjectName("sidebarState")
        self.last_order_main.setWordWrap(True)
        column.addWidget(self.last_order_main)
        self.last_order_note = QLabel("抢到单后会显示在这里")
        self.last_order_note.setObjectName("sidebarHint")
        self.last_order_note.setWordWrap(True)
        column.addWidget(self.last_order_note)
        self._refresh_last_order()
        return card

    def _refresh_last_order(self):
        label = getattr(self, "last_order_main", None)
        note = getattr(self, "last_order_note", None)
        if label is None:
            return
        try:
            entries = history_mod.load()
        except Exception:
            entries = []
        if not entries:
            label.setText("暂无记录")
            note.setText("抢到单后会显示在这里")
            return
        entry = max(entries, key=lambda e: str(e.get("grabbed_at") or ""))
        stamp = str(entry.get("grabbed_at") or "").replace("T", " ")[5:16]
        outcome = str(entry.get("outcome") or "success")
        tag, _ = _outcome_label(outcome, self._palette)
        try:
            amount = float(entry.get("amount") or 0)
        except (TypeError, ValueError):
            amount = 0.0
        label.setText("%s   ¥%.2f   [%s]" % (stamp, amount, tag))
        reason = str(entry.get("reason") or "").strip()
        note.setText(reason if reason else str(entry.get("no") or ""))

    def _refresh_stats(self):
        labels = getattr(self, "stat_labels", None)
        if not labels:
            return
        for key, value in self._stats_snapshot().items():
            if key in labels:
                labels[key].setText(str(value))

    def _build_conditions_section(self, main_layout):
        header = QHBoxLayout()
        header.addWidget(self._section_title("接单条件"))
        header.addStretch(1)
        header.addWidget(self._ghost_button("保存设置", self._on_save_settings))
        main_layout.addLayout(header)
        main_layout.addSpacing(8)
        card = self._card()
        card_layout = QVBoxLayout(card)
        card_layout.setContentsMargins(13, 13, 13, 13)
        inner = QWidget()
        grid = QGridLayout(inner)
        grid.setContentsMargins(0, 0, 0, 0)
        grid.setVerticalSpacing(4)
        grid.setHorizontalSpacing(10)
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
            widget.setFixedWidth(150)
        self.memo_mode_box = QComboBox()
        self.memo_mode_box.addItems(["不检测备注", "有备注不抢"])
        self._form_row(grid, 3, "备注检测", self.memo_mode_box,
                       "有备注即跳过")
        self.confirm_wait_min_spin = QSpinBox()
        self.confirm_wait_min_spin.setRange(0, 120)
        self.confirm_wait_min_spin.setSuffix(" 秒")
        self._form_row(grid, 4, "等待最小（秒）", self.confirm_wait_min_spin,
                       "0 不等待；可等于最大值")
        self.confirm_wait_max_spin = QSpinBox()
        self.confirm_wait_max_spin.setRange(0, 120)
        self.confirm_wait_max_spin.setSuffix(" 秒")
        self._form_row(grid, 5, "等待最大（秒）", self.confirm_wait_max_spin,
                       "相同即固定等待")
        inner.setMaximumWidth(500)
        for widget in (self.memo_mode_box, self.confirm_wait_min_spin,
                       self.confirm_wait_max_spin):
            widget.setFixedWidth(150)
        card_layout.addWidget(inner, 0, Qt.AlignLeft)
        tip = self._hint("悬停任意输入框可查看该项说明")
        card_layout.addWidget(tip)

        # 刷新与休息 (run/rest): visible toggle + four minute fields.
        rest_group = QWidget()
        rest_grid = QGridLayout(rest_group)
        rest_title = QLabel("刷新与休息")
        rest_title.setObjectName("subTitle")
        rest_grid.addWidget(rest_title, 0, 0, 1, 4)
        self.run_rest_check = QCheckBox("开启刷新与休息（风控店铺轮换休息）")
        self.run_rest_check.setChecked(True)
        rest_grid.addWidget(self.run_rest_check, 1, 0, 1, 4)
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
        for spin in (self.risk_run_min_spin, self.risk_run_max_spin,
                     self.risk_rest_min_spin, self.risk_rest_max_spin):
            spin.setFixedWidth(80)
        for index, (lab_text, spin) in enumerate([
            ("刷新最短", self.risk_run_min_spin),
            ("刷新最长", self.risk_run_max_spin),
            ("休息最短", self.risk_rest_min_spin),
            ("休息最长", self.risk_rest_max_spin),
        ]):
            lab = QLabel(lab_text)
            lab.setObjectName("fieldLabel")
            # two pairs per row with 88px spins: 2x2 keeps this card hugging
            # its content instead of stretching to fill the column
            rest_grid.addWidget(lab, 2 + index // 2, (index % 2) * 2)
            rest_grid.addWidget(spin, 2 + index // 2, (index % 2) * 2 + 1)
        rest_grid.setVerticalSpacing(6)
        rest_grid.setHorizontalSpacing(8)
        # 刷新与休息 gets its own card at the bottom of the column: inside the
        # conditions card it left ~200px of bare background under the form.
        # One row measure for every control in the form: QSS min-height cannot
        # win against each widget's own padding, so pin it here.
        for widget in (self.min_amount_edit, self.max_amount_edit,
                       self.poll_interval_edit, self.memo_mode_box,
                       self.confirm_wait_min_spin, self.confirm_wait_max_spin,
                       self.risk_run_min_spin, self.risk_run_max_spin,
                       self.risk_rest_min_spin, self.risk_rest_max_spin):
            widget.setFixedHeight(34)

        rest_card = self._card()
        rest_layout = QVBoxLayout(rest_card)
        rest_layout.setContentsMargins(13, 13, 13, 13)
        rest_layout.addWidget(rest_group)
        rest_layout.addStretch(1)
        main_layout.addWidget(card)
        main_layout.addSpacing(21)
        main_layout.addWidget(rest_card, 1)

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
        self.auto_stop_check = QCheckBox("接到单后自动停止监听")
        self.auto_stop_check.setObjectName("sidebarCheck")
        self.auto_stop_check.setChecked(bool(self.cfg.get("auto_stop_after_grab", True)))
        self.auto_stop_check.setToolTip("关闭后抢到一单会继续轮询，适合连续接单")
        layout.addWidget(self.auto_stop_check)
        self.online_check = QCheckBox("在线接单（离线中）")
        self.online_check.setObjectName("sidebarCheck")
        self.online_check.setToolTip("对应平台的 status：1 上线 / 2 下线")
        self.online_check.toggled.connect(self._toggle_online)
        layout.addWidget(self.online_check)

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
            self._save_cfg()
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

    def _build_log_section(self, main_layout, tabbed=False):
        if not tabbed:
            main_layout.addWidget(self._section_title("运行日志"))
        card = self._card()
        card_layout = QVBoxLayout(card)
        card_layout.setContentsMargins(13, 13, 13, 13)
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
        if not tabbed:
            main_layout.addSpacing(21)

    def _build_history_section(self, main_layout, tabbed=False):
        if not tabbed:
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
        if tabbed:
            card_layout.addWidget(self.history_list, 1)
            clear_row = QHBoxLayout()
            clear_row.addWidget(self._secondary_button("清空历史", self._on_clear_history))
            clear_row.addStretch(1)
            card_layout.addLayout(clear_row)
        else:
            self.history_list.setFixedHeight(180)
            card_layout.addWidget(self.history_list, 1)
        main_layout.addWidget(card, 1)
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
        self._refresh_stats()
        self._refresh_last_order()
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
                self._save_cfg()
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
        self._note_renewal_rate(reason or "")
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
            self._save_cfg()
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
        """Hero status stays short: the full sentence lives in the sidebar.

        The login worker emits "已登录：<name>（ID：<id>），店铺 sid=<sid>", which
        next to 今日抢单 / 监听状态 / 续费率 / 主题 / 高级设置 / three window
        buttons has no room at an 1180px window.
        """
        if text.startswith("已登录"):
            sid = getattr(self, "_session_sid", "") or ""
            self.status_label.setText("店铺 %s" % sid if sid else "已登录")
            self.status_label.setToolTip(text)
        elif text.startswith("登录失败"):
            self.status_label.setText("登录失败")
            self.status_label.setToolTip(text)
        else:
            self.status_label.setText(text)
            self.status_label.setToolTip("")

    def _on_login_state(self, text: str):
        self.login_state_label.setText(text)
        if text.startswith("已登录"):
            self._show_account_card(True)
            self._reload_account_picker()
        elif text.startswith(("未登录", "已注销", "登录失败")):
            self._show_account_card(False)

    def _on_listen_state(self, text: str):
        self.listen_state_label.setText(text)
        self.listen_state_sidebar_label.setText(text)

    # -- login --------------------------------------------------------------

    def _save_cfg(self):
        """Persist to the active account file (falls back to the legacy path)."""
        phone = str(self.cfg.get("phone") or "")
        try:
            if phone:
                return accounts_mod.save_account(self.cfg, phone)
        except Exception:
            pass
        return cfgmod.save(self.cfg)

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
            self._clerk_name = str((clerk or {}).get("name") or "")
            self._clerk_id = str((clerk or {}).get("id") or "")
            self._session_sid = str(getattr(session, "sid", "") or "")
            self.cfg["clerk_name"] = self._clerk_name
            self.cfg["last_shop"] = self._session_sid
            try:
                accounts_mod.save_account(self.cfg, phone)
            except Exception:
                pass
            self._fetch_avatar(clerk or {})
            self._fetch_week_orders(session)
            self._fetch_clerk_state(session, clerk or {})
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
        # the stock title bar looked out of place next to the frameless main
        # window, so the dialog draws its own header and drags by it
        self.setWindowFlag(Qt.FramelessWindowHint, True)
        self._drag_offset = None
        self.setFixedSize(880, 700)
        self.cfg = dict(cfg)
        self._on_saved = on_saved

        outer = QVBoxLayout(self)
        outer.setContentsMargins(16, 10, 16, 14)

        header = QHBoxLayout()
        header.setContentsMargins(0, 0, 0, 0)
        header_title = QLabel("高级设置")
        header_title.setObjectName("dialogTitle")
        header.addWidget(header_title)
        header.addStretch(1)
        close_button = QPushButton("✕")
        close_button.setObjectName("dialogClose")
        close_button.setFixedSize(28, 28)
        close_button.clicked.connect(self.reject)
        header.addWidget(close_button)
        outer.addLayout(header)
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setFrameShape(QFrame.NoFrame)
        content = QWidget()
        root = QVBoxLayout(content)
        root.setContentsMargins(0, 0, 0, 0)
        scroll.setWidget(content)
        outer.addWidget(scroll, 1)

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

    def mousePressEvent(self, event):
        if event.button() == Qt.LeftButton and event.position().y() <= 44:
            child = self.childAt(event.position().toPoint())
            if child is None or not isinstance(child, QPushButton):
                self._drag_offset = (event.globalPosition().toPoint()
                                     - self.frameGeometry().topLeft())
                event.accept()
                return
        super().mousePressEvent(event)

    def mouseMoveEvent(self, event):
        if self._drag_offset is not None and event.buttons() & Qt.LeftButton:
            self.move(event.globalPosition().toPoint() - self._drag_offset)
            event.accept()
            return
        super().mouseMoveEvent(event)

    def mouseReleaseEvent(self, event):
        self._drag_offset = None
        super().mouseReleaseEvent(event)

    def showEvent(self, event):
        super().showEvent(event)
        _apply_windows_effects(self, dark=str(self.windowTitle()) is not None)

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
            self.cfg["auto_stop_after_grab"] = self.auto_stop_check.isChecked()
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
            self._save_cfg()
        except (cfgmod.ConfigError, ValueError) as exc:
            QMessageBox.critical(self, "保存失败", str(exc))
            return
        self._on_saved(self.cfg)
        self.accept()
