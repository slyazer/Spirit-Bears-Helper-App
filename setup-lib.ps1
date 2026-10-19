# setup-lib.ps1 - pure helper functions for setup.ps1.
# Kept separate so the upgrade-critical binding logic can be unit tested
# without running the interactive installer. Dot-sourced by setup.ps1.

# Read MM_HOST / MM_PORT out of a .env file's lines.
# Returns an object with Host/Port (either may be $null when absent).
function Get-EnvBinding {
    param([string[]]$Lines)
    $hostVal = $null
    $portVal = $null
    foreach ($line in @($Lines)) {
        if ($line -match '^\s*MM_HOST\s*=\s*(.+?)\s*$') { $hostVal = $Matches[1].Trim() }
        if ($line -match '^\s*MM_PORT\s*=\s*(.+?)\s*$') { $portVal = $Matches[1].Trim() }
    }
    return [pscustomobject]@{ Host = $hostVal; Port = $portVal }
}

# Decide the host/port to write on this run.
# Existing values are preserved unless the matching -Explicit* switch is set
# (i.e. the user passed -BindHost / -Port). Falls back to the parameter
# defaults when there is no existing value.
function Get-EffectiveBinding {
    param(
        [string]$ExistingHost,
        [string]$ExistingPort,
        [string]$BindHost,
        [int]$Port,
        [switch]$ExplicitHost,
        [switch]$ExplicitPort
    )
    $effectiveHost = if ($ExplicitHost) { $BindHost } elseif ($ExistingHost) { $ExistingHost } else { $BindHost }
    # Parse defensively: a corrupted MM_PORT must not throw here. The caller
    # validates the raw text with Test-BridgePortText and reports it clearly
    # before we ever get this far; this fallback only keeps the function total.
    $effectivePort = $Port
    if (-not $ExplicitPort -and $ExistingPort) {
        $parsed = 0
        if ([int]::TryParse([string]$ExistingPort, [ref]$parsed)) { $effectivePort = $parsed }
    }
    return [pscustomobject]@{ Host = $effectiveHost; Port = $effectivePort }
}

# Produce new .env lines: strip any existing MM_HOST/MM_PORT and append the
# effective ones (so exactly one of each remains). Other lines are preserved.
function Set-EnvBinding {
    param(
        [string[]]$Lines,
        [string]$HostAddress,
        [int]$Port
    )
    $out = @(@($Lines) | Where-Object { $_ -notmatch '^\s*MM_HOST\s*=' -and $_ -notmatch '^\s*MM_PORT\s*=' })
    $out += ('MM_HOST=' + $HostAddress)
    $out += ('MM_PORT=' + $Port)
    return $out
}

# True when a "major.minor" version string meets the required minimum.
# Handles a future major bump correctly (Python 4.0 passes a "3.10" gate).
function Test-PythonVersion {
    param(
        [string]$Version,
        [int]$MinMajor = 3,
        [int]$MinMinor = 10
    )
    if ([string]::IsNullOrWhiteSpace($Version)) { return $false }
    $parts = $Version.Trim().Split('.')
    if ($parts.Count -lt 2) { return $false }
    $major = 0
    $minor = 0
    if (-not [int]::TryParse($parts[0], [ref]$major)) { return $false }
    if (-not [int]::TryParse($parts[1], [ref]$minor)) { return $false }
    if ($major -ne $MinMajor) { return ($major -gt $MinMajor) }
    return ($minor -ge $MinMinor)
}

# True when the value is a usable TCP port.
function Test-BridgePort {
    param([int]$Port)
    return ($Port -ge 1 -and $Port -le 65535)
}

