# Pester tests for setup-lib.ps1 (the upgrade-critical binding logic).
# Run:  Invoke-Pester .\tests\setup-lib.Tests.ps1

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $here '..\setup-lib.ps1')

Describe 'Get-EnvBinding' {
    It 'reads MM_HOST and MM_PORT' {
        $b = Get-EnvBinding -Lines @('# comment', 'MM_HOST=10.0.0.5', 'MM_PORT=9000', 'MM_STALE_AFTER_SECONDS=180')
        $b.Host | Should Be '10.0.0.5'
        $b.Port | Should Be '9000'
    }
    It 'tolerates surrounding whitespace' {
        $b = Get-EnvBinding -Lines @('  MM_HOST = 192.168.1.9 ', 'MM_PORT = 1234')
        $b.Host | Should Be '192.168.1.9'
        $b.Port | Should Be '1234'
    }
    It 'returns empty when absent' {
        $b = Get-EnvBinding -Lines @('MM_USERS_FILE=config/users.json')
        $b.Host | Should BeNullOrEmpty
        $b.Port | Should BeNullOrEmpty
    }
}

Describe 'Get-EffectiveBinding' {
    It 'uses defaults for a fresh install' {
        $r = Get-EffectiveBinding -BindHost '0.0.0.0' -Port 8787
        $r.Host | Should Be '0.0.0.0'
        $r.Port | Should Be 8787
    }
    It 'preserves existing values when not explicit' {
        $r = Get-EffectiveBinding -ExistingHost '10.0.0.5' -ExistingPort '9000' -BindHost '0.0.0.0' -Port 8787
        $r.Host | Should Be '10.0.0.5'
        $r.Port | Should Be 9000
    }
    It 'overrides only the port when explicitly passed' {
        $r = Get-EffectiveBinding -ExistingHost '10.0.0.5' -ExistingPort '9000' -BindHost '0.0.0.0' -Port 8787 -ExplicitPort
        $r.Host | Should Be '10.0.0.5'
        $r.Port | Should Be 8787
    }
    It 'overrides only the host when explicitly passed' {
        $r = Get-EffectiveBinding -ExistingHost '10.0.0.5' -ExistingPort '9000' -BindHost '127.0.0.1' -Port 8787 -ExplicitHost
        $r.Host | Should Be '127.0.0.1'
        $r.Port | Should Be 9000
    }
    It 'overrides both when both are explicit' {
        $r = Get-EffectiveBinding -ExistingHost '10.0.0.5' -ExistingPort '9000' -BindHost '127.0.0.1' -Port 1234 -ExplicitHost -ExplicitPort
        $r.Host | Should Be '127.0.0.1'
        $r.Port | Should Be 1234
    }
}

Describe 'Set-EnvBinding' {
    It 'replaces existing MM_HOST/MM_PORT and keeps other lines' {
        $lines = @('MM_HOST=0.0.0.0', 'MM_PORT=8787', 'MM_USERS_FILE=config/users.json')
        $out = Set-EnvBinding -Lines $lines -HostAddress '10.0.0.5' -Port 9000
        @($out | Where-Object { $_ -match '^MM_HOST=' }).Count | Should Be 1
        @($out | Where-Object { $_ -match '^MM_PORT=' }).Count | Should Be 1
        ($out -contains 'MM_HOST=10.0.0.5') | Should Be $true
        ($out -contains 'MM_PORT=9000') | Should Be $true
        ($out -contains 'MM_USERS_FILE=config/users.json') | Should Be $true
        ($out -contains 'MM_HOST=0.0.0.0') | Should Be $false
    }
    It 'appends when no binding lines exist' {
        $out = Set-EnvBinding -Lines @('MM_RATE_LIMIT_PER_MINUTE=120') -HostAddress '0.0.0.0' -Port 8787
        ($out -contains 'MM_HOST=0.0.0.0') | Should Be $true
        ($out -contains 'MM_PORT=8787') | Should Be $true
    }
    It 'collapses duplicate binding lines' {
        $out = Set-EnvBinding -Lines @('MM_HOST=a', 'MM_HOST=b', 'MM_PORT=1', 'MM_PORT=2') -HostAddress 'c' -Port 3
        @($out | Where-Object { $_ -match '^MM_HOST=' }).Count | Should Be 1
        @($out | Where-Object { $_ -match '^MM_PORT=' }).Count | Should Be 1
        ($out -contains 'MM_HOST=c') | Should Be $true
        ($out -contains 'MM_PORT=3') | Should Be $true
    }
}

