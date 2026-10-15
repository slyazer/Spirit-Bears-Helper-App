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
