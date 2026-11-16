"""Advanced settings geometry/interaction regression. Offline synthetic data only."""
from __future__ import annotations

import ast
import copy
import inspect
import os
from pathlib import Path
import sys
import tempfile
import textwrap
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
if sys.platform != 'win32' and not os.environ.get('DISPLAY'):
    os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')

from PySide6.QtCore import QPoint, QPointF, QRect, Qt
from PySide6.QtGui import QWheelEvent
from PySide6.QtTest import QTest
from PySide6.QtWidgets import QApplication, QLabel
import config
from themes import THEMES
import ui

_APP = None

class AdvancedDialogTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        global _APP
        _APP = QApplication.instance() or QApplication([])
        _APP.setQuitOnLastWindowClosed(False)
        cls.app = _APP

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.addCleanup(patch.stopall)
        patch.object(config, 'config_dir', return_value=self.tmp.name).start()
        patch.object(config, 'config_path', return_value=os.path.join(self.tmp.name, 'config.json')).start()
        self.effects = patch.object(ui, '_apply_windows_effects', return_value=True).start()
        self.dialogs = []
        self.addCleanup(self.close_dialogs)
        ui.apply_theme(self.app, THEMES['blue']['palette'], 'blue')

    def close_dialogs(self):
        for dialog in self.dialogs:
            dialog.close()
            dialog.deleteLater()
        self.app.processEvents()

    def dialog(self, cfg=None, callback=None):
        settings = copy.deepcopy(config.DEFAULT_CONFIG) if cfg is None else cfg
        result = ui.AdvancedDialog(None, settings, callback or Mock())
        self.dialogs.append(result)
        result.show()
        QTest.qWait(15)
        return result

    def controls(self, dialog):
        return (list(dialog.entries.values()) + list(dialog.item_map_edits.values()) +
                [dialog.auto_wait_offset_spin, dialog.ui_scale_box,
                 dialog.rotate_minutes_spin, dialog.notify_provider_box, dialog.test_button])

    def assert_controls_fit(self, dialog):
        rectangles = []
        for control in self.controls(dialog):
            with self.subTest(control=control.objectName() or type(control).__name__):
                self.assertGreaterEqual(control.height(), control.sizeHint().height())
                point = control.mapTo(dialog._content, QPoint())
                rect = QRect(point, control.size())
                self.assertGreaterEqual(rect.left(), 0)
                self.assertLessEqual(rect.right(), dialog._content.width() - 1)
                rectangles.append(rect)
        for i, rect in enumerate(rectangles):
            for other in rectangles[i + 1:]:
                self.assertFalse(rect.intersects(other), (rect, other))

    def test_only_one_show_event_applies_fit_and_windows_effects(self):
        tree = ast.parse(textwrap.dedent(inspect.getsource(ui.AdvancedDialog)))
        definitions = [node for node in tree.body[0].body
                       if isinstance(node, ast.FunctionDef) and node.name == 'showEvent']
        self.assertEqual(len(definitions), 1)
        dialog = self.dialog()
        self.effects.assert_called_once_with(dialog, dark=True)
        self.assertGreater(dialog.maximumHeight(), dialog.minimumHeight())

    def test_rows_do_not_overlap_or_clip_scale_selector(self):
        dialog = self.dialog()
        self.assert_controls_fit(dialog)
        entries = list(dialog.entries.values())
        for a, b in zip(entries, entries[1:]):
            a_top = a.mapTo(dialog._content, QPoint()).y()
            b_top = b.mapTo(dialog._content, QPoint()).y()
            self.assertGreaterEqual(b_top - (a_top + a.height()), 10)
        point = dialog.ui_scale_box.mapTo(dialog._form, QPoint())
        self.assertLessEqual(point.y() + dialog.ui_scale_box.height(), dialog._form.height())
        heading = next(label for label in dialog._content.findChildren(QLabel)
                       if label.text() == '接单类型匹配词')
        form_bottom = dialog._form.mapTo(dialog._content, QPoint()).y() + dialog._form.height()
        self.assertLess(form_bottom, heading.mapTo(dialog._content, QPoint()).y())

    def test_scroll_keeps_title_and_save_cancel_visible(self):
        dialog = self.dialog()
        dialog.resize(640, 480)
        QTest.qWait(20)
        self.assert_controls_fit(dialog)
        scrollbar = dialog._scroll.verticalScrollBar()
        self.assertGreater(scrollbar.maximum(), 0)
        header_pos, footer_pos = dialog._header.pos(), dialog._footer.pos()
        scrollbar.setValue(scrollbar.maximum())
        QTest.qWait(10)
        self.assertEqual(dialog._header.pos(), header_pos)
        self.assertEqual(dialog._footer.pos(), footer_pos)
        self.assertTrue(dialog.rect().contains(dialog._footer.geometry()))
        for button in (dialog.save_button, dialog.cancel_button):
            self.assertTrue(button.isVisibleTo(dialog))
            self.assertTrue(dialog._footer.rect().contains(button.geometry()))
        self.assertEqual(dialog._scroll.horizontalScrollBar().maximum(), 0)

    def test_many_long_types_and_status_expand_scroll_not_rows(self):
        cfg = copy.deepcopy(config.DEFAULT_CONFIG)
        for i in range(24):
            cfg['item_switch_names'][str(9000 + i)] = '较长的测试类型名称' + str(i)
            cfg['item_name_map'][str(9000 + i)] = ['测试词' + str(i)]
        dialog = self.dialog(cfg)
        dialog.resize(600, 360)
        QTest.qWait(20)
        self.assertEqual(len(dialog.item_map_edits), 27)
        self.assert_controls_fit(dialog)
        maximum = dialog._scroll.verticalScrollBar().maximum()
        self.assertGreater(maximum, 1000)
        dialog._show_test_result(False, '测试渠道' * 30)
        QTest.qWait(20)
        self.assertGreater(dialog._scroll.verticalScrollBar().maximum(), maximum)
        dialog._scroll.ensureWidgetVisible(dialog.test_label)
        QTest.qWait(10)
        self.assert_controls_fit(dialog)

    def test_screen_fit_handles_small_and_negative_origin_screens(self):
        dialog = self.dialog()
        for area in (QRect(0, 0, 640, 480), QRect(-1280, 80, 800, 600)):
            with self.subTest(area=area):
                screen = Mock()
                screen.availableGeometry.return_value = area
                with patch.object(dialog, 'screen', return_value=screen):
                    dialog._fit_to_content()
                    QTest.qWait(10)
                self.assertTrue(area.contains(dialog.frameGeometry()), (area, dialog.frameGeometry()))
                self.assert_controls_fit(dialog)
                self.assertTrue(dialog.rect().contains(dialog._footer.geometry()))

    def test_all_six_themes_keep_control_geometry(self):
        dialog = self.dialog()
        dialog.resize(640, 480)
        for name, theme in THEMES.items():
            with self.subTest(theme=name):
                ui.apply_theme(self.app, theme['palette'], name)
                QTest.qWait(10)
                self.assert_controls_fit(dialog)

    def test_save_preserves_settings_and_calls_back_once(self):
        cfg = copy.deepcopy(config.DEFAULT_CONFIG)
        cfg['memo_exclude_keywords'] = ['排除测试词']
        cfg['memo_include_keywords'] = ['包含测试词']
        callback = Mock()
        dialog = self.dialog(cfg, callback)
        dialog.entries['shop_id'].setText('92')
        dialog.entries['request_timeout_sec'].setText('7.5')
        dialog.entries['notify_key'].setText('DEMO_KEY')
        dialog.entries['notify_webhook'].setText('https://example.test/notify')
        dialog.entries['notify_bark_server'].setText('https://example.test/bark')
        dialog.auto_wait_offset_spin.setValue(0.15)
        dialog.ui_scale_box.setCurrentIndex(4)
        dialog.item_map_edits['465'].setText('连麦，测试词')
        dialog.blacklist_text.setPlainText('test-user-1\ntest-user-2')
        dialog.verify_check.setChecked(False)
        dialog.rotate_check.setChecked(True)
        dialog.rotate_minutes_spin.setValue(45)
        dialog.notify_check.setChecked(True)
        dialog.voice_check.setChecked(False)
        QTest.mouseClick(dialog.save_button, Qt.LeftButton)
        callback.assert_called_once()
        saved = callback.call_args.args[0]
        self.assertEqual(saved['shop_id'], 92)
        self.assertEqual(saved['request_timeout_sec'], 7.5)
        self.assertEqual(saved['ui_scale'], 1.25)
        self.assertAlmostEqual(saved['auto_wait_offset'], 0.15)
        self.assertEqual(saved['notify_key'], 'DEMO_KEY')
        self.assertEqual(saved['notify_webhook'], 'https://example.test/notify')
        self.assertEqual(saved['notify_bark_server'], 'https://example.test/bark')
        self.assertEqual(saved['item_name_map']['465'], ['连麦', '测试词'])
        self.assertEqual(saved['blacklist'], ['test-user-1', 'test-user-2'])
        self.assertEqual(saved['rotate_minutes'], 45)
        self.assertTrue(saved['rotate_enabled'])
        self.assertTrue(saved['notify_enabled'])
        self.assertFalse(saved['verify_tls'])
        self.assertFalse(saved['voice_enabled'])
        self.assertEqual(saved['memo_exclude_keywords'], ['排除测试词'])
        self.assertEqual(saved['memo_include_keywords'], ['包含测试词'])
        self.assertEqual(saved['item_always_types'], cfg['item_always_types'])

    def test_cancel_and_escape_do_not_save(self):
        for action in ('cancel', 'escape'):
            with self.subTest(action=action):
                callback = Mock()
                cfg = copy.deepcopy(config.DEFAULT_CONFIG)
                original = copy.deepcopy(cfg)
                dialog = self.dialog(cfg, callback)
                dialog.entries['shop_id'].setText('92')
                if action == 'cancel':
                    QTest.mouseClick(dialog.cancel_button, Qt.LeftButton)
                else:
                    QTest.keyClick(dialog, Qt.Key_Escape)
                callback.assert_not_called()
                self.assertEqual(cfg, original)
                self.assertFalse(dialog.isVisible())

    def test_invalid_numeric_setting_stays_open(self):
        callback = Mock()
        dialog = self.dialog(callback=callback)
        dialog.entries['shop_id'].setText('invalid')
        with patch.object(ui.MessageDialog, 'show_error') as error:
            dialog._save()
        error.assert_called_once()
        callback.assert_not_called()
        self.assertTrue(dialog.isVisible())

    def test_blacklist_confirmation_cancel_stays_open(self):
        cfg = copy.deepcopy(config.DEFAULT_CONFIG)
        cfg['blacklist'] = ['existing-user']
        callback = Mock()
        dialog = self.dialog(cfg, callback)
        dialog.blacklist_text.setPlainText('new-user')
        with patch.object(ui.MessageDialog, 'ask', return_value=False) as question:
            dialog._save()
        question.assert_called_once()
        callback.assert_not_called()
        self.assertTrue(dialog.isVisible())
        self.assertEqual(dialog.cfg['blacklist'], ['existing-user'])

    def test_header_drag_uses_header_bounds_not_scroll_content(self):
        dialog = self.dialog()
        point = dialog._header.geometry().center()
        QTest.mousePress(dialog, Qt.LeftButton, pos=point)
        self.assertIsNotNone(dialog._drag_offset)
        QTest.mouseRelease(dialog, Qt.LeftButton, pos=point)
        self.assertIsNone(dialog._drag_offset)
        QTest.mousePress(dialog, Qt.LeftButton, pos=dialog._scroll.geometry().center())
        self.assertIsNone(dialog._drag_offset)
        QTest.mouseRelease(dialog, Qt.LeftButton, pos=dialog._scroll.geometry().center())

    def test_scrolling_unfocused_selector_does_not_change_setting(self):
        dialog = self.dialog()
        dialog.entries['shop_id'].setFocus()
        QTest.qWait(10)
        selector = dialog.ui_scale_box
        previous = selector.currentIndex()
        before_scroll = dialog._scroll.verticalScrollBar().value()
        event = QWheelEvent(QPointF(5, 5),
                            QPointF(selector.mapToGlobal(QPoint(5, 5))),
                            QPoint(), QPoint(0, -120), Qt.NoButton,
                            Qt.NoModifier, Qt.NoScrollPhase, False)
        self.app.sendEvent(selector, event)
        QTest.qWait(10)
        self.assertEqual(selector.currentIndex(), previous)
        self.assertGreater(dialog._scroll.verticalScrollBar().value(), before_scroll)

    def test_return_uses_save_not_test_push(self):
        callback = Mock()
        dialog = self.dialog(callback=callback)
        with patch('notify.Notifier.send') as send:
            QTest.keyClick(dialog.entries['request_timeout_sec'], Qt.Key_Return)
        callback.assert_called_once()
        send.assert_not_called()

if __name__ == '__main__':
    unittest.main()
