# 🎮 PC Controller (v2.0.6)

> A lightweight, blazing-fast, and secure client-server system to remotely monitor, manage, and interact with your Windows PC from an Android device over your local Wi-Fi network.

[![Android Build & Release](https://github.com/Ayoubvvch/pc-controller/actions/workflows/build-apk.yml/badge.svg)](https://github.com/Ayoubvvch/pc-controller/actions/workflows/build-apk.yml)
[![Latest Release](https://img.shields.io/github/v/release/Ayoubvvch/pc-controller?color=blue&label=Release)](https://github.com/Ayoubvvch/pc-controller/releases)
[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20Windows-green.svg)](https://github.com/Ayoubvvch/pc-controller)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

---

## 🏗️ Architecture

```
┌────────────────────────────────────────┐                     Wi-Fi / LAN                     ┌────────────────────────────────────────┐
│           Android Mobile App           │ ──────────────────────────────────────────────────> │           Windows PC Server            │
│  • Modern Kotlin + Coroutines          │                 HTTP REST API (Port 5050)           │  • Python 3 Standard Library Server    │
│  • Material Design 3 + ViewBinding     │ <────────────────────────────────────────────────── │  • Zero External Pip Dependencies     │
│  • Zero-Config UDP Auto-Discovery      │                UDP Broadcast (Port 5051)            │  • Compiled C# Multi-Monitor Engine    │
│  • Pinch-to-Zoom Screenshot Preview    │                                                     │  • Windows CoreAudio Native API        │
│  • Direct SSH Background File Download │ <══════════════════════════════════════════════════ │  • 24/7 Watchdog Recovery Service      │
│  • Instant Clipboard Synchronization  │                                                     │  • Absolute Persistent Power Scheduler │
│  • Persistent Background Service       │                 Direct Background SSH/SCP           │                                        │
└────────────────────────────────────────┘                                                     └────────────────────────────────────────┘
```

---

## 📦 What to Download & Install

Setting up **PC Controller** takes less than a minute. Everything needed for both devices is available directly in [**GitHub Releases**](https://github.com/Ayoubvvch/pc-controller/releases).

```
┌───────────────────────────────────────┐          ┌───────────────────────────────────────┐
│           📱 For Your Phone           │          │           💻 For Your PC              │
│                                       │          │                                       │
│  Download: PCRemote-v2.0.6.apk        │          │  Download: pc-controller-server.zip   │
│  (Directly from GitHub Releases)      │          │  (Or use the `server/` folder)        │
└───────────────────────────────────────┘          └───────────────────────────────────────┘
```

### 1. 📱 Android Setup (Mobile Device)
1. Go to [**Releases**](https://github.com/Ayoubvvch/pc-controller/releases) and download **`PCRemote-v2.0.6.apk`**.
2. Install the APK on your Android phone.
3. Open the app. When connected to the same Wi-Fi, it will automatically discover your PC via UDP broadcast.

### 2. 💻 Windows PC Setup (Host Computer)
1. Download **`pc-controller-server-windows.zip`** from [**Releases**](https://github.com/Ayoubvvch/pc-controller/releases) (or clone this repo and open the [`server/`](server/) directory).
2. Extract the folder to any location on your PC.
3. Double-click **`start.bat`**.
   * *The server and 24/7 self-healing watchdog will launch silently in the background.*
4. *(Optional but Recommended)* Double-click **`install_autostart.bat`** to enable automatic boot on Windows startup.

> 💡 **Prerequisite:** Make sure you have **Python 3.8+** installed on Windows with *"Add Python to PATH"* checked. **No external pip packages are needed** — it runs entirely on Python's built-in standard library.

---

## ✨ Features

### 🛡️ 1. Absolute Persistent Power Management
* **Accidental Click Protection:** Critical power commands (`shutdown`, `restart`, `hibernate`) require explicit confirmation (`yes` / `no`) within 60 seconds before executing.
* **Persistent Power Scheduler:** Scheduled timers (`sleep 30m`, `shutdown 1h`, `hibernate 20`) are saved to disk (`power_timer.json`) and backed up by Windows Kernel/Task Scheduler.
* **Survives Lock Screen:** Timers remain fully active and guaranteed even if you lock Windows (`Win + L`) or if the server process restarts.
* **Real-time Countdown:** Check remaining time for any active power timer anytime using `status`.

### 📸 2. Live Multi-Monitor Screen Previews
* Ultra-fast native capture powered by custom compiled C# binary:
  * `screenshot` or `screenshot all` — Captures all connected displays combined.
  * `screenshot 1` — Captures primary display.
  * `screenshot 2` — Captures secondary display.
* Fullscreen preview modal in mobile app with **Pinch-to-Zoom** (`ZoomTouchListener`), one-tap Save to Gallery, and native Share Sheet integration.

### 📂 3. Smart Remote File Explorer & Download
* Browse drives (`C:`, `D:`) and quick folders (Downloads, Desktop, Documents).
* **Sorted by Newest First:** Newly created and downloaded files always appear right at the top.
* **Direct SSH/SCP Background Push:**
  * Files are pushed directly to your phone via SSH in the background.
  * You can lock your phone or close the app safely while large files continue transferring uninterrupted.
* **Smart Media Routing:**
  * Photos and videos (`.jpg`, `.png`, `.mp4`, `.mkv`, etc.) are automatically saved to `/sdcard/Pictures/`.
  * Documents, archives, and other files are routed to `/sdcard/Download/`.

### 📤 4. Seamless Mobile-to-PC Sharing
* **Chat Attachment (`📎`):** Tap the clip icon in chat to pick and send any photo, video, or document straight to your PC's `Downloads` folder.
* **Android System Share Sheet:** Share links, gallery photos, or PDFs from any Android app directly into **PC Remote**.

### 📋 5. Instant Clipboard Synchronization (Phone ➔ PC)
* **One-Tap Clipboard Button (`📋`):** Tap the dedicated clipboard button in the message bar or the `📋 Paste to PC` chip at the top.
* **Interactive Modal Dialog:** Paste or type any text, URL, code snippet, or notes with a one-touch **"Paste from Phone"** button and live character count.
* **Instant Native Windows Clipboard:** Text is immediately set in the Windows PC clipboard using native Win32 API. Press `Ctrl + V` anywhere on your computer to paste instantly!
* **Chat Command Support:** You can also type `clip <text>`, `copy <text>`, or `setclip <text>` directly into the command bar.

### 🔄 6. Persistent Background Service & Notification Action
* **Stay Active 24/7:** Enable **"Keep Active in Background"** in Settings to prevent Android from terminating the app when minimized or when the screen turns off.
* **Notification Bar Quick Action:** Displays a persistent system notification with a direct **"📋 Send Clipboard"** button.
* **Instant Sync from Anywhere:** Tap the notification action from any app (browser, YouTube, chats) to immediately send your copied text to your PC without opening the full app!

### 🔑 7. Permanent Keystore & Seamless In-Place Updates
* **Fixed Signing Certificate:** Built with a committed cryptographic keystore (`release.keystore`), guaranteeing identical signatures for all builds.
* **No More Uninstalling:** Every update can be installed directly over existing versions with one click.
* **In-App Update Checker:** Check for new releases directly from Settings with one-click download.

### 🔊 8. Real-Time Volume Control
* Adjust master Windows audio volume using the native CoreAudio API (`vol.exe`).
* Visual interactive volume slider in mobile chat.
* Quick preset chips (`15%`, `25%`, `40%`, `50%`, `75%`, `100%`) and `mute` / `unmute` toggles.

### 🌧️ 9. Ambient Automations
* `sleep rain 15m` — Launches `Rain.mp4` via mpv in fullscreen loop mode, adjusts volume to 28%, and automatically suspends the PC when the timer ends.
* `sleep rain 15m min` — Minimizes all open windows so only a clean black desktop remains while audio plays, then enters sleep mode.
* `cancel` / `stop` — Instantly stops media playback and cancels any active power scheduler.

---

## 🎮 Command Cheat Sheet

| Command | Action |
| :--- | :--- |
| `clip <text>` / `copy <text>` | Copy text directly to Windows PC clipboard (`Ctrl + V`) |
| `clip` | View current text in PC clipboard |
| `screenshot` / `ss` | Capture all connected monitors combined |
| `screenshot 1` / `ss 1` | Capture primary monitor |
| `screenshot 2` / `ss 2` | Capture secondary monitor |
| `sleep <duration>` | Put PC to sleep (immediate or e.g. `sleep 15m`, `sleep 1h`) |
| `shutdown <duration>` | Shut down PC (immediate with confirmation or e.g. `shutdown 30m`) |
| `restart <duration>` | Restart PC (immediate with confirmation or scheduled) |
| `hibernate <duration>` | Put PC to hibernate (immediate with confirmation or scheduled) |
| `sleep rain <duration>` | Ambient rain video + volume 28% + sleep timer |
| `sound <0-100>` / `s <0-100>` | Set PC master volume (e.g. `s 20`, `sound 50`) |
| `mute` / `unmute` | Toggle audio mute |
| `lock` | Lock Windows workstation (`Win + L`) |
| `status` | View PC health, hostname, local IP addresses, and active timers |
| `cancel` / `stop` | Cancel active power timers and stop media player |
| `open <app or url>` | Launch a program or open a web URL on PC |
| `cmd <command>` | Execute a command prompt command |

---

## 🛠️ Server Folder Structure

```
server/
├── start.bat                  # Start server & watchdog in background
├── stop.bat                   # Cleanly terminate server & watchdog
├── status.bat                 # Check server status and local IPs
├── start_server.bat           # Run in visible console mode (debug)
├── install_autostart.bat      # Install 24/7 Windows boot autostart
├── uninstall_autostart.bat    # Remove autostart
├── server.py                  # Core HTTP & API server
├── watchdog.py                # 24/7 background self-healing service
├── setup_autostart.py         # Autostart configuration engine
├── uninstall_autostart.py     # Autostart uninstaller
├── screenshot.exe             # High-speed multi-monitor capture helper
├── screenshot.cs              # Source code for screenshot helper
├── vol.exe                    # Windows CoreAudio volume helper
├── vol.cs                     # Source code for volume helper
└── README.md                  # Dedicated server quick-start guide
```

---

## 🔧 Building from Source

### Android App
```bash
# Clone repository
git clone https://github.com/Ayoubvvch/pc-controller.git
cd pc-controller

# Build debug APK with Gradle
./gradlew assembleDebug
```
The output APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

### Native Helpers Compilation (Optional)
The `.exe` binaries are pre-compiled and included, but can be recompiled using the standard .NET Framework compiler bundled with Windows:
```cmd
# Recompile screenshot.exe
C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe /target:exe /out:server\screenshot.exe server\screenshot.cs

# Recompile vol.exe
C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe /target:exe /out:server\vol.exe server\vol.cs
```

---

## 📄 License
This project is open-source and released under the [MIT License](LICENSE).
