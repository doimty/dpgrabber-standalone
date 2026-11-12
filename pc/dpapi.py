"""DPGrabber PC - DuoPei API client.

Implements PROTOCOL.md sections 1-5:

* Base URL ``https://api.duopei.feiniaowangluo.com``; only ``/s/`` paths are
  allowed and the host is pinned (DPGAPIClient.m:225-233).
* Fixed headers: DPGrabber UA, ``api-version: 202501071``, ``platform: app``.
* Bodies: JSON or form (keys sorted, RFC 3986 percent-encoding).
* Response envelope (section 4.2): outer JSON ``response`` field; when
  ``isEncrypted`` is set, or when the response string does not parse as
  plain JSON, the payload is Base64-decoded and AES-CBC/PKCS#7-decrypted.
  The primary AES pair is tried first, then the alternate pair
  ("decrypt with whichever works", section 10.6).
* Login flow (section 3.2): phone + password -> token (sid "50") ->
  list-shop-account -> switch-account per shop -> getBalance validation.
  Token is kept in memory only, never written to disk.

The transport is injectable (``http``): pass an object with a
``request(method, url, headers=..., data=..., timeout=..., verify=...)``
method compatible with ``requests.Session``. The unit tests use a stub so
no real request is ever sent from a test.
"""

from __future__ import annotations

import base64
import binascii
import json
import threading
import time
from dataclasses import dataclass, field
from urllib.parse import quote

import requests
from Crypto.Cipher import AES
from Crypto.Util.Padding import unpad

BASE_URL = "https://api.duopei.feiniaowangluo.com"
API_HOST = "api.duopei.feiniaowangluo.com"

USER_AGENT = (
    "Mozilla/5.0 (iPhone; CPU iPhone OS 15_1_1 like Mac OS X) "
    "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 "
    "Html5Plus/1.0 (Immersed/20) uni-app"
)
API_VERSION = "202501071"

# Characters left unescaped in form bodies (RFC 3986 unreserved set).
_FORM_SAFE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"


class ApiError(Exception):
    """One failure of an API call.

    ``kind`` is one of: ``auth`` (401/403, login state invalid),
    ``ip`` (code 1 with "IP" in msg, IP control), ``api`` (other business
    error or malformed response), ``network`` (transport failure),
    ``cancelled`` (caller asked to stop).
    """

    def __init__(self, kind: str, message: str):
        super().__init__(message)
        self.kind = kind
        self.message = message


@dataclass
class Session:
    """A logged-in session: token + shop id. Memory only."""

    token: str
    sid: str = "50"


# ---------------------------------------------------------------------------
# Encoding helpers (PROTOCOL.md section 4.5: the conversions happen inside
# the crypto layer; callers only pass hex strings and Base64 strings).
# ---------------------------------------------------------------------------

def hex_decode(text: str) -> bytes:
    """Hex string -> bytes. Raises ValueError on bad input."""
    if not isinstance(text, str) or len(text) % 2 != 0 or not text:
        raise ValueError("bad hex string")
    return bytes.fromhex(text.strip().lower())


