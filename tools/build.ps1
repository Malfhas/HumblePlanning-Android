# Builds Humble Planning for Android on Windows. Needs JDK 21: JAVA_HOME, otherwise the first
# jdk-21* found in ~\.jdks (a newer system Java is too recent for the Android tools).
#   .\tools\build.ps1                    -> app\build\outputs\apk\debug\app-debug.apk
#   .\tools\build.ps1 installDebug       -> installs the debug build on the emulator or a connected phone
#   .\tools\build.ps1 testDebugUnitTest  -> unit tests
#   .\tools\build.ps1 bundleRelease      -> app\build\outputs\bundle\release\app-release.aab (signed with signing.properties)
param([string]$Task = "assembleDebug")

if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $jdk = Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -Filter "jdk-21*" -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $jdk) { throw "JDK 21 not found: set JAVA_HOME" }
    $env:JAVA_HOME = $jdk.FullName
}
# Works around "Unable to establish loopback connection" when TEMP points to a short or restricted path
$tmp = "$env:USERPROFILE\.humbleplanning-tmp"
New-Item -ItemType Directory -Force $tmp | Out-Null
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$tmp"

Push-Location (Split-Path $PSScriptRoot)
try { .\gradlew.bat ":app:$Task" --console=plain } finally { Pop-Location }
