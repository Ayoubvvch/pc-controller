"""
PC Remote Control Server
Lightweight HTTP Server for Remote PC Control via Local Network.
Zero dependencies - Standard Python Library only.
"""

import http.server
import json
import os
import platform
import subprocess
import sys
import threading
import time
import socket
import urllib.parse
import re
import base64
import tempfile
import uuid
from datetime import datetime, timedelta

# Ensure safe stdout/stderr on Windows (especially when run via pythonw.exe)
log_file_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "server.log")
if sys.stdout is None:
    try:
        sys.stdout = open(log_file_path, "a", encoding="utf-8", buffering=1)
    except Exception:
        pass
elif sys.platform == 'win32':
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

if sys.stderr is None:
    try:
        sys.stderr = open(log_file_path, "a", encoding="utf-8", buffering=1)
    except Exception:
        pass
elif sys.platform == 'win32':
    try:
        sys.stderr.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

PORT = 5050

def get_local_ips():
    ips = []
    try:
        hostname = socket.gethostname()
        for ip in socket.gethostbyname_ex(hostname)[2]:
            if not ip.startswith("127."):
                ips.append(ip)
    except Exception:
        pass
    return ips

# Media & Custom Automation Config
MPV_PATH = r"C:\Apps\mpv\mpv.exe"
RAIN_VIDEO = r"F:\ZPCController\Rain.mp4"
MESSI_VIDEO = r"F:\ZPCController\Messi.mp4"

active_sleep_timer = None
active_sleep_details = None
pending_confirmation = None

# ---------------- Windows Clipboard Synchronization ----------------
import ctypes
from ctypes import wintypes

CF_UNICODETEXT = 13
GMEM_MOVEABLE = 0x0002

user32 = ctypes.windll.user32
kernel32 = ctypes.windll.kernel32

user32.OpenClipboard.argtypes = [wintypes.HWND]
user32.OpenClipboard.restype = wintypes.BOOL

user32.CloseClipboard.argtypes = []
user32.CloseClipboard.restype = wintypes.BOOL

user32.EmptyClipboard.argtypes = []
user32.EmptyClipboard.restype = wintypes.BOOL

user32.GetClipboardData.argtypes = [wintypes.UINT]
user32.GetClipboardData.restype = wintypes.HANDLE

user32.SetClipboardData.argtypes = [wintypes.UINT, wintypes.HANDLE]
user32.SetClipboardData.restype = wintypes.HANDLE

kernel32.GlobalAlloc.argtypes = [wintypes.UINT, ctypes.c_size_t]
kernel32.GlobalAlloc.restype = wintypes.HGLOBAL

kernel32.GlobalLock.argtypes = [wintypes.HGLOBAL]
kernel32.GlobalLock.restype = wintypes.LPVOID

kernel32.GlobalUnlock.argtypes = [wintypes.HGLOBAL]
kernel32.GlobalUnlock.restype = wintypes.BOOL

def open_clipboard_retry(max_retries=10, delay=0.02):
    for _ in range(max_retries):
        if user32.OpenClipboard(None):
            return True
        time.sleep(delay)
    return False

def get_win_clipboard():
    if not open_clipboard_retry():
        return None
    try:
        handle = user32.GetClipboardData(CF_UNICODETEXT)
        if not handle:
            return ""
        ptr = kernel32.GlobalLock(handle)
        if not ptr:
            return ""
        try:
            val = ctypes.c_wchar_p(ptr).value
            return val if val is not None else ""
        finally:
            kernel32.GlobalUnlock(handle)
    finally:
        user32.CloseClipboard()

def set_win_clipboard(text):
    if text is None:
        text = ""
    if not open_clipboard_retry():
        return False
    try:
        user32.EmptyClipboard()
        raw = (text + '\0').encode('utf-16le')
        hMem = kernel32.GlobalAlloc(GMEM_MOVEABLE, len(raw))
        if not hMem:
            return False
        ptr = kernel32.GlobalLock(hMem)
        if not ptr:
            return False
        try:
            ctypes.memmove(ptr, raw, len(raw))
        finally:
            kernel32.GlobalUnlock(hMem)
        user32.SetClipboardData(CF_UNICODETEXT, hMem)
        return True
    finally:
        user32.CloseClipboard()

CLIPBOARD_HISTORY_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "clipboard_history.json")
clipboard_history = []
clipboard_history_lock = threading.Lock()

