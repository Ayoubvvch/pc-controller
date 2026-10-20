# 🎮 PC Controller (v2.1.3)

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
│  • Full Folder SSH Transfer (Overwrite)│                                                     │  • Absolute Persistent Power Scheduler │
│  • Windows Win+V Clipboard History     │                 Direct Background SSH/SCP           │  • Win32 Live Clipboard Watcher        │
│  • Phone Hardware Volume Keys for PC   │                                                     │  • Atomic Relative Volume API          │
│  • In-App Direct APK Auto-Installer    │                                                     │  • Live Visible CMD Console Transfer   │
└────────────────────────────────────────┘                                                     └────────────────────────────────────────┘
```

---

## 📦 What to Download & Install

Setting up **PC Controller** takes less than a minute. Everything needed for both devices is available directly in [**GitHub Releases**](https://github.com/Ayoubvvch/pc-controller/releases).

```
┌───────────────────────────────────────┐          ┌───────────────────────────────────────┐
│           📱 For Your Phone           │          │           💻 For Your PC              │
│                                       │          │                                       │
│  Download: PCRemote-v2.1.3.apk        │          │  Download: pc-controller-server.zip   │
│  (Directly from GitHub Releases)      │          │  (Or use the `server/` folder)        │
└───────────────────────────────────────┘          └───────────────────────────────────────┘
```

### 1. 📱 Android Setup (Mobile Device)
1. Go to [**Releases**](https://github.com/Ayoubvvch/pc-controller/releases) and download **`PCRemote-v2.1.3.apk`**.
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

### 📂 3. Smart Remote File Explorer & High-Speed SSH Transfers
* Browse drives (`C:`, `D:`) and quick folders (Downloads, Desktop, Documents).
* **Sorted by Newest First:** Newly created and downloaded files always appear right at the top.
* **📁 Full Folder Transfers with Automatic Overwrite:**
  * Transfer entire directories from PC to phone in one tap with zero third-party tools.
  * **Overwrite Mode Enabled:** Existing matching files in the target directory on your phone are cleanly replaced (never skipped).
  * **Phone Destination Picker:** Easily choose where the folder lands (`Download`, `Documents`, `Pictures`, `Movies`, `Music`, or any custom phone path).
  * **Shielded Drive Roots:** Drive roots (`C:\`, `D:\`) are strictly protected and cannot be transferred in bulk.
  * **Safe Size Pre-Scan (> 500 MB Warning):** Non-blocking recursive scanner detects large directories without following symlink/junction loops, warning the user beforehand.
  * **Download Current Folder Button:** Handy action button in the file browser top bar to download whatever folder you're currently viewing.
* **🖥️ Dedicated Live CMD Console on PC:**
  * Starting any SSH file or folder transfer automatically opens a clean, visible Command Prompt terminal on your PC.
  * Displays live OpenSSH transfer progress, file names, speed (MB/s), and ETA.
  * **Immediate Control & Cancellation:** Press `Ctrl+C` or close the CMD window from your PC anytime to cancel the transfer immediately.
  * Completely unburdens your phone: you can lock your screen or close the app safely while the transfer continues in the background.

### 📤 4. Seamless Mobile-to-PC Sharing
* **Chat Attachment (`📎`):** Tap the clip icon in chat to pick and send any photo, video, or document straight to your PC's `Downloads` folder.
* **Android System Share Sheet:** Share links, gallery photos, or PDFs from any Android app directly into **PC Remote**.

### 📋 5. PC Clipboard History (Windows Win + V on Phone)
* **Browse Windows Clipboard History:** View and search items previously copied on your PC directly from your phone, just like Windows `Win + V`.
* **One-Tap Actions:**
  * **"📋 Copy":** Copies the PC clipboard item directly into your Android phone's clipboard.
  * **"💻 Set on PC":** Re-activates that snippet on the Windows PC so you can immediately paste it (`Ctrl + V`) on your computer.
* **Real-Time Win32 Background Watcher:** Continuously tracks Windows clipboard changes in the background without needing cloud sync or external pip packages, persisting up to 50 items securely across restarts.

### 📱 6. Organized Top Bar with "More" Menu (`⋮`)
* **Decluttered Interface:** Consolidates multiple header buttons into an elegant 3-dots popup menu providing quick access to PC Clipboard History, PC File Browser, Settings, and Connection Refresh.
* **Quick Action Chip:** Dedicated `📋 Win+V History` chip in the quick action bar for instant one-tap access.

### 📋 7. Instant Clipboard Synchronization (Phone ➔ PC)
* **One-Tap Clipboard Button (`📋`):** Tap the dedicated clipboard button in the message bar or the `📋 Paste to PC` chip at the top.
* **Interactive Modal Dialog:** Paste or type any text, URL, code snippet, or notes with a one-touch **"Paste from Phone"** button and live character count.
* **Instant Native Windows Clipboard:** Text is immediately set in the Windows PC clipboard using native Win32 API. Press `Ctrl + V` anywhere on your computer to paste instantly!
* **Chat Command Support:** You can also type `clip <text>`, `copy <text>`, or `setclip <text>` directly into the command bar.

### 🔄 8. Persistent Background Service & Dual Notification Actions
* **Stay Active 24/7:** Enable **"Keep Active in Background"** in Settings to prevent Android from terminating the app when minimized or when the screen turns off.
* **Notification Bar Quick Actions:** Displays a persistent system notification with dual one-tap actions:
  * **"📋 Send Clip":** Immediately sends your phone's copied text to your PC without opening the app!
  * **"📥 Get PC Clip":** Immediately fetches whatever was copied on your PC (Ctrl + C) and copies it into your phone's clipboard!
* **Instant Bidirectional Sync from Anywhere:** Tap either notification action from any app (browser, YouTube, chats, lock screen) for instant two-way clipboard exchange.

### 🚀 9. In-App Direct APK Downloader & Auto-Installer
* **One-Click In-App Update:** Tapping **"Check for App Updates"** in Settings now downloads the new APK directly inside the app with a live progress dialog showing percentage, downloaded MB, and total size.
* **Automatic Installation Prompt:** Once the download reaches 100%, the app automatically launches the Android package installer prompt — zero browser redirects needed!
* **Fixed Signing Certificate:** Built with a committed cryptographic keystore (`release.keystore`), guaranteeing every update can be installed directly over existing versions with one click.

### 🔊 10. Hardware Volume Keys & Real-Time Volume HUD
* **Control PC with Phone Side Buttons:** Pressing your phone's physical **Volume Up** or **Volume Down** buttons while inside the app directly raises or lowers your Windows PC's master volume!
* **Floating Volume HUD:** Displays a sleek, animated on-screen volume pill with current percentage and a visual progress bar.
* **Zero Disruption to Phone:** Consumes hardware key events so your phone's ringtone and media volume remain unaffected.
* **Settings Toggle:** Easily toggle **"Phone Volume Keys Control PC"** on or off in Settings anytime.
* **Native Windows CoreAudio API:** Instant, crackle-free audio level adjustments (`vol.exe`).
* **Visual Slider & Presets:** Quick preset chips (`15%`, `25%`, `40%`, `50%`, `75%`, `100%`) and `mute` / `unmute` toggles.

### 🌧️ 11. Ambient Automations
* `sleep rain 15m` — Launches `Rain.mp4` via mpv in fullscreen loop mode, adjusts volume to 28%, and automatically suspends the PC when the timer ends.
* `sleep rain 15m min` — Minimizes all open windows so only a clean black desktop remains while audio plays, then enters sleep mode.
* `cancel` / `stop` — Instantly stops media playback and cancels any active power scheduler.

---

## 🎮 Command Cheat Sheet

| Command | Action |
| :--- | :--- |
| `clip <text>` / `copy <text>` | Copy text directly to Windows PC clipboard (`Ctrl + V`) |
| `clip` | View current text in PC clipboard |
| `history` / `clips` | View recent PC clipboard history items |
| `sound <0-100>` / `s <0-100>` | Set PC master volume (e.g. `s 20`, `sound 50`) |
| `+` / `++` / `s +<val>` | Increment PC volume (e.g. `+`, `s +5`, `vol +10`) |
| `-` / `--` / `s -<val>` | Decrement PC volume (e.g. `-`, `s -5`, `vol -10`) |
| `mute` / `unmute` | Toggle audio mute |
| `screenshot` / `ss` | Capture all connected monitors combined |
| `screenshot 1` / `ss 1` | Capture primary monitor |
| `screenshot 2` / `ss 2` | Capture secondary monitor |
| `sleep <duration>` | Put PC to sleep (immediate or e.g. `sleep 15m`, `sleep 1h`) |
| `shutdown <duration>` | Shut down PC (immediate with confirmation or e.g. `shutdown 30m`) |
| `restart <duration>` | Restart PC (immediate with confirmation or scheduled) |
| `hibernate <duration>` | Put PC to hibernate (immediate with confirmation or scheduled) |
| `sleep rain <duration>` | Ambient rain video + volume 28% + sleep timer |
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
