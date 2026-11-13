<#
.SYNOPSIS
  Read-only environment diagnosis for Pleos Connect Emulator testing.
.DESCRIPTION
  Changes nothing on the host or device. Prints OK/WARN/FAIL/NA per item and
  suggests commands for the agent to run. Exit 0 = no FAIL, 1 = at least one FAIL.
.EXAMPLE
  .\preflight.ps1 -Serial emulator-5554 -Package com.example.pleostest
#>
[CmdletBinding()]
param(
    [string]$Serial,
    [string]$Package,
    [switch]$Json
)

$ErrorActionPreference = 'Continue'
$previousEncoding = [Console]::OutputEncoding
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$script:results = New-Object System.Collections.ArrayList
function Add-Result([string]$Name, [string]$Status, [string]$Detail, [string]$Suggest = '') {
    [void]$script:results.Add([pscustomobject]@{ item = $Name; status = $Status; detail = $Detail; suggest = $Suggest })
}

function Find-Tool([string]$Name, [string[]]$Extra = @()) {
    $found = Get-Command $Name -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($found) { return $found.Source }
    foreach ($candidate in $Extra) { if ($candidate -and (Test-Path $candidate)) { return $candidate } }
    return $null
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path

# --- Host tools ---
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) {
    $localProperties = Join-Path $repoRoot 'local.properties'
    if (Test-Path $localProperties) {
        $line = Select-String -Path $localProperties -Pattern '^sdk\.dir=(.+)$' | Select-Object -First 1
        if ($line) { $sdk = $line.Matches[0].Groups[1].Value -replace '\\:', ':' -replace '\\\\', '\' }
    }
}
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (Test-Path $sdk) { Add-Result 'android_sdk' 'OK' $sdk }
else { Add-Result 'android_sdk' 'FAIL' "not found: $sdk" 'Set ANDROID_HOME or local.properties sdk.dir' }

$adb = Find-Tool 'adb' @((Join-Path $sdk 'platform-tools\adb.exe'))
if ($adb) { Add-Result 'adb' 'OK' (((& $adb version 2>$null) | Select-Object -First 2) -join ' / ') }
else { Add-Result 'adb' 'FAIL' 'not found' 'Install Android SDK Platform-Tools' }

$androidCli = Find-Tool 'android' @((Join-Path $env:USERPROFILE 'AppData\AndroidCLI\android.exe'))
if ($androidCli) { Add-Result 'android_cli' 'OK' "$androidCli $((& $androidCli --version 2>$null) | Select-Object -Last 1)" }
else { Add-Result 'android_cli' 'WARN' 'not installed; adb fallback only' 'See references/emulator-setup.md' }

$agentBrowser = Find-Tool 'agent-browser'
if ($agentBrowser) { Add-Result 'agent_browser' 'OK' $agentBrowser }
else { Add-Result 'agent_browser' 'WARN' 'not installed; Virtual Console UI automation unavailable' }

$node = Find-Tool 'node'
if ($node) { Add-Result 'node' 'OK' ((& $node --version 2>$null) | Select-Object -First 1) }
else { Add-Result 'node' 'WARN' 'not found; MCP checks unavailable' }

function Finish {
    [Console]::OutputEncoding = $previousEncoding
    $failed = @($script:results | Where-Object { $_.status -eq 'FAIL' }).Count
    if ($Json) {
        [pscustomobject]@{ serial = $Serial; failed = $failed; results = $script:results } | ConvertTo-Json -Depth 4
    }
    else {
        foreach ($r in $script:results) {
            '{0,-5} {1,-20} {2}' -f $r.status, $r.item, $r.detail
            if ($r.suggest) { '      -> {0}' -f $r.suggest }
        }
        "SERIAL=$Serial FAILED=$failed"
    }
    if ($failed -gt 0) { exit 1 }
    exit 0
}

if (-not $adb) { Finish }

# --- Device selection ---
$devices = @((& $adb devices 2>$null) | Select-Object -Skip 1 | Where-Object { $_ -match '\S' } | ForEach-Object {
        $parts = $_ -split '\s+'
        [pscustomobject]@{ serial = $parts[0]; state = $parts[1] }
    })
$ready = @($devices | Where-Object { $_.state -eq 'device' })
if ($Serial) {
    $match = @($devices | Where-Object { $_.serial -eq $Serial })
    if ($match.Count -eq 0) {
        Add-Result 'device' 'FAIL' "serial '$Serial' not connected (seen: $(($devices.serial) -join ', '))" 'Run adb devices -l and pass a listed serial'
        Finish
    }
    if ($match[0].state -ne 'device') {
        Add-Result 'device' 'FAIL' "serial '$Serial' state=$($match[0].state)"
        Finish
    }
}
elseif ($ready.Count -eq 1) { $Serial = $ready[0].serial }
elseif ($ready.Count -eq 0) {
    Add-Result 'device' 'FAIL' 'no ready device' 'Start the Pleos_Connect AVD with cold boot; see references/emulator-setup.md'
    Finish
}
else {
    Add-Result 'device' 'FAIL' "multiple devices, none chosen: $(($ready.serial) -join ', ')" 'Pass -Serial; do not guess the target'
    Finish
}
Add-Result 'device' 'OK' $Serial

function Dev { & $adb -s $Serial @args 2>$null }
function Prop([string]$Key) { "$(Dev shell getprop $Key)".Trim() }
function PackageVersion([string]$Name) {
    $line = Dev shell dumpsys package $Name | Select-String 'versionName=' | Select-Object -First 1
    if ($line) { return ($line.Line.Trim() -replace '^versionName=', '') }
    return $null
}

# --- Pleos identity: guards any later state change ---
$brand = Prop 'ro.product.brand'
$type = Prop 'ro.hardware.type'
$avd = Prop 'ro.boot.qemu.avd_name'
$fingerprint = Prop 'ro.build.fingerprint'
$isPleos = ($Serial -like 'emulator-*') -and ($type -eq 'automotive') -and ($brand -eq 'Pleos') -and ($fingerprint -like 'Pleos/*')
if ($isPleos) { Add-Result 'pleos_identity' 'OK' "avd=$avd api=$(Prop 'ro.build.version.sdk') fingerprint=$fingerprint" }
else { Add-Result 'pleos_identity' 'FAIL' "serial=$Serial brand=$brand type=$type avd=$avd" 'Not a Pleos emulator: do not change state or record results as Pleos' }

if ((Prop 'sys.boot_completed') -eq '1') { Add-Result 'boot_completed' 'OK' 'sys.boot_completed=1' }
else { Add-Result 'boot_completed' 'FAIL' 'not booted yet' 'Wait for boot; do not test before it completes' }

# --- Cold boot: the official guide says VHAL is unusable with quick boot ---
$avdConfig = Join-Path $env:USERPROFILE ".android\avd\$avd.avd\config.ini"
if ($avd -and (Test-Path $avdConfig)) {
    $cold = Select-String -Path $avdConfig -Pattern '^\s*fastboot\.forceColdBoot\s*=\s*(\S+)' | Select-Object -First 1
    if ($cold -and $cold.Matches[0].Groups[1].Value -eq 'yes') { Add-Result 'cold_boot_config' 'OK' 'fastboot.forceColdBoot=yes' }
    elseif ($cold) { Add-Result 'cold_boot_config' 'WARN' "fastboot.forceColdBoot=$($cold.Matches[0].Groups[1].Value)" 'Set Default boot to Cold in Device Manager; VHAL needs cold boot' }
    else { Add-Result 'cold_boot_config' 'WARN' 'fastboot.forceColdBoot not set' 'Confirm Default boot = Cold in Device Manager' }
}
else { Add-Result 'cold_boot_config' 'NA' 'AVD config not found on this host' }

# --- Pleos services ---
foreach ($service in @(@('vehicle_service', 'ai.pleos.playground.service.vehicle'), @('navi_service', 'ai.pleos.playground.service.navi'))) {
    $version = PackageVersion $service[1]
    if ($version) { Add-Result $service[0] 'OK' "$($service[1]) $version" }
    else { Add-Result $service[0] 'WARN' "$($service[1]) not found" }
}

# --- Android user and target package ---
$user = "$(Dev shell am get-current-user)".Trim()
Add-Result 'current_user' 'OK' $user
if ($Package) {
    $installed = "$(Dev shell pm path --user $user $Package)".Trim()
    if ($installed) {
        $appPid = "$(Dev shell pidof $Package)".Trim()
        $appState = if ($appPid) { "running pid=$appPid" } else { 'not running' }
        Add-Result 'target_package' 'OK' "$Package v$(PackageVersion $Package) user=$user $appState"
    }
    else { Add-Result 'target_package' 'FAIL' "$Package not installed for user $user" "Install it for user $user; user 0 lookups are not sufficient on Automotive" }
}
else { Add-Result 'target_package' 'NA' 'no -Package given' }

# --- Another session on this emulator? SDK callbacks reach every registered app. ---
$lock = "$(Dev shell 'cat /data/local/tmp/pleos-test.lock 2>/dev/null')".Trim()
if ($lock) { Add-Result 'device_lock' 'WARN' "lock held: $lock" 'Another session may be testing; ask the user before any SDK call or state change. Remove only your own lock' }
else { Add-Result 'device_lock' 'OK' 'no lock (/data/local/tmp/pleos-test.lock)' }

$testApps = @('ai.pleos.playground.sdk.navidevapp', 'ai.pleos.playground.sample.vehicle', 'com.example.pleostest', 'com.example.pleos_pa.placecheck', 'com.example.pleos_pa')
$top = "$((Dev shell dumpsys activity activities) | Select-String 'topResumedActivity' | Select-Object -First 1)"
$topPkg = if ($top -match 'u\d+\s+([A-Za-z0-9_.]+)/') { $Matches[1] } else { '' }
$now = [long]"$(Dev shell date +%s)".Trim()
$recent = @((Dev logcat -d -v epoch -t 2000) | Where-Object { $_ -match '^\s*(\d+)\.\d+' -and ($now - [long]$Matches[1]) -le 120 } |
    Where-Object { $_ -match '\[NaviDevApp\]|PlaceCheck|\[COM\d+\]' })
$foreign = @()
if ($topPkg -and $topPkg -in $testApps -and $topPkg -ne $Package) { $foreign += "foreground $topPkg" }
if ($recent.Count -gt 0) { $foreign += "$($recent.Count) test-app log lines in the last 120 s" }
if ($foreign) { Add-Result 'foreign_activity' 'WARN' ($foreign -join '; ') 'Check with the user that no other session is running before calling the SDK' }
else { Add-Result 'foreign_activity' 'OK' "foreground $topPkg; no test-app logs in the last 120 s" }

# --- Who holds the single UiAutomation connection? ---
# Android-MCP (uiautomator2) keeps com.wetest.uia2.Main running from /data/local/tmp/u2.jar and
# forwards a host port to device tcp:9008. While it runs, android layout/input and uiautomator dump fail.
$forwards = @((& $adb forward --list 2>$null))
$procs = @(Dev shell 'ps -A -o PID,NAME,ARGS')
$u2 = @($procs | Where-Object { $_ -match 'uia2\.Main|u2\.jar' -and $_ -notmatch '^\s*\d+\s+sh\s' })
$u2Forward = @($forwards | Where-Object { $_ -match "^$([regex]::Escape($Serial))\s+tcp:\d+\s+tcp:9008$" })
$cliHolder = @($procs | Where-Object { $_ -match 'com\.android\.cli\.interact\.instrumentation' -and $_ -notmatch 'Am instrument' })
if ($u2) {
    $pids = ($u2 | ForEach-Object { ($_.Trim() -split '\s+')[0] }) -join ','
    $fw = if ($u2Forward) { "; host forward $(($u2Forward[0] -split '\s+')[1])->tcp:9008" } else { '' }
    Add-Result 'ui_automation' 'WARN' "Android-MCP/uiautomator2 server running (pid $pids$fw); android layout/input and uiautomator dump will fail" 'Do not kill it (it belongs to the Android-MCP plugin). Ask the user to disable Android-MCP, or use only its Snapshot for reading'
}
elseif ($cliHolder) { Add-Result 'ui_automation' 'OK' 'held by Android CLI instrumentation (uiautomator dump falls back to android layout)' }
else { Add-Result 'ui_automation' 'OK' 'free' }

# --- Forwards and listeners ---
$listeners = @(netstat -ano -p tcp | Select-String 'LISTENING')
function Test-Forward([string]$Name, [int]$Port, [string]$Suggestion) {
    $forward = $forwards | Where-Object { $_ -match "^$([regex]::Escape($Serial))\s+tcp:$Port\s+" }
    $exposed = $listeners | Where-Object { $_.Line -match "(0\.0\.0\.0|\[::\]):$Port\s" }
    if ($exposed) { Add-Result $Name 'FAIL' "port $Port is bound beyond loopback" 'Remove this mapping; never expose it to the network' }
    elseif ($forward) { Add-Result $Name 'OK' "tcp:$Port forwarded for $Serial" }
    else { Add-Result $Name 'WARN' "tcp:$Port not forwarded for $Serial" $Suggestion }
    return [bool]$forward
}
$consoleForward = Test-Forward 'forward_console' 8088 "adb -s $Serial forward --no-rebind tcp:8088 tcp:8088"
[void](Test-Forward 'forward_mcp' 18766 "adb -s $Serial forward --no-rebind tcp:18766 tcp:18766 (only while testing the app's MCP server; remove afterwards)")
[void](Test-Forward 'forward_bridge' 18765 "adb -s $Serial forward --no-rebind tcp:18765 tcp:18765 (PoC bridge only)")

# --- Virtual Console (undocumented read endpoint, observed on v2.1.0) ---
if ($consoleForward) {
    try {
        $state = Invoke-RestMethod -Uri 'http://127.0.0.1:8088/vehicle/state' -TimeoutSec 5
        $clients = $state.browser.clients
        $detail = "reachable browser_clients=$clients available=$($state.availability.available) source=$($state.availability.source)"
        if ($clients -ge 1) { Add-Result 'virtual_console' 'OK' $detail }
        else { Add-Result 'virtual_console' 'WARN' $detail 'Open the page in a browser before changing state; see references/pleos-specific.md' }
    }
    catch { Add-Result 'virtual_console' 'FAIL' "forwarded but not reachable: $($_.Exception.Message)" 'Check the emulator image and port 8088 owner' }
}
else { Add-Result 'virtual_console' 'NA' 'no forward' }

# --- App MCP server (ADR-0008), PoC bridge, test support ---
$mcpListen = "$(Dev shell 'netstat -ltn 2>/dev/null | grep 18766')".Trim()
if ($mcpListen) { Add-Result 'mcp_listener' 'OK' 'device is listening on 18766 (Pleos_PA MCP)' }
else { Add-Result 'mcp_listener' 'NA' 'nothing is listening on 18766 on the device' 'Start the connection from the Pleos_PA screen if this test needs MCP' }

$bridgeListen = "$(Dev shell 'netstat -ltn 2>/dev/null | grep 18765')".Trim()
if ($bridgeListen) { Add-Result 'bridge_listener' 'OK' 'device is listening on 18765 (PoC bridge)' }
else { Add-Result 'bridge_listener' 'NA' 'nothing is listening on 18765 on the device' 'Only the PoC uses 18765' }

if (Test-Path (Join-Path $repoRoot 'app\src\main\java\com\example\pleos_pa\mcp')) { Add-Result 'repo_mcp' 'OK' 'embedded MCP server source present (app/.../mcp)' }
else { Add-Result 'repo_mcp' 'NA' 'no embedded MCP server source in this checkout' }

$placeCheck = "$(Dev shell pm path --user $user com.example.pleos_pa.placecheck)".Trim()
if ($placeCheck) { Add-Result 'nav_place_check' 'OK' "installed for user $user (changes navigation; approve every run)" }
else { Add-Result 'nav_place_check' 'NA' "com.example.pleos_pa.placecheck not installed for user $user" 'Only needed to set or read back destinations; see tools/nav-place-check/README.md' }

Finish
