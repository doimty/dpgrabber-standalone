"""DPGrabber PC - tkinter user interface.

Own wording and layout ("DPGrabber PC"). The reference tool's UI text and
brand are not copied; only the functional flow is aligned with it
(PROTOCOL.md): login, listen settings, start/stop listening, run log.

All network work runs on worker threads. Threads never touch widgets
directly: they push ``(level, message)`` into a queue and the UI drains it
with ``after()``.
"""

from __future__ import annotations

import queue
import threading
import time
import tkinter as tk
from tkinter import messagebox, scrolledtext, ttk

import config as cfgmod
from dpapi import ApiClient, ApiError, extract_clerk
from engine import Engine
from voice import Voice

APP_TITLE = "DPGrabber PC"
WINDOW_SIZE = "980x700"


class App(tk.Tk):
    def __init__(self, cfg: dict):
        super().__init__()
        self.title(APP_TITLE)
        self.geometry(WINDOW_SIZE)
        self.cfg = cfg
        self.log_queue: queue.Queue = queue.Queue()
        self.client = self._make_client()
        self.voice = Voice(enabled=bool(cfg.get("voice_enabled", True)))
        self.engine: Engine | None = None
        self._login_thread: threading.Thread | None = None
        self._build_widgets()
        self._load_settings_into_widgets()
        self.after(200, self._drain_logs)
        self.after(500, self._refresh_buttons)
        self.protocol("WM_DELETE_WINDOW", self._on_close)

    # -- construction -----------------------------------------------------

    def _make_client(self) -> ApiClient:
        return ApiClient(
            aes_key_hex=self.cfg["aes_key_hex"],
            aes_iv_hex=self.cfg["aes_iv_hex"],
            aes_key_hex_alt=self.cfg.get("aes_key_hex_alt", ""),
            aes_iv_hex_alt=self.cfg.get("aes_iv_hex_alt", ""),
            verify_tls=bool(self.cfg.get("verify_tls", True)),
        )

    def _build_widgets(self):
        style = ttk.Style(self)
        try:
            style.theme_use("clam")
        except tk.TclError:
            pass

        main = ttk.Frame(self, padding=12)
        main.pack(fill=tk.BOTH, expand=True)

        # Status line.
        top = ttk.Frame(main)
        top.pack(fill=tk.X, pady=(0, 8))
        ttk.Label(top, text=APP_TITLE, font=("TkDefaultFont", 14, "bold")).pack(side=tk.LEFT)
        self.status_var = tk.StringVar(value="未登录")
        ttk.Label(top, textvariable=self.status_var).pack(side=tk.RIGHT)

        # 1. Login.
        login = ttk.LabelFrame(main, text="账号登录（手机号 + 密码）", padding=10)
        login.pack(fill=tk.X, pady=(0, 8))
        ttk.Label(login, text="手机号").grid(row=0, column=0, sticky=tk.W, padx=(0, 6))
        self.phone_var = tk.StringVar()
        ttk.Entry(login, textvariable=self.phone_var, width=24).grid(row=0, column=1, sticky=tk.W)
        ttk.Label(login, text="密码").grid(row=0, column=2, sticky=tk.W, padx=(16, 6))
        self.password_var = tk.StringVar()
        self.password_entry = ttk.Entry(login, textvariable=self.password_var, width=24, show="*")
        self.password_entry.grid(row=0, column=3, sticky=tk.W)
        self.show_pw_var = tk.BooleanVar(value=False)
        ttk.Checkbutton(
            login, text="显示密码", variable=self.show_pw_var,
            command=self._toggle_show_password,
        ).grid(row=0, column=4, sticky=tk.W, padx=(8, 0))
        ttk.Button(login, text="登录", command=self._on_login).grid(row=0, column=5, padx=(16, 4))
        ttk.Button(login, text="注销", command=self._on_logout).grid(row=0, column=6)
        self.login_state_var = tk.StringVar(value="请输入手机号和密码后登录")
        ttk.Label(login, textvariable=self.login_state_var).grid(
            row=1, column=0, columnspan=7, sticky=tk.W, pady=(6, 0)
        )

        # 2. Listen settings.
        settings = ttk.LabelFrame(main, text="监听设置", padding=10)
        settings.pack(fill=tk.X, pady=(0, 8))
        ttk.Label(settings, text="最小金额（元）").grid(row=0, column=0, sticky=tk.W, padx=(0, 6))
        self.min_amount_var = tk.StringVar()
        ttk.Entry(settings, textvariable=self.min_amount_var, width=10).grid(row=0, column=1, sticky=tk.W)
        ttk.Label(settings, text="最大金额（元，0 表示不限制）").grid(row=0, column=2, sticky=tk.W, padx=(16, 6))
        self.max_amount_var = tk.StringVar()
        ttk.Entry(settings, textvariable=self.max_amount_var, width=10).grid(row=0, column=3, sticky=tk.W)
        ttk.Label(settings, text="抢单后延时（秒）").grid(row=0, column=4, sticky=tk.W, padx=(16, 6))
        self.user_delay_var = tk.StringVar()
        ttk.Entry(settings, textvariable=self.user_delay_var, width=6).grid(row=0, column=5, sticky=tk.W)
        ttk.Label(settings, text="轮询间隔（秒）").grid(row=0, column=6, sticky=tk.W, padx=(16, 6))
        self.poll_interval_var = tk.StringVar()
        ttk.Entry(settings, textvariable=self.poll_interval_var, width=6).grid(row=0, column=7, sticky=tk.W)

        ttk.Label(settings, text="备注检测").grid(row=1, column=0, sticky=tk.W, padx=(0, 6), pady=(8, 0))
        self.memo_mode_var = tk.StringVar()
        self.memo_mode_box = ttk.Combobox(
            settings, textvariable=self.memo_mode_var, width=18, state="readonly",
            values=["不检测备注", "有备注不抢"],
        )
        self.memo_mode_box.grid(row=1, column=1, columnspan=2, sticky=tk.W, pady=(8, 0))
        ttk.Label(settings, text="备注关键词（一行一个，命中就不抢）").grid(
            row=1, column=3, columnspan=3, sticky=tk.W, padx=(16, 0), pady=(8, 0)
        )
        ttk.Label(settings, text="黑名单微信号（一行一个）").grid(
            row=1, column=6, columnspan=2, sticky=tk.W, padx=(16, 0), pady=(8, 0)
        )
        self.keyword_text = tk.Text(settings, width=32, height=4)
        self.keyword_text.grid(row=2, column=0, columnspan=4, sticky=tk.W, pady=(4, 0))
        self.blacklist_text = tk.Text(settings, width=32, height=4)
        self.blacklist_text.grid(row=2, column=4, columnspan=4, sticky=tk.W, padx=(16, 0), pady=(4, 0))

        btn_row = ttk.Frame(settings)
        btn_row.grid(row=3, column=0, columnspan=8, sticky=tk.W, pady=(8, 0))
        ttk.Button(btn_row, text="保存设置", command=self._on_save_settings).pack(side=tk.LEFT)
        ttk.Button(btn_row, text="高级设置", command=self._open_advanced).pack(side=tk.LEFT, padx=(8, 0))

        # 3. Listen control.
        control = ttk.LabelFrame(main, text="监听", padding=10)
        control.pack(fill=tk.X, pady=(0, 8))
        self.start_btn = ttk.Button(control, text="开始监听", command=self._on_start_listen)
        self.start_btn.pack(side=tk.LEFT)
        self.stop_btn = ttk.Button(control, text="停止监听", command=self._on_stop_listen, state=tk.DISABLED)
        self.stop_btn.pack(side=tk.LEFT, padx=(8, 0))
        self.listen_state_var = tk.StringVar(value="未开始监听")
        ttk.Label(control, textvariable=self.listen_state_var).pack(side=tk.LEFT, padx=(16, 0))

        # 4. Run log.
        log_frame = ttk.LabelFrame(main, text="运行日志", padding=10)
        log_frame.pack(fill=tk.BOTH, expand=True)
        self.log_text = scrolledtext.ScrolledText(log_frame, state=tk.DISABLED, height=12)
        self.log_text.pack(fill=tk.BOTH, expand=True)
        self.log_text.tag_config("info", foreground="")
        self.log_text.tag_config("success", foreground="#1a7f37")
        self.log_text.tag_config("warning", foreground="#9a6700")
        self.log_text.tag_config("error", foreground="#cf222e")
        self.log_text.tag_config("debug", foreground="#666666")
        ttk.Button(log_frame, text="清空日志", command=self._clear_log).pack(anchor=tk.W, pady=(6, 0))

    def _load_settings_into_widgets(self):
        self.phone_var.set(self.cfg.get("phone", ""))
        self.password_var.set(self.cfg.get("password", ""))
        self.min_amount_var.set(str(self.cfg.get("min_amount", 10.0)))
        self.max_amount_var.set(str(self.cfg.get("max_amount", 0.0)))
        self.user_delay_var.set(str(self.cfg.get("user_delay", 0)))
        self.poll_interval_var.set(str(self.cfg.get("poll_interval_sec", 0.5)))
        self.memo_mode_box.set("有备注不抢" if int(self.cfg.get("memo_mode", 0)) == 1 else "不检测备注")
        self.keyword_text.delete("1.0", tk.END)
        self.keyword_text.insert(tk.END, "\n".join(self.cfg.get("memo_keywords", [])))
        self.blacklist_text.delete("1.0", tk.END)
        self.blacklist_text.insert(tk.END, "\n".join(self.cfg.get("blacklist", [])))

    # -- logging ------------------------------------------------------------

    def log(self, level: str, message: str):
        self.log_queue.put((level, message))

    def _drain_logs(self):
        try:
            while True:
                level, message = self.log_queue.get_nowait()
                stamp = time.strftime("%H:%M:%S")
                self.log_text.configure(state=tk.NORMAL)
                self.log_text.insert(tk.END, "[%s] %s\n" % (stamp, message), level)
                self.log_text.see(tk.END)
                self.log_text.configure(state=tk.DISABLED)
                if level in ("success", "error", "warning"):
                    self.voice.speak(message)
        except queue.Empty:
            pass
        self.after(200, self._drain_logs)

    def _clear_log(self):
        self.log_text.configure(state=tk.NORMAL)
        self.log_text.delete("1.0", tk.END)
        self.log_text.configure(state=tk.DISABLED)

    # -- settings -----------------------------------------------------------

    def _read_settings_from_widgets(self) -> dict:
        try:
            min_amount = float(self.min_amount_var.get())
            max_amount = float(self.max_amount_var.get())
            user_delay = int(self.user_delay_var.get())
            poll_interval = float(self.poll_interval_var.get())
        except ValueError:
            raise cfgmod.ConfigError("金额必须是数字，延时和轮询间隔必须是数字")
        if min_amount < 0 or max_amount < 0:
            raise cfgmod.ConfigError("金额不能小于 0")
        if max_amount > 0 and max_amount < min_amount:
            raise cfgmod.ConfigError("最大金额不能小于最小金额；填 0 表示不限制")
        if user_delay < 0 or poll_interval <= 0:
            raise cfgmod.ConfigError("延时不能小于 0，轮询间隔必须大于 0")
        keywords = [line.strip() for line in self.keyword_text.get("1.0", tk.END).splitlines()]
        blacklist = [line.strip() for line in self.blacklist_text.get("1.0", tk.END).splitlines()]
        return {
            "phone": self.phone_var.get().strip(),
            "password": self.password_var.get(),
            "min_amount": min_amount,
            "max_amount": max_amount,
            "user_delay": user_delay,
            "poll_interval_sec": poll_interval,
            "memo_mode": 1 if self.memo_mode_var.get() == "有备注不抢" else 0,
            "memo_keywords": [k for k in keywords if k],
            "blacklist": [b for b in blacklist if b],
        }

    def _on_save_settings(self):
        try:
            updates = self._read_settings_from_widgets()
        except cfgmod.ConfigError as exc:
            messagebox.showerror("设置有误", str(exc), parent=self)
            return
        self.cfg.update(updates)
        try:
            cfgmod.save(self.cfg)
        except cfgmod.ConfigError as exc:
            messagebox.showerror("保存失败", str(exc), parent=self)
            return
        self.log("success", "设置已保存")

    def _open_advanced(self):
        AdvancedDialog(self, self.cfg, on_saved=self._on_advanced_saved)

    def _on_advanced_saved(self, new_cfg: dict):
        self.cfg = new_cfg
        self.client = self._make_client()
        self.voice.enabled = bool(new_cfg.get("voice_enabled", True))
        self.log("success", "高级设置已保存")

    # -- login --------------------------------------------------------------

    def _toggle_show_password(self):
        self.password_entry.configure(show="" if self.show_pw_var.get() else "*")

    def _on_login(self):
        phone = self.phone_var.get().strip()
        password = self.password_var.get()
        if not phone or not password:
            messagebox.showwarning("缺少信息", "请先填写手机号和密码", parent=self)
            return
        if len(phone) != 11 or not phone.isdigit():
            messagebox.showwarning("手机号格式", "手机号应为 11 位数字", parent=self)
            return
        if self._login_thread is not None and self._login_thread.is_alive():
            return
        self.login_state_var.set("正在登录，请稍后…")
        self._login_thread = threading.Thread(
            target=self._login_worker, args=(phone, password), daemon=True
        )
        self._login_thread.start()

    def _login_worker(self, phone: str, password: str):
        try:
            session = self.client.session_for_account(
                phone, password, int(self.cfg.get("shop_id", 92))
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
            self.log_queue.put(("success", label))
            self.after(0, lambda: self.status_var.set(label))
            self.after(0, lambda: self.login_state_var.set(label))
        except ApiError as exc:
            self.log_queue.put(("error", "登录失败：%s" % exc.message))
            self.after(0, lambda: self.status_var.set("未登录"))
            self.after(0, lambda: self.login_state_var.set("登录失败：%s" % exc.message))

    def _on_logout(self):
        if self.engine is not None and self.engine.running:
            self._on_stop_listen()
        self.client.session = None
        self.status_var.set("未登录")
        self.login_state_var.set("已注销，请重新登录")
        self.log("info", "已注销")

    # -- listening ----------------------------------------------------------

    def _on_start_listen(self):
        if self.engine is not None and self.engine.running:
            return
        try:
            updates = self._read_settings_from_widgets()
        except cfgmod.ConfigError as exc:
            messagebox.showerror("设置有误", str(exc), parent=self)
            return
        phone = updates["phone"]
        password = updates["password"]
        if not phone or not password:
            messagebox.showwarning("缺少信息", "请先填写手机号和密码", parent=self)
            return
        self.cfg.update(updates)
        self.engine = Engine(self.client, self.cfg, log=self.log, speak=self.voice.speak)
        if self.engine.start(phone, password):
            self.listen_state_var.set("正在监听")
            self.start_btn.configure(state=tk.DISABLED)
            self.stop_btn.configure(state=tk.NORMAL)

    def _on_stop_listen(self):
        if self.engine is not None:
            self.engine.stop()
        self.listen_state_var.set("已停止监听")
        self.start_btn.configure(state=tk.NORMAL)
        self.stop_btn.configure(state=tk.DISABLED)

    def _refresh_buttons(self):
        if self.engine is not None and not self.engine.running and self.start_btn["state"] == tk.DISABLED:
            # Engine stopped on its own (e.g. grab succeeded).
            self.listen_state_var.set("监听已停止")
            self.start_btn.configure(state=tk.NORMAL)
            self.stop_btn.configure(state=tk.DISABLED)
        self.after(500, self._refresh_buttons)

    def _on_close(self):
        if self.engine is not None:
            self.engine.stop()
        self.voice.stop()
        self.destroy()


class AdvancedDialog(tk.Toplevel):
    """Advanced settings: AES pairs, shop id, timeout, TLS, voice."""

    def __init__(self, parent: App, cfg: dict, on_saved):
        super().__init__(parent)
        self.title("高级设置")
        self.cfg = dict(cfg)
        self._on_saved = on_saved
        self.resizable(False, False)

        frame = ttk.Frame(self, padding=14)
        frame.pack(fill=tk.BOTH, expand=True)
        ttk.Label(
            frame,
            text="加解密密钥可在配置文件中修改；密钥只用于解密服务器响应，\n"
                 "主密钥解密失败时自动尝试备用密钥。",
            wraplength=460,
        ).pack(anchor=tk.W, pady=(0, 10))

        self.entries: dict[str, tk.StringVar] = {}
        rows = [
            ("aes_key_hex", "主密钥（十六进制）"),
            ("aes_iv_hex", "主 IV（十六进制）"),
            ("aes_key_hex_alt", "备用密钥（十六进制）"),
            ("aes_iv_hex_alt", "备用 IV（十六进制）"),
            ("shop_id", "优先店铺 ID"),
            ("request_timeout_sec", "请求超时（秒）"),
        ]
        for key, label in rows:
            row = ttk.Frame(frame)
            row.pack(fill=tk.X, pady=3)
            ttk.Label(row, text=label, width=22).pack(side=tk.LEFT)
            var = tk.StringVar(value=str(self.cfg.get(key, "")))
            self.entries[key] = var
            width = 68 if "aes" in key else 12
            ttk.Entry(row, textvariable=var, width=width).pack(side=tk.LEFT)

        self.verify_var = tk.BooleanVar(value=bool(self.cfg.get("verify_tls", True)))
        ttk.Checkbutton(frame, text="验证服务器 TLS 证书（推荐开启）", variable=self.verify_var).pack(
            anchor=tk.W, pady=(8, 0)
        )
        self.voice_var = tk.BooleanVar(value=bool(self.cfg.get("voice_enabled", True)))
        ttk.Checkbutton(frame, text="语音播报（需要本机语音支持）", variable=self.voice_var).pack(
            anchor=tk.W
        )
        ttk.Label(frame, text="配置文件：%s" % cfgmod.config_path(), wraplength=460).pack(
            anchor=tk.W, pady=(8, 0)
        )

        btns = ttk.Frame(frame)
        btns.pack(fill=tk.X, pady=(12, 0))
        ttk.Button(btns, text="保存", command=self._save).pack(side=tk.RIGHT, padx=(6, 0))
        ttk.Button(btns, text="取消", command=self.destroy).pack(side=tk.RIGHT)

    def _save(self):
        try:
            for key, var in self.entries.items():
                value = var.get().strip()
                if key == "shop_id":
                    self.cfg[key] = int(value)
                elif key == "request_timeout_sec":
                    self.cfg[key] = float(value)
                else:
                    self.cfg[key] = value
            self.cfg["verify_tls"] = self.verify_var.get()
            self.cfg["voice_enabled"] = self.voice_var.get()
            cfgmod.save(self.cfg)
        except (cfgmod.ConfigError, ValueError) as exc:
            messagebox.showerror("保存失败", str(exc), parent=self)
            return
        self._on_saved(self.cfg)
        self.destroy()
