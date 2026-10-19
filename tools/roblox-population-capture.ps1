<#
  roblox-population-capture.ps1

  Windows capture agent for the Roblox private-server population feature.

  It opens the owner's private-servers page in the *existing, already logged in*
  Firefox session, screenshots the primary monitor, OCRs the two population
  integers with the built-in Windows OCR engine, and POSTs ONLY those two
  integers to the bridge. It never authenticates to Roblox itself, never reads
  cookies, and never uploads the screenshot.

  Design notes (see SETUP_WINDOWS.md):
    * A dedicated Firefox *window* is opened and closed via WM_CLOSE - never
      Ctrl+W - so no unrelated tab is touched. The new window is found by
      diffing Firefox window handles before/after, never by assuming focus.
    * A single-flight lock file prevents overlapping captures.
    * The temp screenshot is deleted on every path (success and failure).
    * OCR output is untrusted: only values validated by the shared parser are
      ever sent.

  Usage:
    # one capture (for testing / a manual refresh):
    powershell -ExecutionPolicy Bypass -File .\tools\roblox-population-capture.ps1 -Once

    # long-running agent (what the scheduled task runs):
    powershell -ExecutionPolicy Bypass -File .\tools\roblox-population-capture.ps1
#>
[CmdletBinding()]
param(
    # Where the credentials + endpoint live (created by roblox-population-setup.ps1).
    [string]$ConfigPath,
    [string]$BaseUrl,
    [string]$Username,
    [string]$Password,
    [string]$Url,
    [string]$VipServerId,

    [int]$IntervalSeconds = 0,
    [double]$PageDelaySeconds = 0,
    [int]$WindowWaitSeconds = 25,
    [int]$ParseAttempts = 3,
    [string]$FirefoxPath,

    [string]$LogPath,
    [string]$LockPath,

    [switch]$Once,
    [switch]$AllowUnmatchedServer,
    [switch]$KeepWindow,
    [switch]$NoLog
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$RepoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'roblox-population-lib.ps1')

# -- Win32 + screen + OCR interop -----------------------------------------

Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Runtime.WindowsRuntime

if (-not ('MacroMonitor.Win32' -as [type])) {
    Add-Type -Namespace MacroMonitor -Name Win32 -MemberDefinition @'
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);
    public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool IsWindow(IntPtr hWnd);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetClassName(IntPtr hWnd, System.Text.StringBuilder lpClassName, int nMaxCount);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, IntPtr lpdwProcessId);
    [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint idAttach, uint idAttachTo, bool fAttach);
    [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr hWnd, uint Msg, IntPtr wParam, IntPtr lParam);
    [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();

    public static System.Collections.Generic.List<IntPtr> FindVisibleWindowsByClass(string cls) {
        var list = new System.Collections.Generic.List<IntPtr>();
        EnumWindows(delegate(IntPtr h, IntPtr l) {
            if (!IsWindowVisible(h)) return true;
            var sb = new System.Text.StringBuilder(256);
            GetClassName(h, sb, sb.Capacity);
            if (sb.ToString() == cls) list.Add(h);
            return true;
        }, IntPtr.Zero);
        return list;
    }

    // Best-effort foreground activation; returns true once the window is in front.
    public static bool ForceForeground(IntPtr hWnd) {
        ShowWindow(hWnd, 9); // SW_RESTORE
        if (SetForegroundWindow(hWnd) && GetForegroundWindow() == hWnd) return true;
        IntPtr fg = GetForegroundWindow();
        uint thisThread = GetCurrentThreadId();
        uint fgThread = GetWindowThreadProcessId(fg, IntPtr.Zero);
        uint targetThread = GetWindowThreadProcessId(hWnd, IntPtr.Zero);
        bool a1 = false, a2 = false;
        if (fgThread != thisThread) a1 = AttachThreadInput(thisThread, fgThread, true);
        if (targetThread != thisThread && targetThread != fgThread) a2 = AttachThreadInput(thisThread, targetThread, true);
        bool ok = SetForegroundWindow(hWnd);
        if (!ok) { BringWindowToTop(hWnd); ok = SetForegroundWindow(hWnd); }
        if (a1) AttachThreadInput(thisThread, fgThread, false);
        if (a2) AttachThreadInput(thisThread, targetThread, false);
        return (ok && GetForegroundWindow() == hWnd);
    }
'@
}

