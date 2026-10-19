# roblox-population-lib.ps1
#
# Pure, side-effect-free helpers for the Roblox private-server population
# capture agent. Dot-source this file to get the parser, the ingest-body
# builder, the single-flight lock, and the screenshot cleanup helper.
#
# Nothing here reads the registry, the network, or the filesystem layout; the
# capture script owns that. Keeping the logic pure is what makes it testable
# with Pester (see tests/roblox-population.Tests.ps1).
#
# The parser treats ALL OCR output as untrusted: it only ever returns the two
# integers (playing, max_players), and only when the target server's row is
# identifiable and the pair passes range checks. No player names, ids, tokens,
# or access codes are ever extracted or returned.
#
# Assumption: within one private-server record the `vipServerId` key precedes
# its `playing` / `maxPlayers` keys (as the page renders them). Counts are
# attributed to the nearest preceding vipServerId, so a multi-server page still
# binds each count to the right row. A single-server page (the common case) is
# unambiguous either way.

Set-StrictMode -Version Latest

# -- validation ------------------------------------------------------------

function Test-PopulationReading {
    <#
      .SYNOPSIS
      True when a (playing, max_players) pair is a plausible population reading.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][long]$Playing,
        [Parameter(Mandatory)][long]$MaxPlayers,
        [long]$MaxPlayersBound = 1000
    )
    if ($MaxPlayers -lt 1 -or $MaxPlayers -gt $MaxPlayersBound) { return $false }
    if ($Playing -lt 0 -or $Playing -gt $MaxPlayers) { return $false }
    return $true
}

function New-PopulationSuccess {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][long]$Playing,
        [Parameter(Mandatory)][long]$MaxPlayers
    )
    # Callers validate before reaching here, so both fit in an int.
    [pscustomobject]@{ Ok = $true; Playing = [int]$Playing; MaxPlayers = [int]$MaxPlayers; Reason = $null }
}

function New-PopulationFailure {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Reason)
    [pscustomobject]@{ Ok = $false; Playing = $null; MaxPlayers = $null; Reason = $Reason }
}

# -- internal parsing helpers ----------------------------------------------

function Get-FirstInteger {
    # First signed integer token in the string, or $null. Catches negatives so
    # the parser can reject them rather than silently dropping the sign.
    param([AllowEmptyString()][string]$Line)
    if ([string]::IsNullOrEmpty($Line)) { return $null }
    $m = [regex]::Match($Line, '-?\d+')
    if (-not $m.Success) { return $null }
    # [long], not [int]: a vipServerId is a 64-bit id.
    return [long]$m.Value
}

function Get-PopulationLabelsOnLine {
    # Find every population-related key on a line, tolerant of OCR noise and of
    # several keys sharing a line (minified JSON). Returns objects with
    # Kind ('Vip'|'Play'|'Max'), Start and End (end is exclusive).
    #
    #   * max\w*                     -> Max   (matches "maxPlayers", "maxP1ayers")
    #   * play\w* but not "player*"  -> Play  (excludes "players", "playerTokens")
    #   * vip\w*serv\w*[il1]d        -> Vip   (the id key only, never accessCode)
    param([AllowEmptyString()][string]$Line)
    if ([string]::IsNullOrEmpty($Line)) { return @() }

    $candidates = @()
    foreach ($m in [regex]::Matches($Line, '(?i)max\w*')) {
        $candidates += [pscustomobject]@{ Kind = 'Max'; Start = $m.Index; End = ($m.Index + $m.Length) }
    }
    foreach ($m in [regex]::Matches($Line, '(?i)vip\w*serv\w*[il1]d')) {
        $candidates += [pscustomobject]@{ Kind = 'Vip'; Start = $m.Index; End = ($m.Index + $m.Length) }
    }
    foreach ($m in [regex]::Matches($Line, '(?i)play\w*')) {
        if ($m.Value -match '(?i)^play.*er') { continue }   # players / playerTokens
        $candidates += [pscustomobject]@{ Kind = 'Play'; Start = $m.Index; End = ($m.Index + $m.Length) }
    }

    # Drop overlapping matches (e.g. the "play" inside "maxPlayers"): keep the
    # left-most, and on a tie prefer Max (it is always the intended label).
    $accepted = @()
    foreach ($c in ($candidates | Sort-Object Start)) {
        $clash = $false
        foreach ($a in $accepted) {
            if ($c.Start -lt $a.End -and $c.End -gt $a.Start) { $clash = $true; break }
        }
        if (-not $clash) { $accepted += $c }
    }
    return ($accepted | Sort-Object Start)
}

