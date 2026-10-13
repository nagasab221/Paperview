# Builds the release APKs and copies them into dist\ with clear names.
#
#   powershell -ExecutionPolicy Bypass -File scripts\package.ps1
#
# Install the arm64-v8a build on any phone from roughly 2015 onwards; the
# universal APK is the fallback that installs anywhere.

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$env:JAVA_HOME = "$env:ProgramFiles\Android\Android Studio\jbr"
if (-not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    throw "Android Studio's JBR not found at $env:JAVA_HOME - set JAVA_HOME to a JDK 17."
}

Write-Host "Building release APKs..." -ForegroundColor Cyan
& .\gradlew.bat assembleRelease --console=plain
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }

$dist = Join-Path $root "dist"
New-Item -ItemType Directory -Force -Path $dist | Out-Null
Get-ChildItem $dist -Filter *.apk | Remove-Item -Force

$outputs = Join-Path $root "app\build\outputs\apk\release"
$version = (Select-String -Path "app\build.gradle.kts" -Pattern 'versionName\s*=\s*"([^"]+)"').Matches[0].Groups[1].Value

Get-ChildItem $outputs -Filter *.apk | ForEach-Object {
    $abi = switch -Wildcard ($_.Name) {
        "*arm64-v8a*"   { "arm64" }
        "*armeabi-v7a*" { "arm32" }
        default          { "universal" }
    }
    $dest = Join-Path $dist "PaperView-$version-$abi.apk"
    Copy-Item $_.FullName $dest -Force
    "{0,-34} {1,6:N1} MB" -f (Split-Path $dest -Leaf), ($_.Length / 1MB)
}

Write-Host "`nAPKs written to $dist" -ForegroundColor Green
Write-Host "Install with: adb install -r dist\PaperView-$version-arm64.apk"