$script:AsTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() |
    Where-Object {
        $_.Name -eq 'AsTask' -and
        $_.GetParameters().Count -eq 1 -and
        $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
    })[0]

function Await-WinRt {
    param($Operation, [Type]$ResultType)
    $asTask = $script:AsTaskGeneric.MakeGenericMethod($ResultType)
    $netTask = $asTask.Invoke($null, @($Operation))
    $netTask.Wait(-1) | Out-Null
    $netTask.Result
}

function Initialize-OcrEngine {
    [Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType = WindowsRuntime] | Out-Null
    [Windows.Graphics.Imaging.BitmapDecoder, Windows.Foundation, ContentType = WindowsRuntime] | Out-Null
    [Windows.Storage.StorageFile, Windows.Foundation, ContentType = WindowsRuntime] | Out-Null
    $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
    if (-not $engine) {
        $lang = New-Object Windows.Globalization.Language 'en-US'
        $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage($lang)
    }
    if (-not $engine) { throw 'No Windows OCR engine is available for this user profile.' }
    return $engine
}

# -- logging ---------------------------------------------------------------

function Write-Log {
    param([string]$Message, [string]$Level = 'INFO')
    $line = "[{0}] {1} {2}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Level, $Message
    Write-Host $line
    if ($NoLog -or [string]::IsNullOrWhiteSpace($script:LogFile)) { return }
    try {
        $dir = Split-Path -Parent $script:LogFile
        if ($dir -and -not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
        Add-Content -LiteralPath $script:LogFile -Value $line -Encoding UTF8
    }
    catch { }
}

# -- configuration ---------------------------------------------------------

function Get-CaptureConfig {
    $cfg = [ordered]@{
        base_url           = 'http://127.0.0.1:8787'
        username           = ''
        password           = ''
        url                = ''
        vip_server_id      = ''
        interval_seconds   = 300
        page_delay_seconds = 1.5
        firefox_path       = 'C:\Program Files\Mozilla Firefox\firefox.exe'
    }

    if (-not [string]::IsNullOrWhiteSpace($ConfigPath) -and (Test-Path -LiteralPath $ConfigPath)) {
        $fromFile = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json
        foreach ($p in $fromFile.PSObject.Properties) {
            if ($cfg.Contains($p.Name)) { $cfg[$p.Name] = $p.Value }
        }
    }

    # Explicit parameters win over the file.
    if ($BaseUrl) { $cfg.base_url = $BaseUrl }
    if ($Username) { $cfg.username = $Username }
    if ($Password) { $cfg.password = $Password }
    if ($Url) { $cfg.url = $Url }
    if ($VipServerId) { $cfg.vip_server_id = $VipServerId }
    if ($IntervalSeconds -gt 0) { $cfg.interval_seconds = $IntervalSeconds }
    if ($PageDelaySeconds -gt 0) { $cfg.page_delay_seconds = $PageDelaySeconds }
    if ($FirefoxPath) { $cfg.firefox_path = $FirefoxPath }

    return [pscustomobject]$cfg
}

function Get-FirefoxPath {
    param([string]$Configured)
    $candidates = @()
    if (-not [string]::IsNullOrWhiteSpace($Configured)) { $candidates += $Configured }
    $candidates += @(
        'C:\Program Files\Mozilla Firefox\firefox.exe',
        'C:\Program Files (x86)\Mozilla Firefox\firefox.exe'
    )
    foreach ($c in $candidates) {
        if ($c -and (Test-Path -LiteralPath $c)) { return $c }
    }
    throw "Firefox not found. Set 'firefox_path' in the config or pass -FirefoxPath."
}

# -- bridge io -------------------------------------------------------------

function Invoke-BridgeLogin {
    param([string]$Base, [string]$User, [string]$Pass)
    $body = @{ username = $User; password = $Pass; device_name = 'roblox-population-capture' } | ConvertTo-Json
    $resp = Invoke-RestMethod -Uri "$Base/api/v1/auth/login" -Method Post -ContentType 'application/json' -Body $body
    return $resp.token
}

function Send-PopulationSample {
    param([string]$Base, [ref]$Token, [string]$User, [string]$Pass, [int]$Playing, [int]$MaxPlayers, [string]$CapturedAt)
    $json = Get-PopulationIngestBody -Playing $Playing -MaxPlayers $MaxPlayers -CapturedAt $CapturedAt
    for ($attempt = 0; $attempt -lt 2; $attempt++) {
        try {
            $headers = @{ Authorization = "Bearer $($Token.Value)" }
            return Invoke-RestMethod -Uri "$Base/api/v1/roblox/population" -Method Post -Headers $headers -ContentType 'application/json' -Body $json
        }
        catch {
            $status = $null
            if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
            if ($status -eq 401 -and $attempt -eq 0) {
                $Token.Value = Invoke-BridgeLogin -Base $Base -User $User -Pass $Pass
                continue
            }
            throw
        }
    }
}

# -- screen capture --------------------------------------------------------

function Save-PrimaryScreenPng {
    param([string]$Path)
    $bounds = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
    $bmp = New-Object System.Drawing.Bitmap($bounds.Width, $bounds.Height)
    try {
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        try {
            $g.CopyFromScreen($bounds.Location, [System.Drawing.Point]::Empty, $bounds.Size)
        }
        finally { $g.Dispose() }
        $bmp.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
    }
    finally { $bmp.Dispose() }
}

function Read-PopulationText {
    param([string]$Path)
    $engine = $script:OcrEngine
    $file = Await-WinRt ([Windows.Storage.StorageFile]::GetFileFromPathAsync($Path)) ([Windows.Storage.StorageFile])
    $stream = Await-WinRt ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
    try {
        $decoder = Await-WinRt ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
        $bitmap = Await-WinRt ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
        $result = Await-WinRt ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])
        return $result.Text
    }
    finally { $stream.Dispose() }
}

