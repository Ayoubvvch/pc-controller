$PSScriptDir = $PSScriptRoot
$procs = Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like '*server.py*' }
if ($procs) {
    $procs | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}
Start-Sleep -Milliseconds 500

$pythonCmd = (Get-Command python -ErrorAction SilentlyContinue).Source
$pythonw = if ($pythonCmd) { Join-Path (Split-Path $pythonCmd) "pythonw.exe" } else { "pythonw.exe" }
$serverScript = Join-Path $PSScriptDir "server.py"

$cmd = "`"$pythonw`" `"$serverScript`""
Invoke-WmiMethod -Class Win32_Process -Name Create -ArgumentList $cmd
Write-Host "PC Remote Server restarted in background!"
