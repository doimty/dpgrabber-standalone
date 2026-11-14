"""Theme tests: the 6 theme palettes, stylesheet building and the theme
config key. Offline only; no network, no real account.

Run:  python3 -m unittest discover -s tests -v   (from the pc/ directory)
"""

from __future__ import annotations

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import config as cfgmod
from themes import DEFAULT_THEME, PALETTE_KEYS, THEMES, palette_for, theme_name
from ui import build_stylesheet


class ThemePaletteTests(unittest.TestCase):
    def test_six_themes_present(self):
        self.assertEqual(
            list(THEMES.keys()),
            ["day", "night", "blue", "sakura", "mint", "violet"],
        )
        self.assertEqual(DEFAULT_THEME, "day")

    def test_every_palette_has_all_keys(self):
        for theme_id, theme in THEMES.items():
            for key in PALETTE_KEYS:
                self.assertIn(key, theme["palette"], f"{theme_id} missing {key}")
                value = theme["palette"][key]
                self.assertIsInstance(value, str, f"{theme_id}.{key} not a string")
                self.assertTrue(
                    value.startswith("#") and len(value) == 7,
                    f"{theme_id}.{key}={value!r} is not a #RRGGBB colour",
                )

    def test_theme_names_are_chinese(self):
        self.assertEqual(
            [theme["name"] for theme in THEMES.values()],
            ["白天", "黑夜", "蓝色", "樱花粉", "薄荷绿", "紫罗兰"],
        )

    def test_palette_for_falls_back_to_day(self):
        self.assertEqual(palette_for("nope"), THEMES["day"]["palette"])
        self.assertEqual(palette_for(""), THEMES["day"]["palette"])
        self.assertEqual(palette_for("sakura"), THEMES["sakura"]["palette"])
        self.assertEqual(theme_name("nope"), "白天")
        self.assertEqual(theme_name("sakura"), "樱花粉")


class StylesheetTests(unittest.TestCase):
    def test_stylesheet_uses_the_theme_accent(self):
        for theme_id, theme in THEMES.items():
            css = build_stylesheet(theme["palette"], theme_id)
            self.assertIn(theme["palette"]["accent"], css, theme_id)
            self.assertNotIn("%(", css, theme_id)

    def test_stylesheets_differ_per_theme(self):
        day = build_stylesheet(THEMES["day"]["palette"], "day")
        sakura = build_stylesheet(THEMES["sakura"]["palette"], "sakura")
        night = build_stylesheet(THEMES["night"]["palette"], "night")
        self.assertNotEqual(day, sakura)
        self.assertNotEqual(day, night)
        # The sakura stylesheet must not contain the day accent.
        self.assertNotIn(THEMES["day"]["palette"]["accent"], sakura)
        # The night stylesheet must not contain the day window background.
        self.assertNotIn(THEMES["day"]["palette"]["window_bg"], night)

    def test_chevron_arrows_are_per_theme(self):
        sakura = build_stylesheet(THEMES["sakura"]["palette"], "sakura")
        night = build_stylesheet(THEMES["night"]["palette"], "night")
        self.assertIn("dpgrabber_chevron_down_sakura.png", sakura)
        self.assertIn("dpgrabber_chevron_up_sakura.png", sakura)
        self.assertIn("dpgrabber_chevron_down_night.png", night)
        self.assertNotIn("dpgrabber_chevron_down_sakura.png", night)

    def test_unknown_palette_keys_fall_back_to_day(self):
        css = build_stylesheet({}, "day")
        self.assertIn(THEMES["day"]["palette"]["accent"], css)
        self.assertNotIn("%(", css)


class ThemeConfigTests(unittest.TestCase):
    def test_theme_defaults_to_day(self):
        cfg = cfgmod.validate({})
        self.assertEqual(cfg["theme"], "day")

    def test_valid_theme_is_kept(self):
        cfg = cfgmod.validate({"theme": "sakura"})
        self.assertEqual(cfg["theme"], "sakura")

    def test_unknown_theme_falls_back_to_day(self):
        cfg = cfgmod.validate({"theme": "not-a-theme"})
        self.assertEqual(cfg["theme"], "day")
        cfg = cfgmod.validate({"theme": ""})
        self.assertEqual(cfg["theme"], "day")


if __name__ == "__main__":
    unittest.main()
