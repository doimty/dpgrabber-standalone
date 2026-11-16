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

import accounts as accounts_mod
import config as cfgmod


def _install_crash_logging():
    """把崩溃现场写到 <配置目录>/dpgrabber_crash.log。

    - faulthandler 抓 C 层崩溃（PySide 段错误）时的所有线程栈
    - sys/threading.excepthook 抓 Python 未捕获异常（含子线程）
    - Qt 消息处理器抓 QWidget/QPainter 之类的运行时警告
    窗口版 exe 的 stderr 看不见，这些是唯一线索。
    """
    import time as _time
    import traceback

    try:
        path = os.path.join(cfgmod.config_dir(), "dpgrabber_crash.log")
        handle = open(path, "a", encoding="utf-8", buffering=1)
    except Exception:
        return None

    try:
        import faulthandler
        faulthandler.enable(file=handle, all_threads=True)
    except Exception:
        pass

    def hook(exc_type, exc, tb):
        try:
            handle.write("\n===== %s 未捕获异常 =====\n"
                         % _time.strftime("%Y-%m-%d %H:%M:%S"))
            traceback.print_exception(exc_type, exc, tb, file=handle)
            handle.flush()
        except Exception:
            pass
        sys.__excepthook__(exc_type, exc, tb)

    sys.excepthook = hook

    try:
        import threading

        def thread_hook(args):
            try:
                handle.write("\n===== %s 线程异常 %s =====\n"
                             % (_time.strftime("%Y-%m-%d %H:%M:%S"),
                                getattr(getattr(args, "thread", None), "name", "?")))
                traceback.print_exception(args.exc_type, args.exc_value,
                                          args.exc_traceback, file=handle)
                handle.flush()
            except Exception:
                pass

        threading.excepthook = thread_hook
    except Exception:
        pass
    return handle


def _install_qt_message_log(handle):
    if handle is None:
        return
    try:
        import time as _time
        from PySide6.QtCore import qInstallMessageHandler

        def handler(mode, context, message):
            try:
                handle.write("[qt %s] %s\n" % (_time.strftime("%H:%M:%S"), message))
                handle.flush()
            except Exception:
                pass

        qInstallMessageHandler(handler)
    except Exception:
        pass


def _resource(name: str) -> str:
    """随 exe 一起打包的资源路径（PyInstaller 解包到 _MEIPASS）。"""
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    return os.path.join(base, name)


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