function Get-IntegerOnFollowingLines {
    # First integer on the next couple of non-empty lines (stacked layouts).
    # A following line that is itself a label line is never treated as a value,
    # so a missing value cannot silently borrow the next key's number.
    param([string[]]$Lines, [int]$Index)
    $last = [Math]::Min($Index + 2, $Lines.Count - 1)
    for ($j = $Index + 1; $j -le $last; $j++) {
        if ([string]::IsNullOrWhiteSpace($Lines[$j])) { continue }
        if (@(Get-PopulationLabelsOnLine -Line $Lines[$j]).Count -gt 0) { return $null }
        return (Get-FirstInteger -Line $Lines[$j])
    }
    return $null
}

# -- main parser -----------------------------------------------------------

function ConvertFrom-PopulationOcrText {
    <#
      .SYNOPSIS
        Extract (playing, max_players) for a specific private server from OCR text.

      .DESCRIPTION
        Locates every `vipServerId` anchor and every `playing` / `maxPlayers`
        value, then attributes each count to the nearest preceding anchor (by
        absolute text position). Only the row whose id equals -VipServerId is
        read, so data captured from a different server is rejected rather than
        mis-reported. If no anchor is present the capture is rejected unless
        -AllowUnmatchedServer is set (default: fail closed).

      .OUTPUTS
        A [pscustomobject] { Ok; Playing; MaxPlayers; Reason }. On failure Ok is
        $false, Playing/MaxPlayers are $null and Reason explains why.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][AllowEmptyString()][string]$Text,
        [string]$VipServerId,
        [int]$MaxPlayersBound = 1000,
        [switch]$AllowUnmatchedServer
    )

    if ([string]::IsNullOrWhiteSpace($Text)) {
        return (New-PopulationFailure 'OCR produced no text.')
    }

    $raw = $Text -split "`r`n|`n|`r"
    $lines = @()
    foreach ($l in $raw) { $lines += $l.Trim() }

    # Absolute position of each line's first character (1 spare char for the
    # trimmed newline) lets same-line and multi-line layouts share one ordering.
    $offsets = @()
    $acc = 0
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $offsets += $acc
        $acc += ($lines[$i].Length + 1)
    }

    $anchors = @()
    $readings = @()
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        $lineLabels = @(Get-PopulationLabelsOnLine -Line $line)
        for ($k = 0; $k -lt $lineLabels.Count; $k++) {
            $lab = $lineLabels[$k]
            # Look only between this label and the next key on the same line so a
            # value can never bleed into a neighbouring field.
            $segEnd = if ($k + 1 -lt $lineLabels.Count) { $lineLabels[$k + 1].Start } else { $line.Length }
            $segLen = [Math]::Max(0, $segEnd - $lab.End)
            $value = Get-FirstInteger -Line $line.Substring($lab.End, $segLen)
            if ($null -eq $value) { $value = Get-IntegerOnFollowingLines -Lines $lines -Index $i }
            if ($null -eq $value) { continue }
            $pos = $offsets[$i] + $lab.Start
            if ($lab.Kind -eq 'Vip') {
                $anchors += [pscustomobject]@{ Pos = $pos; Value = $value }
            }
            else {
                $readings += [pscustomobject]@{ Kind = $lab.Kind; Pos = $pos; Value = $value }
            }
        }
    }

    # Choose the target row.
    $target = $null
    if ($anchors.Count -eq 0) {
        if (-not $AllowUnmatchedServer) {
            return (New-PopulationFailure 'Could not find a vipServerId on the captured page; not accepting data from an unverified server.')
        }
    }
    else {
        if (-not [string]::IsNullOrWhiteSpace($VipServerId)) {
            $wanted = $VipServerId.Trim()
            $target = $anchors | Where-Object { "$($_.Value)" -eq $wanted } | Select-Object -First 1
            if ($null -eq $target) {
                return (New-PopulationFailure "Captured page did not contain the target server (vipServerId $wanted).")
            }
        }
        else {
            if ($anchors.Count -gt 1) {
                return (New-PopulationFailure 'Multiple servers are listed but no target vipServerId was supplied.')
            }
            $target = $anchors[0]
        }
    }

    # Attribute each reading to the nearest preceding anchor; keep the target's.
    $rowReadings = @()
    foreach ($r in $readings) {
        $owner = $null
        foreach ($a in $anchors) {
            if ($a.Pos -le $r.Pos -and ($null -eq $owner -or $a.Pos -gt $owner.Pos)) { $owner = $a }
        }
        if ($anchors.Count -eq 0) {
            $rowReadings += $r
        }
        elseif ($null -ne $owner -and $null -ne $target -and $owner.Pos -eq $target.Pos) {
            $rowReadings += $r
        }
    }

    $plays = @($rowReadings | Where-Object { $_.Kind -eq 'Play' } | ForEach-Object { $_.Value } | Select-Object -Unique)
    $maxes = @($rowReadings | Where-Object { $_.Kind -eq 'Max' } | ForEach-Object { $_.Value } | Select-Object -Unique)

    if ($plays.Count -eq 0) { return (New-PopulationFailure 'Could not read the player count ("playing") for the target server.') }
    if ($plays.Count -gt 1) { return (New-PopulationFailure 'Ambiguous player count: multiple conflicting "playing" values on the target row.') }
    if ($maxes.Count -eq 0) { return (New-PopulationFailure 'Could not read "maxPlayers" for the target server.') }
    if ($maxes.Count -gt 1) { return (New-PopulationFailure 'Ambiguous max players: multiple conflicting "maxPlayers" values on the target row.') }

    $playing = $plays[0]
    $maxPlayers = $maxes[0]
    if (-not (Test-PopulationReading -Playing $playing -MaxPlayers $maxPlayers -MaxPlayersBound $MaxPlayersBound)) {
        return (New-PopulationFailure "Rejected out-of-range reading (playing=$playing, maxPlayers=$maxPlayers).")
    }
    return (New-PopulationSuccess -Playing $playing -MaxPlayers $maxPlayers)
}

