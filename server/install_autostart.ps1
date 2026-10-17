# Install PC Remote Server Autostart in Task Scheduler and Startup (Portable)
$PSScriptDir = $PSScriptRoot
$pythonCmd = (Get-Command python -ErrorAction SilentlyContinue).Source
$pythonw = if ($pythonCmd) { Join-Path (Split-Path $pythonCmd) "pythonw.exe" } else { "pythonw.exe" }
if (-not (Test-Path $pythonw)) { $pythonw = "pythonw.exe" }

$scriptPath = Join-Path $PSScriptDir "server.py"
$workDir = $PSScriptDir

Write-Host "Configuring PC Remote Server Autostart..." -ForegroundColor Cyan

# 1. Register Scheduled Task
try {
    $action = New-ScheduledTaskAction -Execute $pythonw -Argument "`"$scriptPath`"" -WorkingDirectory $workDir
    $trigger = New-ScheduledTaskTrigger -AtLogOn
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit (New-TimeSpan -Days 365) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
    Register-ScheduledTask -TaskName "PCRemoteServer" -Action $action -Trigger $trigger -Settings $settings -Force
    Write-Host "✅ Scheduled Task 'PCRemoteServer' registered successfully!" -ForegroundColor Green
} catch {
    Write-Host "⚠️ Task Scheduler registration error: $_" -ForegroundColor Yellow
}

# 2. Also create a shortcut in Windows Startup folder
$startupFolder = [System.Environment]::GetFolderPath([System.Environment+SpecialFolder]::Startup)
$shortcutPath = Join-Path $startupFolder "PCRemoteServer.lnk"

try {
    $wsh = New-Object -ComObject WScript.Shell
    $shortcut = $wsh.CreateShortcut($shortcutPath)
    $shortcut.TargetPath = $pythonw
    $shortcut.Arguments = "`"$scriptPath`""
    $shortcut.WorkingDirectory = $workDir
    $shortcut.WindowStyle = 7 # Minimized
    $shortcut.Description = "PC Remote Control Server"
    $shortcut.Save()
    Write-Host "✅ Startup folder shortcut created at: $shortcutPath" -ForegroundColor Green
} catch {
    Write-Host "⚠️ Startup shortcut error: $_" -ForegroundColor Yellow
}

# 3. Start the server right now using pythonw
Write-Host "Starting PC Remote Server now..." -ForegroundColor Cyan
Start-Process -FilePath $pythonw -ArgumentList "`"$scriptPath`"" -WorkingDirectory $workDir

Write-Host "Done! Server is running in the background and will start automatically with Windows." -ForegroundColor Green
