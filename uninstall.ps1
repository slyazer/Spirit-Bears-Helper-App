#Requires -Version 5.1
<#
.SYNOPSIS
  Macro Monitor bridge - uninstall.

.DESCRIPTION
  Removes the auto-start scheduled task and the firewall rule, and optionally the
  Python virtual environment. By default it PRESERVES your configuration
  (bridge\.env, config\accounts.json, config\users.json, sessions, logs) so a
  reinstall keeps working - and it never touches Natro Macro data.

  Use -PurgeConfig to also delete the bridge's own config/secrets, and
  -RemoveVenv to delete the virtual environment. Natro Macro folders are never
  removed by this script.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\uninstall.ps1
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\uninstall.ps1 -RemoveVenv -PurgeConfig
#>
[CmdletBinding()]
param(
    [switch]$RemoveVenv,
    [switch]$PurgeConfig,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$RepoRoot = $PSScriptRoot
$Bridge = Join-Path $RepoRoot 'bridge'
$Venv = Join-Path $Bridge '.venv'
$TaskName = 'MacroMonitorBridge'
$FirewallRule = 'Macro Monitor Bridge'

function Write-Info($msg) { Write-Host ('  ' + $msg) -ForegroundColor Gray }
function Write-Ok($msg) { Write-Host ('  [ok] ' + $msg) -ForegroundColor Green }
function Write-Warn2($msg) { Write-Host ('  [!] ' + $msg) -ForegroundColor Yellow }
function Write-Step($msg) { Write-Host ("`n==> " + $msg) -ForegroundColor Cyan }

function Test-Admin {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    (New-Object Security.Principal.WindowsPrincipal($id)).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)
}

Write-Host ''
Write-Host 'Spirit Bear''s Helper App - bridge uninstall' -ForegroundColor Magenta
Write-Info 'This removes the background service. Your Natro Macro files are never touched.'

if (-not $Force) {
    $answer = Read-Host '  Continue? (y/N)'
    if ($answer -notmatch '^[Yy]') { Write-Info 'Cancelled.'; exit 0 }
}

# --- Stop + remove scheduled task ----------------------------------------
Write-Step 'Stopping and removing the auto-start task'
if (Test-Admin) {
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
    Write-Ok 'Scheduled task removed'

    Get-CimInstance Win32_Process -Filter "Name='python.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine -like ('*' + $Bridge + '*') } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine -like '*run_bridge_guard.ps1*' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Write-Ok 'Any running bridge processes stopped'
} else {
    Write-Warn2 'Not elevated - run as Administrator to remove the task and firewall rule.'
}

# --- Firewall -------------------------------------------------------------
Write-Step 'Removing the firewall rule'
if (Test-Admin) {
    $rule = Get-NetFirewallRule -DisplayName $FirewallRule -ErrorAction SilentlyContinue
    if ($rule) {
        Remove-NetFirewallRule -DisplayName $FirewallRule
        Write-Ok 'Firewall rule removed'
    } else {
        Write-Info 'No firewall rule to remove'
    }
} else {
    Write-Warn2 'Skipped (not elevated).'
}

# --- Optional venv --------------------------------------------------------
if ($RemoveVenv) {
    Write-Step 'Removing the Python virtual environment'
    if (Test-Path -LiteralPath $Venv) {
        Remove-Item -LiteralPath $Venv -Recurse -Force
        Write-Ok 'Venv removed'
    } else {
        Write-Info 'No venv found'
    }
} else {
    Write-Info 'Keeping the Python venv (pass -RemoveVenv to delete it).'
}

# --- Optional config purge -------------------------------------------------
if ($PurgeConfig) {
    Write-Step 'Purging bridge configuration and secrets'
    $targets = @(
        (Join-Path $Bridge '.env'),
        (Join-Path $Bridge 'config\users.json'),
        (Join-Path $Bridge 'config\sessions.json'),
        (Join-Path $Bridge 'config\pairing.json')
    )
    foreach ($t in $targets) {
        if (Test-Path -LiteralPath $t) { Remove-Item -LiteralPath $t -Force; Write-Ok ('Deleted ' + $t) }
    }
    Write-Warn2 'accounts.json and logs were kept. Your Natro Macro installs are untouched.'
} else {
    Write-Info 'Configuration preserved (pass -PurgeConfig to delete bridge secrets).'
}

Write-Host ''
Write-Host 'Uninstall complete.' -ForegroundColor Magenta
Write-Host ''
