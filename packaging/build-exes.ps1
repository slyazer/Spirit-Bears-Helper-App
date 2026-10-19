#Requires -Version 5.1
<#
.SYNOPSIS
  Build the user-facing Windows executables from the PowerShell scripts.

.DESCRIPTION
  Compiles setup.ps1  -> MacroMonitor-Setup.exe
           uninstall.ps1 -> MacroMonitor-Uninstall.exe

  The EXEs are thin, repository-anchored wrappers: at runtime they resolve the
  repository root from their own directory (see Resolve-RepoRoot in setup-lib.ps1)
  and then run the same logic as the scripts. They do NOT bundle the bridge, so
  MacroMonitor-Setup.exe / MacroMonitor-Uninstall.exe must be distributed in the
  project folder, next to setup-lib.ps1 and bridge\.

  Packaging uses PS2EXE (https://github.com/MScholtes/PS2EXE), a PowerShell module
  that compiles a .ps1 into a real .NET PE with embedded version metadata, an icon
  and an elevation manifest. The module is used only at build time; the produced
  EXEs run on stock Windows PowerShell 5.1.

.PARAMETER OutputDir
  Where to write the EXEs. Defaults to the repository root.

.PARAMETER Version
  Product/file version stamped into both EXEs (four-part, e.g. 0.2.0.0).

.PARAMETER IconPath
  Icon to embed. Defaults to packaging\macromonitor.ico when present.

.PARAMETER NoRequireAdmin
  Do NOT embed the "requireAdministrator" manifest. By default both EXEs request
  elevation, because firewall rules and the auto-start scheduled task are
  machine-level changes.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\packaging\build-exes.ps1
#>
[CmdletBinding()]
param(
    [string]$OutputDir,
    [string]$Version = '0.2.0.0',
    [string]$IconPath,
    [switch]$NoRequireAdmin
)

$ErrorActionPreference = 'Stop'
$PackagingDir = $PSScriptRoot
$RepoRoot = Split-Path -Parent $PackagingDir
if (-not $OutputDir) { $OutputDir = $RepoRoot }

function Write-Step($msg) { Write-Host ("`n==> " + $msg) -ForegroundColor Cyan }
function Write-Ok($msg) { Write-Host ('  [ok] ' + $msg) -ForegroundColor Green }
function Write-Info($msg) { Write-Host ('  ' + $msg) -ForegroundColor Gray }

# Ensure the PS2EXE module is importable. Prefer an already-installed copy;
# otherwise fetch it into packaging\.tools (kept out of version control) so the
# build is reproducible without polluting the machine's global module path.
function Get-Ps2ExeModulePath {
    $installed = Get-Module -ListAvailable ps2exe |
        Sort-Object Version -Descending | Select-Object -First 1
    if ($installed) { return $installed.Path }

    $tools = Join-Path $PackagingDir '.tools'
    if (-not (Test-Path -LiteralPath $tools)) {
        New-Item -ItemType Directory -Path $tools -Force | Out-Null
    }
    Write-Info 'ps2exe not installed; fetching from PSGallery into packaging\.tools ...'
    try {
        Install-PackageProvider -Name NuGet -MinimumVersion 2.8.5.201 -Force -Scope CurrentUser -ErrorAction SilentlyContinue | Out-Null
    } catch { }
    Save-Module -Name ps2exe -Path $tools -Force
    $found = Get-ChildItem -Path $tools -Recurse -Filter 'ps2exe.psd1' |
        Sort-Object FullName -Descending | Select-Object -First 1
    if (-not $found) { throw 'Could not obtain the ps2exe module from PSGallery.' }
    return $found.FullName
}

# Build a multi-size PNG-based .ico from a square PNG. Returns $null (icon is
# optional) if the source is unavailable, rather than failing the build.
function New-IcoFromPng {
    param(
        [string]$SourcePng,
        [string]$Destination,
        [int[]]$Sizes = @(16, 32, 48, 64, 128, 256)
    )
    if (-not (Test-Path -LiteralPath $SourcePng)) { return $null }
    if (Test-Path -LiteralPath $Destination) { return $Destination }
    Add-Type -AssemblyName System.Drawing
    $srcImg = [System.Drawing.Image]::FromFile($SourcePng)
    $pngs = New-Object System.Collections.Generic.List[byte[]]
    try {
        foreach ($size in $Sizes) {
            $bmp = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
            $g = [System.Drawing.Graphics]::FromImage($bmp)
            $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
            $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
            $g.Clear([System.Drawing.Color]::Transparent)
            $g.DrawImage($srcImg, 0, 0, $size, $size)
            $g.Dispose()
            $ms = New-Object System.IO.MemoryStream
            $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
            $bmp.Dispose()
            $pngs.Add($ms.ToArray())
            $ms.Dispose()
        }
    } finally {
        $srcImg.Dispose()
    }
    $fs = [System.IO.File]::Create($Destination)
    $bw = New-Object System.IO.BinaryWriter($fs)
    try {
        $bw.Write([UInt16]0); $bw.Write([UInt16]1); $bw.Write([UInt16]$pngs.Count)
        $offset = 6 + 16 * $pngs.Count
        for ($i = 0; $i -lt $pngs.Count; $i++) {
            $dim = if ($Sizes[$i] -ge 256) { [byte]0 } else { [byte]$Sizes[$i] }
            $bw.Write($dim); $bw.Write($dim); $bw.Write([byte]0); $bw.Write([byte]0)
            $bw.Write([UInt16]1); $bw.Write([UInt16]32)
            $bw.Write([UInt32]$pngs[$i].Length); $bw.Write([UInt32]$offset)
            $offset += $pngs[$i].Length
        }
        foreach ($png in $pngs) { $bw.Write($png) }
        $bw.Flush()
    } finally {
        $bw.Dispose(); $fs.Dispose()
    }
    return $Destination
}

# Read the PE machine field to confirm the architecture (0x8664 = x64).
function Get-PeMachine {
    param([string]$Path)
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -lt 0x40) { return 0 }
    $pe = [BitConverter]::ToInt32($bytes, 0x3C)
    return [BitConverter]::ToUInt16($bytes, $pe + 4)
}

