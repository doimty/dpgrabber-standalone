"""Order history store for the DPGrabber PC app.

Every successfully grabbed order is appended here (one JSON object per
order, newest last) so the dashboard's 接单历史 section can show it and
it survives app restarts. The file lives next to the config file.

An entry records what the grab actually had, following the fields the
reference console (autoduopei.cc) shows per record — order time, order
number, amount, customer WeChat and memo:
    grabbed_at : local ISO timestamp of the grab ("2026-11-14T01:58:33")
    no         : order number (e.g. "26111401581302023638")
    id         : internal order id (e.g. "15650502")
    amount     : yuan as a float (e.g. 30.0)
    userWechatId: customer WeChat id (e.g. "A_sweet_moon")
    userMemo   : order memo/remarks (may be "")
    createTime : order creation time as returned by the server (may be "")
    outcome    : autoduopei-style outcome — success / failed / risk /
                 unknown / skipped (old entries without it read as success)
    reason     : human-readable reason for a non-success outcome (may be "")
"""

from __future__ import annotations

import json
import os
import stat

import config as cfgmod

HISTORY_FILENAME = "dpgrabber_history.json"
MAX_ENTRIES = 200

#: Outcome values, aligned with autoduopei.cc order records
#: (success / failed / risk / unknown / skipped).
OUTCOMES = ("success", "failed", "risk", "unknown", "skipped")


def normalize_entry(entry: dict) -> dict:
    """Return a copy of ``entry`` with ``outcome`` / ``reason`` filled in.

    Old entries (written before outcome tracking) lack both fields: they
    were all confirmed grabs, so missing outcome means "success" and a
    missing reason means "". A present but unknown outcome is kept as
    "unknown" rather than invented."""
    entry = dict(entry or {})
    outcome = entry.get("outcome")
    if not isinstance(outcome, str) or not outcome:
        outcome = "success"
    elif outcome not in OUTCOMES:
        outcome = "unknown"
    entry["outcome"] = outcome
    reason = entry.get("reason")
    entry["reason"] = str(reason or "")
    return entry


def history_path() -> str:
    return os.path.join(cfgmod.config_dir(), HISTORY_FILENAME)


def load_history(path: str | None = None) -> list:
    """Load the history list; [] when the file is missing or corrupt."""
    path = path or history_path()
    if not os.path.exists(path):
        return []
    try:
        with open(path, "r", encoding="utf-8") as fh:
            raw = json.load(fh)
    except (OSError, ValueError):
        return []
    if not isinstance(raw, list):
        return []
    return [normalize_entry(e) for e in raw if isinstance(e, dict)]


def save_history(entries: list, path: str | None = None) -> str:
    """Write the history atomically (tmp + replace) and return the path."""
    path = path or history_path()
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(entries, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    os.replace(tmp, path)
    try:
        os.chmod(path, stat.S_IRUSR | stat.S_IWUSR)
    except OSError:
        pass
    return path


def append_history(entry: dict, path: str | None = None) -> list:
    """Append one entry, keep only the newest MAX_ENTRIES, save, return list."""
    entries = load_history(path)
    entries.append(normalize_entry(entry))
    entries = entries[-MAX_ENTRIES:]
    save_history(entries, path)
    return entries


def clear_history(path: str | None = None) -> None:
    save_history([], path)
