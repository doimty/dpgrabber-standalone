"""DPGrabber PC - spoken announcements (optional).

Uses pyttsx3 when it is installed and usable; otherwise every call is a
silent no-op and the program keeps working. Speech runs on its own thread
so it never blocks listening.
"""

from __future__ import annotations

import queue
import threading


class Voice:
    def __init__(self, enabled: bool = True):
        self.enabled = bool(enabled)
        self._queue: queue.Queue[str] = queue.Queue()
        self._engine = None
        self._thread: threading.Thread | None = None
        self._available = False
        try:
            import pyttsx3

            self._engine = pyttsx3.init()
            self._available = True
        except Exception:
            self._engine = None
            self._available = False

    def start(self):
        if not self._available or self._thread is not None:
            return
        self._thread = threading.Thread(
            target=self._pump, daemon=True, name="dpgrabber-voice"
        )
        self._thread.start()

    def _pump(self):
        while True:
            text = self._queue.get()
            if text is None:
                return
            try:
                self._engine.say(text)
                self._engine.runAndWait()
            except Exception:
                # Speech must never break listening.
                continue

    def speak(self, text: str):
        if not self.enabled or not self._available or not text:
            return
        if self._thread is None:
            self.start()
        try:
            self._queue.put_nowait(text)
        except queue.Full:
            pass

    def stop(self):
        try:
            self._queue.put_nowait(None)
        except queue.Full:
            pass