Write-Host ''
Write-Host 'Macro Monitor - building Windows executables' -ForegroundColor Magenta
Write-Info ('Repo root: ' + $RepoRoot)
Write-Info ('Output:    ' + $OutputDir)

foreach ($required in @('setup.ps1', 'uninstall.ps1', 'setup-lib.ps1')) {
    if (-not (Test-Path -LiteralPath (Join-Path $RepoRoot $required))) {
        throw ("$required not found in $RepoRoot - run this from the repository.")
    }
}

Write-Step 'Loading the PS2EXE module'
$modulePath = Get-Ps2ExeModulePath
Import-Module $modulePath -Force
Write-Ok ('ps2exe ' + (Get-Module ps2exe).Version + ' from ' + $modulePath)

Write-Step 'Resolving the icon'
$icon = $IconPath
if (-not $icon) {
    $icon = New-IcoFromPng `
        -SourcePng (Join-Path $RepoRoot 'android\app\src\main\res\drawable-nodpi\ic_launcher_bear.png') `
        -Destination (Join-Path $PackagingDir 'macromonitor.ico')
}
if ($icon -and (Test-Path -LiteralPath $icon)) {
    Write-Ok ('Using icon ' + $icon)
} else {
    $icon = $null
    Write-Info 'No icon available; building without one.'
}

$common = @{
    x64         = $true
    product     = 'Macro Monitor'
    company     = "Spirit Bear's Helper App"
    version     = $Version
}
if ($icon) { $common.iconFile = $icon }
if (-not $NoRequireAdmin) { $common.requireAdmin = $true }

$targets = @(
    @{ Input = 'setup.ps1';     Output = (Join-Path $OutputDir 'MacroMonitor-Setup.exe');     Title = 'Macro Monitor Setup';     Description = 'Installs and configures the Macro Monitor bridge.' },
    @{ Input = 'uninstall.ps1'; Output = (Join-Path $OutputDir 'MacroMonitor-Uninstall.exe'); Title = 'Macro Monitor Uninstall'; Description = 'Removes the Macro Monitor bridge.' }
)

foreach ($t in $targets) {
    Write-Step ('Compiling ' + (Split-Path $t.Output -Leaf))
    $params = $common.Clone()
    $params.title = $t.Title
    $params.description = $t.Description
    Invoke-PS2EXE -InputFile (Join-Path $RepoRoot $t.Input) -OutputFile $t.Output @params
    if (-not (Test-Path -LiteralPath $t.Output)) {
        throw ('Build failed: ' + $t.Output + ' was not produced.')
    }
    $machine = Get-PeMachine -Path $t.Output
    $item = Get-Item -LiteralPath $t.Output
    $vi = $item.VersionInfo
    $arch = if ($machine -eq 0x8664) { 'x64' } elseif ($machine -eq 0x14C) { 'x86' } else { ('0x{0:X4}' -f $machine) }
    Write-Ok ($item.Name + '  ' + [math]::Round($item.Length / 1KB) + ' KB  arch=' + $arch +
        '  product=' + $vi.ProductName + '  version=' + $vi.ProductVersion)
}

Write-Host ''
Write-Host 'Done.' -ForegroundColor Magenta
Write-Host ''
