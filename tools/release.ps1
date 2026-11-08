# Builds the APK published on GitHub Releases: signed with the app key (signing.properties), checked, with its SHA-256.
#   .\tools\release.ps1            -> dist\humbleplanning-<version>.apk and .apk.sha256
#   .\tools\release.ps1 -NoPush    -> same without app\google-services.json: no real-time push (alerts every ~15 min)
#   .\tools\release.ps1 -AllowDirty -> from uncommitted changes (a test build, never a published one)
# The steps to publish come at the end, see also docs/RELEASING.md.
param(
    [switch]$NoPush,
    [switch]$AllowDirty
)
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot
$dist = Join-Path $root "dist"
# Certificate of the app key: every published APK must carry it, or phones refuse the update
$expectedCert = "d5a003f6e0b3937906efcb5925151f07df619e2da3ae3698bc64bb6c90247647"

function Fail([string]$message) { Write-Host "ERREUR : $message" -ForegroundColor Red; exit 2 }

if (-not (Test-Path (Join-Path $root "signing.properties"))) { Fail "signing.properties absent : l'APK ne serait pas signé" }
if (-not (Test-Path (Join-Path $root "app\google-services.json")) -and -not $NoPush) {
    Fail "app\google-services.json absent : pas de notifications en temps réel. Ajoutez-le, ou relancez avec -NoPush"
}
$dirty = git -C $root status --porcelain
if ($dirty -and -not $AllowDirty) { Fail "modifications non commitées : un APK publié doit correspondre à un commit (ou -AllowDirty pour un essai)" }

$gradle = Get-Content (Join-Path $root "app\build.gradle.kts") -Raw
$version = [regex]::Match($gradle, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$code = [regex]::Match($gradle, 'versionCode\s*=\s*(\d+)').Groups[1].Value
if (-not $version) { Fail "versionName introuvable dans app\build.gradle.kts" }
Write-Host "Version $version ($code)"

& (Join-Path $PSScriptRoot "build.ps1") assembleRelease
if ($LASTEXITCODE -ne 0) { Fail "la compilation a échoué" }
$apk = Join-Path $root "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $apk)) { Fail "APK introuvable : $apk" }

# Signature: verified by the Android tool, and the certificate is the app's
$tools = Get-ChildItem (Join-Path $env:LOCALAPPDATA "Android\Sdk\build-tools") -Directory | Sort-Object Name | Select-Object -Last 1
$certs = & (Join-Path $tools.FullName "apksigner.bat") verify --print-certs $apk 2>&1
if ($LASTEXITCODE -ne 0) { Fail "signature invalide : $certs" }
$digest = ($certs | Select-String -Pattern 'SHA-256 digest: ([0-9a-f]+)').Matches | Select-Object -First 1
if (-not $digest -or $digest.Groups[1].Value -ne $expectedCert) { Fail "l'APK n'est pas signé avec la clé de l'app ($($digest.Groups[1].Value))" }
Write-Host "Signature : OK (clé de l'app)"

New-Item -ItemType Directory -Force $dist | Out-Null
$name = "humbleplanning-$version.apk"
$out = Join-Path $dist $name
Copy-Item $apk $out -Force
$hash = (Get-FileHash $out -Algorithm SHA256).Hash.ToLower()
Set-Content -Path "$out.sha256" -Value "$hash  $name" -Encoding ascii -NoNewline
$size = [math]::Round((Get-Item $out).Length / 1MB, 1)

Write-Host ""
Write-Host "Prêt : $out ($size Mo)"
Write-Host "SHA-256 : $hash"
if ($NoPush) { Write-Host "Sans google-services.json : alertes par vérification toutes les ~15 min, pas de push en temps réel." -ForegroundColor Yellow }
Write-Host ""
Write-Host "Pour publier (docs/RELEASING.md) :"
Write-Host "  1. git tag v$version ; git push origin v$version"
Write-Host "  2. GitHub > Releases > Draft a new release > tag v$version, titre « Humble Planning $version »"
Write-Host "  3. Joindre $name et $name.sha256, puis Publish release"
