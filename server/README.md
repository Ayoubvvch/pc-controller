# 💻 PC Controller — Windows Server Package

> Lightweight, zero-dependency background server for Windows that allows remote interaction and monitoring from the **PC Remote** Android app.

---

## ⚡ Quick Start (Ready in 10 Seconds)

### 1. Requirements
* Windows 10 or Windows 11 (64-bit).
* **Python 3.8 or newer** installed (from [python.org](https://www.python.org/downloads/) or Microsoft Store).
  > **Note:** During Python installation, make sure to check **"Add python.exe to PATH"**.
* **Zero external libraries:** Runs 100% on Python's built-in standard library. No `pip install` required!

---

### 2. Running the Server

| Script | What it does |
| :--- | :--- |
| **`start.bat`** | **Start the server silently in the background** along with the 24/7 watchdog service. |
| **`stop.bat`** | **Stop the server and watchdog** cleanly. |
| **`status.bat`** | Check current server status, process IDs, and your PC's local IP address. |
| **`start_server.bat`** | Run server in a visible debug console window (useful for troubleshooting). |

---

### 3. Automatic Startup with Windows (Recommended)

To have the server automatically start when your PC boots or when you log in:
* **To Enable:** Double-click **`install_autostart.bat`**.
* **To Remove:** Double-click **`uninstall_autostart.bat`**.

This configures:
1. Windows Registry `Run` key.
2. Windows Startup folder script.
3. Windows Task Scheduler 24/7 health check.

---

## 🌐 Network & Firewall Configuration

* **HTTP Control API:** Port `5050`
* **Zero-Config Auto-Discovery (UDP):** Port `5051`

If Windows Firewall prompts you when first launching, select **"Allow access on Private networks"**.

---

## 📁 Package Contents

```
server/
├── start.bat                  # One-click background launcher
├── stop.bat                   # Cleanly terminate server & watchdog
├── status.bat                 # Check health and display local IPs
├── start_server.bat           # Run in visible console mode (debug)
├── install_autostart.bat      # Enable automatic Windows boot startup
├── uninstall_autostart.bat    # Remove automatic startup
├── server.py                  # Core HTTP & API server
├── watchdog.py                # 24/7 health monitor and auto-recovery
├── setup_autostart.py         # Autostart configuration engine
├── uninstall_autostart.py     # Autostart removal engine
├── screenshot.exe             # High-performance multi-monitor capture
├── screenshot.cs              # Source code for screenshot utility
├── vol.exe                    # Windows CoreAudio volume controller
└── vol.cs                     # Source code for volume utility
```

---

## 🛡️ Key Features

* **Zero-Config Auto-Discovery:** When your phone is on the same Wi-Fi, the app automatically finds this PC using UDP broadcast on port 5051.
* **Persistent Power Scheduler:** Scheduled sleep or shutdown timers (`sleep 15m`, `shutdown 30m`) are saved to disk and registered with Windows Kernel/Task Scheduler, surviving screen locks (Win+L) and reboots.
* **Smart Background Transfer:** Files transferred from PC explorer to mobile use direct background SSH/SCP so transfers continue even if the screen turns off.
