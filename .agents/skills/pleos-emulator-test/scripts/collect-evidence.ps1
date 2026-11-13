<#
.SYNOPSIS
  Saves one evidence snapshot under build/pleos-emulator-test/<RunId>/<Label>-<time>/.
.DESCRIPTION
  Captures a screenshot, UI hierarchy and its short summary, the target package's
  notifications, top activity, Virtual Console state, logcat and metadata. Text output is masked for tokens, e-mail addresses and phone numbers.
  Screenshots cannot be masked: do not capture screens with personal data.
  Reads only; the sole device-side effect is reading logcat and the UI.
.EXAMPLE
  .\collect-evidence.ps1 -Serial emulator-5554 -RunId door-20261102 -Label after-open -Package com.example.pleostest
.EXAMPLE
  .\collect-evidence.ps1 -Serial emulator-5554 -RunId places-20261110 -Label r1-coex -LogTag PlaceCheck -Since '11-10 10:15:00.000'
  Also saves the PlaceCheck lines since that device time to tag-PlaceCheck.txt. Never clears logcat.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Serial,
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9._-]+$')][string]$RunId,
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9._-]+$')][string]$Label,
    [string]$Package,
    [int]$LogLines = 2000,
    [ValidatePattern('^[A-Za-z0-9_.-]+$')][string[]]$LogTag = @(),
    # Device time 'MM-DD HH:MM:SS.mmm' (adb shell date '+%m-%d %H:%M:%S.000'); limits logcat files.
    [ValidatePattern('^\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}$')][string]$Since,
    [switch]$NoScreenshot
)

$ErrorActionPreference = 'Continue'
$previousEncoding = [Console]::OutputEncoding
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
$runDir = Join-Path $repoRoot "build\pleos-emulator-test\$RunId\$Label-$(Get-Date -Format 'HHmmss')"
New-Item -ItemType Directory -Force $runDir | Out-Null

$adb = (Get-Command adb -ErrorAction SilentlyContinue | Select-Object -First 1).Source
if (-not $adb) {
    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    $adb = Join-Path $sdk 'platform-tools\adb.exe'
}
if (-not (Test-Path $adb)) { [Console]::OutputEncoding = $previousEncoding; Write-Error 'adb not found'; exit 1 }

function Protect-Text([string]$Text) {
    if (-not $Text) { return '' }
    $masked = $Text
    $masked = $masked -replace '(?i)(bearer\s+)[A-Za-z0-9\-._~+/]+=*', '$1***'
    $masked = $masked -replace '(?i)\b(authorization|api[_-]?key|access[_-]?token|refresh[_-]?token|id[_-]?token|client[_-]?secret|token|secret|password|passwd)\b(\\?["'']?\s*[:=]\s*\\?["'']?)(?!Bearer\b)[^\s"''&,;\\]+', '$1$2***'
    $masked = $masked -replace '\beyJ[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]*', '***jwt***'
    $masked = $masked -replace '\b(sk|ghp|gho|github_pat|lin_api|xox[abp])[-_][A-Za-z0-9_\-]{8,}', '***key***'
    $masked = $masked -replace '[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}', '***email***'
    $masked = $masked -replace '\b01[016789][- ]?\d{3,4}[- ]?\d{4}\b', '***phone***'
    return $masked
}

$utf8 = New-Object System.Text.UTF8Encoding($false)
function Save-Text([string]$Name, [string]$Text) {
    [IO.File]::WriteAllText((Join-Path $runDir $Name), (Protect-Text $Text), $utf8)
}
function Dev { & $adb -s $Serial @args 2>$null }

$items = [ordered]@{}

if ("$(Dev get-state)".Trim() -ne 'device') {
    [Console]::OutputEncoding = $previousEncoding
    Write-Error "device '$Serial' is not ready"
    exit 1
}

# Windows PowerShell corrupts binary pipes, so cmd.exe handles the redirect.
if ($NoScreenshot) { $items.screenshot = 'SKIPPED' }
else {
    $png = Join-Path $runDir 'screen.png'
    cmd /c "`"$adb`" -s $Serial exec-out screencap -p > `"$png`" 2>NUL"
    $head = if (Test-Path $png) { [IO.File]::ReadAllBytes($png) | Select-Object -First 4 } else { @() }
    $items.screenshot = if (($head -join ',') -eq '137,80,78,71') { 'OK' } else { 'FAILED' }
}