# -- ingest body -----------------------------------------------------------

function Get-PopulationIngestBody {
    <#
      .SYNOPSIS
        Build the JSON body for POST /api/v1/roblox/population. Only the two
        integers (and an optional capture timestamp) are ever included.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][int]$Playing,
        [Parameter(Mandatory)][int]$MaxPlayers,
        [string]$CapturedAt
    )
    $obj = [ordered]@{ playing = $Playing; max_players = $MaxPlayers }
    if (-not [string]::IsNullOrWhiteSpace($CapturedAt)) { $obj['captured_at'] = $CapturedAt }
    return ($obj | ConvertTo-Json -Compress)
}

# -- single-flight lock ----------------------------------------------------

function Enter-PopulationCaptureLock {
    <#
      .SYNOPSIS
        Acquire the capture lock, atomically. Returns $true if acquired, $false
        if another capture is already running. A lock older than -StaleSeconds
        (a crashed agent) is reclaimed.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Path,
        [int]$StaleSeconds = 600
    )
    $dir = Split-Path -Parent -Path $Path
    if ($dir -and -not (Test-Path -LiteralPath $dir)) {
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
    }
    try {
        $stream = [System.IO.File]::Open($Path, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
        $bytes = [System.Text.Encoding]::UTF8.GetBytes("$PID $(Get-Date -Format o)")
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Dispose()
        return $true
    }
    catch [System.IO.IOException] {
        try {
            if (Test-Path -LiteralPath $Path) {
                $age = (Get-Date) - (Get-Item -LiteralPath $Path).LastWriteTime
                if ($age.TotalSeconds -gt $StaleSeconds) {
                    Remove-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue
                    if (-not (Test-Path -LiteralPath $Path)) {
                        return (Enter-PopulationCaptureLock -Path $Path -StaleSeconds $StaleSeconds)
                    }
                }
            }
        }
        catch { }
        return $false
    }
}

function Exit-PopulationCaptureLock {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Path)
    if (Test-Path -LiteralPath $Path) {
        Remove-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue
    }
}

# -- artifact cleanup ------------------------------------------------------

function Remove-PopulationArtifact {
    <#
      .SYNOPSIS
        Delete a temp screenshot, tolerating a missing file. Returns $true when
        the file is gone afterwards (deleted now or already absent), else $false.
        Never throws - cleanup must not mask the real outcome of a capture.
    #>
    [CmdletBinding()]
    param([AllowEmptyString()][AllowNull()][string]$Path)
    if ([string]::IsNullOrEmpty($Path)) { return $true }
    if (-not (Test-Path -LiteralPath $Path)) { return $true }
    try {
        Remove-Item -LiteralPath $Path -Force -ErrorAction Stop
        return (-not (Test-Path -LiteralPath $Path))
    }
    catch { return $false }
}
