"""Main dashboard responsive-layout regressions, using offline synthetic fixtures.

Do not run against an in-progress ui.py edit. Once the layout owner says ready:
    python -X utf8 -B -m unittest discover -s tests -p test_main_layout.py -v

Contract under test:
* _hero / _footer stay outside the scrolling / compact pages; the footer owns
  the original start_btn, stop_btn and listen_state_sidebar_label.
* _sidebar_host is an independent account QScrollArea; _form_wrap contains a
  fixed heading and _form_scroll, whose widget is _form_content.
* columns and compact reparent the same widgets. Compact has account / form /
  log / history pages; log/history pages move between compact and bottom_tabs
  without being copied or nested into a second tab bar.
* _fit_window_to_screen(screen=None, initial=False) uses logical available
  geometry, and layout changes do no business IO.

Geometry assertions include ancestor clipping and hit testing: visibility or a
QTest click sent directly to a hidden button would be insufficient evidence.
Single-line inputs must accommodate sizeHint(); the deliberately capped keyword
multiline editor is checked against minimumSizeHint() and its viewport instead.
FakeScreen checks logical-coordinate fitting, not native physical-DPI rendering.
"""
from __future__ import annotations

import ast
import copy
from contextlib import ExitStack
import inspect
import os
from pathlib import Path
import sys
import tempfile
import textwrap
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
# Match the advanced-dialog harness: native Windows metrics, offscreen only
# on headless non-Windows hosts. All App windows below remain synthetic.
if sys.platform != "win32" and not os.environ.get("DISPLAY"):
    os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtCore import QEvent, QPoint, QRect, QSize, Qt, QTimer
from PySide6.QtTest import QTest
from PySide6.QtWidgets import (
    QAbstractScrollArea, QApplication, QLabel, QPushButton, QScrollArea,
    QTabWidget, QWidget,
)
import config
from themes import THEMES

# Importing ui generates stylesheet arrow PNGs. Keep that side effect in a
# disposable directory too; never let the fallback select the user's profile.
_TEST_CACHE = Path(__file__).resolve().parents[3] / "main-layout-fixtures"
_TEST_CACHE.mkdir(parents=True, exist_ok=True)
_IMPORT_ASSETS = tempfile.TemporaryDirectory(prefix="dpg-layout-import-", dir=_TEST_CACHE)
with patch("tempfile.gettempdir", return_value=_IMPORT_ASSETS.name):
    import ui

_QAPP = None  # A strong reference to the one process-wide QApplication.


class FakeScreen:
    """QScreen-shaped logical geometry, including an intentionally large DPR."""

    def __init__(self, area: QRect, dpr: float = 1.0):
        self._area = QRect(area)
        self._dpr = float(dpr)

    def availableGeometry(self):
        return QRect(self._area)

    def geometry(self):
        return QRect(self._area)

    def devicePixelRatio(self):
        return self._dpr

    def logicalDotsPerInch(self):
        return 96.0 * self._dpr

    logicalDotsPerInchX = logicalDotsPerInch
    logicalDotsPerInchY = logicalDotsPerInch

    def physicalDotsPerInch(self):
        return 96.0 * self._dpr

    physicalDotsPerInchX = physicalDotsPerInch
    physicalDotsPerInchY = physicalDotsPerInch

    def name(self):
        return "synthetic-layout-screen"


class FakeEngine:
    """No worker, session, transport, login, or real polling is ever created."""

    def __init__(self, start_result: bool = True):
        self.running = False
        self.start_result = bool(start_result)
        self.start_calls = []
        self.stop_calls = 0

    def start(self, phone, password, session=None):
        self.start_calls.append((phone, password, session))
        self.running = self.start_result
        return self.start_result

    def stop(self):
        self.stop_calls += 1
        self.running = False


