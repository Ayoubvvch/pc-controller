$PSScriptDir = $PSScriptRoot
$pythonCmd = (Get-Command python -ErrorAction SilentlyContinue).Source
$pythonw = if ($pythonCmd) { Join-Path (Split-Path $pythonCmd) "pythonw.exe" } else { "pythonw.exe" }
$script = Join-Path $PSScriptDir "server.py"

$shell = New-Object -ComObject Shell.Application
$shell.ShellExecute($pythonw, "`"$script`"", $PSScriptDir, "open", 0)
Write-Host "Server launched via Explorer Shell!"
