# Campagne de tests d'interface de l'app, sur l'émulateur, contre le serveur de test local (voir tools/test-server).
#
#   .\tools\ui-tests\run.ps1                                 toute la campagne : connectedDebugAndroidTest
#   .\tools\ui-tests\run.ps1 -Class SignInTest               une classe (ou Classe#test, ou plusieurs séparés par des virgules)
#   .\tools\ui-tests\run.ps1 -Class SignInTest -NoBuild      idem, sans recompiler ni réinstaller
#
# Le script vérifie le serveur et l'émulateur, crée un jeu de données neuf (seed.mjs), le pousse sur l'émulateur,
# lance les tests, remet l'émulateur dans son état (animations, taille, police, réseau) et écrit un résumé.
# Une capture de chaque échec et l'arbre affiché vont dans tools/ui-tests/build/.
#
# Il ne démarre ni n'arrête l'émulateur : `emulator -avd <nom> -no-window -no-audio -no-snapshot-save`, ou -StartEmulator
# pour qu'il le démarre et l'arrête lui-même.
param(
    [string]$Class = "",
    [switch]$NoBuild,
    [switch]$StartEmulator
)
$ErrorActionPreference = "Stop"
$root = Split-Path (Split-Path $PSScriptRoot)
$out = Join-Path $PSScriptRoot "build"
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$server = "http://localhost:3100"
$mock = "http://localhost:3102"
$seedOnDevice = "/data/local/tmp/hp-ui-seed.json"
$testPackage = "com.humbleplanning.app.debug.test"
$runner = "$testPackage/androidx.test.runner.AndroidJUnitRunner"
New-Item -ItemType Directory -Force $out | Out-Null

function Fail([string]$message) { Write-Host "ERREUR : $message" -ForegroundColor Red; exit 2 }
function Adb { & $adb @args }

# ---- Serveur ----
function Test-Url([string]$url) {
    try { return (Invoke-WebRequest -Uri $url -TimeoutSec 5 -SkipHttpErrorCheck).StatusCode } catch { return 0 }
}
if ((Test-Url "$server/api/mobile/info") -ne 200) {
    Write-Host "Serveur absent : démarrage de la pile hp-test"
    & docker compose -p hp-test -f (Join-Path $root "tools\test-server\docker-compose.yml") up -d
    for ($i = 0; $i -lt 40 -and (Test-Url "$server/api/mobile/info") -ne 200; $i++) { Start-Sleep 3 }
    if ((Test-Url "$server/api/mobile/info") -ne 200) { Fail "le serveur de test ne répond pas sur $server" }
}
if ((Test-Url "$mock/__pushes") -ne 200) { Fail "le service de simulation ne répond pas sur $mock" }
# Les blocages de connexion d'une campagne précédente ne doivent pas gêner celle-ci (base jetable)
& docker exec hp-test-db-1 psql -q -U humble -d humble_planning -c "delete from security_counters" | Out-Null
Write-Host "Serveur : OK"

# ---- Émulateur ----
$startedHere = $false
if ($StartEmulator -and -not ((Adb devices) -match "emulator-\d+\s+device")) {
    $avd = (& (Join-Path $sdk "emulator\emulator.exe") -list-avds | Select-Object -First 1)
    if (-not $avd) { Fail "aucun AVD" }
    Write-Host "Démarrage de l'émulateur $avd"
    Start-Process -FilePath (Join-Path $sdk "emulator\emulator.exe") -ArgumentList "-avd", $avd, "-no-window", "-no-audio", "-no-snapshot-save", "-no-boot-anim" -WindowStyle Hidden
    $startedHere = $true
    Adb wait-for-device
}
if (-not ((Adb devices) -match "emulator-\d+\s+device")) { Fail "aucun émulateur : emulator -avd <nom> -no-window -no-audio -no-snapshot-save (ou -StartEmulator)" }
for ($i = 0; $i -lt 100 -and (Adb shell getprop sys.boot_completed).Trim() -ne "1"; $i++) { Start-Sleep 3 }
if ((Adb shell getprop sys.boot_completed).Trim() -ne "1") { Fail "l'émulateur n'a pas fini de démarrer" }
$sdkLevel = (Adb shell getprop ro.build.version.sdk).Trim()
Write-Host "Émulateur : OK (API $sdkLevel)"

function Restore-Device {
    Adb shell settings put global window_animation_scale 1 | Out-Null
    Adb shell settings put global transition_animation_scale 1 | Out-Null
    Adb shell settings put global animator_duration_scale 1 | Out-Null
    Adb shell settings put system font_scale 1.0 | Out-Null
    Adb shell settings put system accelerometer_rotation 1 | Out-Null
    Adb shell settings put system user_rotation 0 | Out-Null
    Adb shell wm size reset | Out-Null
    Adb shell svc wifi enable | Out-Null
    Adb shell svc data enable | Out-Null
}