class MainLayoutTests(unittest.TestCase):
    COMPACT_SIZE = (640, 480)
    COLUMN_SIZE = (1180, 729)
    SMALL_SIZES = ((640, 480), (640, 360), (640, 320), (1180, 360))
    SINGLE_LINE_NAMES = (
        "min_amount_edit", "max_amount_edit", "poll_interval_edit",
        "memo_mode_box", "confirm_wait_min_spin", "confirm_wait_max_spin",
        "risk_run_min_spin", "risk_run_max_spin", "risk_rest_min_spin",
        "risk_rest_max_spin",
    )
    IDENTITY_NAMES = (
        "_hero", "_footer", "_sidebar_host", "_sidebar", "_form_wrap",
        "_form_scroll", "_form_content", "_compact_tabs", "bottom_tabs",
        "start_btn", "stop_btn", "listen_state_sidebar_label", "phone_edit",
        "password_edit", "login_btn", "theme_box", "keyword_text",
        "run_rest_check", "auto_stop_check", "online_check", "all_accounts_check",
        "log_text", "history_list",
    ) + SINGLE_LINE_NAMES

    @classmethod
    def setUpClass(cls):
        global _QAPP
        _QAPP = QApplication.instance() or QApplication([])
        _QAPP.setQuitOnLastWindowClosed(False)
        cls.qapp = _QAPP

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="dpg-main-layout-", dir=_TEST_CACHE)
        self.addCleanup(self.tmp.cleanup)
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.windows = []
        self.addCleanup(self._close_windows)
        self.layout_io = []
        self.transport_guards = []

        self.original_stylesheet = self.qapp.styleSheet()
        self.original_palette = self.qapp.palette()
        self._patch(config, "config_dir", return_value=self.tmp.name)
        self._patch(config, "config_path",
                    return_value=os.path.join(self.tmp.name, "synthetic-config.json"))
        self._patch(ui, "_asset_dir", return_value=Path(self.tmp.name))
        self.cfg_load = self._io_patch(config, "load", side_effect=AssertionError(
            "A layout fixture must not load an account/config file"))
        self.cfg_save = self._io_patch(config, "save", return_value=None)
        self.account_migrate = self._io_patch(ui.accounts_mod, "migrate_legacy",
                                              return_value=None)
        self.account_list = self._io_patch(ui.accounts_mod, "list_accounts",
                                           return_value=[])
        self.account_load = self._io_patch(ui.accounts_mod, "load_account",
                                          side_effect=AssertionError(
            "A layout fixture must not activate an account"))
        self.account_save = self._io_patch(ui.accounts_mod, "save_account",
                                           return_value=None)
        self.history_load = self._io_patch(ui.history_mod, "load_history",
                                          return_value=[])
        for name in ("save_history", "append_history", "clear_history"):
            self._io_patch(ui.history_mod, name, return_value=None)
        self.mirror_log = self._io_patch(ui.App, "_mirror_log_line", return_value=None)

        self.client = Mock(spec=ui.ApiClient, session=None)
        self.layout_io.append(self.client)
        self.client_factory = self._io_patch(ui, "ApiClient", return_value=self.client)
        self.voice = Mock(enabled=False)
        self.voice_factory = self._io_patch(ui, "Voice", return_value=self.voice)
        self.layout_io.append(self.voice)
        self.notifier = Mock(enabled=False)
        self.notifier_factory = self.stack.enter_context(
            patch("notify.Notifier", return_value=self.notifier))
        self.layout_io.extend((self.notifier_factory, self.notifier))
        self.engine_guard = self._io_patch(ui, "Engine", side_effect=AssertionError(
            "Use FakeEngine; a real Engine must not be constructed"))
        self.thread_guard = self._io_patch(ui.threading, "Thread",
                                         side_effect=AssertionError(
            "A layout fixture must not start a login/network worker"))
        self.transport_guards.extend((self.client, self.engine_guard, self.thread_guard,
                                      self.notifier))
        for target in ("requests.sessions.Session.request", "urllib.request.urlopen",
                       "socket.create_connection"):
            guard = self.stack.enter_context(patch(target, side_effect=AssertionError(
                "Network access in an offline layout fixture: " + target)))
            self.transport_guards.append(guard)
            self.layout_io.append(guard)

        self.effects = self._patch(ui, "_apply_windows_effects", return_value=False)
        self.mica = self._patch(ui, "_apply_mica", return_value=False)
        self.sleep = self._patch(ui, "_prevent_sleep", return_value=None)
        self.warning = self._patch(ui.MessageDialog, "show_warning", return_value=None)
        self.error = self._patch(ui.MessageDialog, "show_error", return_value=None)

    def _patch(self, owner, name, **kwargs):
        return self.stack.enter_context(patch.object(owner, name, **kwargs))

    def _io_patch(self, owner, name, **kwargs):
        result = self._patch(owner, name, **kwargs)
        self.layout_io.append(result)
        return result

    def _close_windows(self):
        # closeEvent is real, but Engine/Voice/sleep are test doubles and remain
        # patched until this cleanup completes. Stop all owned timers first.
        for window in self.windows:
            for timer in window.findChildren(QTimer):
                timer.stop()
            window.close()
            window.deleteLater()
        self.qapp.processEvents()
        self.qapp.sendPostedEvents(None, QEvent.DeferredDelete)
        self.qapp.setStyleSheet(self.original_stylesheet)
        self.qapp.setPalette(self.original_palette)

    @staticmethod
    def synthetic_config(theme="blue"):
        cfg = copy.deepcopy(config.DEFAULT_CONFIG)
        cfg.update({
            "phone": "", "password": "", "shop_id": 0,
            "aes_key_hex": "00" * 32, "aes_iv_hex": "11" * 16,
            "aes_key_hex_alt": "22" * 16, "aes_iv_hex_alt": "33" * 16,
            "voice_enabled": False, "notify_enabled": False,
            "notify_key": "", "notify_webhook": "https://example.test/unused",
            "notify_bark_server": "https://example.test/unused",
            "rotate_enabled": False, "theme": theme, "memo_mode": 2,
            "memo_keywords": [], "memo_exclude_keywords": ["合成排除词"],
            "memo_include_keywords": ["合成包含词"],
            "item_switch_names": {}, "item_switch_ids": [],
        })
        return cfg

    def window(self, cfg=None, *, tall=False):
        cfg = self.synthetic_config() if cfg is None else copy.deepcopy(cfg)
        ui.apply_theme(self.qapp, THEMES[cfg["theme"]]["palette"], cfg["theme"])
        window = ui.App(cfg)
        self.windows.append(window)
        # Observe the real save routing, whose only storage sinks are mocked.
        self._io_patch(window, "_save_cfg", wraps=window._save_cfg)
        if tall:
            self._populate_long_content(window)
        # Native metrics do not require taking the operator's desktop focus.
        # QTest sends explicitly to each widget; keep real mouse/keyboard/IME
        # input out of these temporary credential fields while the suite runs.
        window.setAttribute(Qt.WA_ShowWithoutActivating, True)
        window.setWindowFlag(Qt.WindowDoesNotAcceptFocus, True)
        window.setWindowFlag(Qt.WindowTransparentForInput, True)
        window.show()
        self._settle()
        for timer in window.findChildren(QTimer):
            timer.stop()
        self.assertIs(QApplication.instance(), self.qapp)
        self.assertIs(window.client, self.client)
        self.assertIsNone(window.client.session)
        return window

    def _populate_long_content(self, window):
        items = [{"id": 9000 + i, "name": "合成接单类型长名称 %02d" % i}
                 for i in range(28)]
        window._rebuild_item_switches(items, [9000, 9002])
        window.login_state_label.setText("合成离线状态说明\n" * 12)
        window.last_order_note.setText("合成记录说明\n" * 8)
        window.log_text.setPlainText("\n".join("synthetic log %03d" % i
                                               for i in range(160)))
        for i in range(35):
            window._add_history_row({
                "grabbed_at": "2000-01-01T00:00:00", "no": "fixture-%03d" % i,
                "id": "fixture-id-%03d" % i, "amount": 12.5,
                "userWechatId": "fixture-user", "userMemo": "合成备注",
                "outcome": "failed", "reason": "合成失败说明", "account": "",
            })
        self._settle()

    def _settle(self):
        self.qapp.processEvents()
        QTest.qWait(15)
        self.qapp.processEvents()

    def _required(self, window, name):
        self.assertTrue(hasattr(window, name),
                        "Responsive layout contract missing App.%s" % name)
        return getattr(window, name)

    def _resize(self, window, size):
        # The real screen may expose only 1024 logical px at DPI factor 1.5.
        # Exercise the production fit path on an explicitly larger synthetic
        # logical screen before matrix resizing; never just clear maximumSize.
        # The initial-show test deliberately does not call this helper.
        area = QRect(0, 0, max(1600, size[0] + 100), max(1200, size[1] + 100))
        window._fit_window_to_screen(screen=FakeScreen(area), initial=False)
        window.resize(*size)
        self._settle()
        # A minimum-size regression must fail instead of silently testing a
        # larger window than the requested 640x480 / short-height case.
        self.assertEqual(window.size(), QSize(*size),
                         ("requested", size, "actual", window.size(),
                          "minimum", window.minimumSize()))
        expected = "compact" if size[0] == 640 else "columns"
        self.assertEqual(self._required(window, "_layout_mode"), expected)

    @staticmethod
    def _rect_in(widget, ancestor):
        return QRect(widget.mapTo(ancestor, QPoint()), widget.size())

    def _assert_inside(self, widget, ancestor):
        rect = self._rect_in(widget, ancestor)
        self.assertTrue(ancestor.rect().contains(rect),
                        (widget.objectName() or type(widget).__name__, rect,
                         ancestor.objectName() or type(ancestor).__name__,
                         ancestor.rect()))
        return rect

    def _assert_reachable(self, widget, window):
        self.assertTrue(widget.isVisibleTo(window), widget.objectName())
        self.assertGreater(widget.width(), 0)
        self.assertGreater(widget.height(), 0)
        # Walk every ancestor: isVisibleTo() alone ignores viewport clipping.
        parent = widget.parentWidget()
        while parent is not None:
            self._assert_inside(widget, parent)
            if parent is window:
                break
            parent = parent.parentWidget()
        self.assertIs(parent, window)
        point = widget.mapTo(window, widget.rect().center())
        hit = window.childAt(point)
        self.assertTrue(hit is widget or (hit is not None and widget.isAncestorOf(hit)),
                        ("occluded", widget.objectName(), point, hit))
        self.assertTrue(widget.visibleRegion().contains(widget.rect().center()))

    def _assert_chrome(self, window):
        hero = self._required(window, "_hero")
        footer = self._required(window, "_footer")
        hero_rect = self._assert_inside(hero, window)
        footer_rect = self._assert_inside(footer, window)
        self.assertTrue(hero.isVisibleTo(window))
        self.assertTrue(footer.isVisibleTo(window))
        if window._layout_mode == "columns":
            # Wide statistics must not collapse into zero-width Ignored cells.
            # Compact intentionally compresses these labels, so leave it free
            # to do that while the footer retains the complete listen state.
            stats = self._required(window, "_hero_stats")
            self.assertTrue(stats.isVisibleTo(window))
            self.assertGreater(stats.width(), 0)
            self._assert_inside(stats, hero)
            for name in ("grab_count_label", "listen_state_label", "status_label"):
                label = getattr(window, name)
                self.assertTrue(label.isVisibleTo(window), name)
                self.assertGreaterEqual(label.width(), label.sizeHint().width(),
                                        (name, label.text(), label.geometry(), label.sizeHint()))
                self.assertGreaterEqual(label.height(), label.sizeHint().height(), name)
                self._assert_inside(label, stats)
            if window.renewal_chip.isVisibleTo(window):
                self.assertGreaterEqual(window.renewal_chip.width(),
                                        window.renewal_chip.sizeHint().width())
                self._assert_inside(window.renewal_chip, stats)
        self.assertEqual(hero_rect.top(), window.rect().top())
        self.assertEqual(footer_rect.bottom(), window.rect().bottom())
        self.assertFalse(hero_rect.intersects(footer_rect))
        for panel in (hero, footer):
            parent = panel.parentWidget()
            while parent is not None:
                self.assertNotIsInstance(parent, QAbstractScrollArea)
                parent = parent.parentWidget()
        compact = self._required(window, "_compact_tabs")
        self.assertFalse(compact.isAncestorOf(footer))
        footer_rects = []
        for control in (window.start_btn, window.stop_btn,
                        window.listen_state_sidebar_label):
            self.assertTrue(footer.isAncestorOf(control))
            footer_rects.append(self._assert_inside(control, footer))
            self._assert_reachable(control, window)
        for index, rect in enumerate(footer_rects):
            for other in footer_rects[index + 1:]:
                self.assertFalse(rect.intersects(other), ("footer overlap", rect, other))
        theme = window.theme_box
        if theme.isVisibleTo(window):
            self.assertGreaterEqual(theme.height(), theme.sizeHint().height())
            self.assertGreaterEqual(theme.width(), theme.minimumSizeHint().width())
            self._assert_inside(theme, hero)
            self._assert_reachable(theme, window)
        for button in hero.findChildren(QPushButton):
            if button.isVisibleTo(window):
                self._assert_reachable(button, window)

    def _select_compact(self, window, index):
        tabs = self._required(window, "_compact_tabs")
        self.assertEqual(window._layout_mode, "compact")
        self.assertEqual(tabs.count(), 4)
        bar = tabs.tabBar()
        self.assertTrue(bar.isVisibleTo(window))
        rect = bar.tabRect(index)
        self.assertFalse(rect.isEmpty())
        self.assertTrue(bar.rect().contains(rect.center()))
        QTest.mouseClick(bar, Qt.LeftButton, pos=rect.center())
        self._settle()
        self.assertEqual(tabs.currentIndex(), index)

    def _show_form(self, window):
        if window._layout_mode == "compact":
            self._select_compact(window, 1)
        self.assertTrue(window._form_wrap.isVisibleTo(window))

    def _assert_form_inputs_fit(self, window):
        self._show_form(window)
        content = self._required(window, "_form_content")
        controls = [self._required(window, name) for name in self.SINGLE_LINE_NAMES]
        for name, control in zip(self.SINGLE_LINE_NAMES, controls):
            self.assertGreaterEqual(control.height(), control.sizeHint().height(),
                                    (name, control.height(), control.sizeHint()))
            self.assertGreaterEqual(control.width(), control.minimumSizeHint().width(),
                                    (name, control.width(), control.minimumSizeHint()))
        controls.append(window.run_rest_check)
        if window.keyword_text.isVisibleTo(content):
            editor = window.keyword_text
            self.assertGreaterEqual(editor.height(), editor.minimumSizeHint().height())
            self.assertGreater(editor.viewport().height(), editor.fontMetrics().height())
            controls.append(editor)
        rectangles = []
        for control in controls:
            self.assertTrue(control.isVisibleTo(content))
            rectangles.append((control, self._assert_inside(control, content)))
        for index, (control, rect) in enumerate(rectangles):
            for other, other_rect in rectangles[index + 1:]:
                self.assertFalse(rect.intersects(other_rect),
                                 (control, rect, other, other_rect))
            for label in content.findChildren(QLabel):
                if label.isVisibleTo(content) and not control.isAncestorOf(label):
                    label_rect = self._rect_in(label, content)
                    self.assertFalse(rect.intersects(label_rect),
                                     (control, rect, label.text(), label_rect))
        self.assertEqual(window._form_scroll.horizontalScrollBar().maximum(), 0)

    def _assert_account_inputs_fit(self, window):
        if window._layout_mode == "compact":
            self._select_compact(window, 0)
        content = window._sidebar_host.widget()
        for name in ("phone_edit", "password_edit"):
            control = getattr(window, name)
            self.assertGreaterEqual(control.height(), control.sizeHint().height(),
                                    (name, control.height(), control.sizeHint()))
            self._assert_inside(control, content)
        self.assertFalse(self._rect_in(window.phone_edit, content).intersects(
            self._rect_in(window.password_edit, content)))
        self.assertEqual(window._sidebar_host.horizontalScrollBar().maximum(), 0)

    def _scroll_all_to_end(self, window):
        for scroll in window.findChildren(QAbstractScrollArea):
            scroll.verticalScrollBar().setValue(scroll.verticalScrollBar().maximum())
        self._settle()

    def _record_pages(self, window):
        # Compact tabs use stable carrier pages. The actual log/history panels
        # move into those carriers, so compare panel identity, not carrier id.
        log_page = self._required(window, "_log_page")
        history_page = self._required(window, "_history_page")
        if window._layout_mode == "compact":
            self.assertEqual(window._compact_tabs.count(), 4)
            carriers = (window._compact_tabs.widget(2), window._compact_tabs.widget(3))
            for carrier, panel in zip(carriers, (log_page, history_page)):
                self.assertTrue(carrier is panel or carrier.isAncestorOf(panel))
        else:
            self.assertEqual(window.bottom_tabs.count(), 2)
            self.assertIs(window.bottom_tabs.widget(0), log_page)
            self.assertIs(window.bottom_tabs.widget(1), history_page)
        return log_page, history_page

    def _identity(self, window):
        result = {name: self._required(window, name) for name in self.IDENTITY_NAMES}
        log_page, history_page = self._record_pages(window)
        result.update({"page:log": log_page, "page:history": history_page})
        result.update({"item:%s" % key: value for key, value in window.item_checks.items()})
        return result

    def _assert_identity(self, window, original):
        current = self._identity(window)
        self.assertEqual(set(current), set(original))
        for name, widget in original.items():
            self.assertIs(current[name], widget, "Recreated responsive control: " + name)

    def _state(self, window):
        return {
            "phone": window.phone_edit.text(), "password": window.password_edit.text(),
            "min": window.min_amount_edit.text(), "max": window.max_amount_edit.text(),
            "poll": window.poll_interval_edit.text(), "memo": window.memo_mode_box.currentIndex(),
            "keywords": window.keyword_text.toPlainText(),
            "confirm_min": window.confirm_wait_min_spin.value(),
            "confirm_max": window.confirm_wait_max_spin.value(),
            "run_min": window.risk_run_min_spin.value(),
            "run_max": window.risk_run_max_spin.value(),
            "rest_min": window.risk_rest_min_spin.value(),
            "rest_max": window.risk_rest_max_spin.value(),
            "run_rest": window.run_rest_check.isChecked(),
            "auto_stop": window.auto_stop_check.isChecked(),
            "online": window.online_check.isChecked(),
            "all_accounts": window.all_accounts_check.isChecked(),
            "items": {key: box.isChecked() for key, box in window.item_checks.items()},
            "theme": window.theme_box.currentData(),
        }

    def _reset_io(self):
        for stub in self.layout_io:
            stub.reset_mock()

    def _assert_no_layout_io(self):
        for stub in self.layout_io:
            self.assertEqual(stub.mock_calls, [], ("layout performed business IO", stub.mock_calls))

    def _assert_no_transport(self):
        for stub in self.transport_guards:
            self.assertEqual(stub.mock_calls, [], ("offline transport/worker invoked", stub.mock_calls))
        self.assertIsNone(self.client.session)

    @staticmethod
    def _type_ascii(edit, text):
        edit.setFocus()
        edit.selectAll()
        QTest.keyClicks(edit, text)

    def _fill_synthetic_credentials(self, window):
        self._type_ascii(window.phone_edit, "19900000000")
        self._type_ascii(window.password_edit, "fixture-password")
        self.assertEqual(window.phone_edit.text(), "19900000000")
        self.assertEqual(window.password_edit.text(), "fixture-password")

    def test_constructor_is_isolated_and_exposes_independent_scroll_contract(self):
        window = self.window()
        self._identity(window)  # Fail explicitly on missing new-layout attributes.
        self.assertIsInstance(window._sidebar_host, QScrollArea)
        self.assertIsInstance(window._form_scroll, QScrollArea)
        self.assertIsInstance(window._compact_tabs, QTabWidget)
        self.assertIsInstance(window.bottom_tabs, QTabWidget)
        self.assertIs(window._sidebar_host.widget(), window._sidebar)
        self.assertIs(window._form_scroll.widget(), window._form_content)
        self.assertTrue(window._form_wrap.isAncestorOf(window._form_scroll))
        self.assertFalse(window._sidebar_host.isAncestorOf(window._form_scroll))
        self.assertFalse(window._form_scroll.isAncestorOf(window._sidebar_host))
        self.assertIs(window.voice, self.voice)
        self.assertIs(window.notifier, self.notifier)
        self.assertIsNone(window.engine)
        self.assertIsNone(window._login_thread)
        self.assertEqual(window.phone_edit.text(), "")
        self.assertEqual(window.password_edit.text(), "")
        self.client_factory.assert_called_once()
        self.voice_factory.assert_called_once()
        self.notifier_factory.assert_called_once()
        self.account_migrate.assert_called_once()
        self.cfg_load.assert_not_called()
        self.account_load.assert_not_called()
        self.cfg_save.assert_not_called()
        self.account_save.assert_not_called()
        self._assert_no_transport()

    def test_initial_show_fits_the_current_logical_screen_and_footer(self):
        window = self.window()
        screen = window.screen() or QApplication.primaryScreen()
        self.assertIsNotNone(screen)
        area = screen.availableGeometry()
        self.assertTrue(area.contains(window.frameGeometry()),
                        ("initial logical screen", area, "frame", window.frameGeometry()))
        self._assert_chrome(window)
        self._assert_no_transport()

    def test_responsive_and_screen_fit_definitions_are_not_shadowed(self):
        tree = ast.parse(textwrap.dedent(inspect.getsource(ui.App)))
        definitions = [node.name for node in tree.body[0].body
                       if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))]
        for name in ("_apply_responsive", "_fit_window_to_screen", "resizeEvent", "showEvent"):
            with self.subTest(method=name):
                self.assertEqual(definitions.count(name), 1)

    def test_640_by_480_fits_window_and_persistent_footer(self):
        window = self.window(tall=True)
        self._resize(window, self.COMPACT_SIZE)
        for page in range(4):
            with self.subTest(page=page):
                self._select_compact(window, page)
                self._assert_chrome(window)
                self._assert_inside(window._compact_tabs, window)
        self._assert_account_inputs_fit(window)
        self._assert_form_inputs_fit(window)

    def test_short_windows_do_not_expand_past_requested_bounds(self):
        window = self.window(tall=True)
        for size in self.SMALL_SIZES:
            with self.subTest(size=size):
                self._resize(window, size)
                self._assert_chrome(window)
                self._assert_account_inputs_fit(window)
                self._assert_form_inputs_fit(window)
                self.assertGreater(window._form_scroll.viewport().height(), 0)
                self._scroll_all_to_end(window)
                self._assert_chrome(window)

    def test_columns_show_three_nonoverlapping_panels(self):
        window = self.window()
        self._resize(window, self.COLUMN_SIZE)
        self.assertFalse(window._compact_tabs.isVisibleTo(window))
        panels = (window._sidebar_host, window._form_wrap, window.bottom_tabs)
        rectangles = []
        for panel in panels:
            self.assertTrue(panel.isVisibleTo(window))
            self.assertFalse(window._compact_tabs.isAncestorOf(panel))
            rectangles.append(self._assert_inside(panel, window))
        # Keep whole-window geometry evidence in a failure: panel-local input
        # checks can all pass while a zero-width grid cell stacks the panels.
        evidence = [(panel.objectName(), panel.geometry(), rect,
                     panel.parentWidget() is window._wide_page)
                    for panel, rect in zip(panels, rectangles)]
        cells = [window._wide_grid.cellRect(0, column) for column in range(3)]
        for left, right in zip(rectangles, rectangles[1:]):
            self.assertLess(left.right(), right.left(),
                            ("overlapping wide columns", evidence, "grid cells", cells))
            self.assertFalse(left.intersects(right), evidence)
        self._assert_chrome(window)

    def test_four_compact_pages_move_original_log_history_without_nested_tabs(self):
        window = self.window()
        self._resize(window, self.COLUMN_SIZE)
        self.assertEqual(window.bottom_tabs.count(), 2)
        self.assertEqual([window.bottom_tabs.tabText(i) for i in range(2)],
                         ["运行日志", "接单历史"])
        log_page, history_page = self._record_pages(window)
        self._resize(window, self.COMPACT_SIZE)
        tabs = window._compact_tabs
        self.assertEqual([tabs.tabText(i) for i in range(tabs.count())],
                         ["账号", "接单条件", "运行日志", "接单历史"])
        self.assertFalse(window.bottom_tabs.isVisibleTo(window))
        self.assertTrue(tabs.widget(2).isAncestorOf(log_page))
        self.assertTrue(tabs.widget(3).isAncestorOf(history_page))
        for index, panel in enumerate((window._sidebar_host, window._form_wrap,
                                       window.log_text, window.history_list)):
            self._select_compact(window, index)
            page = tabs.widget(index)
            self.assertTrue(page is panel or page.isAncestorOf(panel))
            self.assertTrue(panel.isVisibleTo(window))
            self._assert_inside(panel, page)
            self._assert_chrome(window)
        self._resize(window, self.COLUMN_SIZE)
        self.assertIs(window.bottom_tabs.widget(0), log_page)
        self.assertIs(window.bottom_tabs.widget(1), history_page)

    def test_same_widget_ids_survive_repeated_wide_narrow_reparenting(self):
        window = self.window(tall=True)
        original = self._identity(window)
        self._reset_io()
        for size in (self.COMPACT_SIZE, self.COLUMN_SIZE, (640, 320), (1180, 360)) * 3:
            with self.subTest(size=size):
                self._resize(window, size)
                self._assert_identity(window, original)
                self._assert_chrome(window)
        starts = [button for button in window.findChildren(QPushButton)
                  if button.text() == window.start_btn.text()]
        stops = [button for button in window.findChildren(QPushButton)
                 if button.text() == window.stop_btn.text()]
        self.assertEqual(starts, [window.start_btn])
        self.assertEqual(stops, [window.stop_btn])
        self._assert_no_layout_io()

    def test_log_and_history_contents_survive_record_page_reparenting(self):
        window = self.window(tall=True)
        self._resize(window, self.COLUMN_SIZE)
        document = window.log_text.document()
        text = window.log_text.toPlainText()
        entries = [(window.history_list.item(i).text(),
                    window.history_list.item(i).data(Qt.UserRole))
                   for i in range(window.history_list.count())]
        self.assertIn("synthetic log 159", text)
        self.assertGreater(len(entries), 30)
        original = self._identity(window)
        self._reset_io()
        for size in (self.COMPACT_SIZE, self.COLUMN_SIZE, (640, 320), (1180, 360)) * 2:
            with self.subTest(size=size):
                self._resize(window, size)
                self._scroll_all_to_end(window)
                self.assertIs(window.log_text.document(), document)
                self.assertEqual(window.log_text.toPlainText(), text)
                self.assertEqual([(window.history_list.item(i).text(),
                                   window.history_list.item(i).data(Qt.UserRole))
                                  for i in range(window.history_list.count())], entries)
                self._assert_identity(window, original)
                self._assert_no_layout_io()

    def test_start_stop_callbacks_reachable_on_every_compact_page_at_scroll_end(self):
        # Patch before construction: clicked.connect captures these callbacks.
        start = self._patch(ui.App, "_on_start_listen", new=Mock())
        stop = self._patch(ui.App, "_on_stop_listen", new=Mock())
        window = self.window(tall=True)
        self._resize(window, (640, 320))
        self._reset_io()
        for index in range(4):
            with self.subTest(page=index):
                self._select_compact(window, index)
                positions = (QRect(window._hero.geometry()), QRect(window._footer.geometry()))
                self._scroll_all_to_end(window)
                self.assertEqual((window._hero.geometry(), window._footer.geometry()), positions)
                self._assert_chrome(window)
                window._set_listen_buttons(False)
                QTest.mouseClick(window.start_btn, Qt.LeftButton)
                self.assertEqual(start.call_count, index + 1)
                window._set_listen_buttons(True)
                QTest.mouseClick(window.stop_btn, Qt.LeftButton)
                self.assertEqual(stop.call_count, index + 1)
        self._assert_no_layout_io()

    def test_independent_scroll_keeps_form_heading_hero_and_footer_fixed(self):
        window = self.window(tall=True)
        self._resize(window, (1180, 360))
        headings = [label for label in window._form_wrap.findChildren(QLabel)
                    if label.text() == "接单条件"]
        saves = [button for button in window._form_wrap.findChildren(QPushButton)
                 if button.text() == "保存设置"]
        self.assertEqual(len(headings), 1)
        self.assertEqual(len(saves), 1)
        for control in headings + saves:
            self.assertFalse(window._form_scroll.isAncestorOf(control))
            self._assert_reachable(control, window)
        anchors = [window._hero, window._footer, headings[0], saves[0]]
        original = [self._rect_in(widget, window) for widget in anchors]
        account_bar = window._sidebar_host.verticalScrollBar()
        form_bar = window._form_scroll.verticalScrollBar()
        self.assertGreater(account_bar.maximum(), 0)
        self.assertGreater(form_bar.maximum(), 0)
        account_bar.setValue(0)
        form_bar.setValue(0)
        self._settle()
        self._reset_io()
        account_bar.setValue(account_bar.maximum())
        self._settle()
        self.assertEqual(form_bar.value(), 0)
        account_end = account_bar.value()
        form_bar.setValue(form_bar.maximum())
        self._settle()
        self.assertEqual(account_bar.value(), account_end)
        self.assertEqual([self._rect_in(widget, window) for widget in anchors], original)
        self._assert_reachable(headings[0], window)
        self._assert_reachable(saves[0], window)
        self._assert_chrome(window)
        self._assert_no_layout_io()

    def test_inputs_keep_size_hints_and_do_not_overlap_in_both_memo_modes(self):
        window = self.window()
        for mode in (2, 3):
            window.memo_mode_box.setCurrentIndex(mode)
            for size in self.SMALL_SIZES:
                with self.subTest(mode=mode, size=size):
                    self._resize(window, size)
                    self._assert_account_inputs_fit(window)
                    self._assert_form_inputs_fit(window)
                    self._assert_chrome(window)

    def test_unsaved_amount_keywords_and_selections_survive_layout_changes_without_io(self):
        window = self.window(tall=True)
        self._resize(window, self.COMPACT_SIZE)
        self._show_form(window)
        self._type_ascii(window.min_amount_edit, "17.25")
        self._type_ascii(window.max_amount_edit, "88.50")
        self._type_ascii(window.poll_interval_edit, "0.75")
        window.keyword_text.setPlainText("编辑后排除词\n逗号,保留")
        window.memo_mode_box.setCurrentIndex(3)
        window.keyword_text.setPlainText("未保存白名单\nemoji-测试")
        window.confirm_wait_min_spin.setValue(5.0)
        window.confirm_wait_max_spin.setValue(7.0)
        window.run_rest_check.setChecked(False)
        window.risk_run_min_spin.setValue(31)
        window.risk_run_max_spin.setValue(43)
        window.risk_rest_min_spin.setValue(4)
        window.risk_rest_max_spin.setValue(9)
        window.auto_stop_check.setChecked(False)  # Existing business autosave is mocked.
        window.online_check.setChecked(True)      # session=None => no online request.
        window.item_checks[9000].setChecked(False)
        window.item_checks[9001].setChecked(True)
        window.all_accounts_check.setChecked(True)
        self._select_compact(window, 3)
        self._settle()
        expected = self._state(window)
        cfg_before = copy.deepcopy(window.cfg)
        original = self._identity(window)
        self.assertEqual(window.cfg["memo_exclude_keywords"], ["编辑后排除词", "逗号,保留"])
        self._reset_io()  # Only layout operations below are required to do no IO.
        for size in (self.COLUMN_SIZE, (640, 320), (1180, 360), self.COMPACT_SIZE) * 3:
            with self.subTest(size=size):
                self._resize(window, size)
                self._scroll_all_to_end(window)
                window._apply_responsive()
                self._settle()
                self.assertEqual(self._state(window), expected)
                self.assertEqual(window.cfg, cfg_before)
                self._assert_identity(window, original)
                self._assert_no_layout_io()

    def test_two_keyword_lists_remain_separate_after_resize_and_mode_switches(self):
        window = self.window()
        self._resize(window, self.COMPACT_SIZE)
        self._show_form(window)
        exclude = "保留排除词\nexclude-fixture"
        include = "保留包含词\ninclude-fixture"
        window.keyword_text.setPlainText(exclude)
        self._reset_io()
        for cycle in range(3):
            with self.subTest(cycle=cycle):
                self._resize(window, self.COLUMN_SIZE)
                self.assertEqual(window.keyword_text.toPlainText(), exclude)
                window.memo_mode_box.setCurrentIndex(3)
                if cycle == 0:
                    window.keyword_text.setPlainText(include)
                self.assertEqual(window.keyword_text.toPlainText(), include)
                self._resize(window, self.COMPACT_SIZE)
                window.memo_mode_box.setCurrentIndex(2)
                self.assertEqual(window.keyword_text.toPlainText(), exclude)
                self.assertEqual(window.cfg["memo_exclude_keywords"], exclude.splitlines())
                self.assertEqual(window.cfg["memo_include_keywords"], include.splitlines())
                self._assert_no_layout_io()

    def test_all_themes_preserve_small_window_and_input_geometry(self):
        window = self.window()
        original = self._identity(window)
        for theme_id in THEMES:
            with self.subTest(theme=theme_id):
                index = window.theme_box.findData(theme_id)
                self.assertGreaterEqual(index, 0)
                window.theme_box.setCurrentIndex(index)
                self._settle()
                self.assertEqual(window._theme_id, theme_id)
                self._reset_io()  # Theme switching itself intentionally persists.
                for size in ((640, 480), (640, 320), (1180, 360)):
                    with self.subTest(size=size):
                        self._resize(window, size)
                        self._assert_account_inputs_fit(window)
                        self._assert_form_inputs_fit(window)
                        self._assert_chrome(window)
                        self._assert_identity(window, original)
                        self._assert_no_layout_io()
        self._assert_no_transport()

    def test_screen_fit_clamps_logical_size_and_negative_origins_at_large_dpi(self):
        window = self.window()
        cases = (
            (QRect(0, 0, 640, 480), 1.0),
            (QRect(-1280, 80, 800, 600), 1.25),
            (QRect(-1600, -240, 640, 320), 3.0),
            (QRect(1440, -300, 1024, 480), 2.5),
        )
        fit = self._required(window, "_fit_window_to_screen")
        self._reset_io()
        for area, dpr in cases:
            for initial in (False, True):
                with self.subTest(area=area, dpr=dpr, initial=initial):
                    window.resize(1800, 1000)
                    window.move(area.right() + 200, area.bottom() + 200)
                    fit(screen=FakeScreen(area, dpr), initial=initial)
                    self._settle()
                    self.assertTrue(area.contains(window.frameGeometry()),
                                    (area, window.frameGeometry(), "DPR", dpr))
                    self.assertLessEqual(window.minimumWidth(), area.width())
                    self.assertLessEqual(window.minimumHeight(), area.height())
                    self.assertLessEqual(window.width(), area.width())
                    self.assertLessEqual(window.height(), area.height())
                    if initial:
                        self.assertLessEqual(window.width(), ui.WINDOW_SIZE[0])
                        self.assertLessEqual(window.height(), ui.WINDOW_SIZE[1])
                    self._assert_chrome(window)
                    self._assert_form_inputs_fit(window)
                    self._assert_no_layout_io()

    def test_default_screen_fit_uses_current_screen_without_business_io(self):
        window = self.window()
        screen = FakeScreen(QRect(-800, 25, 640, 360), 2.0)
        self._reset_io()
        with patch.object(window, "screen", return_value=screen):
            window._fit_window_to_screen(initial=False)
            self._settle()
        self.assertTrue(screen.availableGeometry().contains(window.frameGeometry()),
                        window.frameGeometry())
        self._assert_chrome(window)
        self._assert_no_layout_io()

    def test_real_start_stop_slots_use_fake_engine_and_original_footer_controls(self):
        window = self.window(tall=True)
        self._resize(window, self.COMPACT_SIZE)
        self._select_compact(window, 0)
        self._fill_synthetic_credentials(window)
        engine = FakeEngine()
        make_engine = self._patch(window, "_make_engine", return_value=engine)
        original = self._identity(window)
        self._reset_io()
        for index in range(4):
            with self.subTest(start_page=index):
                self._select_compact(window, index)
                self._scroll_all_to_end(window)
                self._assert_chrome(window)
                QTest.mouseClick(window.start_btn, Qt.LeftButton)
                self._settle()
                self.assertEqual(engine.start_calls[-1],
                                 ("19900000000", "fixture-password", None))
                self.assertEqual(len(engine.start_calls), index + 1)
                self.assertIs(window.engine, engine)
                self.assertTrue(engine.running)
                self.assertTrue(window._listening)
                self.assertFalse(window.start_btn.isEnabled())
                self.assertTrue(window.stop_btn.isEnabled())
                self.assertEqual(window.listen_state_sidebar_label.text(), "正在监听")
                self.assertEqual(window.listen_state_label.text(), "正在监听")
                for size in (self.COLUMN_SIZE, self.COMPACT_SIZE):
                    self._resize(window, size)
                    self._assert_identity(window, original)
                    self.assertTrue(window._listening)
                    self.assertTrue(window.stop_btn.isEnabled())
                self._select_compact(window, (index + 1) % 4)
                self._scroll_all_to_end(window)
                self._assert_chrome(window)
                QTest.mouseClick(window.stop_btn, Qt.LeftButton)
                self._settle()
                self.assertEqual(engine.stop_calls, index + 1)
                self.assertFalse(engine.running)
                self.assertFalse(window._listening)
                self.assertTrue(window.start_btn.isEnabled())
                self.assertFalse(window.stop_btn.isEnabled())
                self.assertEqual(window.listen_state_sidebar_label.text(), "已停止监听")
                self.assertEqual(window.listen_state_label.text(), "已停止监听")
        self.assertEqual(make_engine.call_count, 4)
        self.cfg_save.assert_not_called()
        self.account_save.assert_not_called()
        self.warning.assert_not_called()
        self.error.assert_not_called()
        self._assert_no_transport()

    def test_failed_fake_start_keeps_idle_footer_and_no_sleep_hold(self):
        window = self.window()
        self._resize(window, self.COMPACT_SIZE)
        self._select_compact(window, 0)
        self._fill_synthetic_credentials(window)
        engine = FakeEngine(start_result=False)
        self._patch(window, "_make_engine", return_value=engine)
        self._reset_io()
        self.sleep.reset_mock()
        self._select_compact(window, 2)
        self._assert_chrome(window)
        QTest.mouseClick(window.start_btn, Qt.LeftButton)
        self._settle()
        self.assertEqual(engine.start_calls, [("19900000000", "fixture-password", None)])
        self.assertFalse(engine.running)
        self.assertFalse(window._listening)
        self.assertTrue(window.start_btn.isEnabled())
        self.assertFalse(window.stop_btn.isEnabled())
        self.sleep.assert_not_called()
        self._assert_chrome(window)
        self._assert_no_transport()

    def test_engine_side_stop_updates_same_footer_after_layout_change(self):
        window = self.window()
        self._resize(window, self.COMPACT_SIZE)
        self._select_compact(window, 0)
        self._fill_synthetic_credentials(window)
        engine = FakeEngine()
        self._patch(window, "_make_engine", return_value=engine)
        original = self._identity(window)
        self._reset_io()
        QTest.mouseClick(window.start_btn, Qt.LeftButton)
        self._settle()
        self._resize(window, self.COLUMN_SIZE)
        engine.running = False  # Synthetic completion, no timer/worker/polling.
        window._refresh_buttons()
        self._settle()
        self._resize(window, self.COMPACT_SIZE)
        self._select_compact(window, 2)
        self._assert_identity(window, original)
        self.assertFalse(window._listening)
        self.assertTrue(window.start_btn.isEnabled())
        self.assertFalse(window.stop_btn.isEnabled())
        self.assertEqual(window.listen_state_sidebar_label.text(), "监听已停止")
        self.assertEqual(window.listen_state_label.text(), "监听已停止")
        self.sleep.assert_any_call(False)
        self._assert_chrome(window)
        self._assert_no_transport()


if __name__ == "__main__":
    unittest.main()
