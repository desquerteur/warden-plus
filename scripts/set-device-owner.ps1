<#
.SYNOPSIS
    Macht Warden+ zum Device Owner ("Maximaler Schutz") - per ADB, Schritt fuer Schritt geprueft.
    Makes Warden+ the device owner via ADB, with every precondition checked first.

.DESCRIPTION
    Prueft nacheinander:
      1. ob adb gefunden wird (PATH, Android-SDK oder -AdbPath),
      2. ob genau ein Handy per USB verbunden und freigegeben ist,
      3. ob Warden+ installiert ist und den Geraeteadministrator-Empfaenger hat
         (Name wird aus app/src/main/AndroidManifest.xml gelesen),
      4. ob schon ein Device Owner gesetzt ist,
      5. ob noch Konten (Google, Samsung, ...) oder weitere Benutzer/Profile
         (Sicherer Ordner, Dual Messenger, Arbeitsprofil) vorhanden sind.
    Erst wenn alles passt, fuehrt es aus:
        adb shell dpm set-device-owner com.warden.blocker/.system.WardenDeviceAdminReceiver
    Jede Fehlermeldung wird in einfachen Worten erklaert.

    Das Skript loescht, aendert oder deinstalliert NICHTS. Konten meldest du selbst ab
    (und danach wieder an). Wieder entfernen geht nur in der App:
    Einstellungen -> Maximum protection -> Remove device owner.

    Anleitung: docs/DEVICE_OWNER.md

    Aufruf (im Projektordner oder im Ordner des Skripts):
        powershell -ExecutionPolicy Bypass -File scripts\set-device-owner.ps1
        powershell -ExecutionPolicy Bypass -File scripts\set-device-owner.ps1 -CheckOnly
        powershell -ExecutionPolicy Bypass -File scripts\set-device-owner.ps1 -Serial R5CT123ABC

.PARAMETER Serial
    Seriennummer des Geraets (aus "adb devices"), nur noetig, wenn mehrere verbunden sind.

.PARAMETER AdbPath
    Voller Pfad zu adb.exe, falls es nicht automatisch gefunden wird.

.PARAMETER CheckOnly
    Nur pruefen, nichts setzen.
#>
param(
    [string]$Serial,
    [string]$AdbPath,
    [switch]$CheckOnly
)

$ErrorActionPreference = 'Stop'
$package = 'com.warden.blocker'
$defaultReceiver = '.system.WardenDeviceAdminReceiver'

function Write-Ok([string]$text)   { Write-Host "  [OK]  $text" -ForegroundColor Green }
function Write-Info([string]$text) { Write-Host "        $text" }
function Stop-WithHelp([string]$problem, [string[]]$help) {
    Write-Host ''
    Write-Host "  [STOPP] $problem" -ForegroundColor Red
    foreach ($line in $help) { Write-Host "          $line" -ForegroundColor Yellow }
    Write-Host ''
    Write-Host '  Es wurde nichts veraendert. Anleitung: docs/DEVICE_OWNER.md'
    exit 1
}

# --- 1. adb finden ---------------------------------------------------------------------------
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
Write-Host 'Warden+ als Device Owner einrichten' -ForegroundColor Cyan
Write-Host ''
Write-Ok "adb gefunden: $adb"

function Invoke-Adb([string[]]$adbArgs) {
    $all = @()
    if ($Serial) { $all += @('-s', $Serial) }
    $all += $adbArgs
    $out = & $adb @all 2>&1 | ForEach-Object { "$_" }
    return ($out -join "`n")
}

# --- 2. Geraet pruefen -----------------------------------------------------------------------
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
            'Kommt keine Frage: USB-Kabel kurz abziehen und wieder anstecken, oder in den',
            'Entwickleroptionen "USB-Debugging-Autorisierungen widerrufen" und neu verbinden.'
        )
    }
    'offline' { Stop-WithHelp 'Das Handy ist "offline".' @('USB-Kabel abziehen, wieder anstecken, Handy entsperren und erneut starten.') }
    'device' { }
    default { Stop-WithHelp "Das Handy ist im Zustand '$state'." @('Handy normal starten, entsperren und erneut versuchen.') }
}
$model = (Invoke-Adb @('shell', 'getprop', 'ro.product.model')).Trim()
$sdk = (Invoke-Adb @('shell', 'getprop', 'ro.build.version.sdk')).Trim()
Write-Ok "Handy verbunden: $model (Seriennummer $Serial, Android-API $sdk)"