def aes_decrypt_b64(payload_b64: str, key_hex: str, iv_hex: str) -> bytes:
    """Base64 ciphertext -> plaintext bytes with AES-CBC + PKCS#7.

    Key length selects the variant: 16 bytes -> AES-128, 32 bytes -> AES-256.
    Raises ValueError on bad input / bad padding / wrong length.
    """
    key = hex_decode(key_hex)
    iv = hex_decode(iv_hex)
    if len(key) not in (16, 32):
        raise ValueError("AES key must be 16 or 32 bytes")
    if len(iv) != 16:
        raise ValueError("AES IV must be 16 bytes")
    try:
        cipher_bytes = base64.b64decode(payload_b64, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise ValueError("payload is not valid Base64: %s" % exc)
    if not cipher_bytes or len(cipher_bytes) % AES.block_size != 0:
        raise ValueError("ciphertext length is not a multiple of 16")
    cipher = AES.new(key, AES.MODE_CBC, iv)
    return unpad(cipher.decrypt(cipher_bytes), AES.block_size)


def aes_encrypt_b64(plaintext: bytes, key_hex: str, iv_hex: str) -> str:
    """Test helper: plaintext -> Base64 ciphertext (AES-CBC + PKCS#7)."""
    from Crypto.Util.Padding import pad

    key = hex_decode(key_hex)
    iv = hex_decode(iv_hex)
    cipher = AES.new(key, AES.MODE_CBC, iv)
    return base64.b64encode(cipher.encrypt(pad(plaintext, AES.block_size))).decode("ascii")


def normalize_ms(value) -> int:
    """Normalize a timestamp to integer milliseconds (DPGCompat.m
    DPGNormalizeMilliseconds): <= 0 -> 0; < 1e12 is seconds -> *1000;
    otherwise already milliseconds."""
    try:
        num = float(value)
    except (TypeError, ValueError):
        return 0
    if num <= 0:
        return 0
    if num < 1e12:
        return int(num * 1000)
    return int(num)


def amount_yuan_from_cents(value) -> float:
    """Order amount in cents -> yuan."""
    try:
        return float(value) / 100.0
    except (TypeError, ValueError):
        return 0.0


def _parse_code(obj) -> int | None:
    """Read the business ``code`` as an int (number or numeric string);
    None when missing or malformed (PROTOCOL.md section 1)."""
    if not isinstance(obj, dict):
        return None
    code = obj.get("code")
    if isinstance(code, bool):
        return None
    if isinstance(code, int):
        return code
    if isinstance(code, float) and code.is_integer():
        return int(code)
    if isinstance(code, str):
        try:
            return int(code.strip())
        except ValueError:
            try:
                as_float = float(code.strip())
            except ValueError:
                return None
            return int(as_float) if as_float.is_integer() else None
    return None


# ---------------------------------------------------------------------------
# Client
# ---------------------------------------------------------------------------

@dataclass
class ApiClient:
    """Stateless-ish client; the live session lives in ``self.session``."""

    aes_key_hex: str
    aes_iv_hex: str
    aes_key_hex_alt: str = ""
    aes_iv_hex_alt: str = ""
    verify_tls: bool = True
    http: object = None  # injectable transport (requests.Session compatible)
    session: Session | None = None
    _lock: threading.Lock = field(default_factory=threading.Lock, init=False, repr=False)

    def __post_init__(self):
        if self.http is None:
            self.http = requests.Session()

    # -- decryption -----------------------------------------------------

    def _decrypt_response_text(self, payload: str) -> dict:
        """Decrypt one Base64 response payload and parse the inner JSON.
        Tries the primary key pair, then the alternate pair."""
        attempts = [(self.aes_key_hex, self.aes_iv_hex)]
        if self.aes_key_hex_alt and self.aes_iv_hex_alt:
            attempts.append((self.aes_key_hex_alt, self.aes_iv_hex_alt))
        last_error: Exception | None = None
        for key_hex, iv_hex in attempts:
            try:
                plain = aes_decrypt_b64(payload, key_hex, iv_hex)
                inner = json.loads(plain.decode("utf-8"))
            except Exception as exc:  # noqa: BLE001 - try the next pair
                last_error = exc
                continue
            if not isinstance(inner, dict):
                raise ApiError("api", "decrypted response is not a JSON object")
            return inner
        raise ApiError("api", "response decryption failed: %s" % last_error)

    def decode_response(self, raw: bytes) -> dict:
        """Decode one HTTP response body per PROTOCOL.md section 4.2, with
        one documented merge: when the ``response`` string does not parse as
        plain JSON, AES decryption is attempted as well (the PC tool
        decrypts unconditionally; DPGrabber only decrypts when
        ``isEncrypted`` is set). Returns the inner dict and enforces the
        ``code`` rules (missing code -> error, 401/403 -> auth, code 1 +
        "IP" in msg -> IP control)."""
        try:
            outer = json.loads(raw.decode("utf-8"))
        except (ValueError, UnicodeDecodeError) as exc:
            raise ApiError("api", "response is not valid JSON: %s" % exc)
        if not isinstance(outer, dict):
            raise ApiError("api", "response must be a JSON object")

        envelope = outer.get("response")
        encrypted = bool(outer.get("isEncrypted"))
        inner = None
        if isinstance(envelope, dict):
            inner = envelope
        elif isinstance(envelope, str) and envelope:
            if encrypted:
                inner = self._decrypt_response_text(envelope)
            else:
                try:
                    parsed = json.loads(envelope)
                except ValueError:
                    parsed = None
                if isinstance(parsed, dict):
                    inner = parsed
                else:
                    # Plain parse failed: fall back to AES decryption.
                    inner = self._decrypt_response_text(envelope)
        if inner is None:
            inner = outer

        code = _parse_code(inner)
        if code is None:
            raise ApiError("api", "response has no valid business code")
        if code in (401, 403):
            raise ApiError("auth", "login state invalid (code %d)" % code)
        msg = inner.get("msg") or inner.get("message") or ""
        if code == 1 and isinstance(msg, str) and "IP" in msg:
            raise ApiError("ip", "IP control: %s" % msg)
        return inner

    # -- transport ------------------------------------------------------

    def request(
        self,
        method: str,
        path: str,
        session: Session | None = None,
        body=None,
        body_type: str = "none",
        extra_headers: dict | None = None,
        timeout: float = 15,
    ) -> dict:
        """One API call. Raises ApiError on any failure."""
        if not path.startswith("/s/") or timeout <= 0:
            raise ApiError("api", "invalid request path or timeout")
        url = BASE_URL + path
        headers = {
            "User-Agent": USER_AGENT,
            "api-version": API_VERSION,
            "platform": "app",
            "Accept": "*/*",
            "Accept-Language": "zh-CN,zh-Hans;q=0.9",
        }
        use_session = session if session is not None else self.session
        if use_session is not None and use_session.token:
            headers["authorization-token"] = use_session.token
            headers["sid"] = use_session.sid
        for key, value in (extra_headers or {}).items():
            headers[key] = value

        data = None
        if body is not None and body_type == "json":
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        elif body is not None and body_type == "form":
            pairs = []
            for key in sorted(body.keys()):
                pairs.append(
                    "%s=%s"
                    % (
                        quote(str(key), safe=_FORM_SAFE),
                        quote(str(body[key]), safe=_FORM_SAFE),
                    )
                )
            data = "&".join(pairs).encode("utf-8")
            headers["Content-Type"] = "application/x-www-form-urlencoded"

        try:
            resp = self.http.request(
                method, url, headers=headers, data=data,
                timeout=max(0.1, timeout), verify=self.verify_tls,
            )
        except Exception as exc:  # noqa: BLE001 - requests errors
            raise ApiError("network", "request failed: %s" % exc)
        status = getattr(resp, "status_code", 200)
        if status in (401, 403):
            raise ApiError("auth", "login state invalid (HTTP %d)" % status)
        if status >= 400:
            raise ApiError("network", "HTTP %d" % status)
        raw = resp.content if isinstance(resp.content, bytes) else resp.content.encode("utf-8")
        return self.decode_response(raw)

    # -- login flow (PROTOCOL.md section 3.2) ---------------------------

    def login_account(self, phone: str, password: str) -> Session:
        """POST /s/duopei-account/login. Returns (token, sid "50")."""
        phone = (phone or "").strip()
        secret = (password or "").strip()
        if not phone or not secret:
            raise ApiError("api", "phone and password are required")
        body = {
            "method": "password",
            "phoneNumber": {"regionCode": "CN", "mobileDial": "86", "mobile": phone},
            "password": secret,
        }
        resp = self.request(
            "POST", "/s/duopei-account/login", session=None,
            body=body, body_type="json",
            extra_headers={"sid": "-1", "authorization-token": ""},
            timeout=15,
        )
        if resp.get("code") != 0:
            raise ApiError("auth", "login rejected: %s" % (resp.get("msg") or resp.get("message") or ""))
        data = resp.get("data") if isinstance(resp.get("data"), dict) else {}
        token = data.get("token") if isinstance(data.get("token"), str) else ""
        if not token:
            raise ApiError("auth", "login response has no token")
        return Session(token=token, sid="50")

    def session_for_account(
        self, phone: str, password: str, preferred_shop_id: int = 92
    ) -> Session:
        """Full manual login: login -> list shops -> switch per shop ->
        getBalance validation. Returns a usable session or raises ApiError.

        Auth / IP / cancelled failures abort immediately; other per-shop
        failures move on to the next shop (DPGAPIClient.m:298-361).
        """
        base = self.login_account(phone, password)
        resp = self.request(
            "GET", "/s/duopei-account/list-shop-account", session=base,
            extra_headers={"sid": "50"}, timeout=15,
        )
        if resp.get("code") != 0:
            raise ApiError("api", "shop list rejected")
        shops = resp.get("data") if isinstance(resp.get("data"), list) else []
        ordered = [s for s in shops if isinstance(s, dict) and _shop_id(s) == preferred_shop_id]
        ordered += [s for s in shops if isinstance(s, dict) and s not in ordered]
        last_error: ApiError | None = None
        for shop in ordered:
            account_id = str(shop.get("accountId") or "")
            sid = str(shop.get("shopId") or "")
            if not account_id or not sid:
                continue
            try:
                switched = self.request(
                    "POST", "/s/duopei-account/switch-account", session=base,
                    body={"accountId": account_id}, body_type="form",
                    extra_headers={"sid": "50"}, timeout=15,
                )
            except ApiError as exc:
                if exc.kind in ("auth", "ip", "cancelled"):
                    raise
                last_error = exc
                continue
            if switched.get("code") != 0:
                continue
            data = switched.get("data") if isinstance(switched.get("data"), dict) else {}
            token = data.get("token") if isinstance(data.get("token"), str) else ""
            if not token:
                continue
            candidate = Session(token=token, sid=sid)
            try:
                balance = self.request(
                    "GET", "/s/balance/getBalance", session=candidate, timeout=15
                )
            except ApiError as exc:
                if exc.kind in ("ip", "cancelled"):
                    raise
                last_error = exc
                continue
            if balance and balance.get("code") == 0:
                with self._lock:
                    self.session = candidate
                return candidate
        raise ApiError(
            "auth",
            "no usable shop session: %s" % (last_error.message if last_error else "all shops rejected"),
        )

    # -- business calls -------------------------------------------------

    def clerk_get(self, session: Session | None = None) -> dict:
        """GET /s/c/clerk/get. Returns the raw decoded dict; callers use
        extract_clerk() to find the clerk object."""
        return self.request("GET", "/s/c/clerk/get", session=session, timeout=15)

    def random_list(self, page_size: int = 20, timeout: float = 15) -> list:
        """GET /s/c/order/randomList. Returns the order list (possibly empty).
        A missing list is treated as empty, not fatal, so polling continues."""
        resp = self.request(
            "GET",
            "/s/c/order/randomList?pageNo=1&pageSize=%d" % page_size,
            timeout=timeout,
        )
        orders = resp.get("list")
        if orders is None and isinstance(resp.get("data"), dict):
            orders = resp["data"].get("list")
        if orders is None:
            return []
        return orders if isinstance(orders, list) else []

    def order_get(self, order_id: str, timeout: float = 10) -> dict:
        """POST /s/c/order/get?id=... (PC tool form, used for the precise
        grab time). Returns the decoded dict; the order object is in
        ``order``."""
        path = "/s/c/order/get?id=%s" % quote(str(order_id), safe=_FORM_SAFE)
        return self.request("POST", path, timeout=timeout)

    def order_confirm(self, order_id: str, timeout: float = 10) -> dict:
        """POST /s/c/order/confirm?id=... . Returns the decoded dict."""
        path = "/s/c/order/confirm?id=%s" % quote(str(order_id), safe=_FORM_SAFE)
        return self.request("POST", path, timeout=timeout)


def _shop_id(shop: dict) -> int:
    try:
        return int(shop.get("shopId"))
    except (TypeError, ValueError):
        return -1


def extract_clerk(decoded: dict) -> dict | None:
    """Find the clerk object in a clerk/get response: try ``response.clerk``,
    ``response.data`` and the top level, in that order; take the first dict
    with a positive integer ``id`` (PROTOCOL.md section 2.1.6)."""
    candidates = []
    if isinstance(decoded, dict):
        for key in ("clerk", "data"):
            value = decoded.get(key)
            if isinstance(value, dict):
                candidates.append(value)
        candidates.append(decoded)
    for cand in candidates:
        try:
            clerk_id = int(cand.get("id"))
        except (TypeError, ValueError):
            continue
        if clerk_id > 0:
            return cand
    return None
