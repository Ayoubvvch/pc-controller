import os
import sys
import winreg
import subprocess

if sys.platform == 'win32':
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

def setup():
    pythonw_path = os.path.join(os.path.dirname(sys.executable), "pythonw.exe")
    if not os.path.exists(pythonw_path):
        pythonw_path = sys.executable
    server_script = os.path.abspath(os.path.join(os.path.dirname(__file__), "server.py"))
    watchdog_script = os.path.abspath(os.path.join(os.path.dirname(__file__), "watchdog.py"))
    server_dir = os.path.dirname(server_script)
    
    watchdog_cmd = f'"{pythonw_path}" "{watchdog_script}"'
    
    print(f"Configuring PC Remote Server & Watchdog Autostart:")
    print(f"Watchdog: {watchdog_cmd}")
    
    # 1. Add to Windows Registry Run Key (Runs watchdog on user login)
    try:
        key = winreg.OpenKey(
            winreg.HKEY_CURRENT_USER,
            r"Software\Microsoft\Windows\CurrentVersion\Run",
            0,
            winreg.KEY_SET_VALUE
        )
        winreg.SetValueEx(key, "PCRemoteWatchdog", 0, winreg.REG_SZ, watchdog_cmd)
        winreg.CloseKey(key)
        print("✅ Added to Windows Registry: HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run")
    except Exception as e:
        print(f"⚠️ Registry setup failed: {e}")

    # 2. Add shortcut to Windows Startup folder
    try:
        startup_dir = os.path.join(os.environ["APPDATA"], r"Microsoft\Windows\Start Menu\Programs\Startup")
        target_vbs = os.path.join(startup_dir, "PCRemote_Watchdog.vbs")
        with open(target_vbs, "w", encoding="utf-8") as f:
            f.write(f'Set WshShell = CreateObject("WScript.Shell")\n')
            f.write(f'WshShell.CurrentDirectory = "{server_dir}"\n')
            f.write(f'WshShell.Run """{pythonw_path}"" ""{watchdog_script}""", 0, False\n')
        print(f"✅ Added startup script to: {target_vbs}")
    except Exception as e:
        print(f"⚠️ Startup folder setup failed: {e}")

    # 3. Create Windows Scheduled Tasks (OnLogon + Periodic health check)
    try:
        # Task 1: Run watchdog at logon
        subprocess.run(
            ['schtasks', '/Create', '/TN', 'PCRemote_Watchdog', '/TR', watchdog_cmd, '/SC', 'ONLOGON', '/F'],
            capture_output=True, text=True
        )
        # Task 2: Check health every 5 minutes and auto-restart if down
        health_cmd = f'"{pythonw_path}" "{watchdog_script}" --once'
        subprocess.run(
            ['schtasks', '/Create', '/TN', 'PCRemote_HealthCheck', '/TR', health_cmd, '/SC', 'MINUTE', '/MO', '5', '/F'],
            capture_output=True, text=True
        )
        print("✅ Created Windows Scheduled Tasks: PCRemote_Watchdog & PCRemote_HealthCheck")
    except Exception as e:
        print(f"⚠️ Task Scheduler setup failed: {e}")

    # 4. Check if server is currently running, if not start it via watchdog
    import socket
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    is_running = False
    try:
        s.connect(("127.0.0.1", 5050))
        is_running = True
        s.close()
    except Exception:
        pass

    if is_running:
        print("✅ PC Remote Server is ALREADY RUNNING on port 5050!")
    else:
        print("🚀 Starting PC Remote Server now in background via watchdog...")
        subprocess.Popen([pythonw_path, watchdog_script], cwd=server_dir, creationflags=0x08000000 | 0x00000008)
        print("✅ PC Remote Server started successfully!")

if __name__ == "__main__":
    setup()
