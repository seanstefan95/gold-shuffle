# Gold Shuffle PC installer (Windows)
# Right-click this file -> "Run with PowerShell".
# Installs Spicetify if needed, adds goldShuffle.js and applies it to Spotify.

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$js = Join-Path $here "goldShuffle.js"
if (-not (Test-Path $js)) { Write-Host "goldShuffle.js must be in the same folder as this script." -ForegroundColor Red; Read-Host "Press Enter to close"; exit 1 }

Write-Host "Closing Spotify..." -ForegroundColor Yellow
Get-Process Spotify -ErrorAction SilentlyContinue | Stop-Process -Force

if (-not (Get-Command spicetify -ErrorAction SilentlyContinue)) {
    Write-Host "Installing Spicetify (answer N if asked about the Marketplace)..." -ForegroundColor Yellow
    Invoke-WebRequest -UseBasicParsing https://raw.githubusercontent.com/spicetify/cli/main/install.ps1 | Invoke-Expression
    $env:PATH = [Environment]::GetEnvironmentVariable("PATH", "User") + ";" + [Environment]::GetEnvironmentVariable("PATH", "Machine")
}

$ext = Join-Path $env:APPDATA "spicetify\Extensions"
New-Item -ItemType Directory -Force -Path $ext | Out-Null
Copy-Item $js $ext -Force
Write-Host "Copied goldShuffle.js to $ext" -ForegroundColor Green

spicetify config extensions goldShuffle.js
spicetify backup apply
if ($LASTEXITCODE -ne 0) { spicetify apply }

Write-Host ""
Write-Host "Done. Spotify should open and show 'Gold Shuffle ready'." -ForegroundColor Green
Read-Host "Press Enter to close"
