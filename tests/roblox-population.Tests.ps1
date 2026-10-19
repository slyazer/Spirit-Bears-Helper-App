# Pester tests for tools/roblox-population-lib.ps1 (the OCR parser, single-flight
# lock, and screenshot cleanup used by the capture agent).
# Run:  Invoke-Pester .\tests\roblox-population.Tests.ps1

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $here '..\tools\roblox-population-lib.ps1')

Describe 'Test-PopulationReading' {
    It 'accepts a valid pair' {
        Test-PopulationReading -Playing 3 -MaxPlayers 6 | Should Be $true
    }
    It 'accepts an empty server (0 of 6)' {
        Test-PopulationReading -Playing 0 -MaxPlayers 6 | Should Be $true
    }
    It 'accepts a full server (playing == max)' {
        Test-PopulationReading -Playing 6 -MaxPlayers 6 | Should Be $true
    }
    It 'rejects a negative player count' {
        Test-PopulationReading -Playing -1 -MaxPlayers 6 | Should Be $false
    }
    It 'rejects playing greater than max' {
        Test-PopulationReading -Playing 7 -MaxPlayers 6 | Should Be $false
    }
    It 'rejects max below 1' {
        Test-PopulationReading -Playing 0 -MaxPlayers 0 | Should Be $false
    }
    It 'rejects an implausibly large max' {
        Test-PopulationReading -Playing 1 -MaxPlayers 5000 | Should Be $false
    }
}

Describe 'ConvertFrom-PopulationOcrText parsing' {
    $vip = '3355460963'

    It 'reads playing then maxPlayers (one key per line)' {
        $text = "vipServerId $vip`nplaying 3`nmaxPlayers 6"
        $r = ConvertFrom-PopulationOcrText -Text $text -VipServerId $vip
        $r.Ok | Should Be $true
        $r.Playing | Should Be 3
        $r.MaxPlayers | Should Be 6
    }

    It 'reads maxPlayers before playing (either order)' {
        $text = "vipServerId $vip`nmaxPlayers 6`nplaying 3"
        $r = ConvertFrom-PopulationOcrText -Text $text -VipServerId $vip
        $r.Ok | Should Be $true
        $r.Playing | Should Be 3
        $r.MaxPlayers | Should Be 6
    }

    It 'reads a minified single-line record with several keys' {
        $text = "{`"vipServerId`":$vip,`"playing`":3,`"maxPlayers`":6}"
        $r = ConvertFrom-PopulationOcrText -Text $text -VipServerId $vip
        $r.Ok | Should Be $true
        $r.Playing | Should Be 3
        $r.MaxPlayers | Should Be 6
    }

    It 'handles label and value stacked on separate lines' {
        $text = "vipServerId`n$vip`nplaying`n3`nmaxPlayers`n6"
        $r = ConvertFrom-PopulationOcrText -Text $text -VipServerId $vip
        $r.Ok | Should Be $true
        $r.Playing | Should Be 3
        $r.MaxPlayers | Should Be 6
    }

    It 'tolerates CRLF, indentation and blank lines' {
        $text = "  vipServerId   $vip  `r`n`r`n    playing   3  `r`n`r`n    maxPlayers   6  `r`n"
        $r = ConvertFrom-PopulationOcrText -Text $text -VipServerId $vip
        $r.Ok | Should Be $true
        $r.Playing | Should Be 3
        $r.MaxPlayers | Should Be 6
    }

    It 'tolerates the misread label "maxP1ayers"' {
        $text = "vipServerId $vip`nplaying 3`nmaxP1ayers 6"
        $r = ConvertFrom-PopulationOcrText -Text $text -VipServerId $vip
        $r.Ok | Should Be $true
        $r.MaxPlayers | Should Be 6
    }

    It 'ignores the players and playerTokens keys' {
        $text = @"
vipServerId $vip
playing 3
players []
playerTokens ["abc","def"]
maxPlayers 6
"@
        $r = ConvertFrom-PopulationOcrText -Text $text -VipServerId $vip
        $r.Ok | Should Be $true
        $r.Playing | Should Be 3
        $r.MaxPlayers | Should Be 6
    }

    It 'reads the value from a colon-separated minified field of 10 players' {
        $text = "{`"vipServerId`":$vip,`"maxPlayers`":10,`"playing`":4}"
        $r = ConvertFrom-PopulationOcrText -Text $text -VipServerId $vip
        $r.Ok | Should Be $true
        $r.Playing | Should Be 4
        $r.MaxPlayers | Should Be 10
    }
}

