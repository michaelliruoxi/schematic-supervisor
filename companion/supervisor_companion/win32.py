"""Small Windows integrations. Each one is a harmless no-op elsewhere or when a call fails."""

from __future__ import annotations

import ctypes
import sys
from typing import Callable

IS_WINDOWS = sys.platform == "win32"
MUTEX_NAME = "Local\\SchematicSupervisorMonitor"
_ERROR_ALREADY_EXISTS = 183
_THEME_KEY = r"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize"

if IS_WINDOWS:
    from ctypes import wintypes

    class _FlashInfo(ctypes.Structure):
        _fields_ = [("cbSize", wintypes.UINT), ("hwnd", wintypes.HWND), ("dwFlags", wintypes.DWORD),
                    ("uCount", wintypes.UINT), ("dwTimeout", wintypes.DWORD)]

    class _MonitorInfo(ctypes.Structure):
        _fields_ = [("cbSize", wintypes.DWORD), ("rcMonitor", wintypes.RECT), ("rcWork", wintypes.RECT),
                    ("dwFlags", wintypes.DWORD)]


def enable_dpi_awareness() -> None:
    """System DPI awareness keeps text crisp; call before creating the Tk root."""
    if not IS_WINDOWS:
        return
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(1)
    except (AttributeError, OSError):
        try:
            ctypes.windll.user32.SetProcessDPIAware()
        except (AttributeError, OSError):
            pass


class SingleInstance:
    """A named mutex; `acquired` is False when another monitor already holds it."""

    def __init__(self, name: str = MUTEX_NAME) -> None:
        self.acquired = True
        self._handle = None
        if not IS_WINDOWS:
            return
        self._kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        self._kernel32.CreateMutexW.restype = wintypes.HANDLE
        self._kernel32.CreateMutexW.argtypes = [wintypes.LPVOID, wintypes.BOOL, wintypes.LPCWSTR]
        self._kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
        handle = self._kernel32.CreateMutexW(None, False, name)
        if handle and ctypes.get_last_error() == _ERROR_ALREADY_EXISTS:
            self.acquired = False
        self._handle = handle

    def release(self) -> None:
        if self._handle:
            self._kernel32.CloseHandle(self._handle)
            self._handle = None


def toplevel_handle(widget_id: int) -> int:
    """The frame window that owns a Tk widget; the taskbar button belongs to it."""
    if not IS_WINDOWS or not widget_id:
        return 0
    user32 = ctypes.windll.user32
    user32.GetAncestor.restype = wintypes.HWND
    user32.GetAncestor.argtypes = [wintypes.HWND, wintypes.UINT]
    return user32.GetAncestor(widget_id, 2) or widget_id


def flash_window(hwnd: int) -> None:
    """Flash the taskbar button until the window comes to the foreground."""
    if not IS_WINDOWS or not hwnd:
        return
    info = _FlashInfo(ctypes.sizeof(_FlashInfo), hwnd, 0x3 | 0xC, 0, 0)
    ctypes.windll.user32.FlashWindowEx(ctypes.byref(info))


def beep() -> None:
    if not IS_WINDOWS:
        return
    import winsound

    try:
        winsound.MessageBeep(winsound.MB_ICONEXCLAMATION)
    except RuntimeError:  # raised when the system cannot play the sound, e.g. without an audio device
        pass


def screen_work_areas() -> list[tuple[int, int, int, int]]:
    if not IS_WINDOWS:
        return []
    areas: list[tuple[int, int, int, int]] = []
    user32 = ctypes.windll.user32
    callback_type = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HMONITOR, wintypes.HDC,
                                       ctypes.POINTER(wintypes.RECT), wintypes.LPARAM)

    def collect(monitor, _dc, _rect, _data):
        info = _MonitorInfo()
        info.cbSize = ctypes.sizeof(_MonitorInfo)
        # A bare int would be passed as a 32-bit C int; the handle is pointer-sized.
        if user32.GetMonitorInfoW(wintypes.HMONITOR(monitor), ctypes.byref(info)):
            work = info.rcWork
            areas.append((work.left, work.top, work.right, work.bottom))
        return True

    try:
        user32.EnumDisplayMonitors(None, None, callback_type(collect), 0)
    except OSError:
        return []
    return areas


def primary_work_area(default: tuple[int, int, int, int] = (0, 0, 1920, 1080)) -> tuple[int, int, int, int]:
    if not IS_WINDOWS:
        return default
    rect = wintypes.RECT()
    if ctypes.windll.user32.SystemParametersInfoW(0x0030, 0, ctypes.byref(rect), 0):
        return rect.left, rect.top, rect.right, rect.bottom
    return default


def _read_light_theme() -> int:
    import winreg

    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, _THEME_KEY) as key:
        value, _kind = winreg.QueryValueEx(key, "AppsUseLightTheme")
    return int(value)


def apps_use_light_theme(reader: Callable[[], int] = _read_light_theme) -> bool:
    if not IS_WINDOWS:
        return True
    try:
        return reader() != 0
    except (OSError, ValueError, TypeError):
        return True


def use_dark_title_bar(hwnd: int) -> None:
    if not IS_WINDOWS or not hwnd:
        return
    value = ctypes.c_int(1)
    try:
        dwmapi = ctypes.windll.dwmapi
        for attribute in (20, 19):  # DWMWA_USE_IMMERSIVE_DARK_MODE, and its value before Windows 10 20H1
            if dwmapi.DwmSetWindowAttribute(wintypes.HWND(hwnd), attribute, ctypes.byref(value),
                                            ctypes.sizeof(value)) == 0:
                return
    except (AttributeError, OSError):
        return