# -- capture cycle ---------------------------------------------------------

function Invoke-CaptureCycle {
    param([pscustomobject]$Config, [string]$Firefox, [ref]$Token)

    $before = @{}
    foreach ($h in [MacroMonitor.Win32]::FindVisibleWindowsByClass('MozillaWindowClass')) { $before[$h.ToInt64()] = $true }

    Write-Log "Opening private-servers page in Firefox."
    Start-Process -FilePath $Firefox -ArgumentList @('-new-window', $Config.url) | Out-Null

    # Find the newly opened Firefox window (never assume it grabbed focus).
    $deadline = (Get-Date).AddSeconds($WindowWaitSeconds)
    $newWindow = [IntPtr]::Zero
    while ((Get-Date) -lt $deadline) {
        foreach ($h in [MacroMonitor.Win32]::FindVisibleWindowsByClass('MozillaWindowClass')) {
            if (-not $before.ContainsKey($h.ToInt64())) { $newWindow = $h; break }
        }
        if ($newWindow -ne [IntPtr]::Zero) { break }
        Start-Sleep -Milliseconds 400
    }

    $shot = Join-Path $env:TEMP ('mm-roblox-' + [guid]::NewGuid().ToString('N') + '.png')
    try {
        if ($newWindow -eq [IntPtr]::Zero) {
            Write-Log 'Opened browser window was not found; skipping this capture.' 'WARN'
            return [pscustomobject]@{ Ok = $false; Reason = 'New browser window not found.' }
        }

        $parsed = $null
        for ($attempt = 1; $attempt -le $ParseAttempts; $attempt++) {
            if (-not [MacroMonitor.Win32]::IsWindow($newWindow)) {
                Write-Log 'Browser window closed before a reading could be taken.' 'WARN'
                return [pscustomobject]@{ Ok = $false; Reason = 'Browser window closed.' }
            }
            [MacroMonitor.Win32]::ForceForeground($newWindow) | Out-Null
            Start-Sleep -Milliseconds ([int]([double]$Config.page_delay_seconds * 1000) * $attempt)

            Save-PrimaryScreenPng -Path $shot
            $text = Read-PopulationText -Path $shot
            $parsed = ConvertFrom-PopulationOcrText -Text $text -VipServerId $Config.vip_server_id -AllowUnmatchedServer:$AllowUnmatchedServer
            if ($parsed.Ok) { break }
            Write-Log ("Capture attempt {0}/{1} did not yield a valid reading: {2}" -f $attempt, $ParseAttempts, $parsed.Reason) 'WARN'
        }

        return $parsed
    }
    finally {
        if (-not $KeepWindow -and $newWindow -ne [IntPtr]::Zero -and [MacroMonitor.Win32]::IsWindow($newWindow)) {
            [MacroMonitor.Win32]::PostMessage($newWindow, 0x0010, [IntPtr]::Zero, [IntPtr]::Zero) | Out-Null  # WM_CLOSE
        }
        # The screenshot never leaves the machine and is always removed.
        if (-not (Remove-PopulationArtifact -Path $shot)) {
            Write-Log "Could not delete temp screenshot: $shot" 'WARN'
        }
    }
}