Describe 'ConvertFrom-PopulationOcrText failure modes' {
    $vip = '3355460963'

    It 'rejects empty text' {
        (ConvertFrom-PopulationOcrText -Text '' -VipServerId $vip).Ok | Should Be $false
    }

    It 'rejects whitespace-only text' {
        (ConvertFrom-PopulationOcrText -Text "   `n  " -VipServerId $vip).Ok | Should Be $false
    }

    It 'rejects a missing playing field' {
        $text = "vipServerId $vip`nmaxPlayers 6"
        $r = ConvertFrom-PopulationOcrText -Text $text -VipServerId $vip
        $r.Ok | Should Be $false
        $r.Reason | Should Match 'playing'
    }

    It 'rejects a missing maxPlayers field' {
        $text = "vipServerId $vip`nplaying 3"
        $r = ConvertFrom-PopulationOcrText -Text $text -VipServerId $vip
        $r.Ok | Should Be $false
        $r.Reason | Should Match 'maxPlayers'
    }

    It 'rejects a malformed non-numeric value without borrowing the next key' {
        $text = "vipServerId $vip`nplaying abc`nmaxPlayers 6"
        $r = ConvertFrom-PopulationOcrText -Text $text -VipServerId $vip
        $r.Ok | Should Be $false
    }

    It 'rejects a negative player count' {
        $text = "vipServerId $vip`nplaying -1`nmaxPlayers 6"
        (ConvertFrom-PopulationOcrText -Text $text -VipServerId $vip).Ok | Should Be $false
    }

    It 'rejects playing greater than maxPlayers' {
        $text = "vipServerId $vip`nplaying 9`nmaxPlayers 6"
        (ConvertFrom-PopulationOcrText -Text $text -VipServerId $vip).Ok | Should Be $false
    }

    It 'rejects ambiguous duplicate values' {
        $text = "vipServerId $vip`nplaying 3`nplaying 5`nmaxPlayers 6"
        $r = ConvertFrom-PopulationOcrText -Text $text -VipServerId $vip
        $r.Ok | Should Be $false
        $r.Reason | Should Match 'Ambiguous'
    }

    It 'accepts duplicate identical values' {
        $text = "vipServerId $vip`nplaying 3`nplaying 3`nmaxPlayers 6`nmaxPlayers 6"
        $r = ConvertFrom-PopulationOcrText -Text $text -VipServerId $vip
        $r.Ok | Should Be $true
        $r.Playing | Should Be 3
        $r.MaxPlayers | Should Be 6
    }
}

Describe 'ConvertFrom-PopulationOcrText server matching' {
    $vip = '3355460963'

    It 'rejects when the target server is not on the page' {
        $text = @"
vipServerId 1111111111
playing 1
maxPlayers 12
"@
        $r = ConvertFrom-PopulationOcrText -Text $text -VipServerId $vip
        $r.Ok | Should Be $false
        $r.Reason | Should Match 'target server'
    }

    It 'selects the target row among multiple servers' {
        $text = @"
vipServerId 1111111111
playing 1
maxPlayers 12
vipServerId $vip
playing 3
maxPlayers 6
"@
        $r = ConvertFrom-PopulationOcrText -Text $text -VipServerId $vip
        $r.Ok | Should Be $true
        $r.Playing | Should Be 3
        $r.MaxPlayers | Should Be 6
    }

    It 'fails closed when no vipServerId is present' {
        $text = "playing 3`nmaxPlayers 6"
        $r = ConvertFrom-PopulationOcrText -Text $text -VipServerId $vip
        $r.Ok | Should Be $false
        $r.Reason | Should Match 'vipServerId'
    }

    It 'allows an unverified server only when explicitly permitted' {
        $text = "playing 3`nmaxPlayers 6"
        $r = ConvertFrom-PopulationOcrText -Text $text -VipServerId $vip -AllowUnmatchedServer
        $r.Ok | Should Be $true
        $r.Playing | Should Be 3
        $r.MaxPlayers | Should Be 6
    }

    It 'does not read an accessCode as the server id' {
        # vipServerAccessCode carries digits but must never satisfy the anchor.
        $text = @"
vipServerAccessCode "A1B2C3D4E5"
vipServerId $vip
playing 2
maxPlayers 6
"@
        $r = ConvertFrom-PopulationOcrText -Text $text -VipServerId $vip
        $r.Ok | Should Be $true
        $r.Playing | Should Be 2
        $r.MaxPlayers | Should Be 6
    }
}

