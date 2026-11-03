# Run from Windows PowerShell 5.1 or PowerShell 7. No vehicle writes, reboot, uninstall or data reset.
# .\scripts\verify-com9-local.ps1 -Stage Install -Serial emulator-5554 -ApkDirectory .\apks
# .\scripts\verify-com9-local.ps1 -Stage RuntimeTest -Serial emulator-5554
# .\scripts\verify-com9-local.ps1 -Stage Start -Serial emulator-5554
# .\scripts\verify-com9-local.ps1 -Stage Capture -Serial emulator-5554 -Scenario parked
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidateSet('Install','RuntimeTest','Start','Capture')][string]$Stage,
    [Parameter(Mandatory=$true)][ValidatePattern('^emulator-[0-9]+$')][string]$Serial,
    [string]$AdbPath,
    [string]$ApkDirectory = (Join-Path $PSScriptRoot '..\app\build\outputs\apk'),
    [string]$OutputDirectory = (Join-Path (Get-Location).Path 'com9-evidence'),
    [ValidateSet('parked','restricted','loss-injection','app-stop','notification-stop',
        'notification-denied','after-manual-reboot','power-resume','inventory')]
    [string]$Scenario = 'inventory'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $AdbPath) {
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) { $AdbPath = $command.Source }
}
if (-not $AdbPath -and $env:LOCALAPPDATA) {
    $AdbPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
}
if (-not $AdbPath -or -not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
    throw 'ADB not found. Specify -AdbPath with your Android SDK platform-tools\adb.exe.'
}
$AdbPath = (Resolve-Path -LiteralPath $AdbPath).Path
$runName = [DateTimeOffset]::UtcNow.ToString('yyyyMMdd-HHmmss-fffffff') + '-' + $Stage + '-' + $Scenario
$runDirectory = Join-Path $OutputDirectory $runName
[void](New-Item -ItemType Directory -Path $runDirectory)
$script:commandNumber = 0
$utf8 = New-Object System.Text.UTF8Encoding($false)
function Write-Evidence([string]$Name, [string]$Text) {
    [IO.File]::WriteAllText((Join-Path $runDirectory $Name), $Text, $utf8)
}
function Invoke-Adb([string[]]$AdbArguments, [int]$TimeoutMillis = 30000) {
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $AdbPath
    # Windows command-line quoting, including embedded quotes and trailing backslashes.
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
            Write-Evidence 'timeout.txt' ('Timed out: adb ' + ($AdbArguments -join ' '))
            throw 'ADB timeout. This client was stopped; the device-side operation may still be running. Inspect before retrying.'
        }
        $result = $stdout.Result + $stderr.Result
        $script:commandNumber++
        Write-Evidence ('command-' + $script:commandNumber + '.txt') $result
        if ($process.ExitCode -ne 0) { throw "ADB failed (exit $($process.ExitCode)): $result" }
        return $result.Trim()
    } finally { $process.Dispose() }
}
try {
    $devices = Invoke-Adb -AdbArguments @('devices')
    if ($devices -notmatch ('(?m)^' + [regex]::Escape($Serial) + '\s+device\s*$')) {
        throw 'Selected emulator is not online/authorized. No other device will be selected automatically.'
    }
    $prefix = @('-s', $Serial)
    $properties = [ordered]@{}
    foreach ($property in @('ro.build.version.sdk', 'ro.build.version.release', 'ro.build.fingerprint', 'product.sw.target_region')) {
        $properties[$property] = Invoke-Adb -AdbArguments ($prefix + @('shell','getprop',$property))
    }
    $features = Invoke-Adb -AdbArguments ($prefix + @('shell','pm','list','features'))
    if ($features -notmatch '(?m)^feature:android\.hardware\.type\.automotive\s*$') {
        throw 'Selected emulator does not report Automotive. Stop and select the Pleos AVD.'
    }
    $report = [ordered]@{
        captured_at_utc = [DateTimeOffset]::UtcNow.ToString('o')
        stage = $Stage; serial = $Serial; operator_scenario_label = $Scenario
        properties = $properties
        scope = 'Scenario is an operator label, not proof of gear, speed or driver display. No automatic COM-9 acceptance.'
    }
    if ($Stage -eq 'Install') {
        $apkPaths = @(
            (Join-Path $ApkDirectory 'app-debug.apk'),
            (Join-Path $ApkDirectory 'app-debug-androidTest.apk')
        )
        if (-not (Test-Path -LiteralPath $apkPaths[0])) {
            $apkPaths = @((Join-Path $ApkDirectory 'debug\app-debug.apk'),
                (Join-Path $ApkDirectory 'androidTest\debug\app-debug-androidTest.apk'))
        }
        # Validate both inputs before making the first installation.
        foreach ($apk in $apkPaths) {
            if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) { throw "Missing APK: $apk" }
        }
        $hashes = [ordered]@{}
        foreach ($apk in $apkPaths) {
            $hashes[[IO.Path]::GetFileName($apk)] = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash
            $installed = Invoke-Adb -AdbArguments ($prefix + @('install','-r', (Resolve-Path -LiteralPath $apk).Path)) -TimeoutMillis 120000
            if ($installed -notmatch '(?m)^Success\s*$') { throw "Installation not confirmed: $installed" }
        }
        $report['installed_apk_sha256'] = $hashes
    } elseif ($Stage -eq 'RuntimeTest') {
        $result = Invoke-Adb -AdbArguments ($prefix + @('shell','am','instrument','-w','-r','-e','class',
            'com.example.pleos_pa.diagnostics.CarUxRuntimeTest',
            'com.example.pleos_pa.test/androidx.test.runner.AndroidJUnitRunner')) -TimeoutMillis 120000
        Write-Evidence 'instrumentation.txt' $result
        if ($result -match 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed|shortMsg=' -or
            $result -notmatch 'OK \(1 test\)') {
            throw 'Runtime test did not report OK (1 test). See instrumentation.txt; adb exit 0 alone is insufficient.'
        }
        $report['runtime_test'] = 'PASS: actual initial UX and direct stop handlers only; not callback transitions, notification panel or real SDK sender.'
    } elseif ($Stage -eq 'Start') {
        $started = Invoke-Adb -AdbArguments ($prefix + @('shell','am','start','-W','-n',
            'com.example.pleos_pa/.diagnostics.Com9DiagnosticActivity'))
        if ($started -match 'Error:|Exception|Permission Denial' -or $started -notmatch '(?m)^Status: ok\s*$') {
            throw "Activity start not confirmed: $started"
        }
    }
    $logs = Invoke-Adb -AdbArguments ($prefix + @('logcat','-d','-v','threadtime','-s','COM9_DIAG:I','COM9_TEST:I','*:S'))
    Write-Evidence 'diagnostics-log.txt' $logs
    $report['logs_scope'] = 'Tag-filtered retained log buffer; may include earlier runs. Match device timestamps and checkpoints; collection success is not a test pass.'
    Write-Evidence 'capture.json' ($report | ConvertTo-Json -Depth 6)
    Write-Output "Saved evidence: $runDirectory"
    Write-Output 'UI reachability, callback transitions, policy/approval review and reboot/power behavior require separate owner observations.'
} catch {
    Write-Evidence 'failure.txt' $_.Exception.Message
    Write-Error $_.Exception.Message
    exit 1
}