# --- 3. Warden+ und Empfaenger pruefen -------------------------------------------------------
$receiver = $defaultReceiver
$manifest = Join-Path (Split-Path -Parent $PSScriptRoot) 'app\src\main\AndroidManifest.xml'
if (Test-Path $manifest) {
    [xml]$xml = Get-Content -Raw -Encoding UTF8 $manifest
    $ns = 'http://schemas.android.com/apk/res/android'
    $found = $xml.manifest.application.receiver |
        Where-Object { $_.GetAttribute('permission', $ns) -eq 'android.permission.BIND_DEVICE_ADMIN' } |
        Select-Object -First 1
    if ($found) { $receiver = $found.GetAttribute('name', $ns) }
}
# ".system.X" and "com.warden.blocker.system.X" are both valid after the slash.
$component = "$package/$receiver"

$installed = Invoke-Adb @('shell', 'pm', 'path', $package)
if ($installed -notmatch 'package:') {
    Stop-WithHelp 'Warden+ ist auf dem Handy nicht installiert.' @(
        'Installiere zuerst die Warden+-App (APK) und oeffne sie einmal. Dann das Skript erneut starten.'
    )
}
$receiverShort = $receiver.TrimStart('.').Split('.')[-1]
$dump = Invoke-Adb @('shell', 'dumpsys', 'package', $package)
if ($dump -notmatch [regex]::Escape($receiverShort)) {
    Stop-WithHelp "Die installierte Warden+-Version kennt den Empfaenger '$receiver' nicht." @(
        'Installiere die aktuelle Warden+-Version und starte das Skript erneut.'
    )
}
Write-Ok "Warden+ installiert, Empfaenger: $component"

# --- 4. Schon ein Device Owner? --------------------------------------------------------------
$policy = Invoke-Adb @('shell', 'dumpsys', 'device_policy')
$ownerMatch = [regex]::Match($policy, 'Device Owner:[\s\S]*?(?:package|admin)=\s*(?:ComponentInfo\{)?([A-Za-z0-9_.]+)')
if ($ownerMatch.Success) {
    $owner = $ownerMatch.Groups[1].Value
    if ($owner -eq $package) {
        Write-Ok 'Warden+ ist bereits Device Owner. Nichts zu tun.'
        Write-Info 'In der App: Einstellungen -> Maximum protection -> Regeln einschalten.'
        exit 0
    }
    Stop-WithHelp "Auf dem Handy ist schon eine andere App Device Owner ($owner)." @(
        'Es kann nur einen geben. Das ist meist ein Firmenhandy (Mobile Device Management).',
        'Dann bitte nicht weitermachen - frag deine IT.'
    )
}
Write-Ok 'Noch kein Device Owner gesetzt.'

# --- 5. Konten und Benutzer ------------------------------------------------------------------
$accounts = Invoke-Adb @('shell', 'dumpsys', 'account')
$count = 0
foreach ($m in [regex]::Matches($accounts, 'Accounts:\s*(\d+)')) { $count += [int]$m.Groups[1].Value }
if ($count -gt 0) {
    $types = [regex]::Matches($accounts, 'Account \{name=[^,]*, type=([^}]+)\}') |
        ForEach-Object { $_.Groups[1].Value.Trim() } | Sort-Object -Unique
    $names = @{
        'com.google' = 'Google-Konto'
        'com.osp.app.signin' = 'Samsung-Konto'
        'com.samsung.android.mobileservice' = 'Samsung-Konto (Dienste)'
        'com.microsoft.workaccount' = 'Microsoft-Konto'
        'com.whatsapp' = 'WhatsApp'
    }
    $list = $types | ForEach-Object { if ($names.ContainsKey($_)) { "  - $($names[$_]) ($_)" } else { "  - $_" } }
    Stop-WithHelp "Auf dem Handy sind noch $count Konto/Konten angemeldet." (@(
        'Android erlaubt den Device Owner nur, wenn KEIN Konto angemeldet ist. Gefunden:'
    ) + @($list) + @(
        'Abmelden: Einstellungen -> Konten und Sicherung -> Konten verwalten -> Konto -> Konto entfernen.',
        'Deine Daten bleiben in der Cloud; nach dem Einrichten meldest du dich einfach wieder an.',
        'Steht hier eine App (z. B. WhatsApp), die sich nicht abmelden laesst: siehe docs/DEVICE_OWNER.md.'
    ))
}
Write-Ok 'Keine Konten angemeldet.'