Describe '.env upgrade round trip' {
    # Simulate an installer re-run (default params, nothing explicit) on a custom .env.
    $tmp = Join-Path $env:TEMP ('mm-envtest-' + [guid]::NewGuid().ToString('N') + '.env')
    @('MM_HOST=10.0.0.5', 'MM_PORT=9000', 'MM_USERS_FILE=config/users.json') |
        Set-Content -LiteralPath $tmp -Encoding UTF8

    $existing = Get-EnvBinding -Lines (Get-Content -LiteralPath $tmp)
    $eff = Get-EffectiveBinding -ExistingHost $existing.Host -ExistingPort $existing.Port -BindHost '0.0.0.0' -Port 8787
    $newLines = Set-EnvBinding -Lines (Get-Content -LiteralPath $tmp) -HostAddress $eff.Host -Port $eff.Port
    $newLines | Set-Content -LiteralPath $tmp -Encoding UTF8
    $after = Get-EnvBinding -Lines (Get-Content -LiteralPath $tmp)

    It 'preserves a custom MM_HOST on re-run' { $after.Host | Should Be '10.0.0.5' }
    It 'preserves a custom MM_PORT on re-run' { $after.Port | Should Be '9000' }
    It 'preserves unrelated lines' {
        ((Get-Content -LiteralPath $tmp) -contains 'MM_USERS_FILE=config/users.json') | Should Be $true
    }

    Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
}

Describe 'Test-PythonVersion' {
    It 'accepts the current 3.x line at or above the minimum' {
        (Test-PythonVersion -Version '3.10') | Should Be $true
        (Test-PythonVersion -Version '3.14') | Should Be $true
        (Test-PythonVersion -Version '3.10.11') | Should Be $true
    }
    It 'rejects versions below the minimum' {
        (Test-PythonVersion -Version '3.9') | Should Be $false
        (Test-PythonVersion -Version '2.7') | Should Be $false
    }
    It 'accepts a future major release (4.0) for a 3.10 gate' {
        (Test-PythonVersion -Version '4.0') | Should Be $true
        (Test-PythonVersion -Version '4.0.0') | Should Be $true
    }
    It 'rejects blank, short, and non-numeric input' {
        (Test-PythonVersion -Version '') | Should Be $false
        (Test-PythonVersion -Version '3') | Should Be $false
        (Test-PythonVersion -Version 'three.ten') | Should Be $false
    }
}

Describe 'Test-BridgePort' {
    It 'accepts in-range TCP ports' {
        (Test-BridgePort -Port 1) | Should Be $true
        (Test-BridgePort -Port 8787) | Should Be $true
        (Test-BridgePort -Port 65535) | Should Be $true
    }
    It 'rejects out-of-range ports' {
        (Test-BridgePort -Port 0) | Should Be $false
        (Test-BridgePort -Port -1) | Should Be $false
        (Test-BridgePort -Port 65536) | Should Be $false
    }
}

Describe 'Get-FirewallAction' {
    It 'creates a rule when none exists' {
        (Get-FirewallAction -RuleExists $false -ExistingPort '' -DesiredPort 8787).Action | Should Be 'create'
    }
    It 'keeps a rule already on the desired port' {
        (Get-FirewallAction -RuleExists $true -ExistingPort '8787' -DesiredPort 8787).Action | Should Be 'keep'
    }
    It 'updates a rule left on a different port by a previous install' {
        (Get-FirewallAction -RuleExists $true -ExistingPort '9000' -DesiredPort 8787).Action | Should Be 'update'
    }
    It 'updates when the existing rule scope is not a single port' {
        (Get-FirewallAction -RuleExists $true -ExistingPort '8787,8788' -DesiredPort 8787).Action | Should Be 'update'
        (Get-FirewallAction -RuleExists $true -ExistingPort 'Any' -DesiredPort 8787).Action | Should Be 'update'
        (Get-FirewallAction -RuleExists $true -ExistingPort '' -DesiredPort 8787).Action | Should Be 'update'
    }
}

