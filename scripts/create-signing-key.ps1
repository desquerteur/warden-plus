<#
.SYNOPSIS
    Creates the release signing key for Warden+ (run ONCE).

.DESCRIPTION
    - Creates %USERPROFILE%\.warden-plus\release.jks (PKCS12, RSA 4096, alias "warden-plus")
      with a random password.
    - Writes keystore.properties in the project root so local release builds are signed.
    - Writes %USERPROFILE%\.warden-plus\github-secrets.txt with the four values to paste into
      GitHub -> Settings -> Secrets and variables -> Actions.

    Passwords are never printed to the console. The script refuses to overwrite an existing
    key: every Warden+ release must be signed with the SAME key, otherwise installed apps
    can't be updated.

    Usage (from the project root):
        powershell -ExecutionPolicy Bypass -File scripts\create-signing-key.ps1

    See docs/RELEASING.md.
#>

$ErrorActionPreference = 'Stop'

$keyDir      = Join-Path $env:USERPROFILE '.warden-plus'
$keystore    = Join-Path $keyDir 'release.jks'
$secretsFile = Join-Path $keyDir 'github-secrets.txt'
$projectRoot = Split-Path -Parent $PSScriptRoot
$propsFile   = Join-Path $projectRoot 'keystore.properties'
$alias       = 'warden-plus'

function New-RandomPassword([int]$length = 32) {
    # Letters and digits only: safe in properties files, shells and GitHub secrets.
    $chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'.ToCharArray()
    $rng = New-Object System.Security.Cryptography.RNGCryptoServiceProvider
    $bytes = New-Object byte[] 1
    $result = New-Object System.Text.StringBuilder
    while ($result.Length -lt $length) {
        $rng.GetBytes($bytes)
        # Rejection sampling avoids modulo bias.
        if ($bytes[0] -lt ([int](256 / $chars.Length) * $chars.Length)) {
            [void]$result.Append($chars[$bytes[0] % $chars.Length])
        }
    }
    $rng.Dispose()
    return $result.ToString()
}

function Find-Keytool {
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
        if (Test-Path $candidate) { return $candidate }
    }
    $cmd = Get-Command keytool -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw 'keytool not found. Install a JDK (17 or newer) and set JAVA_HOME.'
}

if (Test-Path $keystore) {
    Write-Host "A release key already exists: $keystore" -ForegroundColor Yellow
    Write-Host 'Nothing was changed. Never replace this key - installed apps could no longer be updated.'
    Write-Host "Your GitHub secret values are in: $secretsFile"
    exit 1
}

$keytool = Find-Keytool
New-Item -ItemType Directory -Force -Path $keyDir | Out-Null

# PKCS12 keystores use one password for the store and the key.
$password = New-RandomPassword
$env:WARDEN_TMP_STOREPASS = $password
try {
    Write-Host "Creating release key in $keystore ..."
    & $keytool -genkeypair -noprompt `
        -keystore $keystore -storetype PKCS12 `
        -alias $alias -keyalg RSA -keysize 4096 -validity 10000 `
        -dname 'CN=Warden Plus, O=desquerteur' `
        -storepass:env WARDEN_TMP_STOREPASS -keypass:env WARDEN_TMP_STOREPASS
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $keystore)) { throw "keytool failed (exit code $LASTEXITCODE)." }

    $fingerprint = ''
    $match = & $keytool -list -v -keystore $keystore -alias $alias -storepass:env WARDEN_TMP_STOREPASS |
        Select-String 'SHA256:' | Select-Object -First 1
    if ($match) { $fingerprint = $match.Line.Trim() }
} finally {
    Remove-Item Env:\WARDEN_TMP_STOREPASS -ErrorAction SilentlyContinue
}

# keystore.properties: forward slashes, because backslashes are escape characters there.
$storeFilePath = $keystore -replace '\\', '/'
$props = @(
    '# Release signing for local builds. Created by scripts/create-signing-key.ps1.',
    '# Gitignored - never commit this file.',
    "storeFile=$storeFilePath",
    "storePassword=$password",
    "keyAlias=$alias",
    "keyPassword=$password"
)
[System.IO.File]::WriteAllLines($propsFile, $props, (New-Object System.Text.UTF8Encoding($false)))

$base64 = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($keystore))
$secrets = @(
    'GitHub -> your repository -> Settings -> Secrets and variables -> Actions -> New repository secret',
    'Create these four secrets (name on the first line, value on the line below it):',
    '',
    'WARDEN_KEYSTORE_BASE64',
    $base64,
    '',
    'WARDEN_KEYSTORE_PASSWORD',
    $password,
    '',
    'WARDEN_KEY_ALIAS',
    $alias,
    '',
    'WARDEN_KEY_PASSWORD',
    $password
)
[System.IO.File]::WriteAllLines($secretsFile, $secrets, (New-Object System.Text.UTF8Encoding($false)))
$password = $null

Write-Host ''
Write-Host 'Done.' -ForegroundColor Green
Write-Host "  Keystore:            $keystore"
Write-Host "  Local build config:  $propsFile"
Write-Host "  GitHub secrets:      $secretsFile"
if ($fingerprint) { Write-Host "  Certificate $fingerprint" }
Write-Host ''
Write-Host 'Next steps:' -ForegroundColor Cyan
Write-Host "  1. Open $secretsFile and add the four secrets on GitHub (see docs/RELEASING.md)."
Write-Host '  2. BACK UP the folder' $keyDir 'somewhere safe (e.g. a password manager or an encrypted USB stick).'
Write-Host '     If this key is lost, installed apps can never be updated again.'
Write-Host '  3. Never share or commit release.jks, keystore.properties or github-secrets.txt.'
