<#
.SYNOPSIS
    Erlaubt Warden+, den Bildschirm grau zu schalten ("Gray mode") - per ADB, einmalig.
    Grants Warden+ the one-time permission for gray mode via ADB.

.DESCRIPTION
    Prueft nacheinander:
      1. ob adb gefunden wird (PATH, Android-SDK oder -AdbPath),
      2. ob genau ein Handy per USB verbunden und freigegeben ist,
      3. ob Warden+ installiert ist.
    Dann fuehrt es aus:
        adb shell pm grant com.warden.blocker android.permission.WRITE_SECURE_SETTINGS
    und prueft, ob die Erlaubnis jetzt da ist. Sonst wird nichts veraendert.

    Anleitung: docs/GRAY_MODE.md

    Aufruf (im Projektordner):
        powershell -ExecutionPolicy Bypass -File scripts\enable-gray-mode.ps1
        powershell -ExecutionPolicy Bypass -File scripts\enable-gray-mode.ps1 -Serial R5CT123ABC

.PARAMETER Serial
    Seriennummer des Geraets (aus "adb devices"), nur noetig, wenn mehrere verbunden sind.

.PARAMETER AdbPath
    Voller Pfad zu adb.exe, falls es nicht automatisch gefunden wird.
#>
param(
    [string]$Serial,
    [string]$AdbPath
)

$ErrorActionPreference = 'Stop'
$package = 'com.warden.blocker'
$permission = 'android.permission.WRITE_SECURE_SETTINGS'

function Write-Ok([string]$text)   { Write-Host "  [OK]  $text" -ForegroundColor Green }
function Write-Info([string]$text) { Write-Host "        $text" }
function Stop-WithHelp([string]$problem, [string[]]$help) {
    Write-Host ''
    Write-Host "  [STOPP] $problem" -ForegroundColor Red
    foreach ($line in $help) { Write-Host "          $line" -ForegroundColor Yellow }
    Write-Host ''
    Write-Host '  Es wurde nichts veraendert. Anleitung: docs/GRAY_MODE.md'
    exit 1
}

function Find-Adb {
    if ($AdbPath) {
        if (Test-Path $AdbPath) { return (Resolve-Path $AdbPath).Path }
        Stop-WithHelp "adb wurde unter '$AdbPath' nicht gefunden." @('Pruefe den Pfad zu adb.exe (Ordner platform-tools).')
    }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $candidates = @(
        (Join-Path $PSScriptRoot 'platform-tools\adb.exe'),
        (Join-Path (Get-Location) 'platform-tools\adb.exe'),
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'),
        (Join-Path $env:USERPROFILE 'Downloads\platform-tools\adb.exe'),
        'C:\platform-tools\adb.exe'
    )
    if ($env:ANDROID_HOME) { $candidates += (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe') }
    foreach ($c in $candidates) { if (Test-Path $c) { return $c } }
    Stop-WithHelp 'adb (Android platform-tools) wurde nicht gefunden.' @(
        'Lade die "SDK Platform-Tools for Windows" von Google herunter:',
        '  https://developer.android.com/tools/releases/platform-tools',
        'Entpacke die ZIP-Datei z. B. nach C:\platform-tools und starte das Skript erneut',
        '(oder gib den Pfad an: -AdbPath C:\platform-tools\adb.exe).'
    )
}

$adb = Find-Adb
Write-Host ''
Write-Host 'Warden+ Gray mode freischalten' -ForegroundColor Cyan
Write-Host ''
Write-Ok "adb gefunden: $adb"

function Invoke-Adb([string[]]$adbArgs) {
    $all = @()
    if ($Serial) { $all += @('-s', $Serial) }
    $all += $adbArgs
    $out = & $adb @all 2>&1 | ForEach-Object { "$_" }
    return ($out -join "`n")
}

& $adb start-server 2>&1 | Out-Null
$deviceLines = (& $adb devices 2>&1 | ForEach-Object { "$_" }) |
    Where-Object { $_ -match '^\S+\s+(device|unauthorized|offline|no permissions|recovery|sideload)\b' }
if ($Serial) { $deviceLines = $deviceLines | Where-Object { $_ -match ('^' + [regex]::Escape($Serial) + '\s') } }

if (-not $deviceLines) {
    Stop-WithHelp 'Kein Handy gefunden.' @(
        '1. Handy per USB-Kabel an den PC anschliessen (ein Kabel, das Daten kann, nicht nur Laden).',
        '2. Auf dem Handy: Einstellungen -> Telefoninfo -> Softwareinformationen -> 7x auf "Buildnummer" tippen.',
        '3. Einstellungen -> Entwickleroptionen -> "USB-Debugging" einschalten.',
        '4. Auf dem Handy die Frage "USB-Debugging zulassen?" mit "Zulassen" bestaetigen.'
    )
}
if (@($deviceLines).Count -gt 1) {
    Stop-WithHelp 'Mehrere Geraete verbunden.' (@('Gib an, welches gemeint ist, z. B.:  -Serial <Nummer>') + @($deviceLines))
}
$line = @($deviceLines)[0]
$Serial = ($line -split '\s+')[0]
$state = ($line -split '\s+')[1]
switch ($state) {
    'unauthorized' {
        Stop-WithHelp 'Das Handy hat den PC noch nicht freigegeben.' @(
            'Entsperre das Handy. Dort erscheint "USB-Debugging zulassen?" -> "Zulassen" tippen.',
            'Kommt keine Frage: USB-Kabel kurz abziehen und wieder anstecken.'
        )
    }
    'offline' { Stop-WithHelp 'Das Handy ist "offline".' @('USB-Kabel abziehen, wieder anstecken, Handy entsperren und erneut starten.') }
    'device' { }
    default { Stop-WithHelp "Das Handy ist im Zustand '$state'." @('Handy normal starten, entsperren und erneut versuchen.') }
}
$model = (Invoke-Adb @('shell', 'getprop', 'ro.product.model')).Trim()
Write-Ok "Handy verbunden: $model (Seriennummer $Serial)"

$installed = Invoke-Adb @('shell', 'pm', 'path', $package)
if ($installed -notmatch 'package:') {
    Stop-WithHelp 'Warden+ ist auf dem Handy nicht installiert.' @(
        'Installiere zuerst die Warden+-App (APK). Dann das Skript erneut starten.'
    )
}
Write-Ok 'Warden+ installiert.'

Write-Info "Fuehre aus: adb shell pm grant $package $permission"
$result = Invoke-Adb @('shell', 'pm', 'grant', $package, $permission)
if ($result.Trim()) { Write-Info $result.Trim() }

$dump = Invoke-Adb @('shell', 'dumpsys', 'package', $package)
if ($dump -match ([regex]::Escape($permission) + ': granted=true')) {
    Write-Host ''
    Write-Host '  Fertig! Warden+ darf den Bildschirm jetzt grau schalten.' -ForegroundColor Green
    Write-Info 'Jetzt: Warden+ oeffnen -> Settings -> "Gray mode" einschalten.'
    exit 0
}
Stop-WithHelp 'Android hat die Erlaubnis nicht erteilt.' @(
    'Ist die aktuelle Warden+-Version installiert (sie muss die Erlaubnis kennen)?',
    'Manche Hersteller verlangen in den Entwickleroptionen zusaetzlich',
    '"USB-Debugging (Sicherheitseinstellungen)" (Xiaomi). Danach erneut starten.'
)
