#Requires -Version 5.1
<#
.SYNOPSIS
  Macro Monitor bridge - guided Windows setup.

.DESCRIPTION
  One-shot setup for the local bridge that Spirit Bear's Helper App connects to.
  It creates the Python venv, installs dependencies, writes a default .env,
  creates your login account, adds an inbound firewall rule (LAN + Tailscale
  only), registers an auto-start scheduled task, optionally shows a QR pairing
  code, and starts the bridge.

  Safe to re-run to upgrade: it stops any running bridge first and starts it
  again so the new code takes effect, backs up the existing .env before
  changing it (restoring it if the write fails), and preserves your current
  MM_HOST / MM_PORT unless you explicitly pass -BindHost / -Port.

  It never touches your Natro Macro files.

.PARAMETER Port
  TCP port for the bridge (default 8787). On an existing install the current
  port is kept unless you pass this explicitly.

.PARAMETER BindHost
  Address to bind. Default 0.0.0.0 so phones on your LAN/tailnet can reach it.
  On an existing install the current value is kept unless you pass this
  explicitly. The bridge is never exposed to the public internet by this script.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\setup.ps1
#>
[CmdletBinding()]
param(
    [int]$Port = 8787,
    [string]$BindHost = '0.0.0.0',
    [switch]$SkipFirewall,
    [switch]$SkipTask,
    [switch]$NoStart,
    [switch]$InstallTailscale,
    [switch]$SkipTailscale
)

$ErrorActionPreference = 'Stop'

# Resolve the repository root whether we run as setup.ps1 or as the compiled
# MacroMonitor-Setup.exe. Inside a compiled EXE $PSScriptRoot is empty, but the
# executable's own directory is available from the command line. setup-lib.ps1
# and bridge\ must sit next to the script/exe.
$scriptDir = if ($PSScriptRoot) {
    $PSScriptRoot
} else {
    try { Split-Path -Parent ([Environment]::GetCommandLineArgs()[0]) } catch { '' }
}
$libPath = if ($scriptDir) { Join-Path $scriptDir 'setup-lib.ps1' } else { '' }
if (-not $libPath -or -not (Test-Path -LiteralPath $libPath)) {
    Write-Host ('[x] setup-lib.ps1 was not found next to this program (' + $scriptDir + ').') -ForegroundColor Red
    Write-Host '    Keep the setup program in the project folder, next to setup-lib.ps1 and bridge\.' -ForegroundColor Red
    exit 1
}
. $libPath
$RepoRoot = Resolve-RepoRoot -Candidates @($PSScriptRoot, $scriptDir)
if (-not $RepoRoot) {
    Write-Host ('[x] Could not find bridge\ next to this program (' + $scriptDir + ').') -ForegroundColor Red
    Write-Host '    Keep the setup program in the project folder, next to setup-lib.ps1 and bridge\.' -ForegroundColor Red
    exit 1
}
$Bridge = Join-Path $RepoRoot 'bridge'
$Venv = Join-Path $Bridge '.venv'
$VenvPython = Join-Path $Venv 'Scripts\python.exe'
$TaskName = 'MacroMonitorBridge'
$FirewallRule = 'Macro Monitor Bridge'

function Write-Info($msg) { Write-Host ('  ' + $msg) -ForegroundColor Gray }
function Write-Ok($msg) { Write-Host ('  [ok] ' + $msg) -ForegroundColor Green }
function Write-Warn2($msg) { Write-Host ('  [!] ' + $msg) -ForegroundColor Yellow }
function Write-Err2($msg) { Write-Host ('  [x] ' + $msg) -ForegroundColor Red }
function Write-Step($msg) { Write-Host ("`n==> " + $msg) -ForegroundColor Cyan }

function Get-BridgeListenerPids {
    param([int]$BridgePort)
    try {
        @(Get-NetTCPConnection -LocalPort $BridgePort -State Listen -ErrorAction Stop |
            Select-Object -ExpandProperty OwningProcess -Unique)
    } catch {
        @()
    }
}

