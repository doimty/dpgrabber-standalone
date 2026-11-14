"""Theme palettes for the DPGrabber PC PySide6 UI.

Six themes, one palette each. A palette is the single source of truth
for every colour the UI uses — the QSS stylesheet (ui.build_stylesheet),
the combo/spin chevron arrows (drawn in the palette's accent colour),
the run-log level colours and the history outcome chips. No colour is
hardcoded in the UI; switching theme = swapping the palette.

References used for the palettes:
- qt-material / qtshadcn / qt-theme-manager: the idea of runtime
  switchable light/dark palettes (not copied — these palettes are
  written from scratch for this app).
- Sakura pink: ihatecolors "Cherry Blossom" (#FFF5F7 bg / #FFB7C5
  primary / #FF85A1 secondary / #A0522D text), "Sakura Blossom"
  (#FFF0F5 / #FFB5CC / #FF8FAB / #CC4477) and Sakura-iro #FCE2E6.

Palette keys (every theme must define all of them):
- window_bg, card_bg, card_border: main area background / card fill / border.
- accent, accent_dark: the theme's main colour (links, focus, buttons
  text, scrollbar, chevron arrows) and its darker variant.
- text_dark, hint: primary text and muted hint text.
- sidebar_bg, sidebar_card_bg, sidebar_card_border, sidebar_text,
  sidebar_hint: the sidebar strip and its cards.
- hero_top, hero_bottom, hero_border, hero_stat_dim: the top gradient
  band and its border / dimmed stat text.
- btn_top, btn_bottom, btn_hover_top, btn_hover_bottom,
  btn_disabled_bg, btn_disabled_text: primary pill buttons.
- entry_bg, entry_border: text fields / combo / spin backgrounds.
- selection_bg: list / popup selection and hover background.
- history_reason: the "原因" line in the order history.
- log_info, log_success, log_warning, log_error, log_debug: run-log
  colours per level.
- outcome_success, outcome_failed, outcome_risk, outcome_unknown,
  outcome_skipped: history outcome chips.
"""

from __future__ import annotations

# Every key build_stylesheet() and the UI read from a palette.
PALETTE_KEYS = (
    "window_bg", "card_bg", "card_border",
    "accent", "accent_dark", "text_dark", "hint",
    "sidebar_bg", "sidebar_card_bg", "sidebar_card_border",
    "sidebar_text", "sidebar_hint",
    "hero_top", "hero_bottom", "hero_border", "hero_stat_dim",
    "btn_top", "btn_bottom", "btn_hover_top", "btn_hover_bottom",
    "btn_disabled_bg", "btn_disabled_text",
    "entry_bg", "entry_border", "selection_bg", "history_reason",
    "log_info", "log_success", "log_warning", "log_error", "log_debug",
    "outcome_success", "outcome_failed", "outcome_risk",
    "outcome_unknown", "outcome_skipped",
)