$xml = (Dev exec-out uiautomator dump /dev/tty) -join "`n"
$xmlEnd = $xml.LastIndexOf('</hierarchy>')
if ($xmlEnd -ge 0) {
    Save-Text 'ui.xml' $xml.Substring(0, $xmlEnd + '</hierarchy>'.Length)
    $items.ui_hierarchy = 'OK (focused window only; use android layout for all windows)'
}
else {
    # Android CLI's instrumentation holds the single UiAutomation connection, so uiautomator dies
    # ("Killed"). Its own layout command still works and covers every window.
    $cli = Join-Path $env:USERPROFILE 'AppData\AndroidCLI\android.exe'
    $holder = "$(Dev shell 'pidof com.android.cli.interact.instrumentation')".Trim()
    if ((Test-Path $cli) -and $holder) {
        $layout = Join-Path $runDir 'layout.json'
        & $cli layout "--device=$Serial" -o $layout *> $null
        if (Test-Path $layout) {
            Save-Text 'layout.json' ([IO.File]::ReadAllText($layout, $utf8))
            $items.ui_hierarchy = 'OK (layout.json from android layout: uiautomator blocked by Android CLI instrumentation)'
        }
        else { $items.ui_hierarchy = 'FAILED (uiautomator blocked by Android CLI instrumentation; android layout failed)' }
    }
    elseif ("$(Dev shell 'ps -A -o ARGS')" -match 'uia2\.Main') {
        $items.ui_hierarchy = 'FAILED (UiAutomation held by Android-MCP/uiautomator2; ask the user to disable Android-MCP)'
    }
    else { $items.ui_hierarchy = "FAILED ($(($xml -split "`n")[0]))" }
}

$summarySource = @('layout.json', 'ui.xml') | ForEach-Object { Join-Path $runDir $_ } | Where-Object { Test-Path $_ } | Select-Object -First 1
if ($summarySource) {
    & (Join-Path $PSScriptRoot 'summarize-layout.ps1') -Path $summarySource -OutFile (Join-Path $runDir 'ui-summary.txt') *> $null
    $items.ui_summary = if (Test-Path (Join-Path $runDir 'ui-summary.txt')) { "OK (from $(Split-Path $summarySource -Leaf))" } else { 'FAILED' }
}
else { $items.ui_summary = 'SKIPPED (no hierarchy)' }

# Notifications of the target package only (L1). Other apps' notifications may carry personal data.
if ($Package) {
    $records = New-Object System.Collections.ArrayList
    $current = $null
    foreach ($line in @(Dev shell dumpsys notification --noredact)) {
        if ($line -match '^\s+NotificationRecord\(0x[0-9a-f]+: pkg=(\S+) user=UserHandle\{(-?\d+)\} id=(-?\d+) tag=(\S+).*channel=(\S+).*flags=(0x[0-9a-f]+)') {
            $current = if ($Matches[1] -eq $Package) { [ordered]@{ user = $Matches[2]; id = $Matches[3]; tag = $Matches[4]; channel = $Matches[5]; flags = $Matches[6] } } else { $null }
            if ($current) { [void]$records.Add($current) }
        }
        elseif ($current -and $line -match '^\s+android\.(title|text|subText|bigText|infoText)=\S+ \((.*)\)\s*$') {
            if (-not $current.Contains($Matches[1])) { $current[$Matches[1]] = $Matches[2] }
        }
    }
    $body = foreach ($r in $records) { ($r.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ' | ' }
    Save-Text 'notifications.txt' (@("package=$Package records=$($records.Count) (flags 0x40 = foreground service)") + $body -join "`n")
    $items.notifications = "OK ($($records.Count) for $Package)"
}
else { $items.notifications = 'SKIPPED (no -Package)' }

$activities = (Dev shell dumpsys activity activities) | Select-String 'topResumedActivity|mResumedActivity' | ForEach-Object { $_.Line.Trim() }
Save-Text 'top-activity.txt' ($activities -join "`n")
$items.top_activity = if ($activities) { 'OK' } else { 'EMPTY' }

try {
    $response = Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:8088/vehicle/state' -TimeoutSec 5
    Save-Text 'console-state.json' $response.Content
    $items.console_state = 'OK (L0: Console claim, not SDK evidence)'
}
catch { $items.console_state = "UNAVAILABLE: $($_.Exception.Message)" }

$window = if ($Since) { @('-T', $Since) } else { @('-t', "$LogLines") }
$logArgs = @('logcat', '-d', '-v', 'threadtime') + $window
$appPid = ''
if ($Package) {
    $appPid = "$(Dev shell pidof $Package)".Trim()
    if ($appPid) { $logArgs += "--pid=$($appPid -split '\s+' | Select-Object -First 1)" }
}
Save-Text 'logcat.txt' ((Dev @logArgs) -join "`n")
$items.logcat = if ($Package -and -not $appPid) { 'OK (package not running: unfiltered)' } elseif ($appPid) { "OK (pid $appPid)" } else { 'OK (unfiltered)' }
if ($Since) { $items.logcat += " since $Since" }

# A tool's own lines, by tag, independent of whether its process is still running.
foreach ($tag in $LogTag) {
    $tagArgs = @('logcat', '-d', '-v', 'threadtime') + $window + @('-s', "${tag}:V")
    $tagLines = Dev @tagArgs
    $body = ($tagLines | Where-Object { $_ -notmatch '^-+ beginning of' }) -join "`n"
    Save-Text "tag-$tag.txt" $body
    $count = @($tagLines | Where-Object { $_ -match " $([regex]::Escape($tag))\s*:" }).Count
    $items["tag_$tag"] = if ($count -gt 0) { "OK ($count lines)" } else { 'EMPTY' }
}

$user = "$(Dev shell am get-current-user)".Trim()
$meta = [ordered]@{
    run_id       = $RunId
    label        = $Label
    collected_at = (Get-Date).ToString('o')
    serial       = $Serial
    avd          = "$(Dev shell getprop ro.boot.qemu.avd_name)".Trim()
    fingerprint  = "$(Dev shell getprop ro.build.fingerprint)".Trim()
    android_user = $user
    package      = $Package
    log_tags     = $LogTag
    since        = $Since
    items        = $items
}
Save-Text 'meta.json' ($meta | ConvertTo-Json -Depth 4)

[Console]::OutputEncoding = $previousEncoding
"EVIDENCE_DIR=$runDir"
$items.GetEnumerator() | ForEach-Object { '{0,-14} {1}' -f $_.Key, $_.Value }
$failed = @($items.Values | Where-Object { $_ -like 'FAILED*' }).Count
if ($failed -gt 0) { exit 1 }
exit 0
