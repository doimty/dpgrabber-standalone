"""Push notifications for a confirmed grab.

Three channels, all key-based so nothing has to be self-hosted:

* ``serverchan``  POST https://sctapi.ftqq.com/<key>.send   (form: title/desp)
* ``pushplus``    POST https://www.pushplus.plus/send      (json: token/title/content)
* ``webhook``     POST <notify_webhook>                    (json: title/body)

``send`` never raises: a push failure must not disturb the grab loop, exactly
like the optional voice announcer. The caller runs it on a worker thread.
"""
from __future__ import annotations

import json
import urllib.parse
import urllib.request

PROVIDERS = ("bark", "serverchan", "pushplus", "webhook")
PROVIDER_NAMES = {
    "bark": "Bark",
    "serverchan": "Server酱",
    "pushplus": "PushPlus",
    "webhook": "自定义 Webhook",
}


def _http_post(url: str, body: bytes, headers: dict, timeout: float):
    request = urllib.request.Request(url, data=body, headers=headers, method="POST")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.status, response.read().decode("utf-8", "replace")


class Notifier:
    """Configuration-backed push sender; safe to call from any thread."""

    def __init__(self, cfg: dict, post=None):
        self.cfg = cfg or {}
        self._post = post or _http_post

    @property
    def enabled(self) -> bool:
        return bool(self.cfg.get("notify_enabled", False))

    @property
    def provider(self) -> str:
        name = str(self.cfg.get("notify_provider") or "serverchan")
        return name if name in PROVIDERS else "serverchan"

    def describe(self) -> str:
        return PROVIDER_NAMES.get(self.provider, self.provider)

    def send(self, title: str, body: str) -> bool:
        """Best-effort push. Returns True only when the channel accepted it."""
        if not self.enabled:
            return False
        try:
            if self.provider == "bark":
                return self._bark(title, body)
            if self.provider == "serverchan":
                return self._serverchan(title, body)
            if self.provider == "pushplus":
                return self._pushplus(title, body)
            return self._webhook(title, body)
        except Exception:
            return False

    # -- channels ----------------------------------------------------------

    def _bark(self, title: str, body: str) -> bool:
        """Bark: iOS push. Key is the device key; server may be self-hosted."""
        key = str(self.cfg.get("notify_key") or "").strip()
        if not key:
            return False
        server = str(self.cfg.get("notify_bark_server") or "https://api.day.app").strip()
        if not server.lower().startswith(("http://", "https://")):
            return False
        url = server.rstrip("/") + "/push"
        payload = json.dumps(
            {"device_key": key, "title": title, "body": body, "group": "DPGrabber"},
            ensure_ascii=False,
        ).encode("utf-8")
        status, text = self._post(url, payload, {"Content-Type": "application/json"}, 8)
        return status == 200 and '"code":200' in text.replace(" ", "")

    def _serverchan(self, title: str, body: str) -> bool:
        key = str(self.cfg.get("notify_key") or "").strip()
        if not key:
            return False
        url = "https://sctapi.ftqq.com/%s.send" % urllib.parse.quote(key)
        payload = urllib.parse.urlencode({"title": title, "desp": body}).encode("utf-8")
        status, text = self._post(
            url, payload, {"Content-Type": "application/x-www-form-urlencoded"}, 8
        )
        return status == 200 and '"code":0' in text.replace(" ", "")

    def _pushplus(self, title: str, body: str) -> bool:
        token = str(self.cfg.get("notify_key") or "").strip()
        if not token:
            return False
        payload = json.dumps(
            {"token": token, "title": title, "content": body, "template": "txt"},
            ensure_ascii=False,
        ).encode("utf-8")
        status, text = self._post(
            "https://www.pushplus.plus/send", payload,
            {"Content-Type": "application/json"}, 8,
        )
        return status == 200 and '"code":200' in text.replace(" ", "")

    def _webhook(self, title: str, body: str) -> bool:
        url = str(self.cfg.get("notify_webhook") or "").strip()
        if not url.lower().startswith(("http://", "https://")):
            return False
        payload = json.dumps({"title": title, "body": body}, ensure_ascii=False).encode("utf-8")
        status, _ = self._post(url, payload, {"Content-Type": "application/json"}, 8)
        return 200 <= status < 300