# -- main ------------------------------------------------------------------

function Start-CaptureAgent {
    $config = Get-CaptureConfig
    if ([string]::IsNullOrWhiteSpace($config.url)) { throw 'No capture URL configured.' }
    if ([string]::IsNullOrWhiteSpace($config.username) -or [string]::IsNullOrWhiteSpace($config.password)) {
        throw 'No bridge credentials configured. Run tools\roblox-population-setup.ps1 first.'
    }
    $ff = Get-FirefoxPath -Configured $config.firefox_path

    if (-not $NoLog) {
        $script:LogFile = if ($LogPath) { $LogPath } else { Join-Path $RepoRoot 'bridge\logs\roblox-population.log' }
    }
    $lock = if ($LockPath) { $LockPath } else { Join-Path $RepoRoot 'bridge\logs\roblox-population.lock' }

    $script:OcrEngine = Initialize-OcrEngine

    Write-Log ("Starting capture agent (interval {0}s, page delay {1}s)." -f $config.interval_seconds, $config.page_delay_seconds)
    $token = $null

    while ($true) {
        $gotLock = Enter-PopulationCaptureLock -Path $lock
        if (-not $gotLock) {
            Write-Log 'Another capture is already running; skipping this cycle.' 'WARN'
        }
        else {
            try {
                if (-not $token) {
                    $token = Invoke-BridgeLogin -Base $config.base_url -User $config.username -Pass $config.password
                }
                $result = Invoke-CaptureCycle -Config $config -Firefox $ff -Token ([ref]$token)
                if ($result.Ok) {
                    $capturedAt = (Get-Date).ToUniversalTime().ToString('o')
                    $sent = Send-PopulationSample -Base $config.base_url -Token ([ref]$token) -User $config.username -Pass $config.password -Playing $result.Playing -MaxPlayers $result.MaxPlayers -CapturedAt $capturedAt
                    Write-Log ("Reported population {0}/{1}." -f $sent.playing, $sent.max_players)
                }
                else {
                    Write-Log ("No sample reported this cycle: {0}" -f $result.Reason) 'WARN'
                }
            }
            catch {
                Write-Log ("Capture cycle failed: {0}" -f $_.Exception.Message) 'ERROR'
            }
            finally {
                Exit-PopulationCaptureLock -Path $lock
            }
        }

        if ($Once) { break }
        Start-Sleep -Seconds ([int]$config.interval_seconds)
    }
}

Start-CaptureAgent
