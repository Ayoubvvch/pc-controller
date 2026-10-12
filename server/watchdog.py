"""
PC Remote Watchdog Service
Keeps the PC Remote Server running 24/7.
Automatically detects crashes, freezes, port drops, or computer sleep/wake events,
and restarts the server immediately.
"""

import os
import sys
import time
import socket
import urllib.request
import subprocess
import json
from datetime import datetime

SERVER_DIR = os.path.dirname(os.path.abspath(__file__))
SERVER_SCRIPT = os.path.join(SERVER_DIR, "server.py")
PYTHON_DIR = os.path.dirname(sys.executable)
PYTHONW_PATH = os.path.join(PYTHON_DIR, "pythonw.exe")
if not os.path.exists(PYTHONW_PATH):
    PYTHONW_PATH = sys.executable

LOG_FILE = os.path.join(SERVER_DIR, "watchdog.log")

def log(msg):
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    entry = f"[{timestamp}] [Watchdog] {msg}\n"
    try:
        with open(LOG_FILE, "a", encoding="utf-8") as f:
            f.write(entry)
    except Exception:
        pass
    if sys.stdout:
        try:
            sys.stdout.write(entry)
            sys.stdout.flush()
        except Exception:
            pass

def is_server_healthy(timeout=3):
    try:
        req = urllib.request.Request("http://127.0.0.1:5050/ping")
        with urllib.request.urlopen(req, timeout=timeout) as response:
            if response.status == 200:
                data = json.loads(response.read().decode('utf-8'))
                return data.get("status") == "success"
    except Exception:
        pass
    return False

def kill_stuck_server():
    """Kill any stuck python process listening on port 5050 if unresponsive."""
    try:
        # Find PID on port 5050 using netstat
        output = subprocess.check_output('netstat -ano -p tcp', shell=True, text=True)
        for line in output.splitlines():
            if ":5050" in line and "LISTENING" in line:
                parts = line.strip().split()
                pid = parts[-1]
                if pid.isdigit() and int(pid) != os.getpid():
                    log(f"Killing unresponsive server process PID {pid}...")
                    subprocess.run(f"taskkill /F /PID {pid}", shell=True, capture_output=True)
    except Exception as e:
        log(f"Error while killing stuck process: {e}")

def start_server():
    log(f"Starting PC Remote Server: {PYTHONW_PATH} {SERVER_SCRIPT}")
    try:
        # CREATE_NO_WINDOW = 0x08000000, DETACHED_PROCESS = 0x00000008
        flags = 0x08000000 | 0x00000008
        subprocess.Popen(
            [PYTHONW_PATH, SERVER_SCRIPT],
            cwd=SERVER_DIR,
            creationflags=flags,
            close_fds=True
        )
        time.sleep(2)
        if is_server_healthy():
            log("✅ PC Remote Server started and confirmed healthy!")
            return True
        else:
            log("⚠️ Server started but ping not yet responding, will re-check next cycle.")
            return False
    except Exception as e:
        log(f"❌ Failed to start server: {e}")
        return False

def check_and_repair():
    if not is_server_healthy():
        # Double check after a brief moment to rule out temporary load
        time.sleep(2)
        if not is_server_healthy():
            log("🔴 Server is NOT healthy / down. Initiating recovery...")
            kill_stuck_server()
            time.sleep(1)
            start_server()
            return False
    return True

def main():
    if "--once" in sys.argv:
        healthy = check_and_repair()
        sys.exit(0 if healthy else 1)

    log("🛡️ Watchdog service started. Monitoring PC Remote Server every 10 seconds...")
    consecutive_failures = 0

    while True:
        try:
            if is_server_healthy():
                consecutive_failures = 0
            else:
                consecutive_failures += 1
                log(f"⚠️ Health check failed (failure count: {consecutive_failures})")
                if consecutive_failures >= 2:
                    log("🔴 Server unresponsive after 2 checks. Restarting now...")
                    kill_stuck_server()
                    time.sleep(1)
                    start_server()
                    consecutive_failures = 0
        except Exception as e:
            log(f"Watchdog loop error: {e}")

        time.sleep(10)

if __name__ == "__main__":
    main()
