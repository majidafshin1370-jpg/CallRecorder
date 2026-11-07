# Creates a permanent signing key for CallRecorder and prepares the GitHub secrets.
# Requires a JDK (keytool). Run in PowerShell:  .\scripts\make-keystore.ps1

$ErrorActionPreference = "Stop"

if (-not (Get-Command keytool -ErrorAction SilentlyContinue)) {
    Write-Host "keytool not found. Install a JDK first (for example Temurin 17: https://adoptium.net)." -ForegroundColor Red
    exit 1
}

$alias = "callrecorder"
$ksPath = Join-Path (Get-Location) "callrecorder-release.jks"

if (Test-Path $ksPath) {
    Write-Host "$ksPath already exists. Delete or move it first." -ForegroundColor Red
    exit 1
}

$secure = Read-Host "Choose a password for the key (min 6 characters)" -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
$pass = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)

keytool -genkeypair -keystore $ksPath -alias $alias -keyalg RSA -keysize 2048 -validity 10000 `
    -storepass $pass -keypass $pass -dname "CN=CallRecorder"

$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($ksPath))
$b64 | Set-Clipboard

Write-Host ""
Write-Host "Done. The key file is: $ksPath" -ForegroundColor Green
Write-Host "BACK IT UP somewhere safe and never upload it to GitHub."
Write-Host ""
Write-Host "Now add these 4 secrets in GitHub: Settings > Secrets and variables > Actions > New repository secret"
Write-Host "  KEYSTORE_BASE64   = (already copied to your clipboard - just paste)"
Write-Host "  KEYSTORE_PASSWORD = the password you just typed"
Write-Host "  KEY_ALIAS         = $alias"
Write-Host "  KEY_PASSWORD      = the same password"
