# 🎮 PC Controller (v2.0)

> A modern, lightweight, and blazing-fast Android application to remotely manage, monitor, and interact with your Windows PC across your local network (Wi-Fi).

[![Android Build & Release](https://github.com/Ayoubvvch/pc-controller/actions/workflows/build-apk.yml/badge.svg)](https://github.com/Ayoubvvch/pc-controller/actions/workflows/build-apk.yml)
[![Latest Release](https://img.shields.io/github/v/release/Ayoubvvch/pc-controller?color=blue&label=Release)](https://github.com/Ayoubvvch/pc-controller/releases)
[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20Windows-green.svg)](https://github.com/Ayoubvvch/pc-controller)

---

## ✨ Features

### 🛡️ 1. Smart Power Management & Confirmation
* **Accidental Click Protection:** Critical commands (`shutdown`, `restart`, `hibernate`) require explicit confirmation (`yes` / `no`) within 60 seconds before executing.
* **Timed Sleep & Automations:** Schedule sleep directly from chat:
  * `sleep 15` / `sleep 30` — Puts the PC to sleep after 15 or 30 minutes.
  * `sleep rain 15` / `sr 15` — Plays ambient rain video via mpv, gently sets volume, and suspends PC when the timer completes.
  * `cancel sleep` — Cancels any active sleep timer instantly.

### 📸 2. Multi-Monitor Live Screenshots in Chat
* Capture screen previews directly in your mobile chat interface:
  * `screenshot` or `screenshot all` — Captures both displays combined.
  * `screenshot 1` — Captures primary display.
  * `screenshot 2` — Captures secondary display.
* Embedded instant image preview with one-tap full-resolution view.

### 📂 3. Built-in PC File Explorer & Download
* Browse PC drives (`C:`, `D:`, etc.) and system shortcuts (Downloads, Desktop, Documents).
* **Sorted by Newest First:** Automatically sorts files by last modified/created date so newly added downloads are always right at the top.
* **One-Tap Download:** Download any file directly from PC to your phone's storage.

### 📤 4. Seamless File & Photo Sharing to PC
* **Chat Attachment:** Tap the `📎` button in chat to pick and send any photo, video, or document straight to your PC's `Downloads` folder.
* **Android Share Sheet Integration:** Share anything (photos from Gallery, links from browser, PDFs) directly to **PC Controller** from any Android app.

### 🔊 5. Volume & Quick PC Controls
* Volume adjustment (`sound 30`, `vol 50`, `mute`, `unmute`).
* Media control, quick terminal execution (`cmd <command>`), and app launching (`open notepad`).

---

## 🏗️ Architecture

```
┌─────────────────────────────────┐           HTTP (Wi-Fi)            ┌──────────────────────────────────┐
│       Android Client App        │ ────────────────────────────────> │       Windows PC Server          │
│  - Kotlin + Coroutines          │                                   │  - Python 3 HTTP Server (5050)   │
│  - Material Design 3            │ <──────────────────────────────── │  - Win32 API & C# Helpers        │
│  - Zero Root / Standard Sandbox │       JSON / Media Streaming      │  - Standalone Autostart Service  │
└─────────────────────────────────┘                                   └──────────────────────────────────┘
```

* **No Root Required:** Operates strictly within standard Android sandbox without background battery drain.
* **Ultra-low latency:** Direct local network HTTP communication without cloud middle-servers.

---

## 🚀 Getting Started

### 1. Windows PC Server Setup
1. Clone or download this repository on your PC:
   ```bash
   git clone https://github.com/Ayoubvvch/pc-controller.git
   ```
2. Navigate to `server/`:
   ```bash
   cd pc-controller/server
   ```
3. Run `start_background.bat` to launch the server silently.
   *(Optional)* Run `install_autostart.ps1` to have the server automatically start when your PC boots.

### 2. Android App Installation
1. Download the latest `PCRemote-v2.0.apk` from [GitHub Releases](https://github.com/Ayoubvvch/pc-controller/releases).
2. Install the APK on your Android device.
3. Open the app, enter your PC's local IP address (e.g. `192.168.1.100`), and tap **Connect**.

---

## 🛠️ Tech Stack
* **Android:** Kotlin, AndroidX, Material Design 3, OkHttp3, Glide.
* **PC Server:** Python 3, C# (.NET Framework for multi-monitor capture & core audio).
* **CI/CD:** GitHub Actions (Automated APK build and release creation).

---

## 📄 License
This project is open-source and available under the [MIT License](LICENSE).
