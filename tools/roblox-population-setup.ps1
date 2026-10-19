<#
  roblox-population-setup.ps1

  One-time setup for the Roblox private-server population capture feature.

  It:
    1. ensures the bridge venv exists,
    2. creates (or re-keys) a dedicated low-privilege bridge account the capture
       agent signs in with — your own login is never used,
    3. writes bridge\config\roblox_capture.json (git-ignored) with the random
       password and locks its ACL down to you + SYSTEM + Administrators,
    4. registers a logon scheduled task that runs the capture agent in a loop.

  No administrator rights are required: a per-user logon task is registered for
  the current account. Re-run any time (e.g. to rotate the password); it is
  idempotent.

  The target place/server ids below are this deployment's configuration and can
  be overridden with -Url / -VipServerId.
#>
[CmdletBinding()]
param(
    [string]$ConfigPath,
    [string]$BaseUrl,
    [string]$Url = 'https://games.roblox.com/v1/games/1537690962/private-servers?limit=10',
    [string]$VipServerId = '3355460963',
    [string]$CaptureUser = 'roblox-capture',
    [string]$Password,
    [int]$IntervalSeconds = 300,
    [double]$PageDelaySeconds = 1.5,
    [string]$FirefoxPath,
    [string]$TaskName = 'MacroMonitorRobloxPopulation',
    [switch]$SkipTask,
    [switch]$Start
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$RepoRoot = Split-Path -Parent $PSScriptRoot
$BridgeDir = Join-Path $RepoRoot 'bridge'
$VenvPython = Join-Path $BridgeDir '.venv\Scripts\python.exe'
$CaptureScript = Join-Path $PSScriptRoot 'roblox-population-capture.ps1'
if (-not $ConfigPath) { $ConfigPath = Join-Path $BridgeDir 'config\roblox_capture.json' }

function Write-Step { param([string]$Message) Write-Host "==> $Message" -ForegroundColor Cyan }

function New-RandomPassword {
    $bytes = New-Object byte[] 24
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($bytes)
    return (( [Convert]::ToBase64String($bytes) ) -replace '[+/=]', '')
}

# -- preconditions ---------------------------------------------------------

if (-not (Test-Path -LiteralPath $VenvPython)) {
    throw "Bridge venv not found at $VenvPython. Run setup.ps1 (or MacroMonitor-Setup.exe) first."
}
if (-not (Test-Path -LiteralPath $CaptureScript)) {
    throw "Capture script not found at $CaptureScript."
}

if (-not $BaseUrl) {
    $port = 8787
    $envFile = Join-Path $BridgeDir '.env'
    if (Test-Path -LiteralPath $envFile) {
        $m = Select-String -Path $envFile -Pattern '^\s*MM_PORT\s*=\s*(\d+)' | Select-Object -First 1
        if ($m) { $port = [int]$m.Matches[0].Groups[1].Value }
    }
    $BaseUrl = "http://127.0.0.1:$port"
}

if (-not $FirefoxPath) {
    foreach ($c in @('C:\Program Files\Mozilla Firefox\firefox.exe', 'C:\Program Files (x86)\Mozilla Firefox\firefox.exe')) {
        if (Test-Path -LiteralPath $c) { $FirefoxPath = $c; break }
    }
}
if (-not $FirefoxPath) {
    Write-Host "!! Firefox was not found in the usual locations. Pass -FirefoxPath, or set firefox_path later in the config." -ForegroundColor Yellow
}

if (-not $Password) { $Password = New-RandomPassword }

# -- create / re-key the dedicated capture account -------------------------

Write-Step "Provisioning the dedicated bridge account '$CaptureUser'."
Push-Location $BridgeDir
try {
    $existing = & $VenvPython -m app.cli list-users 2>&1 | Out-String
    if ($existing -match "(?m)^-\s+$([regex]::Escape($CaptureUser))\b") {
        & $VenvPython -m app.cli reset-password $CaptureUser --password $Password | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Failed to re-key account '$CaptureUser' (exit $LASTEXITCODE)." }
        Write-Host "Re-keyed existing account '$CaptureUser'."
    }
    else {
        & $VenvPython -m app.cli create-user $CaptureUser --password $Password --role device | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Failed to create account '$CaptureUser' (exit $LASTEXITCODE)." }
        Write-Host "Created account '$CaptureUser' (role=device)."
    }
}
finally {
    Pop-Location
}

# -- write the credential file, locked down --------------------------------

Write-Step "Writing capture configuration to $ConfigPath."
$configDir = Split-Path -Parent $ConfigPath
if (-not (Test-Path -LiteralPath $configDir)) { New-Item -ItemType Directory -Path $configDir -Force | Out-Null }

$config = [ordered]@{
    base_url           = $BaseUrl
    username           = $CaptureUser
    password           = $Password
    url                = $Url
    vip_server_id      = $VipServerId
    interval_seconds   = $IntervalSeconds
    page_delay_seconds = $PageDelaySeconds
    firefox_path       = $FirefoxPath
}
($config | ConvertTo-Json) | Set-Content -LiteralPath $ConfigPath -Encoding UTF8

# Restrict to the current user + SYSTEM + Administrators; drop inherited ACEs.
$me = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
& icacls $ConfigPath /inheritance:r /grant:r "${me}:(R,W)" 'SYSTEM:(F)' '*S-1-5-32-544:(F)' | Out-Null
Write-Host "Locked down ACL on $ConfigPath."

# -- register the logon task -----------------------------------------------

if (-not $SkipTask) {
    Write-Step "Registering logon task '$TaskName'."
    $arg = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$CaptureScript`" -ConfigPath `"$ConfigPath`""
    $action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $arg -WorkingDirectory $RepoRoot
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User $me
    $taskSettings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
        -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit ([TimeSpan]::Zero) `
        -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
    $principal = New-ScheduledTaskPrincipal -UserId $me -LogonType Interactive -RunLevel Limited
    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $taskSettings -Principal $principal -Force | Out-Null
    Write-Host "Registered scheduled task '$TaskName'."

    if ($Start) {
        Start-ScheduledTask -TaskName $TaskName
        Write-Host "Started '$TaskName'."
    }
}

Write-Host ""
Write-Host "Done. The capture agent will refresh the population every $IntervalSeconds seconds." -ForegroundColor Green
Write-Host "Run one capture now to verify:"
Write-Host "  powershell -ExecutionPolicy Bypass -File `"$CaptureScript`" -Once"
