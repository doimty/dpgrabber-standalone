#!/usr/bin/env python3
"""DPGrabber PC - standalone DuoPei order grabber (desktop).

Run:  python3 dpgrabber_pc.py
Smoke: python3 dpgrabber_pc.py --smoke-test   (opens the window briefly
       under a virtual display and exits; no real request is sent)
Tests: python3 -m unittest discover -s tests -v

The program logs in with phone + password (PROTOCOL.md section 3.2),
polls the order hall, filters orders and confirms a passing order with
DPGrabber's confirm state machine (section 8): submit immediately inside
a 20 s window, retry every 0.5 s until the deadline, reconcile with
order/get when the result is uncertain. No real account, token or
password is shipped in code; the config file is created empty on first
run and filled in by the user.

UI: PySide6 (Qt Widgets) — see ui.py. Real Windows 11 Mica is applied
inside the window when running on Windows; elsewhere it is a no-op.
"""

from __future__ import annotations

import argparse
import os
import sys

import config as cfgmod


def _ensure_qapp():
    """Create (or reuse) the QApplication. Under a headless environment
    with no DISPLAY, fall back to the offscreen platform so the smoke
    test can still build the window."""
    if "DISPLAY" not in os.environ and sys.platform != "win32":
        os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
    from PySide6.QtWidgets import QApplication

    app = QApplication.instance()
    if app is None:
        app = QApplication(sys.argv)
    return app


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="DPGrabber PC")
    parser.add_argument(
        "--smoke-test", action="store_true",
        help="Open the window, verify it builds, then exit (no network).",
    )
    args = parser.parse_args(argv)

    try:
        cfg = cfgmod.load()
    except cfgmod.ConfigError as exc:
        print("config error: %s" % exc, file=sys.stderr)
        return 2

    from PySide6.QtCore import QTimer
    from themes import DEFAULT_THEME, THEMES
    from ui import App, apply_theme

    qapp = _ensure_qapp()
    # Apply the saved theme before showing anything.
    theme_id = str(cfg.get("theme") or DEFAULT_THEME)
    if theme_id not in THEMES:
        theme_id = DEFAULT_THEME
    apply_theme(qapp, THEMES[theme_id]["palette"], theme_id)

    if args.smoke_test:
        app = App(cfg)
        app.show()
        title = app.windowTitle()  # read before close; widgets die with it
        assert title == "DPGrabber PC", "unexpected window title: %r" % title
        assert app.phone_edit is not None and app.log_text is not None
        assert app.history_list is not None
        QTimer.singleShot(1500, app.close)
        qapp.exec()
        print("SMOKE OK: window opened, title=%r, config=%s" % (title, cfgmod.config_path()))
        return 0

    app = App(cfg)
    app.show()
    app.log("info", "DPGrabber PC 已启动。请输入账号登录，或先在监听设置中保存配置。")
    return qapp.exec()


if __name__ == "__main__":
    raise SystemExit(main())
