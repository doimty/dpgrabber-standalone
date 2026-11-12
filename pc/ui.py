"""DPGrabber PC - CustomTkinter user interface (blue-white theme).

Own wording and layout ("DPGrabber PC"). The reference tool's UI text and
brand are not copied; only the functional flow is aligned with it
(PROTOCOL.md): login, listen settings, start/stop listening, run log.

Style: the blue-white look of the DPGrabber panel — light blue-white
window background, white cards with blue titles, a solid blue hero band
with a lighter accent strip (CustomTkinter has no gradient, so the band
is two stacked solid blocks), blue primary buttons, white secondary
buttons with a blue outline. Still plain Python + tkinter underneath
(CustomTkinter); no new fonts, no image assets.

Layout: four titled sections (login / order conditions / listen control /
run log). Inside a section every field is one row: label right-aligned,
input left-aligned with a uniform width, and a short muted-blue hint
explaining what the field does, so no input is left unexplained.

All network work runs on worker threads. Threads never touch widgets
directly: they push ``(level, message)`` into a queue and the UI drains it
with ``after()``.
"""

from __future__ import annotations

import os
import queue
import threading
import time
import tkinter as tk
from tkinter import messagebox

import customtkinter as ctk

import config as cfgmod
from dpapi import ApiClient, ApiError, extract_clerk
from engine import Engine
from voice import Voice

ctk.set_appearance_mode("light")
ctk.set_default_color_theme("blue")

APP_TITLE = "DPGrabber PC"
WINDOW_SIZE = "1000x960"

# Blue-white palette.
BLUE = "#1F6AA5"
BLUE_DARK = "#185A8A"
BLUE_DISABLED = "#9BB3C4"
BLUE_ACCENT = "#63A4D6"
WINDOW_BG = "#F2F7FC"
CARD_BG = "#FFFFFF"
CARD_BORDER = "#D5E3F2"
ENTRY_BORDER = "#B9D2EC"
TEXT_DARK = "#1F2A37"
HINT = "#5B7A99"
PLACEHOLDER = "#9AA8B8"


def _primary_button(master, text, command):
    return ctk.CTkButton(
        master, text=text, command=command, width=100, height=32,
        fg_color=BLUE, hover_color=BLUE_DARK, text_color="white",
    )


def _secondary_button(master, text, command):
    return ctk.CTkButton(
        master, text=text, command=command, width=100, height=32,
        fg_color="white", hover_color="#EAF2FA", border_color=BLUE,
        border_width=1, text_color=BLUE,
    )


class _PlaceholderText(ctk.CTkTextbox):
    """CTkTextbox that shows grey example text while empty.

    CustomTkinter textboxes have no native placeholder, so this keeps the
    old behaviour: the example is never part of the value — ``get_lines()``
    returns [] while it is showing, and focus clears it automatically.
    """

    def __init__(self, master, placeholder: str, **kwargs):
        super().__init__(
            master, text_color=TEXT_DARK, fg_color="white",
            border_color=ENTRY_BORDER, **kwargs
        )
        self._placeholder = placeholder
        self._showing = False
        self.bind("<FocusIn>", self._on_focus_in)
        self.bind("<FocusOut>", self._on_focus_out)
        self._show()

    def _show(self):
        self.delete("1.0", tk.END)
        self.insert("1.0", self._placeholder)
        self.configure(text_color=PLACEHOLDER)
        self._showing = True

    def _on_focus_in(self, _event):
        if self._showing:
            self.delete("1.0", tk.END)
            self.configure(text_color=TEXT_DARK)
            self._showing = False

    def _on_focus_out(self, _event):
        if not self.get("1.0", tk.END).strip():
            self._show()

    def set_lines(self, lines):
        lines = [ln for ln in lines if ln.strip()]
        if lines:
            self.delete("1.0", tk.END)
            self.configure(text_color=TEXT_DARK)
            self.insert("1.0", "\n".join(lines))
            self._showing = False
        else:
            self._show()

    def get_lines(self):
        if self._showing:
            return []
        return [ln.strip() for ln in self.get("1.0", tk.END).splitlines() if ln.strip()]