def load_clipboard_history():
    global clipboard_history
    if os.path.exists(CLIPBOARD_HISTORY_FILE):
        try:
            with open(CLIPBOARD_HISTORY_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
                if isinstance(data, list):
                    clipboard_history = data
        except Exception:
            clipboard_history = []

def save_clipboard_history():
    try:
        with open(CLIPBOARD_HISTORY_FILE, "w", encoding="utf-8") as f:
            json.dump(clipboard_history[:50], f, ensure_ascii=False, indent=2)
    except Exception:
        pass

def add_clipboard_history_item(text):
    global clipboard_history
    if not text or not text.strip():
        return
    with clipboard_history_lock:
        clipboard_history = [item for item in clipboard_history if item.get("text") != text]
        item = {
            "text": text,
            "time": time.strftime("%I:%M %p"),
            "timestamp": time.time(),
            "len": len(text)
        }
        clipboard_history.insert(0, item)
        clipboard_history = clipboard_history[:50]
        save_clipboard_history()

load_clipboard_history()

clipboard_lock = threading.Lock()
clipboard_cond = threading.Condition(clipboard_lock)
current_clipboard_text = ""
clipboard_version = 1

def win_clipboard_watcher():
    global current_clipboard_text, clipboard_version
    try:
        init_txt = get_win_clipboard()
        if init_txt:
            current_clipboard_text = init_txt
            add_clipboard_history_item(init_txt)
    except Exception:
        pass

    while True:
        try:
            time.sleep(0.4)
            txt = get_win_clipboard()
            if txt is not None and txt != current_clipboard_text:
                with clipboard_cond:
                    current_clipboard_text = txt
                    clipboard_version += 1
                    clipboard_cond.notify_all()
                if txt.strip():
                    add_clipboard_history_item(txt)
                preview = txt[:35].replace('\n', ' ').strip()
                print(f"[CLIPBOARD] Windows clipboard updated (v{clipboard_version}, len={len(txt)}): '{preview}'")
        except Exception:
            pass

threading.Thread(target=win_clipboard_watcher, daemon=True).start()

def kill_mpv():
    """Closes any running mpv instances."""
    try:
        subprocess.run(["taskkill", "/f", "/im", "mpv.exe"], capture_output=True,
                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        subprocess.run(["taskkill", "/f", "/im", "mpv.com"], capture_output=True,
                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    except Exception:
        pass

def parse_duration_seconds(duration_str, default_sec=15 * 60):
    """Parses durations like '15min', '15m', '1h', '30s', '45', 'بعد 15 دقيقة', 'in 30m' into seconds."""
    import re
    s = duration_str.strip().lower()
    for prefix in ["after", "in", "بعد", "خلال", "في"]:
        if s.startswith(prefix + " "):
            s = s[len(prefix):].strip()
            
    if not s:
        return default_sec
        
    if s in ["ساعة", "ساعه", "1 ساعة", "hour", "1 hour", "1h"]:
        return 3600
    if s in ["ساعتين", "ساعتان", "2 hours", "2h"]:
        return 7200
    if s in ["نصف ساعة", "نص ساعة", "نصف ساعه", "نص ساعه", "half hour", "30min", "30m"]:
        return 1800
    if s in ["ثلث ساعة", "ثلث ساعه", "20min", "20m"]:
        return 1200
    if s in ["ربع ساعة", "ربع ساعه", "15min", "15m"]:
        return 900

    m = re.match(r"^([\d\.]+)\s*(h|hr|hours?|m|min|minutes?|s|sec|seconds?|دقيقة|دقائق|ساعة|ساعات|ثانية|ثواني)?$", s)
    if m:
        val = float(m.group(1))
        unit = (m.group(2) or "m").lower()
        if unit in ["h", "hr", "hour", "hours", "ساعة", "ساعات"]:
            return int(val * 3600)
        elif unit in ["s", "sec", "second", "seconds", "ثانية", "ثواني"]:
            return int(val)
        else:
            return int(val * 60)
    return default_sec

def format_duration(seconds):
    """Formats seconds into readable English string."""
    if seconds >= 3600:
        hrs = seconds / 3600
        return f"{hrs:.1f} hours" if hrs % 1 != 0 else (f"1 hour" if int(hrs) == 1 else f"{int(hrs)} hours")
    elif seconds >= 60:
        mins = seconds // 60
        rem_sec = seconds % 60
        if rem_sec > 0:
            return f"{mins} min {rem_sec} sec"
        return f"{mins} minutes" if mins > 1 else "1 minute"
    else:
        return f"{seconds} seconds"

# ---------------- Absolute Persistent Power Scheduler ----------------
POWER_STATE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "power_timer.json")
active_power_thread = None
active_power_cancel_event = threading.Event()

def execute_system_power(action):
    """Executes forced, absolute system power action (sleep, hibernate, shutdown, restart)."""
    global active_sleep_details
    kill_mpv()
    print(f"[POWER] Executing forced power action: {action}")
    
    # 1. Unconditionally stop active media / audio streams so Windows won't block sleep
    try:
        user32.keybd_event(0xB3, 0, 0, 0) # VK_MEDIA_PLAY_PAUSE down
        user32.keybd_event(0xB3, 0, 2, 0) # VK_MEDIA_PLAY_PAUSE up
    except Exception:
        pass

    # Cancel secondary scheduled task if one existed
    try:
        subprocess.run(['schtasks', '/Delete', '/TN', 'PCRemote_OneShotPower', '/F'],
                       capture_output=True,
                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    except Exception:
        pass

    # 2. Execute power command with FORCE flags
    if action == "shutdown":
        subprocess.run(["shutdown", "/s", "/f", "/t", "0", "/c", "PC Remote Absolute Shutdown"],
                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    elif action == "restart":
        subprocess.run(["shutdown", "/r", "/f", "/t", "0", "/c", "PC Remote Absolute Restart"],
                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    elif action == "hibernate":
        subprocess.run(["shutdown", "/h"],
                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    elif action == "sleep":
        # First try ctypes SetSuspendState(0, 1, 0) -> Suspend=0, Force=1, DisableWake=0
        try:
            ctypes.windll.powrprof.SetSuspendState(0, 1, 0)
        except Exception:
            pass
        # Backup: PowerShell forced suspend
        ps_cmd = (
            "Add-Type -AssemblyName System.Windows.Forms; "
            "[System.Windows.Forms.Application]::SetSuspendState([System.Windows.Forms.PowerState]::Suspend, $true, $false)"
        )
        subprocess.run(["powershell", "-NoProfile", "-Command", ps_cmd],
                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)

    # 3. Clean up persistent timer file
    if os.path.exists(POWER_STATE_FILE):
        try:
            os.remove(POWER_STATE_FILE)
        except Exception:
            pass
    active_sleep_details = None

def schedule_power_timer(action, seconds, human_text):
    """Schedules an absolute, persistent power timer with OS-level redundancy."""
    global active_power_thread, active_power_cancel_event, active_sleep_details
    
    # Cancel previous timers
    cancel_power_timer()
    
    target_epoch = time.time() + seconds
    active_power_cancel_event.clear()
    
    state = {
        "action": action,
        "seconds": seconds,
        "target_epoch": target_epoch,
        "human_text": human_text,
        "created_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    }
    try:
        with open(POWER_STATE_FILE, "w", encoding="utf-8") as f:
            json.dump(state, f, ensure_ascii=False, indent=2)
    except Exception as e:
        print(f"[POWER] Error saving power state: {e}")

    active_sleep_details = state

    # OS-level kernel backup for shutdown & restart
    if action == "shutdown":
        subprocess.run(["shutdown", "/s", "/f", "/t", str(seconds), "/c", f"Scheduled Shutdown in {human_text}"],
                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    elif action == "restart":
        subprocess.run(["shutdown", "/r", "/f", "/t", str(seconds), "/c", f"Scheduled Restart in {human_text}"],
                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    elif action in ["sleep", "hibernate"]:
        # Schedule one-shot Windows Task Scheduler as secondary OS backup
        try:
            target_dt = datetime.now() + timedelta(seconds=seconds)
            st_time = target_dt.strftime("%H:%M:%S")
            sd_date = target_dt.strftime("%d/%m/%Y")
            if action == "sleep":
                tr_cmd = 'powershell -NoProfile -Command "Add-Type -AssemblyName System.Windows.Forms; [System.Windows.Forms.Application]::SetSuspendState([System.Windows.Forms.PowerState]::Suspend, $true, $false)"'
            else:
                tr_cmd = 'shutdown /h'
            subprocess.run(
                ['schtasks', '/Create', '/TN', 'PCRemote_OneShotPower', '/TR', tr_cmd, '/SC', 'ONCE', '/ST', st_time, '/SD', sd_date, '/F'],
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0,
                capture_output=True
            )
        except Exception:
            pass

    # Background Python thread countdown
    def countdown_worker():
        while not active_power_cancel_event.is_set():
            remaining = target_epoch - time.time()
            if remaining <= 0:
                execute_system_power(action)
                break
            time.sleep(min(remaining, 1.0))

    active_power_thread = threading.Thread(target=countdown_worker, daemon=True, name=f"Power-{action}")
    active_power_thread.start()
    print(f"[POWER] Scheduled {action} in {human_text} ({seconds}s). Target: {datetime.fromtimestamp(target_epoch).strftime('%H:%M:%S')}")

def cancel_power_timer():
    """Cancels any scheduled power timer across OS kernel, Task Scheduler, and Python."""
    global active_power_cancel_event, active_sleep_details
    active_power_cancel_event.set()
    
    # Cancel Windows shutdown/restart if scheduled
    try:
        subprocess.run(["shutdown", "/a"], capture_output=True,
                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    except Exception:
        pass

    # Cancel one-shot scheduled task if scheduled
    try:
        subprocess.run(['schtasks', '/Delete', '/TN', 'PCRemote_OneShotPower', '/F'], capture_output=True,
                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    except Exception:
        pass

    # Remove persistent state file
    if os.path.exists(POWER_STATE_FILE):
        try:
            os.remove(POWER_STATE_FILE)
        except Exception:
            pass

    had_timer = active_sleep_details is not None
    active_sleep_details = None
    kill_mpv()
    return had_timer

def get_active_power_status():
    """Returns active timer info if one is currently scheduled."""
    global active_sleep_details
    if os.path.exists(POWER_STATE_FILE):
        try:
            with open(POWER_STATE_FILE, "r", encoding="utf-8") as f:
                state = json.load(f)
            remaining = int(state.get("target_epoch", 0) - time.time())
            if remaining > 0:
                state["remaining_seconds"] = remaining
                state["remaining_human"] = format_duration(remaining)
                active_sleep_details = state
                return state
        except Exception:
            pass
    return None

def init_power_scheduler():
    """Restores any active power timer saved on disk on server startup."""
    global active_power_thread, active_power_cancel_event, active_sleep_details
    if os.path.exists(POWER_STATE_FILE):
        try:
            with open(POWER_STATE_FILE, "r", encoding="utf-8") as f:
                state = json.load(f)
            target_epoch = state.get("target_epoch", 0)
            action = state.get("action", "sleep")
            remaining = target_epoch - time.time()
            if remaining > 0:
                print(f"[POWER] 🔄 Resuming active {action} timer! {int(remaining)}s ({format_duration(int(remaining))}) remaining...")
                active_sleep_details = state
                active_power_cancel_event.clear()
                def resume_worker():
                    while not active_power_cancel_event.is_set():
                        rem = target_epoch - time.time()
                        if rem <= 0:
                            execute_system_power(action)
                            break
                        time.sleep(min(rem, 1.0))
                active_power_thread = threading.Thread(target=resume_worker, daemon=True, name="PowerResume")
                active_power_thread.start()
            else:
                print(f"[POWER] ⚠️ Found expired {action} timer from disk! Executing now...")
                execute_system_power(action)
        except Exception as e:
            print(f"[POWER] Error restoring power scheduler: {e}")

# Volume Control via vol.exe (Windows CoreAudio API)
VOL_EXE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "vol.exe")

def set_system_volume(level):
    """Sets Windows master volume to a percentage (0-100)."""
    try:
        val = int(level)
        val = max(0, min(100, val))
        if os.path.exists(VOL_EXE):
            res = subprocess.run([VOL_EXE, str(val)], capture_output=True, text=True,
                                 creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            return val
    except Exception:
        pass
    return None

def get_system_volume():
    """Gets Windows master volume percentage."""
    try:
        if os.path.exists(VOL_EXE):
            res = subprocess.run([VOL_EXE], capture_output=True, text=True,
                                 creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            out = res.stdout.strip()
            if out.startswith("GET:"):
                return out.replace("GET:", "").strip()
    except Exception:
        pass
    return None

def toggle_system_mute():
    """Toggles system mute."""
    try:
        if os.path.exists(VOL_EXE):
            res = subprocess.run([VOL_EXE, "mute"], capture_output=True, text=True,
                                 creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            out = res.stdout.strip()
            return "MUTED:True" in out
    except Exception:
        pass
    return None

# Screenshot & Download Paths
SCREENSHOT_EXE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "screenshot.exe")
SCREENSHOTS_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "screenshots")
DOWNLOADS_DIR = os.path.join(os.path.expanduser("~"), "Downloads")

def format_size(num_bytes):
    """Formats bytes into readable string."""
    if num_bytes >= 1024 * 1024 * 1024:
        return f"{num_bytes / (1024 * 1024 * 1024):.2f} GB"
    elif num_bytes >= 1024 * 1024:
        return f"{num_bytes / (1024 * 1024):.1f} MB"
    elif num_bytes >= 1024:
        return f"{num_bytes / 1024:.1f} KB"
    else:
        return f"{num_bytes} B"

def take_screenshot(screen_target="all"):
    """Captures the PC screen (all or specific display) and returns the image details."""
    try:
        os.makedirs(SCREENSHOTS_DIR, exist_ok=True)
        filename = f"ss_{int(time.time())}.jpg"
        filepath = os.path.join(SCREENSHOTS_DIR, filename)

        if not os.path.exists(SCREENSHOT_EXE):
            return {
                "status": "error",
                "message": "❌ Screenshot utility (screenshot.exe) not found."
            }

        res = subprocess.run([SCREENSHOT_EXE, filepath, screen_target], capture_output=True, text=True,
                             creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)

        if res.returncode == 0 and os.path.exists(filepath):
            # Clean up old screenshots (keep latest 8)
            try:
                files = [os.path.join(SCREENSHOTS_DIR, f) for f in os.listdir(SCREENSHOTS_DIR) if f.startswith("ss_") and f.endswith(".jpg")]
                files.sort(key=os.path.getmtime)
                if len(files) > 8:
                    for old_file in files[:-8]:
                        try:
                            os.remove(old_file)
                        except Exception:
                            pass
            except Exception:
                pass

            file_size = os.path.getsize(filepath)
            screen_desc = "All Displays" if screen_target == "all" else f"Display {screen_target}"
            return {
                "status": "success",
                "type": "image",
                "image_url": f"/screenshot/{filename}",
                "filename": filename,
                "message": f"📸 Screenshot captured successfully from {screen_desc}! ({format_size(file_size)})"
            }
        else:
            err_msg = res.stderr.strip() or res.stdout.strip() or "Unknown failure"
            return {
                "status": "error",
                "message": f"❌ Could not capture screenshot: {err_msg}"
            }
    except Exception as e:
        return {
            "status": "error",
            "message": f"❌ Error during screen capture: {str(e)}"
        }

def is_drive_root(path):
    """Returns True if path is a drive root (e.g. C:\\ or D:) or empty."""
    if not path or not str(path).strip():
        return True
    norm = os.path.normpath(str(path)).rstrip("\\/")
    return (len(norm) <= 2 and norm.endswith(":")) or norm in ["\\", "/"]

def check_folder_size_safe(folder_path, max_scan_bytes=524288000, max_scan_files=20000):
    """
    Safely calculates folder size without following junctions or symlinks.
    Early breaks if size exceeds 500 MB (524,288,000 bytes) or 20,000 files to avoid locking CPU/disk.
    """
    if not folder_path or not os.path.exists(folder_path):
        return {"status": "error", "message": "Path does not exist"}
    if not os.path.isdir(folder_path):
        return {"status": "error", "message": "Path is not a folder"}

    if is_drive_root(folder_path):
        return {
            "status": "error",
            "is_drive": True,
            "message": "❌ Transferring an entire drive root is not allowed."
        }

    total_size = 0
    file_count = 0
    is_large = False

    try:
        for root, dirs, files in os.walk(folder_path, followlinks=False):
            # Exclude Windows system folders and reparse points/junctions
            dirs[:] = [d for d in dirs if not d.startswith('$') and d not in ["System Volume Information", "$RECYCLE.BIN"]]
            for f in files:
                if f.startswith('$'):
                    continue
                file_full = os.path.join(root, f)
                try:
                    if not os.path.islink(file_full):
                        total_size += os.path.getsize(file_full)
                        file_count += 1
                        if total_size >= max_scan_bytes:
                            is_large = True
                            break
                except (OSError, PermissionError):
                    pass
            if is_large or file_count >= max_scan_files:
                is_large = True
                break
    except Exception as e:
        print(f"[FOLDER_SIZE] Error scanning {folder_path}: {e}")

    folder_name = os.path.basename(os.path.normpath(folder_path)) or "Folder"
    size_str = format_size(total_size)
    if is_large:
        size_str = f"> {size_str}"

    return {
        "status": "success",
        "name": folder_name,
        "path": folder_path,
        "size_bytes": total_size,
        "size_str": size_str,
        "is_large": is_large,
        "is_drive": False,
        "file_count": file_count
    }

def browse_path(target_path=None):
    """Browses a directory on PC and returns folders, files, drives, and shortcuts."""
    import string
    home = os.path.expanduser("~")
    shortcuts = [
        {"name": "Downloads", "path": os.path.join(home, "Downloads"), "icon": "📥"},
        {"name": "Desktop", "path": os.path.join(home, "Desktop"), "icon": "🖥️"},
        {"name": "Documents", "path": os.path.join(home, "Documents"), "icon": "📄"},
        {"name": "Pictures", "path": os.path.join(home, "Pictures"), "icon": "🖼️"},
        {"name": "Videos", "path": os.path.join(home, "Videos"), "icon": "🎬"},
    ]
    if os.path.exists(r"F:\ZPCController"):
        shortcuts.append({"name": "ZPCController", "path": r"F:\ZPCController", "icon": "🎮"})

    drives = []
    for letter in string.ascii_uppercase:
        d = f"{letter}:\\"
        if os.path.exists(d):
            drives.append(d)

    if not target_path or not target_path.strip():
        target_path = os.path.join(home, "Downloads")

    target_path = os.path.abspath(target_path)
    if not os.path.exists(target_path) or not os.path.isdir(target_path):
        target_path = os.path.join(home, "Downloads")

    parent = os.path.dirname(target_path)
    if parent == target_path:
        parent = None

    folders = []
    files = []

    try:
        with os.scandir(target_path) as it:
            for entry in it:
                try:
                    name = entry.name
                    # Filter out system and hidden files
                    if name.startswith(".") or name.startswith("$") or name in [
                        "System Volume Information", "pagefile.sys", "hiberfil.sys", "swapfile.sys", "DumpStack.log"
                    ]:
                        continue
                    if entry.is_dir(follow_symlinks=False):
                        stat = entry.stat()
                        last_time = max(stat.st_mtime, getattr(stat, 'st_ctime', stat.st_mtime))
                        folders.append({
                            "name": name,
                            "path": entry.path,
                            "mtime": last_time
                        })
                    elif entry.is_file(follow_symlinks=False):
                        stat = entry.stat()
                        size = stat.st_size
                        last_time = max(stat.st_mtime, getattr(stat, 'st_ctime', stat.st_mtime))
                        date_str = datetime.fromtimestamp(last_time).strftime("%Y-%m-%d %I:%M %p")
                        ext = os.path.splitext(name)[1].lower()
                        files.append({
                            "name": name,
                            "path": entry.path,
                            "size": size,
                            "size_str": f"{format_size(size)} • {date_str}",
                            "ext": ext,
                            "mtime": last_time
                        })
                except (PermissionError, OSError):
                    continue
    except (PermissionError, OSError) as e:
        return {
            "status": "error",
            "message": f"Could not open directory: {str(e)}",
            "current_path": target_path,
            "parent_path": parent,
            "drives": drives,
            "shortcuts": shortcuts,
            "folders": [],
            "files": []
        }

    # Sort descending by latest modified/added time (newest first)
    folders.sort(key=lambda x: x.get("mtime", 0), reverse=True)
    files.sort(key=lambda x: x.get("mtime", 0), reverse=True)

    return {
        "status": "success",
        "current_path": target_path,
        "parent_path": parent,
        "drives": drives,
        "shortcuts": shortcuts,
        "folders": folders[:150],
        "files": files[:200]
    }

def execute_action(action_type, param=None):
    """Executes the requested action safely and returns a response message."""
    global active_sleep_timer, active_sleep_details, pending_confirmation
    cmd = action_type.strip().lower()
    
    # Delayed execution helper so response reaches the client before PC sleeps/shuts down
    def run_delayed(func, delay=1.0):
        def worker():
            time.sleep(delay)
            func()
        t = threading.Thread(target=worker, daemon=True)
        t.start()

    # Confirmation Handlers (yes / no)
    if cmd in ["yes", "y", "نعم", "ايوه", "ok"]:
        if pending_confirmation and (time.time() - pending_confirmation.get("time", 0) <= 60):
            action_to_do = pending_confirmation["action"]
            pending_confirmation = None
            
            if action_to_do == "sleep":
                run_delayed(lambda: execute_system_power("sleep"), 1.2)
                return {
                    "status": "success",
                    "message": "💤 Confirmed! Entering Sleep mode... Goodnight!"
                }

            elif action_to_do == "shutdown":
                run_delayed(lambda: execute_system_power("shutdown"), 1.2)
                return {
                    "status": "warning",
                    "message": "🛑 Confirmed! Shutting down the PC immediately."
                }

            elif action_to_do == "restart":
                run_delayed(lambda: execute_system_power("restart"), 1.2)
                return {
                    "status": "warning",
                    "message": "🔄 Confirmed! Restarting the PC immediately."
                }

            elif action_to_do == "hibernate":
                run_delayed(lambda: execute_system_power("hibernate"), 1.2)
                return {
                    "status": "success",
                    "message": "⚡ Confirmed! Entering Hibernate mode..."
                }
        else:
            pending_confirmation = None
            return {
                "status": "info",
                "message": "ℹ️ No pending action awaiting confirmation (or the 60-second window expired)."
            }

    elif cmd in ["no", "n", "لا", "رفض"]:
        if pending_confirmation:
            action_name = pending_confirmation["action"]
            pending_confirmation = None
            return {
                "status": "success",
                "message": f"✅ Cancelled '{action_name}' action successfully. Your PC is safe."
            }
        else:
            return {
                "status": "info",
                "message": "ℹ️ No pending action to cancel."
            }

    # ------------------ Timed Power Commands Detection ------------------
    is_timed_sleep = (
        (cmd.startswith("sleep ") or cmd.startswith("نوم ") or cmd.startswith("سكون ") or cmd.startswith("slp "))
        and not (cmd.startswith("sleep rain") or cmd.startswith("sleeprain"))
    )
    is_timed_shutdown = (
        cmd.startswith("shutdown ") or cmd.startswith("طفي ") or cmd.startswith("إطفاء ") or
        cmd.startswith("إيقاف ") or cmd.startswith("sd ") or cmd.startswith("poweroff ")
    )
    is_timed_hibernate = (
        cmd.startswith("hibernate ") or cmd.startswith("سبات ") or cmd.startswith("hib ")
    )
    is_timed_restart = (
        cmd.startswith("restart ") or cmd.startswith("reboot ") or cmd.startswith("إعادة تشغيل ")
    )

    if is_timed_sleep:
        dur_part = cmd
        for p in ["sleep", "نوم", "سكون", "slp"]:
            if dur_part.startswith(p + " "):
                dur_part = dur_part[len(p) + 1:].strip()
                break
        sec = parse_duration_seconds(dur_part, default_sec=15 * 60)
        dur_human = format_duration(sec)
        target_str = (datetime.now() + timedelta(seconds=sec)).strftime("%I:%M %p")
        schedule_power_timer("sleep", sec, dur_human)
        return {
            "status": "success",
            "message": (
                f"💤 Sleep scheduled successfully!\n\n"
                f"⏱️ PC will enter sleep mode in: {dur_human}.\n"
                f"🕒 Target time: {target_str}\n"
                f"🛡️ Persistent scheduler: active even if screen locks or server restarts.\n"
                f"💡 Send 'cancel' or 'stop' to abort anytime."
            )
        }

    elif is_timed_shutdown:
        dur_part = cmd
        for p in ["shutdown", "طفي", "إطفاء", "إيقاف", "sd", "poweroff"]:
            if dur_part.startswith(p + " "):
                dur_part = dur_part[len(p) + 1:].strip()
                break
        sec = parse_duration_seconds(dur_part, default_sec=15 * 60)
        dur_human = format_duration(sec)
        target_str = (datetime.now() + timedelta(seconds=sec)).strftime("%I:%M %p")
        schedule_power_timer("shutdown", sec, dur_human)
        return {
            "status": "success",
            "message": (
                f"🛑 Shutdown scheduled successfully!\n\n"
                f"⏱️ PC will shut down in: {dur_human}.\n"
                f"🕒 Target time: {target_str}\n"
                f"🛡️ Registered in Windows kernel and persistent scheduler.\n"
                f"💡 Send 'cancel' or 'stop' to abort anytime."
            )
        }

    elif is_timed_hibernate:
        dur_part = cmd
        for p in ["hibernate", "سبات", "hib"]:
            if dur_part.startswith(p + " "):
                dur_part = dur_part[len(p) + 1:].strip()
                break
        sec = parse_duration_seconds(dur_part, default_sec=15 * 60)
        dur_human = format_duration(sec)
        target_str = (datetime.now() + timedelta(seconds=sec)).strftime("%I:%M %p")
        schedule_power_timer("hibernate", sec, dur_human)
        return {
            "status": "success",
            "message": (
                f"⚡ Hibernate scheduled successfully!\n\n"
                f"⏱️ PC will hibernate in: {dur_human}.\n"
                f"🕒 Target time: {target_str}\n"
                f"🛡️ Registered in Task Scheduler and persistent scheduler.\n"
                f"💡 Send 'cancel' or 'stop' to abort anytime."
            )
        }

    elif is_timed_restart:
        dur_part = cmd
        for p in ["restart", "reboot", "إعادة تشغيل"]:
            if dur_part.startswith(p + " "):
                dur_part = dur_part[len(p) + 1:].strip()
                break
        sec = parse_duration_seconds(dur_part, default_sec=15 * 60)
        dur_human = format_duration(sec)
        target_str = (datetime.now() + timedelta(seconds=sec)).strftime("%I:%M %p")
        schedule_power_timer("restart", sec, dur_human)
        return {
            "status": "success",
            "message": (
                f"🔄 Restart scheduled successfully!\n\n"
                f"⏱️ PC will restart in: {dur_human}.\n"
                f"🕒 Target time: {target_str}\n"
                f"🛡️ Registered in Windows kernel and persistent scheduler.\n"
                f"💡 Send 'cancel' or 'stop' to abort anytime."
            )
        }

    # Immediate critical actions that require confirmation
    elif cmd in ["sleep", "نوم", "سكون", "suspend"]:
        pending_confirmation = {"action": "sleep", "time": time.time()}
        return {
            "status": "warning",
            "message": (
                "⚠️ Safety Confirmation:\nAre you sure you want to put the PC to Sleep now?\n\n"
                "👉 Send **yes** to confirm.\n"
                "👉 Send **no** to cancel.\n"
                "⏳ Timeout: 60 seconds."
            )
        }

    elif cmd in ["shutdown", "طفي", "إيقاف", "إطفاء"]:
        pending_confirmation = {"action": "shutdown", "time": time.time()}
        return {
            "status": "warning",
            "message": (
                "⚠️ Safety Confirmation:\nAre you sure you want to shut down the PC completely?\n\n"
                "👉 Send **yes** to confirm.\n"
                "👉 Send **no** to cancel.\n"
                "⏳ Timeout: 60 seconds."
            )
        }

    elif cmd in ["restart", "reboot", "إعادة تشغيل"]:
        pending_confirmation = {"action": "restart", "time": time.time()}
        return {
            "status": "warning",
            "message": (
                "⚠️ Safety Confirmation:\nAre you sure you want to restart the PC?\n\n"
                "👉 Send **yes** to confirm.\n"
                "👉 Send **no** to cancel.\n"
                "⏳ Timeout: 60 seconds."
            )
        }

    elif cmd in ["hibernate", "سبات"]:
        pending_confirmation = {"action": "hibernate", "time": time.time()}
        return {
            "status": "warning",
            "message": (
                "⚠️ Safety Confirmation:\nAre you sure you want to put the PC into Hibernate mode now?\n\n"
                "👉 Send **yes** to confirm.\n"
                "👉 Send **no** to cancel.\n"
                "⏳ Timeout: 60 seconds."
            )
        }

    elif (cmd.startswith("sleep rain") or cmd == "sleep rain" or 
          cmd.startswith("rain") or cmd.startswith("sr ") or cmd == "sr" or
          cmd.startswith("srm ") or cmd == "srm"):
        # Check if minimized desktop mode is requested
        is_minimized = (
            "minimized" in cmd or 
            "minimize" in cmd or 
            "min" in cmd or 
            "مصغر" in cmd or 
            "مخفي" in cmd or 
            cmd.startswith("srm")
        )

        # Parse duration (default 15 minutes)
        cleaned = cmd
        for word in ["sleep rain", "rain", "srm", "sr", "minimized", "minimize", "min", "مصغر", "مخفي"]:
            cleaned = cleaned.replace(word, "")
        dur_part = cleaned.strip()

        sec = parse_duration_seconds(dur_part, default_sec=15 * 60)
        dur_human = format_duration(sec)

        # Close any current mpv instance
        kill_mpv()

        # Set system volume to 28% (25-30% range)
        set_system_volume(28)

        if not os.path.exists(RAIN_VIDEO):
            return {
                "status": "error",
                "message": f"❌ Video file not found: {RAIN_VIDEO}"
            }

        mpv_exec = MPV_PATH if os.path.exists(MPV_PATH) else "mpv"

        if is_minimized:
            # Minimize all open windows so only the black desktop is shown
            subprocess.run(["powershell", "-NoProfile", "-Command", "(New-Object -ComObject Shell.Application).MinimizeAll()"],
                           creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            
            # Launch mpv minimized in background
            subprocess.Popen([mpv_exec, "--window-minimized=yes", "--loop-file=inf", RAIN_VIDEO],
                             creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            
            # Sweep again after 0.5s to ensure clean black desktop
            def sweep_minimize():
                time.sleep(0.5)
                subprocess.run(["powershell", "-NoProfile", "-Command", "(New-Object -ComObject Shell.Application).MinimizeAll()"],
                               creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            threading.Thread(target=sweep_minimize, daemon=True).start()
            mode_desc = "with all windows minimized (Desktop only)"
        else:
            # Standard fullscreen mode
            subprocess.Popen([mpv_exec, "--fs", "--loop-file=inf", RAIN_VIDEO],
                             creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            mode_desc = "in fullscreen looping mode"

        # Absolute persistent schedule for sleep
        schedule_power_timer("sleep", sec, dur_human)

        return {
            "status": "success",
            "message": (
                f"🌧️ Playing Rain.mp4 at 28% volume {mode_desc}!\n\n"
                f"⏱️ Duration: {dur_human}\n"
                f"💤 PC will enter Sleep mode when the timer completes.\n"
                f"💡 Send 'cancel' or 'stop' to abort anytime."
            )
        }

    elif cmd in ["messi", "ميسي", "m", "messi mp4", "messi.mp4"]:
        kill_mpv()

        # Set system volume to 16%
        set_system_volume(16)

        if not os.path.exists(MESSI_VIDEO):
            return {
                "status": "error",
                "message": f"❌ Video file not found: {MESSI_VIDEO}"
            }

        mpv_exec = MPV_PATH if os.path.exists(MPV_PATH) else "mpv"
        subprocess.Popen([mpv_exec, "--fs", "--loop-file=inf", MESSI_VIDEO],
                         creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        return {
            "status": "success",
            "message": "🐐⚽ Playing Messi.mp4 in looping fullscreen mode at 16% volume! 🔥\n💡 Send 'stop' to close video."
        }

    elif cmd in ["cancel", "abort", "إلغاء", "stop", "إيقاف", "close"]:
        had_timer = cancel_power_timer()
        kill_mpv()
        if had_timer:
            return {
                "status": "success",
                "message": "✅ Media player stopped and scheduled power timer cancelled successfully!"
            }
        else:
            return {
                "status": "success",
                "message": "✅ Media player closed and verified no scheduled timers."
            }

    elif cmd in ["lock", "قفل"]:
        def do_lock():
            import ctypes
            ctypes.windll.user32.LockWorkStation()
        run_delayed(do_lock, 0.5)
        return {
            "status": "success",
            "message": "🔒 PC screen locked successfully."
        }

    elif cmd in ["mute", "صامت", "كتم"]:
        is_muted = toggle_system_mute()
        cur = get_system_volume()
        try:
            cur_int = int(cur) if cur is not None else 50
        except Exception:
            cur_int = 50
        if is_muted is True:
            return {
                "status": "success",
                "type": "volume",
                "volume": cur_int,
                "muted": True,
                "message": "🔇 PC audio muted."
            }
        else:
            return {
                "status": "success",
                "type": "volume",
                "volume": cur_int,
                "muted": False,
                "message": "🔊 PC audio unmuted."
            }

    elif (cmd in ["+", "++", "vol+", "sound+"] or cmd.startswith("s +") or cmd.startswith("vol +") or cmd.startswith("sound +") or
          cmd in ["-", "--", "vol-", "sound-"] or cmd.startswith("s -") or cmd.startswith("vol -") or cmd.startswith("sound -")):
        delta = 5
        nums = re.findall(r"\d+", cmd)
        if nums:
            delta = int(nums[0])
        if "-" in cmd:
            delta = -delta
        cur = get_system_volume()
        cur_int = int(cur) if cur is not None else 50
        new_v = max(0, min(100, cur_int + delta))
        set_system_volume(new_v)
        return {
            "status": "success",
            "type": "volume",
            "volume": new_v,
            "message": f"🔊 PC system volume: {new_v}% ({'+' if delta >= 0 else ''}{delta}%)."
        }

    elif (cmd.startswith("sound") or cmd.startswith("vol") or 
          cmd.startswith("volume") or cmd.startswith("صوت") or
          cmd.startswith("s ") or cmd == "s" or bool(re.match(r"^s\d+$", cmd))):
        nums = re.findall(r"\d+", cmd)
        if nums:
            val = int(nums[0])
            val = max(0, min(100, val))
            set_system_volume(val)
            return {
                "status": "success",
                "type": "volume",
                "volume": val,
                "message": f"🔊 PC system volume set to {val}%."
            }
        else:
            cur = get_system_volume()
            try:
                cur_int = int(cur) if cur is not None else 50
            except Exception:
                cur_int = 50
            cur_str = f"{cur}%" if cur is not None else "50%"
            return {
                "status": "success",
                "type": "volume",
                "volume": cur_int,
                "message": f"🔊 PC system volume: {cur_str}\nAdjust volume using the slider below:"
            }

    elif cmd in ["status", "حالة", "info", "معلومات", "ping", "test"]:
        now_str = datetime.now().strftime("%Y-%m-%d %I:%M:%S %p")
        uname = platform.node()
        system_info = f"{platform.system()} {platform.release()}"
        power_status = get_active_power_status()
        extra_status = ""
        if power_status:
            act_name = power_status.get("action", "").title()
            rem = power_status.get("remaining_human", "")
            extra_status = f"\n\n⏱️ **Active Power Timer:**\n• Action: {act_name}\n• Remaining: {rem}\n💡 Send 'cancel' or 'stop' to abort anytime."
        return {
            "status": "success",
            "message": (
                f"🟢 PC Remote Server is Online & Healthy!\n\n"
                f"💻 Hostname: {uname}\n"
                f"🖥️ OS: {system_info}\n"
                f"🕒 Local Time: {now_str}\n"
                f"🌐 Network IPs: {', '.join(get_local_ips())}"
                f"{extra_status}"
            )
        }

    elif cmd.startswith("cmd "):
        # Execute shell command
        shell_cmd = action_type[4:].strip()
        try:
            res = subprocess.run(shell_cmd, shell=True, capture_output=True, text=True, timeout=10)
            out = res.stdout or res.stderr or "(Executed with no text output)"
            if len(out) > 1500:
                out = out[:1500] + "\n... (Long output truncated)"
            return {
                "status": "success" if res.returncode == 0 else "error",
                "message": f"💻 Command Output:\n{out}"
            }
        except subprocess.TimeoutExpired:
            return {"status": "error", "message": "⏱️ Command execution timed out (10s)."}
        except Exception as e:
            return {"status": "error", "message": f"❌ Error executing command: {str(e)}"}

    elif cmd.startswith("open "):
        target = action_type[5:].strip()
        try:
            os.startfile(target)
            return {"status": "success", "message": f"🚀 Opening: {target}"}
        except Exception as e:
            return {"status": "error", "message": f"❌ Could not open: {str(e)}"}

    elif (cmd.startswith("screenshot") or cmd.startswith("ss") or 
          cmd.startswith("screen") or cmd.startswith("شاشة") or cmd.startswith("لقطة")):
        target = "all"
        if "1" in cmd or "واحد" in cmd or "أولى" in cmd:
            target = "1"
        elif "2" in cmd or "اثنين" in cmd or "ثانية" in cmd:
            target = "2"
        elif "all" in cmd or "كل" in cmd or "شاشتين" in cmd or "كلا" in cmd:
            target = "all"
        return take_screenshot(target)

    elif cmd in ["files", "dir", "ls", "ملفات", "مستندات", "browse"]:
        res = browse_path(DOWNLOADS_DIR)
        flist = "\n".join([f"📁 {f['name']}" for f in res['folders'][:5]] + [f"📄 {f['name']} ({f['size_str']})" for f in res['files'][:8]])
        return {
            "status": "info",
            "message": (
                f"📂 Downloads Folder Contents:\n\n{flist}\n\n"
                f"💡 You can browse and download any file using the 📁 button in the top bar!"
            )
        }

    elif cmd in ["clip", "clipboard", "حافظة", "الحافظة", "getclip"]:
        clip_txt = get_win_clipboard()
        if clip_txt:
            preview = clip_txt if len(clip_txt) <= 500 else clip_txt[:500] + "\n...(Long text truncated)"
            return {
                "status": "success",
                "message": f"📋 PC Clipboard Content:\n\n{preview}"
            }
        else:
            return {
                "status": "info",
                "message": "📋 PC clipboard is currently empty."
            }

    elif cmd.startswith("setclip ") or cmd.startswith("copy ") or cmd.startswith("نسخ ") or cmd.startswith("clip ") or cmd.startswith("clipboard ") or cmd.startswith("paste "):
        txt_to_set = action_type.split(" ", 1)[1] if " " in action_type else ""
        if txt_to_set:
            global current_clipboard_text, clipboard_version
            with clipboard_cond:
                success = set_win_clipboard(txt_to_set)
                if txt_to_set != current_clipboard_text:
                    current_clipboard_text = txt_to_set
                    clipboard_version += 1
                    clipboard_cond.notify_all()
                if success and txt_to_set:
                    add_clipboard_history_item(txt_to_set)
            return {
                "status": "success" if success else "error",
                "message": f"📋 Text copied to PC clipboard successfully:\n'{txt_to_set}'" if success else "❌ Could not set PC clipboard"
            }
        return {"status": "error", "message": "Please specify the text to copy."}

    elif cmd in ["history", "clip history", "clips", "سجل", "سجل الحافظة"]:
        with clipboard_history_lock:
            items = list(clipboard_history)
        if not items:
            return {"status": "info", "message": "📋 PC clipboard history is currently empty."}
        lines = [f"📋 Windows Clipboard History ({len(items)} items):\n"]
        for idx, it in enumerate(items[:10], start=1):
            t = it.get('text', '').replace('\n', ' ').strip()
            preview = t[:60] + "..." if len(t) > 60 else t
            time_str = it.get('time', '')
            lines.append(f"{idx}. [{time_str}] {preview}")
        lines.append("\n💡 Tap '📋 Win+V History' in the app to view or copy any item!")
        return {"status": "success", "message": "\n".join(lines)}

    else:
        return {
            "status": "unknown",
            "message": (
                f"❓ Unknown command: '{action_type}'\n\n"
                "📌 Supported Commands:\n"
                "• screenshot (or ss) - Capture all displays combined\n"
                "• screenshot 1 (or ss 1) - Capture Display 1 only\n"
                "• screenshot 2 (or ss 2) - Capture Display 2 only\n"
                "• files - Quick glance at Downloads folder\n"
                "• sleep <duration> - Put PC to sleep (e.g. sleep 15m or sleep 1h)\n"
                "• shutdown <duration> - Immediate or scheduled shutdown (e.g. shutdown 30m)\n"
                "• hibernate <duration> - Immediate or scheduled hibernate\n"
                "• restart <duration> - Immediate or scheduled restart\n"
                "• sleep rain <duration> - Play Rain.mp4 with sleep timer\n"
                "• sound <0-100> (or s <0-100>) - Set PC volume level\n"
                "• mute / unmute - Toggle system sound mute\n"
                "• lock - Lock Windows workstation\n"
                "• status - View PC status, IP addresses, and active power timers\n"
                "• cancel / stop - Abort any active media or scheduled power timer\n"
                "• open <app/url> - Launch an application or open a webpage\n"
                "• cmd <command> - Execute Windows command line tool"
            )
        }

class RemoteHandler(http.server.BaseHTTPRequestHandler):
    def _send_cors_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, X-Filename, X-Filename-B64")

    def do_OPTIONS(self):
        self.send_response(204)
        self._send_cors_headers()
        self.end_headers()

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path in ["/", "/ping", "/status"]:
            data = execute_action("status")
            body = json.dumps(data, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self._send_cors_headers()
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif parsed.path in ["/api/browse", "/browse"]:
            params = urllib.parse.parse_qs(parsed.query)
            target_path = params.get("path", [""])[0]
            data = browse_path(target_path)
            body = json.dumps(data, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self._send_cors_headers()
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif parsed.path in ["/api/files/folder_info", "/api/folder_info"]:
            params = urllib.parse.parse_qs(parsed.query)
            target_path = params.get("path", [""])[0]
            data = check_folder_size_safe(target_path)
            if not data:
                data = {"status": "error", "message": "Folder does not exist"}
            body = json.dumps(data, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self._send_cors_headers()
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif parsed.path in ["/api/download", "/download"]:
            params = urllib.parse.parse_qs(parsed.query)
            file_path = params.get("path", [""])[0] or params.get("file", [""])[0]
            if file_path and os.path.exists(file_path) and os.path.isfile(file_path):
                try:
                    file_size = os.path.getsize(file_path)
                    filename = os.path.basename(file_path)
                    encoded_name = urllib.parse.quote(filename)
                    self.send_response(200)
                    self._send_cors_headers()
                    self.send_header("Content-Type", "application/octet-stream")
                    self.send_header("Content-Length", str(file_size))
                    self.send_header("Content-Disposition", f"attachment; filename=\"{encoded_name}\"; filename*=UTF-8''{encoded_name}")
                    self.end_headers()
                    with open(file_path, "rb") as f:
                        while True:
                            chunk = f.read(65536)
                            if not chunk:
                                break
                            self.wfile.write(chunk)
                except Exception:
                    pass
            else:
                self.send_response(404)
                self.end_headers()
        elif parsed.path.startswith("/screenshot/"):
            filename = os.path.basename(parsed.path.replace("/screenshot/", "").strip())
            filepath = os.path.join(SCREENSHOTS_DIR, filename)
            if os.path.exists(filepath) and os.path.isfile(filepath):
                try:
                    file_size = os.path.getsize(filepath)
                    self.send_response(200)
                    self._send_cors_headers()
                    self.send_header("Content-Type", "image/jpeg")
                    self.send_header("Content-Length", str(file_size))
                    self.send_header("Cache-Control", "no-cache")
                    self.end_headers()
                    with open(filepath, "rb") as f:
                        while True:
                            chunk = f.read(65536)
                            if not chunk:
                                break
                            self.wfile.write(chunk)
                except Exception:
                    pass
            else:
                self.send_response(404)
                self.end_headers()
        elif parsed.path in ["/api/clipboard", "/clipboard"]:
            params = urllib.parse.parse_qs(parsed.query)
            try:
                client_version = int(params.get("version", [0])[0])
            except Exception:
                client_version = 0
            try:
                wait_timeout = min(30, max(0, int(params.get("wait", [0])[0])))
            except Exception:
                wait_timeout = 0
            
            with clipboard_cond:
                if client_version < clipboard_version or wait_timeout == 0:
                    data = {"version": clipboard_version, "text": current_clipboard_text}
                else:
                    clipboard_cond.wait(timeout=wait_timeout)
                    data = {"version": clipboard_version, "text": current_clipboard_text}
            
            body = json.dumps(data, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self._send_cors_headers()
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif parsed.path in ["/api/clipboard/history", "/clipboard/history"]:
            with clipboard_history_lock:
                items = list(clipboard_history)
            data = {
                "status": "success",
                "count": len(items),
                "current": current_clipboard_text,
                "history": items
            }
            body = json.dumps(data, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self._send_cors_headers()
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif parsed.path in ["/api/volume", "/volume"]:
            cur = get_system_volume()
            try:
                cur_int = int(cur) if cur is not None else 50
            except Exception:
                cur_int = 50
            data = {"status": "success", "type": "volume", "volume": cur_int}
            body = json.dumps(data, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self._send_cors_headers()
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path in ["/upload", "/api/upload"]:
            try:
                os.makedirs(DOWNLOADS_DIR, exist_ok=True)
                raw_filename = ""
                
                # Check base64 header first (preserves Arabic and special chars)
                b64_name = self.headers.get("X-Filename-B64")
                if b64_name:
                    try:
                        raw_filename = base64.b64decode(b64_name).decode("utf-8")
                    except Exception:
                        pass
                
                if not raw_filename:
                    raw_name = self.headers.get("X-Filename", "")
                    if raw_name:
                        raw_filename = urllib.parse.unquote(raw_name)

                # Fallback if no filename provided
                if not raw_filename or not raw_filename.strip():
                    content_type = self.headers.get("Content-Type", "")
                    ext = ".bin"
                    if "image/jpeg" in content_type: ext = ".jpg"
                    elif "image/png" in content_type: ext = ".png"
                    elif "application/pdf" in content_type: ext = ".pdf"
                    raw_filename = f"upload_{int(time.time())}{ext}"

                # Sanitize filename (prevent path traversal)
                safe_filename = os.path.basename(raw_filename.strip()).replace("/", "").replace("\\", "")
                if not safe_filename:
                    safe_filename = f"upload_{int(time.time())}.bin"

                # Handle duplicate filenames in Downloads
                base_name, ext = os.path.splitext(safe_filename)
                target_path = os.path.join(DOWNLOADS_DIR, safe_filename)
                counter = 1
                while os.path.exists(target_path):
                    safe_filename = f"{base_name} ({counter}){ext}"
                    target_path = os.path.join(DOWNLOADS_DIR, safe_filename)
                    counter += 1

                content_len = int(self.headers.get("Content-Length", 0))
                bytes_received = 0
                
                with open(target_path, "wb") as out_file:
                    while bytes_received < content_len:
                        chunk_size = min(65536, content_len - bytes_received)
                        chunk = self.rfile.read(chunk_size)
                        if not chunk:
                            break
                        out_file.write(chunk)
                        bytes_received += len(chunk)

                size_str = format_size(bytes_received)
                print(f"[UPLOAD] Saved '{safe_filename}' ({size_str}) to {DOWNLOADS_DIR}")

                response_data = {
                    "status": "success",
                    "filename": safe_filename,
                    "size": bytes_received,
                    "path": target_path,
                    "message": f"📥 File received successfully in PC Downloads folder:\n📁 {safe_filename} ({size_str})"
                }
                body = json.dumps(response_data, ensure_ascii=False).encode("utf-8")
                self.send_response(200)
                self._send_cors_headers()
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            except Exception as ex:
                err_data = {
                    "status": "error",
                    "message": f"❌ Failed to save uploaded file: {str(ex)}"
                }
                body = json.dumps(err_data, ensure_ascii=False).encode("utf-8")
                self.send_response(500)
                self._send_cors_headers()
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        elif parsed.path in ["/command", "/api/command", "/"]:
            content_len = int(self.headers.get("Content-Length", 0))
            post_body = self.rfile.read(content_len).decode("utf-8").strip()
            
            command_text = ""
            try:
                payload = json.loads(post_body)
                if isinstance(payload, dict):
                    command_text = payload.get("command", "") or payload.get("action", "") or payload.get("msg", "") or payload.get("text", "")
                elif isinstance(payload, str):
                    command_text = payload
            except Exception:
                # Fallback to query string or raw text
                if "=" in post_body and not post_body.startswith("{"):
                    params = urllib.parse.parse_qs(post_body)
                    command_text = params.get("command", [""])[0]
                else:
                    command_text = post_body

            command_text = command_text.strip().strip('"').strip("'")
            print(f"[RECV] Command: '{command_text}' from {self.client_address[0]}")
            result = execute_action(command_text)
            
            body = json.dumps(result, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self._send_cors_headers()
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        elif parsed.path in ["/api/clipboard", "/clipboard"]:
            content_len = int(self.headers.get("Content-Length", 0))
            post_body = self.rfile.read(content_len).decode("utf-8").strip()
            new_text = ""
            global current_clipboard_text, clipboard_version
            try:
                payload = json.loads(post_body)
                if isinstance(payload, dict):
                    new_text = payload.get("text", "") or payload.get("clip", "")
                elif isinstance(payload, str):
                    new_text = payload
            except Exception:
                new_text = post_body

            success = False
            with clipboard_cond:
                success = set_win_clipboard(new_text)
                if new_text != current_clipboard_text:
                    current_clipboard_text = new_text
                    clipboard_version += 1
                    clipboard_cond.notify_all()
                if success and new_text:
                    add_clipboard_history_item(new_text)
                preview = new_text[:50].replace('\n', ' ').strip()
                print(f"[CLIPBOARD] Phone -> PC set clipboard (v{clipboard_version}, len={len(new_text)}): '{preview}'")

            resp = {
                "status": "success" if success else "error",
                "version": clipboard_version,
                "message": "📋 Text copied to PC clipboard successfully!" if success else "❌ Could not set PC clipboard"
            }
            body = json.dumps(resp, ensure_ascii=False).encode("utf-8")
            self.send_response(200 if success else 500)
            self._send_cors_headers()
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif parsed.path in ["/api/clipboard/clear", "/clipboard/clear"]:
            global clipboard_history
            with clipboard_history_lock:
                clipboard_history = []
                save_clipboard_history()
            resp = {"status": "success", "message": "Clipboard history cleared successfully"}
            body = json.dumps(resp, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self._send_cors_headers()
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif parsed.path in ["/api/volume", "/volume"]:
            content_len = int(self.headers.get("Content-Length", 0))
            post_body = self.rfile.read(content_len).decode("utf-8").strip()
            try:
                payload = json.loads(post_body)
            except Exception:
                payload = {}

            if "mute" in payload and payload["mute"] is True:
                toggle_system_mute()
            elif "delta" in payload:
                try:
                    delta_val = int(payload["delta"])
                    cur = get_system_volume()
                    cur_int = int(cur) if cur is not None else 50
                    new_v = max(0, min(100, cur_int + delta_val))
                    set_system_volume(new_v)
                except Exception:
                    pass
            elif "volume" in payload or "val" in payload:
                new_v = payload.get("volume", payload.get("val", None))
                if new_v is not None:
                    try:
                        v = int(new_v)
                        set_system_volume(v)
                    except Exception:
                        pass

            cur = get_system_volume()
            try:
                cur_int = int(cur) if cur is not None else 50
            except Exception:
                cur_int = 50

            resp = {
                "status": "success",
                "type": "volume",
                "volume": cur_int
            }
            body = json.dumps(resp, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self._send_cors_headers()
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        elif parsed.path in ["/api/files/send_ssh", "/api/send_ssh", "/send_ssh"]:
            content_len = int(self.headers.get("Content-Length", 0))
            post_body = self.rfile.read(content_len).decode("utf-8").strip()
            try:
                payload = json.loads(post_body)
            except Exception:
                payload = {}

            file_path = payload.get("path", "")
            raw_dest = payload.get("dest", "")
            phone_ip = payload.get("phone_ip", "") or self.client_address[0]
            is_folder = payload.get("is_folder", False) or os.path.isdir(file_path)

            if not file_path or not os.path.exists(file_path):
                resp = {"status": "error", "message": "❌ Requested path does not exist on PC"}
                body = json.dumps(resp, ensure_ascii=False).encode("utf-8")
                self.send_response(400)
                self._send_cors_headers()
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return

            if is_drive_root(file_path):
                resp = {"status": "error", "message": "❌ Transferring an entire drive root is not allowed."}
                body = json.dumps(resp, ensure_ascii=False).encode("utf-8")
                self.send_response(400)
                self._send_cors_headers()
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return

            item_name = os.path.basename(os.path.normpath(file_path)) or ("Folder" if is_folder else "File")
            if is_folder:
                sz_info = check_folder_size_safe(file_path)
                size_str = sz_info.get("size_str", "Folder") if sz_info else "Folder"
            else:
                try:
                    size_bytes = os.path.getsize(file_path)
                    size_str = format_size(size_bytes)
                except Exception:
                    size_str = "File"

            if raw_dest and str(raw_dest).strip():
                dest_dir = str(raw_dest).strip()
            else:
                if not is_folder:
                    ext = os.path.splitext(item_name)[1].lower()
                    MEDIA_EXTENSIONS = {
                        '.jpg', '.jpeg', '.png', '.gif', '.webp', '.bmp', '.heic', '.heif', '.svg', '.raw', '.dng',
                        '.mp4', '.mkv', '.mov', '.avi', '.webm', '.flv', '.wmv', '.3gp', '.m4v', '.ts'
                    }
                    dest_dir = "/sdcard/Pictures/" if ext in MEDIA_EXTENSIONS else "/sdcard/Download/"
                else:
                    dest_dir = "/sdcard/Download/"

            if not dest_dir.endswith("/"):
                dest_dir += "/"

            bat_dir = os.path.join(tempfile.gettempdir(), "pcremote_transfers")
            os.makedirs(bat_dir, exist_ok=True)
            try:
                existing_bats = [os.path.join(bat_dir, f) for f in os.listdir(bat_dir) if f.endswith(".bat")]
                if len(existing_bats) > 20:
                    existing_bats.sort(key=os.path.getmtime)
                    for old_b in existing_bats[:-20]:
                        try:
                            os.remove(old_b)
                        except Exception:
                            pass
            except Exception:
                pass

            bat_path = os.path.join(bat_dir, f"transfer_{int(time.time())}_{uuid.uuid4().hex[:6]}.bat")
            key_path = os.path.expanduser(r"~/.ssh/id_ed25519")
            key_arg = f'-i "{key_path}" ' if os.path.exists(key_path) else ''
            r_flag = "-r " if is_folder else ""
            escaped_src = os.path.normpath(file_path)

            bat_content = f"""@echo off
chcp 65001 >nul
title PC Remote: SSH Transfer - {item_name}
color 0B
cls
echo ================================================================
echo   PC Remote - High-Speed SSH Transfer
echo ================================================================
echo   Item:        {item_name} {"(Folder)" if is_folder else "(File)"}
echo   Size:        {size_str}
echo   Source:      {escaped_src}
echo   Destination: root@{phone_ip}:{dest_dir}
echo   Mode:        Overwrite matching files (Replace)
echo ================================================================
echo.
echo [INFO] Transfer in progress... Press Ctrl+C or close this window to cancel.
echo.

ssh -o BatchMode=yes -o StrictHostKeyChecking=no {key_arg}root@{phone_ip} "mkdir -p '{dest_dir}'" >nul 2>&1
scp {r_flag}-o BatchMode=yes -o StrictHostKeyChecking=no {key_arg}"{escaped_src}" root@{phone_ip}:"{dest_dir}"
if %ERRORLEVEL% equ 0 goto SUCCESS

echo.
echo [WARN] Direct IP transfer failed, attempting fallback to SSH host 'phone'...
ssh -o BatchMode=yes -o StrictHostKeyChecking=no phone "mkdir -p '{dest_dir}'" >nul 2>&1
scp {r_flag}-o BatchMode=yes -o StrictHostKeyChecking=no "{escaped_src}" phone:"{dest_dir}"
if %ERRORLEVEL% equ 0 goto SUCCESS

goto FAILURE

:SUCCESS
echo.
echo ================================================================
echo   [SUCCESS] Transfer completed successfully!
echo   All matching files have been overwritten at destination.
echo ================================================================
echo Window will close in 8 seconds (or press any key to close now)...
timeout /t 8
exit

:FAILURE
echo.
echo ================================================================
echo   [FAILED] Transfer was stopped, cancelled, or encountered an error.
echo ================================================================
pause
"""
            with open(bat_path, "w", encoding="utf-8") as f:
                f.write(bat_content)

            try:
                subprocess.Popen(
                    ["cmd.exe", "/c", "start", "", bat_path],
                    creationflags=subprocess.CREATE_NEW_CONSOLE
                )
                print(f"[SSH] Launched visible CMD transfer: '{item_name}' ({size_str}) -> {dest_dir}")
            except Exception as pe:
                print(f"[SSH] Error spawning CMD: {pe}")

            item_type_str = "Folder" if is_folder else "File"
            resp = {
                "status": "success",
                "message": f"🚀 SSH {item_type_str} transfer launched on PC!\n📁 Item: {item_name}\n💾 Size: {size_str}\n📍 Destination: {dest_dir}\n⚡ Overwrite Mode: Matching files at destination are replaced.\n🖥️ A live CMD terminal window has opened on your PC showing real-time progress. You can monitor or cancel it from your PC anytime.",
                "filename": item_name,
                "size": size_str,
                "dest": dest_dir
            }
            body = json.dumps(resp, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self._send_cors_headers()
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, format, *args):
        try:
            msg = f"[{datetime.now().strftime('%H:%M:%S')}] {args[0]} - {args[1]} {args[2]}\n"
            if sys.stdout:
                sys.stdout.write(msg)
                sys.stdout.flush()
        except Exception:
            pass

UDP_DISCOVERY_PORT = 5051

def start_udp_discovery_responder():
    """Listens for UDP broadcast discovery pings from the phone app and replies with server details."""
    def discovery_worker():
        while True:
            sock = None
            try:
                sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                sock.bind(("", UDP_DISCOVERY_PORT))
                while True:
                    try:
                        data, addr = sock.recvfrom(1024)
                        message = data.decode('utf-8', errors='ignore').strip()
                        if "PC_REMOTE_DISCOVER" in message or "PING" in message or "DISCOVER" in message:
                            hostname = platform.node() or socket.gethostname()
                            resp_data = json.dumps({
                                "service": "pc-remote-controller",
                                "port": PORT,
                                "hostname": hostname,
                                "status": "ready"
                            })
                            sock.sendto(resp_data.encode('utf-8'), addr)
                    except Exception:
                        break
            except Exception:
                pass
            finally:
                if sock:
                    try:
                        sock.close()
                    except Exception:
                        pass
            time.sleep(2)

    t = threading.Thread(target=discovery_worker, daemon=True, name="UDPDiscoveryResponder")
    t.start()

def run():
    init_power_scheduler()
    start_udp_discovery_responder()
    
    while True:
        httpd = None
        try:
            server_address = ("0.0.0.0", PORT)
            httpd = http.server.ThreadingHTTPServer(server_address, RemoteHandler)
            httpd.allow_reuse_address = True
            httpd.daemon_threads = True
            ips = get_local_ips()
            print("=" * 60)
            print(f"🚀 PC Remote Control Server is RUNNING on port {PORT}")
            print(f"📡 Connect from your phone at any of these URLs:")
            for ip in ips:
                print(f"   👉 http://{ip}:{PORT}")
            print("=" * 60)
            print("Ready to receive commands from your Android app!")
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\nServer shutting down gracefully.")
            if httpd:
                try:
                    httpd.server_close()
                except Exception:
                    pass
            break
        except Exception as e:
            print(f"⚠️ Server loop encountered error: {e}. Auto-restarting server in 2 seconds...")
            if httpd:
                try:
                    httpd.server_close()
                except Exception:
                    pass
            time.sleep(2)

if __name__ == "__main__":
    run()
