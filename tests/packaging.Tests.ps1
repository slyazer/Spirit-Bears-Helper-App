# Packaging tests: the EXE build script, the icon, and (when built) the EXEs.
# Run from the repository root:  Invoke-Pester .\tests\packaging.Tests.ps1
# Written for Pester 3.4 (legacy Should syntax).

$RepoRoot = Split-Path -Parent $PSScriptRoot
$BuildScript = Join-Path $RepoRoot 'packaging\build-exes.ps1'
$IconPath = Join-Path $RepoRoot 'packaging\macromonitor.ico'
$SetupExe = Join-Path $RepoRoot 'MacroMonitor-Setup.exe'
$UninstallExe = Join-Path $RepoRoot 'MacroMonitor-Uninstall.exe'

function Get-PeMachine {
    param([string]$Path)
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    $pe = [BitConverter]::ToInt32($bytes, 0x3C)
    return [BitConverter]::ToUInt16($bytes, $pe + 4)
}

Describe 'packaging/build-exes.ps1' {
    It 'exists' {
        (Test-Path -LiteralPath $BuildScript) | Should Be $true
    }

    It 'parses without syntax errors' {
        $errors = $null
        [void][System.Management.Automation.Language.Parser]::ParseFile($BuildScript, [ref]$null, [ref]$errors)
        ($errors.Count) | Should Be 0
    }

    It 'declares the four-part version default and admin/icon parameters' {
        $text = Get-Content -LiteralPath $BuildScript -Raw
        ($text -match "Version\s*=\s*'0\.2\.0\.0'") | Should Be $true
        ($text -match 'requireAdmin') | Should Be $true
        ($text -match 'iconFile') | Should Be $true
        ($text -match 'x64') | Should Be $true
    }
}

Describe 'packaging/macromonitor.ico' {
    It 'exists' {
        (Test-Path -LiteralPath $IconPath) | Should Be $true
    }

    It 'is a valid ICO with at least the 256px entry' {
        $bytes = [System.IO.File]::ReadAllBytes($IconPath)
        ([BitConverter]::ToUInt16($bytes, 0)) | Should Be 0        # reserved
        ([BitConverter]::ToUInt16($bytes, 2)) | Should Be 1        # type = icon
        $count = [BitConverter]::ToUInt16($bytes, 4)
        ($count -ge 1) | Should Be $true
        $sizes = @()
        for ($i = 0; $i -lt $count; $i++) { $sizes += $bytes[6 + 16 * $i] }  # 0 means 256
        ($sizes -contains 0) | Should Be $true
    }
}

Describe 'built executables (skipped until packaging/build-exes.ps1 has run)' {
    It 'MacroMonitor-Setup.exe is a real x64 PE with the expected metadata' {
        if (-not (Test-Path -LiteralPath $SetupExe)) { return }
        $item = Get-Item -LiteralPath $SetupExe
        ($item.Length -gt 20000) | Should Be $true
        (Get-PeMachine $SetupExe) | Should Be 0x8664
        $vi = $item.VersionInfo
        $vi.ProductName | Should Be 'Macro Monitor'
        $vi.FileVersion | Should Be '0.2.0.0'
    }

    It 'MacroMonitor-Setup.exe embeds a requireAdministrator manifest and is unsigned' {
        if (-not (Test-Path -LiteralPath $SetupExe)) { return }
        $text = [Text.Encoding]::ASCII.GetString([IO.File]::ReadAllBytes($SetupExe))
        $text.Contains('requestedExecutionLevel') | Should Be $true
        $text.Contains('requireAdministrator') | Should Be $true
        (Get-AuthenticodeSignature -LiteralPath $SetupExe).Status | Should Be 'NotSigned'
    }

    It 'MacroMonitor-Uninstall.exe is a real x64 PE with the expected metadata' {
        if (-not (Test-Path -LiteralPath $UninstallExe)) { return }
        $item = Get-Item -LiteralPath $UninstallExe
        ($item.Length -gt 20000) | Should Be $true
        (Get-PeMachine $UninstallExe) | Should Be 0x8664
        $item.VersionInfo.ProductName | Should Be 'Macro Monitor'
    }
}

Describe 'Resolve-RepoRoot (EXE-mode anchor used by the compiled wrappers)' {
    . (Join-Path $RepoRoot 'setup-lib.ps1')

    It 'resolves the repo root when given a directory that contains bridge\' {
        (Resolve-RepoRoot -Candidates @($RepoRoot)) | Should Be $RepoRoot
    }

    It 'skips empty/blank candidates (mimicking PSScriptRoot = "" inside an EXE)' {
        (Resolve-RepoRoot -Candidates @('', '   ', $RepoRoot)) | Should Be $RepoRoot
    }

    It 'returns $null when no candidate contains bridge\' {
        $empty = Join-Path ([IO.Path]::GetTempPath()) ('mm-noroot-' + [Guid]::NewGuid())
        New-Item -ItemType Directory -Path $empty -Force | Out-Null
        try {
            (Resolve-RepoRoot -Candidates @($empty)) | Should BeNullOrEmpty
        } finally {
            Remove-Item -LiteralPath $empty -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}