# Stops the bridge that is currently bound to $BridgePort so an upgrade can
# replace the code and restart with the new version. Handles both the scheduled
# task (guard + child) and any orphaned process still holding the port.
function Stop-ExistingBridge {
    param([int]$BridgePort)
    $stopped = $false

    # 1. Stop the scheduled task (terminates the guard loop and its child).
    if (Test-Admin) {
        $task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
        if ($task -and $task.State -eq 'Running') {
            Write-Info 'Stopping the running bridge (scheduled task) ...'
            Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
            $stopped = $true
        }
    }

    # 2. Give the OS a moment to release the listener.
    $listenPids = @(Get-BridgeListenerPids -BridgePort $BridgePort)
    for ($i = 0; $i -lt 15 -and $listenPids.Count -gt 0; $i++) {
        Start-Sleep -Milliseconds 400
        $listenPids = @(Get-BridgeListenerPids -BridgePort $BridgePort)
    }

    # 3. Anything still holding the port is an orphaned child - stop it.
    foreach ($procId in $listenPids) {
        try {
            Write-Warn2 ('Stopping lingering bridge process (PID ' + $procId + ') ...')
            Stop-Process -Id $procId -Force -ErrorAction Stop
            $stopped = $true
        } catch {
            Write-Warn2 ('Could not stop PID ' + $procId + '.')
        }
    }

    # 4. Confirm the port is free before returning.
    $listenPids = @(Get-BridgeListenerPids -BridgePort $BridgePort)
    if ($listenPids.Count -gt 0) {
        Write-Warn2 ('Port ' + $BridgePort + ' is still in use (PID ' + ($listenPids -join ', ') + ').')
        return $false
    }
    if ($stopped) { Write-Ok 'Stopped the previous bridge' } else { Write-Info 'No running bridge found' }
    return $true
}

function Test-Admin {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    (New-Object Security.Principal.WindowsPrincipal($id)).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Find-Python {
    $candidates = @(
        @{ Exe = 'py'; Prefix = @('-3') },
        @{ Exe = 'python'; Prefix = @() }
    )
    foreach ($c in $candidates) {
        $cmd = Get-Command $c.Exe -ErrorAction SilentlyContinue
        if ($cmd) {
            try {
                $all = @($c.Prefix) + @('-c', 'import sys;print("%d.%d"%sys.version_info[:2])')
                $version = & $cmd.Source @all 2>$null
                if ($LASTEXITCODE -eq 0 -and (Test-PythonVersion -Version $version)) {
                    return @{ Exe = $cmd.Source; Prefix = $c.Prefix; Version = $version.Trim() }
                }
            } catch { }
        }
    }
    return $null
}

function Invoke-Py {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$PyArgs)
    & $script:PyInfo.Exe @($script:PyInfo.Prefix + $PyArgs)
}