$users = Invoke-Adb @('shell', 'pm', 'list', 'users')
$userIds = [regex]::Matches($users, 'UserInfo\{(\d+):([^:]*):') | ForEach-Object { "$($_.Groups[1].Value) ($($_.Groups[2].Value))" }
if (@($userIds).Count -gt 1) {
    Stop-WithHelp 'Auf dem Handy gibt es mehrere Benutzer oder Profile.' (@(
        'Gefunden:'
    ) + @($userIds | ForEach-Object { "  - Benutzer $_" }) + @(
        'Bei Samsung sind das meist: "Sicherer Ordner" (Benutzer 150), "Dual Messenger" (95) oder ein Arbeitsprofil.',
        'Diese muessen fuer die Einrichtung entfernt werden (Inhalte vorher sichern!). Danach erneut starten.'
    ))
}
Write-Ok 'Nur ein Benutzer vorhanden.'

if ($CheckOnly) {
    Write-Host ''
    Write-Host '  Alles bereit. (Nur geprueft, -CheckOnly: nichts gesetzt.)' -ForegroundColor Green
    exit 0
}

# --- 6. Setzen -------------------------------------------------------------------------------
Write-Host ''
Write-Info "Fuehre aus: adb shell dpm set-device-owner $component"
$result = Invoke-Adb @('shell', 'dpm', 'set-device-owner', $component)
Write-Info $result.Trim()

if ($result -match 'Success') {
    Write-Host ''
    Write-Host '  Fertig! Warden+ ist jetzt Device Owner.' -ForegroundColor Green
    Write-Info 'Jetzt:'
    Write-Info '  1. Konten wieder anmelden (Google, Samsung).'
    Write-Info '  2. Warden+ oeffnen -> Settings -> "Maximum protection" -> Regeln einschalten.'
    Write-Info '  3. Wieder entfernen geht jederzeit dort mit "Remove device owner".'
    exit 0
}

$help = switch -Regex ($result) {
    'accounts' { @('Es ist noch ein Konto angemeldet (evtl. eins, das in den Einstellungen nicht auftaucht).',
                   'Alle Konten abmelden, Handy neu starten, erneut versuchen. Siehe docs/DEVICE_OWNER.md.') }
    'several users|multiple users' { @('Es gibt noch einen weiteren Benutzer/Profil (Sicherer Ordner, Dual Messenger, Arbeitsprofil).',
                   'Entfernen, Handy neu starten, erneut versuchen.') }
    'already (set|provisioned)|device owner is already' { @('Es ist schon ein Device Owner gesetzt oder das Geraet gilt als fertig eingerichtet.',
                   'Siehe "Typische Fehlermeldungen" in docs/DEVICE_OWNER.md.') }
    'Unknown admin|not found|NameNotFound' { @('Android kennt den Empfaenger nicht. Ist die aktuelle Warden+-Version installiert?') }
    'MANAGE_DEVICE_ADMINS|SecurityException|not allowed' { @('Das Handy verbietet das per USB-Befehl.',
                   'Bei manchen Herstellern muss in den Entwickleroptionen zusaetzlich',
                   '"USB-Debugging (Sicherheitseinstellungen)" an sein. Samsung: Knox-Hinweise in docs/DEVICE_OWNER.md.') }
    default { @('Unbekannter Fehler - die Meldung oben bitte in docs/DEVICE_OWNER.md nachschlagen.') }
}
Stop-WithHelp 'Android hat den Device Owner nicht gesetzt.' $help
