# PowerShell 5.1/7. Explicit emulator only; original app/data/ADB server are preserved.
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidateSet('Install','UiTest','ProcessRestart','Preview')][string]$Stage,
    [Parameter(Mandatory=$true)][ValidatePattern('^emulator-[0-9]+$')][string]$Serial,
    [string]$AdbPath,
    [string]$KitDirectory = (Join-Path $PSScriptRoot '..'),
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\evidence')
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$package = 'com.example.pleos_pa.com5'
if (-not $AdbPath -and $env:LOCALAPPDATA) {
    $candidate = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
    if (Test-Path -LiteralPath $candidate -PathType Leaf) { $AdbPath = $candidate }
}
if (-not $AdbPath) {
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) { $AdbPath = $command.Source }
}
if (-not $AdbPath -or -not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
    throw 'ADB not found. Supply -AdbPath with the Android SDK adb.exe path.'
}
$AdbPath = (Resolve-Path -LiteralPath $AdbPath).Path
$runName = [DateTimeOffset]::UtcNow.ToString('yyyyMMdd-HHmmss-fffffff') + '-' + $Stage
$runDirectory = Join-Path $OutputDirectory $runName
[void](New-Item -ItemType Directory -Path $runDirectory)
$utf8 = New-Object System.Text.UTF8Encoding($false)
$script:commandNumber = 0
function Write-Evidence([string]$Name, [string]$Text) {
    [IO.File]::WriteAllText((Join-Path $runDirectory $Name), $Text, $utf8)
}
function Invoke-Adb([string[]]$AdbArguments, [int]$TimeoutMillis = 30000) {
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $AdbPath
    $info.Arguments = ($AdbArguments | ForEach-Object {
        '"' + ($_ -replace '(\\*)"', '$1$1\"' -replace '(\\+)$', '$1$1') + '"'
    }) -join ' '
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $info
    try {
        [void]$process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutMillis)) {
            $process.Kill()
            throw 'ADB client timed out. Device-side work may still be running; inspect before retrying. No server/device restart was performed.'
        }
        if (-not $stdout.Wait(5000) -or -not $stderr.Wait(5000)) { throw 'ADB output did not close in time.' }
        $result = $stdout.Result + $stderr.Result
        $script:commandNumber++
        Write-Evidence ('command-' + $script:commandNumber + '.txt') $result
        if ($process.ExitCode -ne 0) { throw "ADB failed (exit $($process.ExitCode)): $result" }
        return $result.Trim()
    } finally { $process.Dispose() }
}
function Invoke-Probe([string]$Operation) {
    $nonce = [guid]::NewGuid().ToString()
    $result = Invoke-Adb -AdbArguments @('-s',$Serial,'shell','am','start','-W','-n',
        "$package/com.example.pleos_pa.ui.DriverStateProbeActivity",'--es','operation',$Operation,'--es','nonce',$nonce)
    if ($result -match 'Error:|Exception|Permission Denial') { throw "Probe start failed: $result" }
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        $logs = Invoke-Adb -AdbArguments @('-s',$Serial,'logcat','-d','-s','COM5_STATE:I','*:S')
        foreach ($line in ($logs -split '\r?\n')) {
            if ($line.Contains($nonce) -and $line.Contains('{')) {
                return ($line.Substring($line.IndexOf('{')) | ConvertFrom-Json)
            }
        }
        Start-Sleep -Milliseconds 250
    }
    throw 'No matching count-only result. Install the diagnostic kit APK, not the ordinary app.'
}
try {
    $devices = Invoke-Adb -AdbArguments @('devices')
    if ($devices -notmatch ('(?m)^' + [regex]::Escape($Serial) + '\s+device\s*$')) {
        throw 'Selected emulator is not online/authorized. No other device was selected.'
    }
    $report = [ordered]@{
        captured_at_kst = [DateTimeOffset]::UtcNow.ToOffset([TimeSpan]::FromHours(9)).ToString('o')
        stage = $Stage; serial = $Serial; package = $package
        api = Invoke-Adb -AdbArguments @('-s',$Serial,'shell','getprop','ro.build.version.sdk')
        scope = 'Synthetic UI and shared-memory checks only. No actual Car UX transition, vehicle values, navigation, notification-panel reachability or final history-limit acceptance.'
    }
    if ($Stage -eq 'Install') {
        $manifest = Get-Content -Raw -LiteralPath (Join-Path $KitDirectory 'kit-manifest.json') | ConvertFrom-Json
        if ($manifest.package -ne $package -or $manifest.testPackage -ne "$package.test") {
            throw 'Kit package mismatch. Refusing to install an ordinary or different application.'
        }
        # Check BOTH APKs before making the first installation.
        $files = @('app-debug.apk','app-debug-androidTest.apk')
        foreach ($name in $files) {
            $apk = Join-Path $KitDirectory ('apks\' + $name)
            $actual = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
            if ($actual -ne $manifest.sha256.$name) { throw "APK checksum mismatch: $name" }
        }
        foreach ($name in $files) {
            $apk = (Resolve-Path -LiteralPath (Join-Path $KitDirectory ('apks\' + $name))).Path
            $remote = '/data/local/tmp/com5-' + $name
            [void](Invoke-Adb -AdbArguments @('-s',$Serial,'push',$apk,$remote) -TimeoutMillis 120000)
            $install = Invoke-Adb -AdbArguments @('-s',$Serial,'shell','timeout','60','pm','install','-r','-t',$remote) -TimeoutMillis 90000
            if ($install -notmatch '(?m)^Success\s*$') { throw "Installation not confirmed: $install" }
        }
        $report['apk_sha256'] = $manifest.sha256
    } elseif ($Stage -eq 'UiTest') {
        $result = Invoke-Adb -AdbArguments @('-s',$Serial,'shell','am','instrument','-w','-r','-e','class',
            'com.example.pleos_pa.ui.DriverUiTest',"$package.test/androidx.test.runner.AndroidJUnitRunner") -TimeoutMillis 180000
        Write-Evidence 'instrumentation.txt' $result
        if ($result -match 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed|shortMsg=' -or
            $result -notmatch 'OK \(4 tests\)') { throw 'Expected OK (4 tests). ADB exit 0 alone is insufficient.' }
        $report['ui_tests'] = 4
    } elseif ($Stage -eq 'ProcessRestart') {
        # This stops only the isolated diagnostic package, never the original app or emulator.
        $before = Invoke-Probe 'seed'
        if ($before.records -lt 1) { throw 'Synthetic record missing before restart.' }
        [void](Invoke-Adb -AdbArguments @('-s',$Serial,'shell','am','force-stop',$package))
        $after = Invoke-Probe 'inspect'
        if ($before.processId -eq $after.processId -or $after.records -ne 0 -or -not $after.connectionStopped) {
            throw 'Process identity/journal/stopped-state check failed.'
        }
        $report['before'] = $before; $report['after'] = $after
    } else {
        $result = Invoke-Adb -AdbArguments @('-s',$Serial,'shell','am','start','-W','-n',
            "$package/com.example.pleos_pa.ui.DriverUiPreviewActivity")
        if ($result -match 'Error:|Exception|Permission Denial') { throw "Preview start failed: $result" }
    }
    $report['passed'] = $true
    Write-Evidence 'result.json' ($report | ConvertTo-Json -Depth 8)
    Write-Output "Saved evidence: $runDirectory"
    Write-Output 'Actual Pleos regulation, real service/notification stop and navigation require separate evidence.'
} catch {
    Write-Evidence 'failure.txt' $_.Exception.Message
    Write-Error $_.Exception.Message
    exit 1
}