function Get-WingetPath {
    $cmd = Get-Command winget -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

function Test-TailscaleInstalled {
    if (Get-Command tailscale -ErrorAction SilentlyContinue) { return $true }
    $paths = @()
    if ($env:ProgramFiles) { $paths += Join-Path $env:ProgramFiles 'Tailscale\tailscale.exe' }
    if (${env:ProgramFiles(x86)}) { $paths += Join-Path ${env:ProgramFiles(x86)} 'Tailscale\tailscale.exe' }
    if ($env:LOCALAPPDATA) { $paths += Join-Path $env:LOCALAPPDATA 'Tailscale\tailscale.exe' }
    foreach ($p in $paths) { if (Test-Path -LiteralPath $p) { return $true } }
    if (Get-Service -Name 'Tailscale' -ErrorAction SilentlyContinue) { return $true }
    return $false
}

# Install the Tailscale client (opt-in only). Prefers winget, falls back to the
# official MSI. This NEVER signs the user in to a tailnet - that account step
# stays with the user via "tailscale up".
function Install-TailscaleClient {
    $winget = Get-WingetPath
    $action = Get-TailscaleInstallAction -Requested $true `
        -AlreadyInstalled (Test-TailscaleInstalled) -WingetAvailable ([bool]$winget)
    switch ($action) {
        'already' {
            Write-Ok 'Tailscale is already installed'
            return $true
        }
        'winget' {
            Write-Info 'Installing Tailscale via winget ...'
            & $winget install --id Tailscale.Tailscale --exact --silent --accept-package-agreements --accept-source-agreements
            if ($LASTEXITCODE -eq 0) { Write-Ok 'Tailscale installed via winget'; return $true }
            Write-Warn2 ('winget install failed (exit ' + $LASTEXITCODE + '); trying the official installer ...')
        }
    }

    $uri = 'https://pkgs.tailscale.com/stable/tailscale-setup-latest-amd64.msi'
    $msi = Join-Path $env:TEMP ('tailscale-setup-' + [guid]::NewGuid().ToString('N') + '.msi')
    try {
        try {
            [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
        } catch { }
        Write-Info 'Downloading the official Tailscale installer ...'
        Invoke-WebRequest -Uri $uri -OutFile $msi -UseBasicParsing -TimeoutSec 180
        Write-Info 'Running the Tailscale installer (silent) ...'
        $p = Start-Process -FilePath 'msiexec.exe' -ArgumentList @('/i', ('"' + $msi + '"'), '/qn', '/norestart') -Wait -PassThru
        if ($p.ExitCode -eq 0 -or $p.ExitCode -eq 3010) { Write-Ok 'Tailscale installed'; return $true }
        Write-Warn2 ('Tailscale installer exited with code ' + $p.ExitCode + '.')
        return $false
    } catch {
        Write-Warn2 ('Could not install Tailscale: ' + $_.Exception.Message)
        return $false
    } finally {
        Remove-Item -LiteralPath $msi -Force -ErrorAction SilentlyContinue
    }
}

Write-Host ''
Write-Host 'Spirit Bear''s Helper App - bridge setup' -ForegroundColor Magenta
Write-Info ('Repo: ' + $RepoRoot)

if (-not (Test-Path -LiteralPath $Bridge)) {
    Write-Err2 ('bridge folder not found at ' + $Bridge)
    exit 1
}

# --- 1. Python ------------------------------------------------------------
Write-Step 'Locating Python'
$script:PyInfo = Find-Python
if (-not $script:PyInfo) {
    Write-Err2 'Python 3.10+ not found. Install from https://www.python.org/downloads/ (tick "Add python.exe to PATH") and re-run.'
    exit 1
}
Write-Ok ('Found Python ' + $script:PyInfo.Version + ' at ' + $script:PyInfo.Exe)

# --- Read current config + stop any running bridge ------------------------
# Read the existing .env so we can (a) preserve MM_HOST/MM_PORT across upgrades
# and (b) stop the process currently bound to that port.
$envPath = Join-Path $Bridge '.env'
$existingBinding = Get-EnvBinding -Lines @(Get-Content -LiteralPath $envPath -ErrorAction SilentlyContinue)
$existingHost = $existingBinding.Host
$existingPort = $existingBinding.Port

# Reject a corrupted MM_PORT in an existing .env BEFORE any [int] cast can throw.
if ($existingPort -and -not (Test-BridgePortText -Text $existingPort)) {
    Write-Err2 ('bridge\.env has an invalid MM_PORT (' + $existingPort + ').')
    Write-Info 'Use a number from 1 to 65535, delete the MM_PORT line, or pass -Port to override.'
    exit 1
}

# Only overwrite the bind address/port when the caller explicitly passed them.
$explicitHost = $PSBoundParameters.ContainsKey('BindHost')
$explicitPort = $PSBoundParameters.ContainsKey('Port')
$effective = Get-EffectiveBinding -ExistingHost $existingHost -ExistingPort $existingPort `
    -BindHost $BindHost -Port $Port -ExplicitHost:$explicitHost -ExplicitPort:$explicitPort
$effectiveHost = $effective.Host
$effectivePort = $effective.Port
if (-not (Test-BridgePort -Port $effectivePort)) {
    Write-Err2 ('Invalid port ' + $effectivePort + '. Use a TCP port between 1 and 65535.')
    exit 1
}
$runningPort = if ($existingPort) { [int]$existingPort } else { $Port }

Write-Step 'Checking for a running bridge'
if ($NoStart) {
    Write-Info 'NoStart set - the bridge is not stopped or started.'
    Write-Warn2 'A bridge that is already running keeps serving the OLD code until you restart it.'
} elseif ($SkipTask) {
    Write-Info 'SkipTask set - leaving any running bridge alone.'
    Write-Warn2 'Restart your bridge after the upgrade so it runs the new code.'
} else {
    [void](Stop-ExistingBridge -BridgePort $runningPort)
}

# --- 2. Virtual environment + deps ----------------------------------------
Write-Step 'Preparing the Python environment'
if (-not (Test-Path -LiteralPath $VenvPython)) {
    Write-Info ('Creating venv at ' + $Venv + ' ...')
    Invoke-Py -m venv $Venv
    if ($LASTEXITCODE -ne 0) { Write-Err2 'Failed to create the venv.'; exit 1 }
}
Write-Info 'Installing dependencies (this may take a minute) ...'
& $VenvPython -m pip install --upgrade pip --quiet
& $VenvPython -m pip install -r (Join-Path $Bridge 'requirements.txt') --quiet
if ($LASTEXITCODE -ne 0) { Write-Err2 'Dependency install failed.'; exit 1 }
Write-Ok 'Dependencies installed'

# --- 3. .env (backup + safe recovery) -------------------------------------
Write-Step 'Writing configuration'
$envBackup = $null
if (-not (Test-Path -LiteralPath $envPath)) {
    Copy-Item -LiteralPath (Join-Path $RepoRoot '.env.example') -Destination $envPath
    Write-Ok 'Created bridge\.env from .env.example'
} else {
    $envBackup = $envPath + '.bak-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
    Copy-Item -LiteralPath $envPath -Destination $envBackup -Force
    Write-Info ('Backed up existing .env to ' + (Split-Path $envBackup -Leaf))
}

try {
    $envLines = @(Get-Content -LiteralPath $envPath -ErrorAction SilentlyContinue)
    $envLines = Set-EnvBinding -Lines $envLines -HostAddress $effectiveHost -Port $effectivePort
    Write-EnvFile -Path $envPath -Lines $envLines

    $written = @(Get-Content -LiteralPath $envPath -ErrorAction SilentlyContinue)
    if (-not (Test-EnvHasBinding -Lines $written)) {
        throw 'the written .env is missing an MM_HOST or MM_PORT line'
    }
} catch {
    Write-Err2 ('Updating .env failed: ' + $_.Exception.Message)
    if ($envBackup -and (Test-Path -LiteralPath $envBackup)) {
        Copy-Item -LiteralPath $envBackup -Destination $envPath -Force
        Write-Warn2 ('Restored .env from ' + (Split-Path $envBackup -Leaf))
    }
    exit 1
}

Write-Ok ('Bridge will listen on ' + $effectiveHost + ':' + $effectivePort)
if (-not $explicitHost -and $existingHost) { Write-Info 'Kept existing MM_HOST (pass -BindHost to change).' }
if (-not $explicitPort -and $existingPort) { Write-Info 'Kept existing MM_PORT (pass -Port to change).' }
if ($envBackup) { Write-Info ('Previous .env saved as ' + (Split-Path $envBackup -Leaf)) }

# --- 4. Login account -----------------------------------------------------
Push-Location -LiteralPath $Bridge
try {
    Write-Step 'Creating your login account'
    $userList = (& $VenvPython -m app.cli list-users 2>&1 | Out-String)
    if ($userList -match 'No users configured') {
        $username = Read-Host '  Choose a username'
        if ([string]::IsNullOrWhiteSpace($username)) { Write-Err2 'Username required.'; exit 1 }
        & $VenvPython -m app.cli create-user $username
        if ($LASTEXITCODE -ne 0) { Write-Err2 'Could not create the account.'; exit 1 }
        Write-Ok ('Account "' + $username + '" created')
    } else {
        Write-Info 'An account already exists - skipping. Use the CLI to add more.'
    }

    # --- 5. Accounts config -------------------------------------------------
    Write-Step 'Checking the Natro accounts config'
    $accounts = Join-Path $Bridge 'config\accounts.json'
    if (-not (Test-Path -LiteralPath $accounts)) {
        Copy-Item -LiteralPath (Join-Path $Bridge 'config\accounts.example.json') -Destination $accounts
        Write-Warn2 'Created bridge\config\accounts.json from the example.'
        Write-Warn2 'Edit it so each natro_dir points at your Natro Macro folder.'
    } else {
        Write-Ok 'accounts.json present'
    }

    # --- 6. Firewall --------------------------------------------------------
    if (-not $SkipFirewall) {
        Write-Step 'Firewall rule (LAN + Tailscale only)'
        if (Test-Admin) {
            $existing = Get-NetFirewallRule -DisplayName $FirewallRule -ErrorAction SilentlyContinue
            $existingPort = $null
            if ($existing) {
                $existingPort = @($existing |
                    Get-NetFirewallPortFilter -ErrorAction SilentlyContinue |
                    Select-Object -ExpandProperty LocalPort) -join ','
            }
            $action = Get-FirewallAction -RuleExists ([bool]$existing) -ExistingPort $existingPort -DesiredPort $effectivePort
            switch ($action.Action) {
                'create' {
                    New-NetFirewallRule -DisplayName $FirewallRule -Direction Inbound -Action Allow -Protocol TCP -LocalPort $effectivePort -Profile Private,Domain | Out-Null
                    Write-Ok ('Added inbound rule for TCP ' + $effectivePort + ' (Private + Domain)')
                }
                'update' {
                    Remove-NetFirewallRule -DisplayName $FirewallRule -ErrorAction SilentlyContinue
                    New-NetFirewallRule -DisplayName $FirewallRule -Direction Inbound -Action Allow -Protocol TCP -LocalPort $effectivePort -Profile Private,Domain | Out-Null
                    Write-Ok ('Updated the firewall rule to TCP ' + $effectivePort + ' (was ' + $existingPort + ')')
                }
                'keep' {
                    Write-Info ('Firewall rule already present for TCP ' + $effectivePort)
                }
            }
        } else {
            Write-Warn2 'Not elevated - skipped the firewall rule.'
            Write-Warn2 'Run as Administrator, or add it manually:'
            Write-Warn2 ('  netsh advfirewall firewall add rule name="' + $FirewallRule + '" dir=in action=allow protocol=TCP localport=' + $effectivePort + ' profile=private,domain')
        }
    }

    # --- 7. Autostart task --------------------------------------------------
    if (-not $SkipTask) {
        Write-Step 'Auto-start at logon'
        if (Test-Admin) {
            $guard = Join-Path $Bridge 'run_bridge_guard.ps1'
            $argString = '-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + $guard + '"'
            $action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $argString -WorkingDirectory $Bridge
            $trigger = New-ScheduledTaskTrigger -AtLogOn
            $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
            Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings -Description 'Keeps the Macro Monitor bridge running.' -Force | Out-Null
            Write-Ok ('Scheduled task "' + $TaskName + '" registered')
        } else {
            Write-Warn2 'Not elevated - skipped the auto-start task.'
        }
    }

    # --- 7b. Optional Tailscale (off-LAN access) ----------------------------
    if ($SkipTailscale) {
        Write-Step 'Off-LAN phone access (Tailscale)'
        Write-Info 'Skipped (-SkipTailscale). Your bridge is reachable on the LAN/Tailscale as before.'
    } else {
        Write-Step 'Off-LAN phone access (Tailscale)'
        $wantTailscale = $false
        if ($InstallTailscale) {
            $wantTailscale = $true
        } else {
            $answer = Read-Host '  Install Tailscale so the app can reach the bridge off your LAN/tailnet? (y/N)'
            $wantTailscale = ($answer -match '^[Yy]')
        }
        if ($wantTailscale) {
            if (Test-Admin) {
                [void](Install-TailscaleClient)
                Write-Info 'Next: run "tailscale up" and sign in, then point the app at this PC''s 100.x.y.z address.'
            } else {
                Write-Warn2 'Not elevated - cannot install Tailscale. Install it from https://tailscale.com/download or re-run as Administrator.'
            }
        } else {
            Write-Info 'Skipped Tailscale. Your bridge is still reachable on the LAN; re-run and answer y to add it later.'
        }
    }

    # --- 8. Start + QR ------------------------------------------------------
    if (-not $NoStart) {
        Write-Step 'Starting the bridge'
        if ($SkipTask) {
            Write-Warn2 'SkipTask set - not managing the auto-start task.'
            Write-Warn2 ('Start it yourself so the new code runs:  & "' + $VenvPython + '" run.py   (from the bridge folder)')
        } elseif (Test-Admin) {
            [void](Stop-ExistingBridge -BridgePort $effectivePort)
            Start-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
        } else {
            Write-Warn2 'Not elevated - restart the auto-start task yourself:'
            Write-Warn2 ('  Stop-ScheduledTask -TaskName ' + $TaskName + '; Start-ScheduledTask -TaskName ' + $TaskName)
        }
        if (-not $SkipTask) {
            $health = $null
            for ($i = 0; $i -lt 10; $i++) {
                try {
                    $health = Invoke-RestMethod -Uri ('http://127.0.0.1:' + $effectivePort + '/health') -TimeoutSec 2
                    break
                } catch { Start-Sleep -Milliseconds 700 }
            }
            if ($health) {
                Write-Ok ('Bridge is up (version ' + $health.bridge_version + ')')
            } else {
                Write-Warn2 'Bridge did not answer yet. Start it with:'
                Write-Warn2 ('  & "' + $VenvPython + '" run.py   (from the bridge folder)')
            }
        }
    }

    Write-Step 'Pairing QR (scan with the app)'
    & $VenvPython -m app.cli pair
}
finally {
    Pop-Location
}

Write-Host ''
Write-Host 'Done.' -ForegroundColor Magenta
Write-Info 'Bridge:  http://<this-PC>:' + $effectivePort
Write-Info ('Logs:    ' + $Bridge + '\logs')
Write-Info ('Health:  http://127.0.0.1:' + $effectivePort + '/health')
Write-Host ''
