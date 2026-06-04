# Keyo build script
# Builds a debug APK locally. Usage: .\build-release.ps1

param(
    [switch]$Release
)

$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

Set-Location $PSScriptRoot

# Read current version (informational)
$gradle = Get-Content "app\build.gradle.kts" -Encoding UTF8 | Out-String
$nameMatch = [regex]::Match($gradle, 'versionName = "([^"]+)"')
$name = $nameMatch.Groups[1].Value

Write-Host "Building Keyo v$name..." -ForegroundColor Cyan

if ($Release) {
    & .\gradlew.bat assembleRelease
    $apk = "app\build\outputs\apk\release\app-release.apk"
} else {
    & .\gradlew.bat assembleDebug
    $apk = "app\build\outputs\apk\debug\app-debug.apk"
}

if ($LASTEXITCODE -ne 0) {
    Write-Host "BUILD FAILED!" -ForegroundColor Red
    exit 1
}

Write-Host "Built: $apk" -ForegroundColor Green
