# Macro Monitor bridge guard.
# Started by the 'MacroMonitorBridge' Scheduled Task at logon. Keeps the
# bridge running so the user never has to start or restart it manually.
$ErrorActionPreference = 'Stop'

Set-Location -LiteralPath $PSScriptRoot

$python = Join-Path $PSScriptRoot '.venv\Scripts\python.exe'
$logDir = Join-Path $PSScriptRoot 'logs'
if (-not (Test-Path -LiteralPath $logDir)) {
    New-Item -ItemType Directory -Path $logDir -Force | Out-Null
}

while ($true) {
    $stdout = Join-Path $logDir 'bridge.out.log'
    $stderr = Join-Path $logDir 'bridge.err.log'
    try {
        $proc = Start-Process -FilePath $python `
            -ArgumentList 'run.py' `
            -WorkingDirectory $PSScriptRoot `
            -WindowStyle Hidden `
            -RedirectStandardOutput $stdout `
            -RedirectStandardError $stderr `
            -PassThru
        $proc.WaitForExit()
    } catch {
        $_ | Out-File -FilePath (Join-Path $logDir 'guard.err.log') -Append -Encoding utf8
    }
    Start-Sleep -Seconds 5
}