Describe 'Write-EnvFile / Test-EnvHasBinding' {
    It 'writes UTF-8 without a byte-order mark' {
        $tmp = Join-Path $env:TEMP ('mm-envfile-' + [guid]::NewGuid().ToString('N') + '.env')
        Write-EnvFile -Path $tmp -Lines @('MM_HOST=0.0.0.0', 'MM_PORT=8787')

        $bytes = [System.IO.File]::ReadAllBytes($tmp)
        $hasBom = ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF)
        $hasBom | Should Be $false
        ($bytes[0] -eq [byte][char]'M') | Should Be $true
        Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
    }
    It 'round-trips the lines it was given' {
        $tmp = Join-Path $env:TEMP ('mm-envfile-' + [guid]::NewGuid().ToString('N') + '.env')
        Write-EnvFile -Path $tmp -Lines @('# comment', 'MM_HOST=10.0.0.5', 'MM_PORT=9000')
        $read = @(Get-Content -LiteralPath $tmp)
        ($read -contains 'MM_HOST=10.0.0.5') | Should Be $true
        ($read -contains 'MM_PORT=9000') | Should Be $true
        Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
    }
    It 'detects a well-formed binding and rejects incomplete ones' {
        (Test-EnvHasBinding -Lines @('MM_HOST=0.0.0.0', 'MM_PORT=8787')) | Should Be $true
        (Test-EnvHasBinding -Lines @('MM_HOST=0.0.0.0')) | Should Be $false
        (Test-EnvHasBinding -Lines @('MM_PORT=8787')) | Should Be $false
        (Test-EnvHasBinding -Lines @('MM_HOST=a', 'MM_HOST=b', 'MM_PORT=8787')) | Should Be $false
    }
}

