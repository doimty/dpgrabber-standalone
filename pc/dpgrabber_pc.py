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
"""

from __future__ import annotations

import argparse
import sys

import config as cfgmod


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

    from ui import App

    if args.smoke_test:
        app = App(cfg)
        app.update_idletasks()
        title = app.title()  # read before destroy; wm calls fail afterwards
        assert title == "DPGrabber PC", "unexpected window title: %r" % title
        assert app.phone_var is not None and app.log_text is not None
        app.after(1500, app.destroy)
        app.mainloop()
        print("SMOKE OK: window opened, title=%r, config=%s" % (title, cfgmod.config_path()))
        return 0

    app = App(cfg)
    app.log("info", "DPGrabber PC 已启动。请输入账号登录，或先在监听设置中保存配置。")
    app.mainloop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
