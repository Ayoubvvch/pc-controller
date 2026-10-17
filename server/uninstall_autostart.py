import os
import sys
import winreg
import subprocess

if sys.platform == 'win32':
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

def uninstall():
    print("Removing PC Remote Autostart configuration...")
    
    # 1. Remove from Windows Registry Run Key
    try:
        key = winreg.OpenKey(
            winreg.HKEY_CURRENT_USER,
            r"Software\Microsoft\Windows\CurrentVersion\Run",
            0,
            winreg.KEY_SET_VALUE
        )
        try:
            winreg.DeleteValue(key, "PCRemoteWatchdog")
            print("✅ Removed from Windows Registry: HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run")
        except FileNotFoundError:
            print("ℹ️ Registry key 'PCRemoteWatchdog' not found.")
        winreg.CloseKey(key)
    except Exception as e:
        print(f"⚠️ Registry cleanup warning: {e}")

    # 2. Remove shortcut from Windows Startup folder
    try:
        startup_dir = os.path.join(os.environ.get("APPDATA", ""), r"Microsoft\Windows\Start Menu\Programs\Startup")
        target_vbs = os.path.join(startup_dir, "PCRemote_Watchdog.vbs")
        if os.path.exists(target_vbs):
            os.remove(target_vbs)
            print(f"✅ Removed startup script: {target_vbs}")
        else:
            print("ℹ️ Startup folder script was not found.")
    except Exception as e:
        print(f"⚠️ Startup folder cleanup warning: {e}")

    # 3. Delete Windows Scheduled Tasks
    for task_name in ['PCRemote_Watchdog', 'PCRemote_HealthCheck', 'PCRemoteServer', 'PCRemote_OneShotPower']:
        try:
            res = subprocess.run(['schtasks', '/Delete', '/TN', task_name, '/F'], capture_output=True, text=True)
            if res.returncode == 0:
                print(f"✅ Deleted Scheduled Task: {task_name}")
        except Exception as e:
            print(f"⚠️ Task Scheduler warning for {task_name}: {e}")

    print("\n[OK] PC Remote Autostart successfully uninstalled.")

if __name__ == "__main__":
    uninstall()