Describe 'upgrade changes the port: end to end decision' {
    It 'preserves a custom host but moves the firewall rule to an explicit port' {
        $existing = Get-EnvBinding -Lines @('MM_HOST=10.0.0.5', 'MM_PORT=9000')
        $eff = Get-EffectiveBinding -ExistingHost $existing.Host -ExistingPort $existing.Port `
            -BindHost '0.0.0.0' -Port 9001 -ExplicitPort
        $eff.Host | Should Be '10.0.0.5'
        $eff.Port | Should Be 9001
        (Get-FirewallAction -RuleExists $true -ExistingPort '9000' -DesiredPort $eff.Port).Action | Should Be 'update'
    }
}

Describe 'Test-BridgePortText' {
    It 'accepts in-range numeric text, with surrounding whitespace' {
        (Test-BridgePortText -Text '1') | Should Be $true
        (Test-BridgePortText -Text '8787') | Should Be $true
        (Test-BridgePortText -Text ' 65535 ') | Should Be $true
    }
    It 'rejects out-of-range and non-numeric text without throwing' {
        (Test-BridgePortText -Text '0') | Should Be $false
        (Test-BridgePortText -Text '65536') | Should Be $false
        (Test-BridgePortText -Text '-1') | Should Be $false
        (Test-BridgePortText -Text 'Any') | Should Be $false
        (Test-BridgePortText -Text '8787,8788') | Should Be $false
        (Test-BridgePortText -Text 'abc') | Should Be $false
        (Test-BridgePortText -Text '') | Should Be $false
        (Test-BridgePortText -Text $null) | Should Be $false
    }
}

Describe 'Get-EffectiveBinding tolerates a corrupted existing port' {
    It 'does not throw on a non-numeric MM_PORT and falls back to the default' {
        $r = Get-EffectiveBinding -ExistingHost '10.0.0.5' -ExistingPort 'Any' -BindHost '0.0.0.0' -Port 8787
        $r.Host | Should Be '10.0.0.5'
        $r.Port | Should Be 8787
    }
    It 'still prefers an explicit port even when the existing one is corrupt' {
        $r = Get-EffectiveBinding -ExistingHost '10.0.0.5' -ExistingPort 'nope' -BindHost '0.0.0.0' -Port 9001 -ExplicitPort
        $r.Port | Should Be 9001
    }
}

Describe 'Resolve-RepoRoot' {
    $good = Join-Path $env:TEMP ('mm-root-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $good -Force | Out-Null
    New-Item -ItemType File -Path (Join-Path $good 'setup-lib.ps1') -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $good 'bridge') -Force | Out-Null

    $libOnly = Join-Path $env:TEMP ('mm-libonly-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $libOnly -Force | Out-Null
    New-Item -ItemType File -Path (Join-Path $libOnly 'setup-lib.ps1') -Force | Out-Null

    $bridgeOnly = Join-Path $env:TEMP ('mm-bridgeonly-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path (Join-Path $bridgeOnly 'bridge') -Force | Out-Null

    It 'returns the folder that has both setup-lib.ps1 and bridge\' {
        (Resolve-RepoRoot -Candidates @($good)) | Should Be (Resolve-Path -LiteralPath $good).Path
    }
    It 'skips candidates that lack setup-lib.ps1 or bridge\' {
        (Resolve-RepoRoot -Candidates @($libOnly, $bridgeOnly)) | Should BeNullOrEmpty
    }
    It 'picks the first valid candidate, ignoring empty entries' {
        (Resolve-RepoRoot -Candidates @('', $bridgeOnly, $good)) | Should Be (Resolve-Path -LiteralPath $good).Path
    }
    It 'returns null when nothing qualifies' {
        (Resolve-RepoRoot -Candidates @('C:\does\not\exist\mm')) | Should BeNullOrEmpty
    }

    Remove-Item -LiteralPath $good, $libOnly, $bridgeOnly -Recurse -Force -ErrorAction SilentlyContinue
}

Describe 'Get-TailscaleInstallAction' {
    It 'skips when not requested' {
        (Get-TailscaleInstallAction -Requested $false -AlreadyInstalled $false -WingetAvailable $true) | Should Be 'skip'
    }
    It 'reports already-installed when a client exists' {
        (Get-TailscaleInstallAction -Requested $true -AlreadyInstalled $true -WingetAvailable $false) | Should Be 'already'
    }
    It 'prefers winget when available and not installed' {
        (Get-TailscaleInstallAction -Requested $true -AlreadyInstalled $false -WingetAvailable $true) | Should Be 'winget'
    }
    It 'falls back to a download when winget is unavailable' {
        (Get-TailscaleInstallAction -Requested $true -AlreadyInstalled $false -WingetAvailable $false) | Should Be 'download'
    }
}

Describe 'Test-BridgeProcessCommandLine' {
    $venv = 'C:\App\bridge\.venv\Scripts\python.exe'
    $guard = 'C:\App\bridge\run_bridge_guard.ps1'
    It 'matches this install''s venv interpreter' {
        (Test-BridgeProcessCommandLine -CommandLine ('"' + $venv + '" run.py') -VenvPython $venv -GuardScript $guard) | Should Be $true
    }
    It 'matches this install''s guard script' {
        (Test-BridgeProcessCommandLine -CommandLine ('powershell -File "' + $guard + '"') -VenvPython $venv -GuardScript $guard) | Should Be $true
    }
    It 'does NOT match an unrelated process that merely contains the folder path' {
        (Test-BridgeProcessCommandLine -CommandLine 'notepad "C:\App\bridge\notes.txt"' -VenvPython $venv -GuardScript $guard) | Should Be $false
        (Test-BridgeProcessCommandLine -CommandLine 'C:\Other\python.exe run.py' -VenvPython $venv -GuardScript $guard) | Should Be $false
    }
    It 'rejects blank command lines' {
        (Test-BridgeProcessCommandLine -CommandLine '' -VenvPython $venv -GuardScript $guard) | Should Be $false
        (Test-BridgeProcessCommandLine -CommandLine $null -VenvPython $venv -GuardScript $guard) | Should Be $false
    }
}

