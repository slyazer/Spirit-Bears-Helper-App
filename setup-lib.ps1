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
    $effectivePort = if ($ExplicitPort) { $Port } elseif ($ExistingPort) { [int]$ExistingPort } else { $Port }
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