class App(ctk.CTk):
    def __init__(self, cfg: dict):
        super().__init__(fg_color=WINDOW_BG)
        self.title(APP_TITLE)
        self.geometry(WINDOW_SIZE)
        self.cfg = cfg
        self.log_queue: queue.Queue = queue.Queue()
        self.client = self._make_client()
        self.voice = Voice(enabled=bool(cfg.get("voice_enabled", True)))
        self.engine: Engine | None = None
        self._listening = False
        self._login_thread: threading.Thread | None = None
        self._build_widgets()
        self._load_settings_into_widgets()
        self.after(200, self._drain_logs)
        self.after(500, self._refresh_buttons)
        # Canary: proves on launch that the log drain is alive. If even
        # this line never appears, the log textbox itself is not rendering.
        self.log("info", "DPGrabber PC 已启动")
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
        main = ctk.CTkFrame(self, fg_color="transparent")
        main.pack(fill=tk.BOTH, expand=True)

        # Blue hero band: solid blue + a lighter accent strip underneath
        # (CustomTkinter has no gradient, this is the closest match).
        hero = ctk.CTkFrame(main, fg_color=BLUE, corner_radius=0, height=56)
        hero.pack(fill=tk.X)
        hero.pack_propagate(False)
        ctk.CTkLabel(
            hero, text=APP_TITLE, text_color="white",
            font=ctk.CTkFont(size=20, weight="bold"),
        ).pack(side=tk.LEFT, padx=20)
        self.status_var = tk.StringVar(value="未登录")
        ctk.CTkLabel(
            hero, textvariable=self.status_var, text_color="#DCEEFF",
            font=ctk.CTkFont(size=13),
        ).pack(side=tk.RIGHT, padx=20)
        ctk.CTkFrame(main, fg_color=BLUE_ACCENT, corner_radius=0, height=4).pack(fill=tk.X)

        body = ctk.CTkFrame(main, fg_color="transparent")
        body.pack(fill=tk.BOTH, expand=True, padx=14, pady=(10, 10))

        self._build_login_section(body)
        self._build_conditions_section(body)
        self._build_control_section(body)
        self._build_log_section(body)

    def _section(self, parent, title):
        """Blue section title + white card; returns the card frame."""
        ctk.CTkLabel(
            parent, text=title, text_color=BLUE, anchor="w",
            font=ctk.CTkFont(size=14, weight="bold"),
        ).pack(anchor=tk.W, pady=(0, 2))
        card = ctk.CTkFrame(
            parent, fg_color=CARD_BG, border_color=CARD_BORDER,
            border_width=1, corner_radius=12,
        )
        card.pack(fill=tk.X, pady=(0, 10))
        return card

    def _hint_label(self, parent, text):
        return ctk.CTkLabel(
            parent, text=text, text_color=HINT,
            font=ctk.CTkFont(size=12), anchor="w",
        )

    def _form_row(self, parent, row, label, widget, hint):
        """One field row: label right-aligned, input left, muted hint."""
        ctk.CTkLabel(
            parent, text=label, width=110, anchor="e",
            text_color=TEXT_DARK,
        ).grid(row=row, column=0, sticky=tk.E, padx=(0, 8), pady=4)
        widget.grid(row=row, column=1, sticky=tk.W, pady=4)
        self._hint_label(parent, hint).grid(
            row=row, column=2, sticky=tk.W, padx=(12, 0), pady=4
        )

    def _entry(self, parent, var, width):
        return ctk.CTkEntry(
            parent, textvariable=var, width=width,
            fg_color="white", border_color=ENTRY_BORDER, text_color=TEXT_DARK,
        )

    def _build_login_section(self, main):
        login = self._section(main, "账号登录")
        inner = ctk.CTkFrame(login, fg_color="transparent")
        inner.pack(fill=tk.X, padx=12, pady=8)
        inner.columnconfigure(2, weight=1)

        self.phone_var = tk.StringVar()
        phone_entry = self._entry(inner, self.phone_var, 220)
        self._form_row(inner, 0, "手机号", phone_entry, "登录用的手机号，11 位数字")

        pw_frame = ctk.CTkFrame(inner, fg_color="transparent")
        self.password_var = tk.StringVar()
        self.password_entry = ctk.CTkEntry(
            pw_frame, textvariable=self.password_var, width=220, show="*",
            fg_color="white", border_color=ENTRY_BORDER, text_color=TEXT_DARK,
        )
        self.password_entry.pack(side=tk.LEFT)
        self.show_pw_var = tk.BooleanVar(value=False)
        ctk.CTkCheckBox(
            pw_frame, text="显示密码", variable=self.show_pw_var,
            command=self._toggle_show_password, text_color=TEXT_DARK,
            fg_color=BLUE, hover_color=BLUE_DARK,
        ).pack(side=tk.LEFT, padx=(10, 0))
        self._form_row(inner, 1, "密码", pw_frame, "登录密码；勾选“显示密码”后明文可见")

        btn_row = ctk.CTkFrame(inner, fg_color="transparent")
        btn_row.grid(row=2, column=1, sticky=tk.W, pady=(6, 0))
        _primary_button(btn_row, "登录", self._on_login).pack(side=tk.LEFT)
        _secondary_button(btn_row, "注销", self._on_logout).pack(side=tk.LEFT, padx=(8, 0))

        self.login_state_var = tk.StringVar(value="请输入手机号和密码后登录")
        ctk.CTkLabel(inner, textvariable=self.login_state_var, text_color=HINT).grid(
            row=3, column=0, columnspan=3, sticky=tk.W, pady=(8, 0)
        )

    def _build_conditions_section(self, main):
        settings = self._section(main, "接单条件")
        inner = ctk.CTkFrame(settings, fg_color="transparent")
        inner.pack(fill=tk.X, padx=12, pady=8)
        inner.columnconfigure(2, weight=1)

        self.min_amount_var = tk.StringVar()
        self._form_row(
            inner, 0, "最小金额（元）",
            self._entry(inner, self.min_amount_var, 120),
            "只抢不低于这个价的单",
        )
        self.max_amount_var = tk.StringVar()
        self._form_row(
            inner, 1, "最大金额（元）",
            self._entry(inner, self.max_amount_var, 120),
            "高于这个价不抢；填 0 表示不限制",
        )
        self.poll_interval_var = tk.StringVar()
        self._form_row(
            inner, 2, "轮询间隔（秒）",
            self._entry(inner, self.poll_interval_var, 120),
            "每隔多久刷一次订单列表",
        )
        self.memo_mode_var = tk.StringVar()
        self.memo_mode_box = ctk.CTkComboBox(
            inner, variable=self.memo_mode_var, width=150, state="readonly",
            values=["不检测备注", "有备注不抢"],
            fg_color="white", border_color=ENTRY_BORDER, text_color=TEXT_DARK,
            button_color=BLUE, button_hover_color=BLUE_DARK,
            dropdown_fg_color="white", dropdown_text_color=TEXT_DARK,
        )
        self._form_row(
            inner, 3, "备注检测", self.memo_mode_box,
            "“不检测备注”只按关键词过滤；“有备注不抢”则订单带任何备注都跳过。"
            "备注关键词命中时本身总会跳过",
        )

        # Two large text boxes, side by side.
        boxes = ctk.CTkFrame(inner, fg_color="transparent")
        boxes.grid(row=4, column=0, columnspan=3, sticky=tk.EW, pady=(8, 0))
        boxes.columnconfigure(0, weight=1)
        boxes.columnconfigure(1, weight=1)

        kw_frame = ctk.CTkFrame(boxes, fg_color="transparent")
        kw_frame.grid(row=0, column=0, sticky=tk.NSEW, padx=(0, 12))
        ctk.CTkLabel(kw_frame, text="备注关键词（一行一个）", text_color=TEXT_DARK).pack(anchor=tk.W)
        self._hint_label(kw_frame, "命中任意一个就不抢").pack(anchor=tk.W)
        self.keyword_text = _PlaceholderText(kw_frame, "例如：加急", width=340, height=56)
        self.keyword_text.pack(fill=tk.X, pady=(4, 0))

        bl_frame = ctk.CTkFrame(boxes, fg_color="transparent")
        bl_frame.grid(row=0, column=1, sticky=tk.NSEW)
        ctk.CTkLabel(bl_frame, text="黑名单微信号（一行一个）", text_color=TEXT_DARK).pack(anchor=tk.W)
        self._hint_label(bl_frame, "每行一个微信号，命中就不抢").pack(anchor=tk.W)
        self.blacklist_text = _PlaceholderText(bl_frame, "例如：wechat123", width=340, height=56)
        self.blacklist_text.pack(fill=tk.X, pady=(4, 0))

        btn_row = ctk.CTkFrame(inner, fg_color="transparent")
        btn_row.grid(row=5, column=0, columnspan=3, sticky=tk.W, pady=(10, 0))
        _primary_button(btn_row, "保存设置", self._on_save_settings).pack(side=tk.LEFT)
        _secondary_button(btn_row, "高级设置", self._open_advanced).pack(side=tk.LEFT, padx=(8, 0))

    def _build_control_section(self, main):
        control = self._section(main, "监听控制")
        inner = ctk.CTkFrame(control, fg_color="transparent")
        inner.pack(fill=tk.X, padx=12, pady=8)
        self.start_btn = _primary_button(inner, "开始监听", self._on_start_listen)
        self.start_btn.pack(side=tk.LEFT)
        self.stop_btn = _secondary_button(inner, "停止监听", self._on_stop_listen)
        self.stop_btn.configure(state="disabled")
        self.stop_btn.pack(side=tk.LEFT, padx=(8, 0))
        self.listen_state_var = tk.StringVar(value="未开始监听")
        ctk.CTkLabel(inner, textvariable=self.listen_state_var, text_color=HINT).pack(
            side=tk.LEFT, padx=(16, 0)
        )

    def _build_log_section(self, main):
        ctk.CTkLabel(
            main, text="运行日志", text_color=BLUE, anchor="w",
            font=ctk.CTkFont(size=14, weight="bold"),
        ).pack(anchor=tk.W, pady=(0, 2))
        log_card = ctk.CTkFrame(
            main, fg_color=CARD_BG, border_color=CARD_BORDER,
            border_width=1, corner_radius=12,
        )
        log_card.pack(fill=tk.BOTH, expand=True)
        inner = ctk.CTkFrame(log_card, fg_color="transparent")
        inner.pack(fill=tk.BOTH, expand=True, padx=12, pady=10)
        # The run log used to be a CTkTextbox. On Windows its text
        # vanished when the window was maximized (and never came back on
        # restore), while the file mirror proved the app kept generating
        # every line — so the loss was in CTk's redraw/scrollbar
        # machinery, not in the log data. A plain tk.Text has none of
        # that machinery, so it cannot lose its content that way.
        log_frame = tk.Frame(inner, bg="white")
        log_frame.pack(fill=tk.BOTH, expand=True)
        self.log_text = tk.Text(
            log_frame, state="disabled", height=10, wrap="word",
            bg="white", fg=TEXT_DARK, relief="flat",
            highlightthickness=0, borderwidth=0,
            font=ctk.CTkFont(size=13),
        )
        self.log_text.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        log_scroll = tk.Scrollbar(
            log_frame, orient="vertical", command=self.log_text.yview,
        )
        log_scroll.pack(side=tk.RIGHT, fill=tk.Y)
        self.log_text.configure(yscrollcommand=log_scroll.set)
        self.log_text.tag_config("info", foreground="")
        self.log_text.tag_config("success", foreground="#1a7f37")
        self.log_text.tag_config("warning", foreground="#9a6700")
        self.log_text.tag_config("error", foreground="#cf222e")
        self.log_text.tag_config("debug", foreground="#666666")
        _secondary_button(inner, "清空日志", self._clear_log).pack(anchor=tk.W, pady=(8, 0))

    def _load_settings_into_widgets(self):
        self.phone_var.set(self.cfg.get("phone", ""))
        self.password_var.set(self.cfg.get("password", ""))
        self.min_amount_var.set(str(self.cfg.get("min_amount", 10.0)))
        self.max_amount_var.set(str(self.cfg.get("max_amount", 0.0)))
        self.poll_interval_var.set(str(self.cfg.get("poll_interval_sec", 0.5)))
        self.memo_mode_box.set("有备注不抢" if int(self.cfg.get("memo_mode", 0)) == 1 else "不检测备注")
        self.keyword_text.set_lines(self.cfg.get("memo_keywords", []))
        self.blacklist_text.set_lines(self.cfg.get("blacklist", []))

    # -- logging ------------------------------------------------------------
    # Log lines go through a queue so worker threads never touch widgets.
    # The drain loop must never die: a single bad line (or a platform
    # quirk in the textbox) used to raise out of _drain_logs before the
    # after() reschedule, freezing the log forever with no symptom.
    # The reschedule now lives in a finally, and each line is guarded.

    def log(self, level: str, message: str):
        self.log_queue.put((level, message))

    def _drain_logs(self):
        try:
            while True:
                try:
                    level, message = self.log_queue.get_nowait()
                except queue.Empty:
                    break
                self._append_log_line(level, message)
        finally:
            self.after(200, self._drain_logs)

    def _append_log_line(self, level: str, message: str):
        stamp = time.strftime("%H:%M:%S")
        text = "[%s] %s\n" % (stamp, message)
        try:
            self.log_text.configure(state="normal")
            self.log_text.insert(tk.END, text, level)
            self.log_text.see(tk.END)
            self.log_text.configure(state="disabled")
        except Exception:
            # State toggling can fail on some platforms; fall back to a
            # direct insert so the line is never silently lost.
            try:
                self.log_text.insert(tk.END, text, level)
                self.log_text.see(tk.END)
            except Exception:
                pass
        self._mirror_log_line(text)
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

    def _clear_log(self):
        self.log_text.configure(state="normal")
        self.log_text.delete("1.0", tk.END)
        self.log_text.configure(state="disabled")

    # -- settings -----------------------------------------------------------

    def _read_settings_from_widgets(self) -> dict:
        try:
            min_amount = float(self.min_amount_var.get())
            max_amount = float(self.max_amount_var.get())
            poll_interval = float(self.poll_interval_var.get())
        except ValueError:
            raise cfgmod.ConfigError("金额和轮询间隔必须是数字")
        if min_amount < 0 or max_amount < 0:
            raise cfgmod.ConfigError("金额不能小于 0")
        if max_amount > 0 and max_amount < min_amount:
            raise cfgmod.ConfigError("最大金额不能小于最小金额；填 0 表示不限制")
        if poll_interval <= 0:
            raise cfgmod.ConfigError("轮询间隔必须大于 0")
        return {
            "phone": self.phone_var.get().strip(),
            "password": self.password_var.get(),
            "min_amount": min_amount,
            "max_amount": max_amount,
            "poll_interval_sec": poll_interval,
            "memo_mode": 1 if self.memo_mode_var.get() == "有备注不抢" else 0,
            "memo_keywords": self.keyword_text.get_lines(),
            "blacklist": self.blacklist_text.get_lines(),
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
            self._listening = True
            self.listen_state_var.set("正在监听")
            self._set_listen_buttons(True)

    def _set_listen_buttons(self, listening: bool):
        # CTk only greys a disabled button's TEXT; the blue fill stays,
        # so a disabled primary button still looks clickable. Swap the
        # fill too, so the state is visible at a glance.
        if listening:
            self.start_btn.configure(
                state="disabled", fg_color=BLUE_DISABLED,
                hover_color=BLUE_DISABLED,
            )
            self.stop_btn.configure(state="normal")
        else:
            self.start_btn.configure(
                state="normal", fg_color=BLUE, hover_color=BLUE_DARK,
            )
            self.stop_btn.configure(state="disabled")

    def _on_stop_listen(self):
        self._listening = False
        if self.engine is not None:
            self.engine.stop()
        self.listen_state_var.set("已停止监听")
        self._set_listen_buttons(False)

    def _refresh_buttons(self):
        # Never read a CTkButton's state with ["state"]/cget — the
        # underlying canvas widget has no -state option and it raises
        # TclError, which used to kill this loop the first time the
        # engine existed but was not running. Track listening in a
        # plain flag instead, and always reschedule.
        try:
            if self._listening and self.engine is not None and not self.engine.running:
                # Engine stopped on its own (e.g. grab succeeded).
                self._listening = False
                self.listen_state_var.set("监听已停止")
                self._set_listen_buttons(False)
        finally:
            self.after(500, self._refresh_buttons)

    def _on_close(self):
        if self.engine is not None:
            self.engine.stop()
        self.voice.stop()
        self.destroy()


class AdvancedDialog(ctk.CTkToplevel):
    """Advanced settings: AES pairs, shop id, timeout, TLS, voice."""

    def __init__(self, parent: App, cfg: dict, on_saved):
        super().__init__(parent, fg_color=WINDOW_BG)
        self.title("高级设置")
        self.cfg = dict(cfg)
        self._on_saved = on_saved
        self.resizable(False, False)

        frame = ctk.CTkFrame(self, fg_color="transparent")
        frame.pack(fill=tk.BOTH, expand=True, padx=16, pady=14)
        ctk.CTkLabel(
            frame, text="高级设置", text_color=BLUE, anchor="w",
            font=ctk.CTkFont(size=15, weight="bold"),
        ).pack(anchor=tk.W, pady=(0, 4))
        ctk.CTkLabel(
            frame,
            text="加解密密钥可在配置文件中修改；密钥只用于解密服务器响应，"
                 "主密钥解密失败时自动尝试备用密钥。",
            text_color=HINT, wraplength=520, anchor="w", justify="left",
        ).pack(anchor=tk.W, pady=(0, 10))
        rows_frame = ctk.CTkFrame(frame, fg_color="transparent")
        rows_frame.pack(fill=tk.X)
        rows_frame.columnconfigure(2, weight=1)

        self.entries: dict[str, tk.StringVar] = {}
        rows = [
            ("aes_key_hex", "主密钥（十六进制）", "解密服务器响应的主密钥"),
            ("aes_iv_hex", "主 IV（十六进制）", "配合主密钥使用"),
            ("aes_key_hex_alt", "备用密钥（十六进制）", "主密钥解密失败时自动尝试"),
            ("aes_iv_hex_alt", "备用 IV（十六进制）", "配合备用密钥使用"),
            ("shop_id", "优先店铺 ID", "登录后优先切换到的店铺"),
            ("request_timeout_sec", "请求超时（秒）", "单次请求最长等待时间"),
        ]
        for i, (key, label, hint) in enumerate(rows):
            ctk.CTkLabel(
                rows_frame, text=label, width=150, anchor="e", text_color=TEXT_DARK,
            ).grid(row=i, column=0, sticky=tk.E, padx=(0, 8), pady=4)
            var = tk.StringVar(value=str(self.cfg.get(key, "")))
            self.entries[key] = var
            width = 500 if "aes" in key else 120
            ctk.CTkEntry(
                rows_frame, textvariable=var, width=width,
                fg_color="white", border_color=ENTRY_BORDER, text_color=TEXT_DARK,
            ).grid(row=i, column=1, sticky=tk.W, pady=4)
            ctk.CTkLabel(
                rows_frame, text=hint, text_color=HINT,
                font=ctk.CTkFont(size=12), anchor="w",
            ).grid(row=i, column=2, sticky=tk.W, padx=(12, 0), pady=4)

        self.verify_var = tk.BooleanVar(value=bool(self.cfg.get("verify_tls", True)))
        ctk.CTkCheckBox(
            frame, text="验证服务器 TLS 证书（推荐开启）", variable=self.verify_var,
            text_color=TEXT_DARK, fg_color=BLUE, hover_color=BLUE_DARK,
        ).pack(anchor=tk.W, pady=(8, 0))
        self.voice_var = tk.BooleanVar(value=bool(self.cfg.get("voice_enabled", True)))
        ctk.CTkCheckBox(
            frame, text="语音播报（需要本机语音支持）", variable=self.voice_var,
            text_color=TEXT_DARK, fg_color=BLUE, hover_color=BLUE_DARK,
        ).pack(anchor=tk.W)
        ctk.CTkLabel(
            frame, text="配置文件：%s" % cfgmod.config_path(),
            text_color=HINT, wraplength=700, anchor="w", justify="left",
        ).pack(anchor=tk.W, pady=(8, 0))

        btns = ctk.CTkFrame(frame, fg_color="transparent")
        btns.pack(fill=tk.X, pady=(12, 0))
        _primary_button(btns, "保存", self._save).pack(side=tk.RIGHT, padx=(6, 0))
        _secondary_button(btns, "取消", self.destroy).pack(side=tk.RIGHT)

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
