"""Per-account configuration files.

One account = one JSON file under ``<config dir>/accounts/<phone>.json``. The
legacy single ``dpgrabber_config.json`` is migrated on first use, so existing
users keep their settings.

Nothing here touches the network: it only maps phone numbers to files.
"""
from __future__ import annotations

import json
import os
import time

import config as cfgmod

ACCOUNTS_DIRNAME = "accounts"


def accounts_dir() -> str:
    return os.path.join(cfgmod.config_dir(), ACCOUNTS_DIRNAME)


def _ensure_dir() -> str:
    directory = accounts_dir()
    os.makedirs(directory, exist_ok=True)
    return directory


def path_for(phone: str) -> str:
    """Config file for an account. Phone digits only, so the name is stable."""
    digits = "".join(ch for ch in str(phone or "") if ch.isdigit())
    if not digits:
        raise ValueError("phone number is required")
    return os.path.join(_ensure_dir(), "%s.json" % digits)


def exists(phone: str) -> bool:
    try:
        return os.path.exists(path_for(phone))
    except ValueError:
        return False


def list_accounts() -> list[dict]:
    """All known accounts, newest change first."""
    directory = _ensure_dir()
    items = []
    for name in os.listdir(directory):
        if not name.endswith(".json"):
            continue
        path = os.path.join(directory, name)
        phone = name[:-5]
        meta = {"phone": phone, "path": path, "clerk_name": "", "shop": "",
                "updated": os.path.getmtime(path)}
        try:
            with open(path, "r", encoding="utf-8") as handle:
                raw = json.load(handle)
            meta["clerk_name"] = str(raw.get("clerk_name") or "")
            meta["shop"] = str(raw.get("shop_id") or "")
        except Exception:
            pass
        items.append(meta)
    items.sort(key=lambda item: item["updated"], reverse=True)
    return items


def label_for(meta: dict) -> str:
    """What the account picker shows: name, masked phone, shop."""
    phone = cfgmod.mask_phone(meta.get("phone", ""))
    name = (meta.get("clerk_name") or "").strip()
    shop = str(meta.get("shop") or "").strip()
    parts = [name or phone]
    if name:
        parts.append(phone)
    if shop and shop != "0":
        parts.append("店 %s" % shop)
    return " · ".join(parts)


def new_config(phone: str) -> dict:
    """Defaults for a fresh account file."""
    cfg = cfgmod.validate({})
    cfg["phone"] = str(phone)
    cfg["password"] = ""
    cfg["shop_id"] = 0
    return cfg


def load_account(phone: str) -> dict:
    """Load an account config, creating the file with defaults if missing."""
    path = path_for(phone)
    if not os.path.exists(path):
        cfg = new_config(phone)
        cfgmod.save(cfg, path)
        return cfg
    cfg = cfgmod.load(path)
    cfg.setdefault("phone", phone)
    return cfg


def save_account(cfg: dict, phone: str | None = None) -> str:
    """Persist to that account's file, stamping the update time."""
    target_phone = phone or str(cfg.get("phone") or "")
    path = path_for(target_phone)
    cfg = dict(cfg)
    cfg["phone"] = target_phone
    cfg["updated_at"] = time.strftime("%Y-%m-%dT%H:%M:%S")
    cfgmod.save(cfg, path)
    return path


def delete_account(phone: str) -> bool:
    try:
        path = path_for(phone)
    except ValueError:
        return False
    if os.path.exists(path):
        os.remove(path)
        return True
    return False


def migrate_legacy() -> str | None:
    """Move the old single config into accounts/<phone>.json once.

    Returns the new path, or None when there is nothing to migrate.
    """
    legacy = cfgmod.config_path()
    if not os.path.exists(legacy):
        return None
    try:
        cfg = cfgmod.load(legacy)
    except Exception:
        return None
    phone = str(cfg.get("phone") or "").strip()
    if not phone:
        return None
    target = path_for(phone)
    if os.path.exists(target):
        return target
    cfgmod.save(cfg, target)
    try:
        os.replace(legacy, legacy + ".migrated")
    except OSError:
        pass
    return target


def read_clerk_name(cfg: dict) -> str:
    return str(cfg.get("clerk_name") or "")