# True when the *text* is a usable TCP port (1-65535). Uses TryParse so a
# corrupted MM_PORT read from .env ("Any", "abc", "", "99999") is rejected
# BEFORE any [int] cast can throw.
function Test-BridgePortText {
    param([string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) { return $false }
    $parsed = 0
    if (-not [int]::TryParse($Text.Trim(), [ref]$parsed)) { return $false }
    return ($parsed -ge 1 -and $parsed -le 65535)
}

# Locate the repository root whether this runs as a .ps1 or a compiled EXE.
# A candidate qualifies only if it contains BOTH setup-lib.ps1 and bridge\. In a
# compiled EXE $PSScriptRoot is empty, so the entry scripts pass the EXE's own
# directory as an additional candidate (see setup.ps1 / uninstall.ps1).
function Resolve-RepoRoot {
    param([string[]]$Candidates)
    foreach ($c in @($Candidates)) {
        if ([string]::IsNullOrWhiteSpace($c)) { continue }
        if ((Test-Path -LiteralPath (Join-Path $c 'setup-lib.ps1')) -and
            (Test-Path -LiteralPath (Join-Path $c 'bridge'))) {
            return (Resolve-Path -LiteralPath $c).Path
        }
    }
    return $null
}

# Decide how to provide Tailscale for off-LAN (tailnet) phone access.
#   'skip'     - the user did not ask for it
#   'already'  - a Tailscale client is already present; nothing to install
#   'winget'   - install via winget (preferred)
#   'download' - fall back to the official MSI when winget is unavailable
function Get-TailscaleInstallAction {
    param(
        [bool]$Requested,
        [bool]$AlreadyInstalled,
        [bool]$WingetAvailable
    )
    if (-not $Requested) { return 'skip' }
    if ($AlreadyInstalled) { return 'already' }
    if ($WingetAvailable) { return 'winget' }
    return 'download'
}

# True when a process command line belongs to THIS install's bridge, so the
# uninstaller stops only its own processes. Matching the venv interpreter path
# (unique per install) or the guard script inside this bridge folder is much
# narrower than "the command line merely contains the folder path", which could
# hit unrelated processes.
function Test-BridgeProcessCommandLine {
    param(
        [string]$CommandLine,
        [string]$VenvPython,
        [string]$GuardScript
    )
    if ([string]::IsNullOrWhiteSpace($CommandLine)) { return $false }
    if ($VenvPython -and $CommandLine -like ('*' + $VenvPython + '*')) { return $true }
    if ($GuardScript -and $CommandLine -like ('*' + $GuardScript + '*')) { return $true }
    return $false
}

# Decide what to do about the inbound firewall rule for the desired port.
# Returns Action = 'create' (no rule), 'update' (rule on a different port),
# or 'keep' (rule already on the desired port). Never touches the firewall.
function Get-FirewallAction {
    param(
        [bool]$RuleExists,
        [string]$ExistingPort,
        [int]$DesiredPort
    )
    if (-not $RuleExists) { return [pscustomobject]@{ Action = 'create' } }
    $existing = 0
    if (-not [int]::TryParse($ExistingPort, [ref]$existing)) {
        # Non-numeric scope (e.g. "Any", "8787,8788"): rebuild the rule.
        return [pscustomobject]@{ Action = 'update' }
    }
    if ($existing -ne $DesiredPort) {
        return [pscustomobject]@{ Action = 'update' }
    }
    return [pscustomobject]@{ Action = 'keep' }
}

# Write .env lines as UTF-8 WITHOUT a byte-order mark. PS 5.1's
# `Set-Content -Encoding UTF8` prepends a BOM, which python-dotenv may read as
# part of the first key; a BOM-free file avoids that class of bug.
function Write-EnvFile {
    param(
        [string]$Path,
        [string[]]$Lines
    )
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllLines($Path, [string[]]$Lines, $encoding)
}

# True when the lines define exactly one MM_HOST and one MM_PORT line.
function Test-EnvHasBinding {
    param([string[]]$Lines)
    $hostCount = @(@($Lines) | Where-Object { $_ -match '^MM_HOST=' }).Count
    $portCount = @(@($Lines) | Where-Object { $_ -match '^MM_PORT=' }).Count
    return ($hostCount -eq 1 -and $portCount -eq 1)
}
