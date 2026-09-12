param(
    [Parameter(Mandatory = $true)]
    [string]$Version
)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

Write-Host "== CoolPlayer manual release v$Version ==" -ForegroundColor Cyan

$gradlePath = Join-Path $PWD 'app\build.gradle.kts'
$text = [IO.File]::ReadAllText($gradlePath)
$m = [regex]::Match($text, 'versionCode\s*=\s*(\d+)')
if (-not $m.Success) { throw 'versionCode not found in app/build.gradle.kts' }
$code = [int]$m.Groups[1].Value + 1
$text = [regex]::Replace($text, 'versionCode\s*=\s*\d+', "versionCode = $code")
$text = [regex]::Replace($text, 'versionName\s*=\s*"[^"]*"', "versionName = `"$Version`"")
[IO.File]::WriteAllText($gradlePath, $text, (New-Object System.Text.UTF8Encoding $false))
Write-Host "[1/5] version: $Version (code $code)" -ForegroundColor Green

Write-Host "[2/5] assembleRelease..." -ForegroundColor Green
.\gradlew.bat assembleRelease
if ($LASTEXITCODE -ne 0) { throw 'Build failed' }

$dist = Join-Path $PWD 'dist'
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$apk = Join-Path $dist "CoolPlayer-$Version.apk"
Copy-Item 'app\build\outputs\apk\release\app-release.apk' $apk -Force
Write-Host "[3/5] APK: $apk ($([math]::Round((Get-Item $apk).Length / 1MB, 1)) MB)" -ForegroundColor Green

Write-Host "[4/5] git commit + push..." -ForegroundColor Green
git add app/build.gradle.kts
git commit -m "release: v$Version (code $code)"
git push

Write-Host "[5/5] opening browser + folder..." -ForegroundColor Green
Start-Process "https://github.com/Wikrytas/CoolPlayer/releases/new?tag=v$Version&title=CoolPlayer+$Version"
Start-Process explorer.exe $dist

Write-Host @"

Готово. Осталось в браузере:
  1. Перетащить CoolPlayer-$Version.apk из открытой папки в зону ассетов
  2. Написать заметки релиза
  3. Publish release
"@ -ForegroundColor Yellow