# ---- Données ----
$today = (Adb shell date +%F).Trim()
$seedFile = Join-Path $out "seed.json"
& node (Join-Path $PSScriptRoot "seed.mjs") --url $server --out $seedFile --today $today
if ($LASTEXITCODE -ne 0) { Fail "le jeu de données n'a pas pu être créé" }
Adb push $seedFile $seedOnDevice | Out-Null
Adb shell chmod 644 $seedOnDevice | Out-Null
Adb shell rm -rf /data/local/tmp/hp-ui | Out-Null

# ---- Tests ----
Adb shell settings put global window_animation_scale 0 | Out-Null
Adb shell settings put global transition_animation_scale 0 | Out-Null
Adb shell settings put global animator_duration_scale 0 | Out-Null
Adb logcat -c
$started = Get-Date
$buildScript = Join-Path $root "tools\build.ps1"
$summary = @{ total = 0; failed = 0; skipped = 0; names = @(); skippedNames = @() }

function Read-JUnitResults([string]$folder) {
    foreach ($file in Get-ChildItem $folder -Filter "*.xml" -Recurse -ErrorAction SilentlyContinue) {
        [xml]$xml = Get-Content -LiteralPath $file.FullName
        foreach ($case in $xml.SelectNodes("//testcase")) {
            $summary.total++
            $name = "$($case.classname.Split('.')[-1]).$($case.name)"
            if ($case.SelectSingleNode("failure|error")) {
                $summary.failed++
                $summary.names += $name
            } elseif ($case.SelectSingleNode("skipped")) {
                $summary.skipped++
                $summary.skippedNames += $name
            }
        }
    }
}

try {
    if ($Class -eq "") {
        $results = Join-Path $root "app\build\outputs\androidTest-results\connected"
        if (Test-Path $results) { Remove-Item -Recurse -Force $results }
        # Nouvelle installation : le premier test passe par la question système des notifications
        Adb uninstall com.humbleplanning.app.debug 2>$null | Out-Null
        Adb uninstall $testPackage 2>$null | Out-Null
        & $buildScript connectedDebugAndroidTest
        Read-JUnitResults $results
    } else {
        if (-not $NoBuild) {
            & $buildScript installDebug
            & $buildScript installDebugAndroidTest
        }
        $target = ($Class -split "," | ForEach-Object { if ($_.Contains(".")) { $_ } else { "com.humbleplanning.app.$_" } }) -join ","
        $log = Join-Path $out "instrument.txt"
        Adb shell am instrument -w -e class $target $runner 2>&1 | Tee-Object -FilePath $log
        $text = Get-Content $log -Raw
        if ($text -match "OK \((\d+) tests?\)") {
            $summary.total = [int]$Matches[1]
        } elseif ($text -match "Tests run: (\d+),\s+Failures: (\d+)") {
            $summary.total = [int]$Matches[1]
            $summary.failed = [int]$Matches[2]
            $summary.names = [regex]::Matches($text, "(?m)^\d+\) (\S+)\((\S+)\)") | ForEach-Object { "$($_.Groups[2].Value.Split('.')[-1]).$($_.Groups[1].Value)" }
        } else {
            $summary.failed = 1
            $summary.names = @("l'instrumentation s'est arrêtée avant la fin (voir $log)")
        }
    }
} finally {
    Restore-Device
    Adb logcat -d -s HPUI > (Join-Path $out "ui-failures.log")
    $shots = Join-Path $out "failures"
    if (Test-Path $shots) { Remove-Item -Recurse -Force $shots }
    New-Item -ItemType Directory -Force $shots | Out-Null
    Adb pull /data/local/tmp/hp-ui/. $shots 2>$null | Out-Null
    if ($startedHere) { Adb emu kill | Out-Null }
}

# ---- Résumé ----
$elapsed = (Get-Date) - $started
$passed = $summary.total - $summary.failed - $summary.skipped
Write-Host ""
Write-Host ("Tests : {0}, réussis : {1}, échecs : {2}, ignorés : {3}, durée : {4:hh\:mm\:ss}" -f $summary.total, $passed, $summary.failed, $summary.skipped, $elapsed)
foreach ($name in $summary.names) { Write-Host "  KO $name" -ForegroundColor Red }
foreach ($name in $summary.skippedNames) { Write-Host "  ignoré $name" }
if ($summary.failed -gt 0) { Write-Host "Captures et arbres affichés : $out" }
exit ([int]($summary.failed -gt 0 -or $summary.total -eq 0))