def _smoke_window(qapp, window, args) -> int:
    """Bounded offline validation of the actual packaged Qt window."""
    import json
    from pathlib import Path
    from PySide6.QtCore import QPoint, QRect, Qt, QTimer
    try:
        from build_stamp import SHA
    except ImportError:
        SHA = "dev"
    requested = None
    if args.smoke_size:
        parts = args.smoke_size.lower().split("x")
        if len(parts) != 2:
            raise ValueError("smoke-size must be WIDTHxHEIGHT")
        requested = tuple(int(part) for part in parts)
        if min(requested) <= 0:
            raise ValueError("smoke-size must be positive")
    result = {"code": 1}
    window.setAttribute(Qt.WA_ShowWithoutActivating, True)
    window.setWindowFlag(Qt.WindowDoesNotAcceptFocus, True)
    window.setWindowFlag(Qt.WindowTransparentForInput, True)
    window.show()
    if requested is not None:
        window.resize(*requested)

    def collect():
        errors = []
        area = window.screen().availableGeometry()
        if not area.contains(window.frameGeometry()):
            errors.append("window outside available screen")
        if requested is not None and (window.width(), window.height()) != requested:
            errors.append("requested size was not respected")
        actions = {}
        for name in ("start_btn", "stop_btn", "save_button", "cancel_button"):
            button = getattr(window, name, None)
            if button is None:
                continue
            reachable = button.isVisibleTo(window)
            parent = button.parentWidget()
            while parent is not None and reachable:
                reachable = parent.rect().contains(QRect(button.mapTo(parent, QPoint()), button.size()))
                if parent is window:
                    break
                parent = parent.parentWidget()
            actions[name] = reachable
            if not reachable:
                errors.append(name + " is not reachable")
        report = {"build": SHA, "platform": qapp.platformName(),
                  "dpr": window.devicePixelRatioF(),
                  "window": [window.width(), window.height()],
                  "screen": [area.width(), area.height()],
                  "mode": getattr(window, "_layout_mode", "advanced"),
                  "actions": actions, "errors": errors,
                  "synthetic_data": True, "screenshot_kind": "qt-widget-grab"}
        if args.smoke_output:
            folder = Path(args.smoke_output).resolve()
            folder.mkdir(parents=True, exist_ok=True)
            window.grab().save(str(folder / "window.png"))
            (folder / "geometry.json").write_text(json.dumps(report, ensure_ascii=False, indent=2),
                                                   encoding="utf-8")
        print("UI SMOKE " + ("OK" if not errors else "FAILED") + ": " + json.dumps(report))
        result["code"] = 0 if not errors else 1
        window.close()
        qapp.quit()

    QTimer.singleShot(700, collect)
    QTimer.singleShot(8000, qapp.quit)
    qapp.exec()
    return result["code"]


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="DPGrabber PC")
    parser.add_argument(
        "--smoke-test", action="store_true",
        help="Open the window, verify it builds, then exit (no network).",
    )
    parser.add_argument(
        "--smoke-advanced", action="store_true",
        help="Open advanced settings with synthetic defaults, then exit (no account files or network).",
    )
    parser.add_argument("--smoke-size", metavar="WIDTHxHEIGHT",
                        help="Logical window size for an offline UI smoke test.")
    parser.add_argument("--smoke-output", metavar="DIRECTORY",
                        help="Write synthetic UI screenshot and geometry JSON here.")
    args = parser.parse_args(argv)

    if args.smoke_advanced or args.smoke_test:
        import copy
        import tempfile
        # Test-only storage beside the working directory, never real account
        # files. The caller can remove it after the process and log handles close.
        smoke_dir = tempfile.mkdtemp(prefix="dppp-ui-smoke-", dir=os.getcwd())
        cfgmod.config_dir = lambda: smoke_dir
        cfgmod.config_path = lambda: os.path.join(smoke_dir, "config.json")
        cfg = cfgmod.validate(copy.deepcopy(cfgmod.DEFAULT_CONFIG))
        cfg["theme"] = "blue"
        cfg["voice_enabled"] = False
    else:
        try:
            # one config file per account; the old single file is migrated once
            accounts_mod.migrate_legacy()
            known = accounts_mod.list_accounts()
        except Exception:
            known = []
        try:
            cfg = accounts_mod.load_account(known[0]["phone"]) if known else cfgmod.load()
        except cfgmod.ConfigError as exc:
            print("config error: %s" % exc, file=sys.stderr)
            return 2

    # 界面缩放必须在 QApplication 创建之前设好：Qt 只在这个时刻读它，
    # QSS 里的 px、控件尺寸、字体都会一起等比变化。
    try:
        scale = cfgmod.resolve_ui_scale(cfg)
        os.environ["QT_SCALE_FACTOR"] = "%.2f" % scale
        os.environ.setdefault("QT_ENABLE_HIGHDPI_SCALING", "1")
    except Exception:
        pass

    from PySide6.QtCore import QTimer
    from themes import DEFAULT_THEME, THEMES
    from ui import App, apply_theme

    crash_handle = _install_crash_logging()
    qapp = _ensure_qapp()
    _install_qt_message_log(crash_handle)
    # 窗口/任务栏图标：只靠 exe 内嵌图标时，无边框窗口在 Win11 上会回退成
    # 通用文档图标，所以显式给应用设一个。
    try:
        from PySide6.QtGui import QIcon
        icon_path = _resource("app.png")
        if os.path.exists(icon_path):
            qapp.setWindowIcon(QIcon(icon_path))
    except Exception:
        pass
    # Apply the saved theme before showing anything.
    theme_id = str(cfg.get("theme") or DEFAULT_THEME)
    if theme_id not in THEMES:
        theme_id = DEFAULT_THEME
    apply_theme(qapp, THEMES[theme_id]["palette"], theme_id)

    if args.smoke_advanced:
        from ui import AdvancedDialog
        return _smoke_window(qapp, AdvancedDialog(None, cfg, lambda _: None), args)

    if args.smoke_test:
        # Preview never initializes speech or starts a login/listening worker.
        from types import SimpleNamespace
        import ui as ui_module
        ui_module.Voice = lambda **_: SimpleNamespace(enabled=False, stop=lambda: None)
        app = App(cfg)
        assert app.windowTitle() == "DPPP"
        assert app.phone_edit is not None and app.log_text is not None
        assert app.history_list is not None
        return _smoke_window(qapp, app, args)

    app = App(cfg)
    app.show()
    app.log("info", "DPPP 已启动。请输入账号登录，或先在监听设置中保存配置。")
    return qapp.exec()


if __name__ == "__main__":
    raise SystemExit(main())
