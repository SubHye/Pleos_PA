<#
.SYNOPSIS
  Prints a short table of the labelled and interactive UI elements.
.DESCRIPTION
  Reads a layout.json from `android layout -o` or a ui.xml from `uiautomator dump`, or
  captures a fresh layout with -Serial. One line per element: index, window, center,
  interactions, label (text, else content-desc, else resource-id name).
  Read only. Keep the source file ASCII: Windows PowerShell 5.1 misreads UTF-8 without BOM.
.EXAMPLE
  .\summarize-layout.ps1 -Serial emulator-5554
.EXAMPLE
  .\summarize-layout.ps1 -Path build\pleos-emulator-test\run\before-101500\layout.json -OutFile ui-summary.txt
#>
[CmdletBinding(DefaultParameterSetName = 'Path')]
param(
    [Parameter(ParameterSetName = 'Path', Mandatory)][string]$Path,
    [Parameter(ParameterSetName = 'Live', Mandatory)][string]$Serial,
    [string]$OutFile,
    # Window type filter for layout.json, e.g. APPLICATION, INPUT_METHOD, SYSTEM.
    [string[]]$Window = @(),
    # Include unlabelled interactive containers too.
    [switch]$All
)

$ErrorActionPreference = 'Stop'
$previousEncoding = [Console]::OutputEncoding
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$utf8 = New-Object System.Text.UTF8Encoding($false)

if ($PSCmdlet.ParameterSetName -eq 'Live') {
    $cli = (Get-Command android -ErrorAction SilentlyContinue | Select-Object -First 1).Source
    if (-not $cli) { $cli = Join-Path $env:USERPROFILE 'AppData\AndroidCLI\android.exe' }
    $Path = Join-Path ([IO.Path]::GetTempPath()) "pleos-layout-$PID.json"
    $ErrorActionPreference = 'Continue'
    & $cli layout "--device=$Serial" -o $Path *> $null
    $ErrorActionPreference = 'Stop'
    if (-not (Test-Path $Path)) {
        [Console]::OutputEncoding = $previousEncoding
        Write-Error 'android layout failed; run preflight and check ui_automation (Android-MCP may hold UiAutomation)'
        exit 1
    }
}

$rows = New-Object System.Collections.ArrayList
function Add-Row([string]$Win, [string]$Center, [string]$Flags, [string]$Text, [string]$Desc, [string]$Id) {
    $label = if ($Text) { $Text } elseif ($Desc) { "desc=$Desc" } elseif ($Id) { "id=$(($Id -split '/')[-1])" } else { '' }
    if (-not $label -and -not ($All -and $Flags)) { return }
    [void]$rows.Add([pscustomobject]@{ win = $Win; center = $Center; flags = $Flags; label = ($label -replace '\s+', ' ') })
}

$raw = [IO.File]::ReadAllText((Resolve-Path $Path), $utf8)
if ($raw.TrimStart().StartsWith('<')) {
    # uiautomator dump: focused window only
    $xml = [xml]$raw
    foreach ($n in $xml.SelectNodes('//node')) {
        $flags = @()
        if ($n.clickable -eq 'true') { $flags += 'C' }
        if ($n.'long-clickable' -eq 'true') { $flags += 'L' }
        if ($n.scrollable -eq 'true') { $flags += 'S' }
        if ($n.focusable -eq 'true' -and $n.class -match 'EditText') { $flags += 'E' }
        $center = ''
        if ($n.bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
            $center = '({0},{1})' -f ([int](([int]$Matches[1] + [int]$Matches[3]) / 2)), ([int](([int]$Matches[2] + [int]$Matches[4]) / 2))
        }
        Add-Row 'FOCUSED' $center ($flags -join '') $n.text $n.'content-desc' $n.'resource-id'
    }
}
else {
    $windows = $raw | ConvertFrom-Json
    function Walk($node, [string]$win) {
        $i = @($node.interactions)
        $flags = ''
        if ($i -contains 'CLICKABLE') { $flags += 'C' }
        if ($i -contains 'LONG_CLICKABLE') { $flags += 'L' }
        if ($i -contains 'SCROLLABLE') { $flags += 'S' }
        if ($i -contains 'EDITABLE' -or "$($node.class)" -match 'EditText') { $flags += 'E' }
        $center = if ($node.center -match '\[(\d+),(\d+)\]') { "($($Matches[1]),$($Matches[2]))" } else { '' }
        Add-Row $win $center $flags $node.text $node.'content-desc' $node.'resource-id'
        foreach ($c in @($node.children)) { if ($c) { Walk $c $win } }
    }
    foreach ($w in @($windows)) {
        if ($Window.Count -gt 0 -and $w.type -notin $Window) { continue }
        $name = if ($w.'window-title') { "$($w.type):$($w.'window-title')" } else { "$($w.type)" }
        foreach ($c in @($w.content)) { if ($c) { Walk $c $name } }
    }
}

$lines = @('{0,4}  {1,-28} {2,-12} {3,-4} {4}' -f 'idx', 'window', 'center', 'act', 'label')
$idx = 0
foreach ($r in $rows) {
    $win = if ($r.win.Length -gt 28) { $r.win.Substring(0, 28) } else { $r.win }
    $lines += '{0,4}  {1,-28} {2,-12} {3,-4} {4}' -f $idx, $win, $r.center, $r.flags, $r.label
    $idx++
}
$lines += "elements=$idx  act: C=click L=long-click S=scroll E=edit  source=$Path"

if ($OutFile) { [IO.File]::WriteAllText($OutFile, ($lines -join "`n"), $utf8) }
else { $lines }
if ($PSCmdlet.ParameterSetName -eq 'Live') { Remove-Item $Path -ErrorAction SilentlyContinue }
[Console]::OutputEncoding = $previousEncoding
exit 0