THEMES = {
    "day": {
        "name": "白天",
        "palette": {
            "window_bg": "#EAF2FA", "card_bg": "#F8FCFF",
            "card_border": "#CFE2F5",
            "accent": "#1F6AA5", "accent_dark": "#185A8A",
            "text_dark": "#1F2A37", "hint": "#5B7A99",
            "sidebar_bg": "#D9E8F6", "sidebar_card_bg": "#FFFFFF",
            "sidebar_card_border": "#C4D9EE",
            "sidebar_text": "#1F2A37", "sidebar_hint": "#5B7A99",
            "hero_top": "#4AA8F0", "hero_bottom": "#1769B0",
            "hero_border": "#CFEBFB", "hero_stat_dim": "#DCEEFF",
            "btn_top": "#5CB3F2", "btn_bottom": "#1E74C4",
            "btn_hover_top": "#7CC2F7", "btn_hover_bottom": "#2A82D2",
            "btn_disabled_bg": "#C9D8E4", "btn_disabled_text": "#8A9BA8",
            "entry_bg": "#FFFFFF", "entry_border": "#B9D2EC",
            "selection_bg": "#EAF2FA", "history_reason": "#9A6700",
            "log_info": "#1F2A37", "log_success": "#1A7F37",
            "log_warning": "#9A6700", "log_error": "#CF222E",
            "log_debug": "#666666",
            "outcome_success": "#1A7F37", "outcome_failed": "#CF222E",
            "outcome_risk": "#9A6700", "outcome_unknown": "#666666",
            "outcome_skipped": "#5B7A99",
        },
    },
    "night": {
        "name": "黑夜",
        "palette": {
            "window_bg": "#12161C", "card_bg": "#1C2129",
            "card_border": "#2E3742",
            "accent": "#5AA9E6", "accent_dark": "#3D7FB8",
            "text_dark": "#E6EDF3", "hint": "#8B9BB4",
            "sidebar_bg": "#161B22", "sidebar_card_bg": "#212830",
            "sidebar_card_border": "#2E3742",
            "sidebar_text": "#E6EDF3", "sidebar_hint": "#8B9BB4",
            "hero_top": "#2E6FA8", "hero_bottom": "#1B4A75",
            "hero_border": "#2E4A63", "hero_stat_dim": "#B8D4EA",
            "btn_top": "#4A90D9", "btn_bottom": "#2E6FA8",
            "btn_hover_top": "#5AA9E6", "btn_hover_bottom": "#3D7FB8",
            "btn_disabled_bg": "#2A313B", "btn_disabled_text": "#6B7A8C",
            "entry_bg": "#2E3947", "entry_border": "#4A5A6E",
            "selection_bg": "#33404F", "history_reason": "#D9A441",
            "log_info": "#E6EDF3", "log_success": "#4ADE80",
            "log_warning": "#D9A441", "log_error": "#FF7B72",
            "log_debug": "#8B9BB4",
            "outcome_success": "#4ADE80", "outcome_failed": "#FF7B72",
            "outcome_risk": "#D9A441", "outcome_unknown": "#8B9BB4",
            "outcome_skipped": "#8B9BB4",
        },
    },
    "blue": {
        "name": "蓝色",
        "palette": {
            "window_bg": "#DCEAF8", "card_bg": "#F2F8FE",
            "card_border": "#B8D4EE",
            "accent": "#1565C0", "accent_dark": "#0D47A1",
            "text_dark": "#0D2B45", "hint": "#4A6B8A",
            "sidebar_bg": "#BBD6F2", "sidebar_card_bg": "#FFFFFF",
            "sidebar_card_border": "#9CC3E8",
            "sidebar_text": "#0D2B45", "sidebar_hint": "#4A6B8A",
            "hero_top": "#42A5F5", "hero_bottom": "#1565C0",
            "hero_border": "#BBDEFB", "hero_stat_dim": "#E3F2FD",
            "btn_top": "#42A5F5", "btn_bottom": "#1565C0",
            "btn_hover_top": "#64B5F6", "btn_hover_bottom": "#1976D2",
            "btn_disabled_bg": "#C9D8E4", "btn_disabled_text": "#8A9BA8",
            "entry_bg": "#FFFFFF", "entry_border": "#9CC3E8",
            "selection_bg": "#E3F2FD", "history_reason": "#9A6700",
            "log_info": "#0D2B45", "log_success": "#1A7F37",
            "log_warning": "#9A6700", "log_error": "#CF222E",
            "log_debug": "#5A6B7A",
            "outcome_success": "#1A7F37", "outcome_failed": "#CF222E",
            "outcome_risk": "#9A6700", "outcome_unknown": "#666666",
            "outcome_skipped": "#5B7A99",
        },
    },
    "sakura": {
        "name": "樱花粉",
        "palette": {
            "window_bg": "#FFF5F7", "card_bg": "#FFFBFC",
            "card_border": "#F5C6D3",
            "accent": "#E85D8A", "accent_dark": "#C2436B",
            "text_dark": "#5C2E3E", "hint": "#A67C8A",
            "sidebar_bg": "#FCE2E6", "sidebar_card_bg": "#FFFFFF",
            "sidebar_card_border": "#F2B8C6",
            "sidebar_text": "#5C2E3E", "sidebar_hint": "#A67C8A",
            "hero_top": "#FF8FAB", "hero_bottom": "#E85D8A",
            "hero_border": "#FBD3DE", "hero_stat_dim": "#FFE4EC",
            "btn_top": "#FF8FAB", "btn_bottom": "#E85D8A",
            "btn_hover_top": "#FFA8BE", "btn_hover_bottom": "#F0739A",
            "btn_disabled_bg": "#F2D6DD", "btn_disabled_text": "#B89AA4",
            "entry_bg": "#FFFFFF", "entry_border": "#F2B8C6",
            "selection_bg": "#FFEDF1", "history_reason": "#9A6700",
            "log_info": "#5C2E3E", "log_success": "#1E8E4D",
            "log_warning": "#9A6700", "log_error": "#D92B4A",
            "log_debug": "#8A6A75",
            "outcome_success": "#1E8E4D", "outcome_failed": "#D92B4A",
            "outcome_risk": "#9A6700", "outcome_unknown": "#8A6A75",
            "outcome_skipped": "#A67C8A",
        },
    },
    "mint": {
        "name": "薄荷绿",
        "palette": {
            "window_bg": "#EAF7F1", "card_bg": "#F8FDFB",
            "card_border": "#C2E8D5",
            "accent": "#1E9E6A", "accent_dark": "#157A52",
            "text_dark": "#16382B", "hint": "#5A8570",
            "sidebar_bg": "#D2EFE0", "sidebar_card_bg": "#FFFFFF",
            "sidebar_card_border": "#A8DCC2",
            "sidebar_text": "#16382B", "sidebar_hint": "#5A8570",
            "hero_top": "#4DBD8E", "hero_bottom": "#1E9E6A",
            "hero_border": "#C8EBDA", "hero_stat_dim": "#DFF5EA",
            "btn_top": "#4DBD8E", "btn_bottom": "#1E9E6A",
            "btn_hover_top": "#66CFA4", "btn_hover_bottom": "#28B57E",
            "btn_disabled_bg": "#D3E8DD", "btn_disabled_text": "#8AA795",
            "entry_bg": "#FFFFFF", "entry_border": "#A8DCC2",
            "selection_bg": "#E6F7EE", "history_reason": "#9A6700",
            "log_info": "#16382B", "log_success": "#157A52",
            "log_warning": "#9A6700", "log_error": "#CF222E",
            "log_debug": "#5A8570",
            "outcome_success": "#157A52", "outcome_failed": "#CF222E",
            "outcome_risk": "#9A6700", "outcome_unknown": "#666666",
            "outcome_skipped": "#5A8570",
        },
    },
    "violet": {
        "name": "紫罗兰",
        "palette": {
            "window_bg": "#F1EDFA", "card_bg": "#FBF9FE",
            "card_border": "#D9CFF0",
            "accent": "#7C4DBE", "accent_dark": "#5E34A0",
            "text_dark": "#2E1F4A", "hint": "#7A6A9A",
            "sidebar_bg": "#E2D8F5", "sidebar_card_bg": "#FFFFFF",
            "sidebar_card_border": "#C9B8E8",
            "sidebar_text": "#2E1F4A", "sidebar_hint": "#7A6A9A",
            "hero_top": "#9B72D0", "hero_bottom": "#7C4DBE",
            "hero_border": "#DDD2F2", "hero_stat_dim": "#EDE7FA",
            "btn_top": "#9B72D0", "btn_bottom": "#7C4DBE",
            "btn_hover_top": "#B08AE0", "btn_hover_bottom": "#8E5ED0",
            "btn_disabled_bg": "#DCD5EC", "btn_disabled_text": "#9A8FB5",
            "entry_bg": "#FFFFFF", "entry_border": "#C9B8E8",
            "selection_bg": "#F0EAF9", "history_reason": "#9A6700",
            "log_info": "#2E1F4A", "log_success": "#1E8E4D",
            "log_warning": "#9A6700", "log_error": "#CF222E",
            "log_debug": "#7A6A9A",
            "outcome_success": "#1E8E4D", "outcome_failed": "#CF222E",
            "outcome_risk": "#9A6700", "outcome_unknown": "#7A6A9A",
            "outcome_skipped": "#7A6A9A",
        },
    },
}

DEFAULT_THEME = "day"


def palette_for(theme_id: str) -> dict:
    """Palette for a theme id; unknown ids fall back to the day theme."""
    theme = THEMES.get(str(theme_id or "").strip() or DEFAULT_THEME)
    if theme is None:
        theme = THEMES[DEFAULT_THEME]
    return theme["palette"]


def theme_name(theme_id: str) -> str:
    """Display name for a theme id; unknown ids fall back to day's name."""
    theme = THEMES.get(str(theme_id or "").strip() or DEFAULT_THEME)
    if theme is None:
        theme = THEMES[DEFAULT_THEME]
    return theme["name"]