Describe 'Get-PopulationIngestBody' {
    It 'emits only the two integers when no timestamp is given' {
        $json = Get-PopulationIngestBody -Playing 3 -MaxPlayers 6
        $obj = $json | ConvertFrom-Json
        $obj.playing | Should Be 3
        $obj.max_players | Should Be 6
        ($obj.PSObject.Properties.Name -contains 'captured_at') | Should Be $false
    }
    It 'includes captured_at when supplied' {
        $json = Get-PopulationIngestBody -Playing 3 -MaxPlayers 6 -CapturedAt '2026-10-18T12:00:00Z'
        $obj = $json | ConvertFrom-Json
        $obj.captured_at | Should Be '2026-10-18T12:00:00Z'
    }
    It 'never includes anything other than the population fields' {
        $json = Get-PopulationIngestBody -Playing 3 -MaxPlayers 6 -CapturedAt '2026-10-18T12:00:00Z'
        $obj = $json | ConvertFrom-Json
        @($obj.PSObject.Properties.Name).Count | Should Be 3
    }
}

Describe 'capture lock (single-flight)' {
    It 'acquires once and reports busy while held' {
        $lock = Join-Path $env:TEMP ('mm-lock-' + [guid]::NewGuid().ToString('N') + '.lock')
        try {
            (Enter-PopulationCaptureLock -Path $lock) | Should Be $true
            (Enter-PopulationCaptureLock -Path $lock) | Should Be $false
        }
        finally {
            Exit-PopulationCaptureLock -Path $lock
        }
    }
    It 'releases so a later capture can acquire' {
        $lock = Join-Path $env:TEMP ('mm-lock-' + [guid]::NewGuid().ToString('N') + '.lock')
        (Enter-PopulationCaptureLock -Path $lock) | Should Be $true
        Exit-PopulationCaptureLock -Path $lock
        try {
            (Enter-PopulationCaptureLock -Path $lock) | Should Be $true
        }
        finally {
            Exit-PopulationCaptureLock -Path $lock
        }
    }
    It 'reclaims a stale lock' {
        $lock = Join-Path $env:TEMP ('mm-lock-' + [guid]::NewGuid().ToString('N') + '.lock')
        Set-Content -LiteralPath $lock -Value 'stale'
        (Get-Item -LiteralPath $lock).LastWriteTime = (Get-Date).AddHours(-2)
        try {
            (Enter-PopulationCaptureLock -Path $lock -StaleSeconds 600) | Should Be $true
        }
        finally {
            Exit-PopulationCaptureLock -Path $lock
        }
    }
}

Describe 'Remove-PopulationArtifact (screenshot cleanup)' {
    It 'returns true and deletes an existing file' {
        $f = Join-Path $env:TEMP ('mm-shot-' + [guid]::NewGuid().ToString('N') + '.png')
        Set-Content -LiteralPath $f -Value 'x'
        (Remove-PopulationArtifact -Path $f) | Should Be $true
        (Test-Path -LiteralPath $f) | Should Be $false
    }
    It 'returns true for a file that is already gone' {
        $f = Join-Path $env:TEMP ('mm-shot-' + [guid]::NewGuid().ToString('N') + '.png')
        (Remove-PopulationArtifact -Path $f) | Should Be $true
    }
    It 'returns true for an empty or null path' {
        (Remove-PopulationArtifact -Path '') | Should Be $true
        (Remove-PopulationArtifact -Path $null) | Should Be $true
    }
}
