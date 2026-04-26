param(
  [string]$PluginDir = "C:\Development\Scruby-Companion"
)

# dev-cycle.ps1
# 1) Build plugin with JDK 17
# 2) Stop running HytaleServer via saved PID
# 3) Copy plugin jar into server/mods (after server stopped so files aren't locked)
# 4) Start server with JDK 25 in a new PowerShell window
# 5) Save PID to .server.pid

$ErrorActionPreference = "Stop"

# --- Paths (adjust only if you moved folders) ---
$serverDir = Join-Path $env:USERPROFILE "Desktop\Hytale-Dedicated-DevServer\Server"
$modsDir   = Join-Path $serverDir "mods"

$pluginDir = $PluginDir

$adoptium = Join-Path $env:LOCALAPPDATA "Programs\Eclipse Adoptium"
$java17 = Join-Path $adoptium "jdk-17.0.18.8-hotspot\bin\java.exe"
$java25 = Join-Path $adoptium "jdk-25.0.2.10-hotspot\bin\java.exe"

$pidFile = Join-Path $serverDir ".server.pid"

Write-Host "=== [1/4] Building plugin (Gradle, JDK 17) ===" -ForegroundColor Cyan
if (!(Test-Path $pluginDir)) { throw "Plugin dir not found: $pluginDir" }
Push-Location $pluginDir
try {
  $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $java17)
  $env:Path = (Split-Path -Parent $java17) + ";" + $env:Path

  & .\gradlew.bat build
} finally {
  Pop-Location
}

Write-Host "=== [2/4] Stopping running Dedicated Server (if any) ===" -ForegroundColor Cyan
if (Test-Path $pidFile) {
  $savedPid = [int](Get-Content $pidFile -Raw).Trim()
  $proc = Get-Process -Id $savedPid -ErrorAction SilentlyContinue
  if ($proc) {
    Write-Host ("Killing server process tree PID $savedPid ...") -ForegroundColor Yellow
    & taskkill /T /F /PID $savedPid 2>$null

    # Wait until process is gone
    $timeoutSec = 15
    $sw = [Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $timeoutSec) {
      if (-not (Get-Process -Id $savedPid -ErrorAction SilentlyContinue)) { break }
      Start-Sleep -Milliseconds 250
    }
    Write-Host "Server stopped." -ForegroundColor Green
  } else {
    Write-Host ("PID $savedPid from .server.pid is no longer running, skipping.") -ForegroundColor DarkGray
  }
  Remove-Item $pidFile -Force
} else {
  Write-Host "No .server.pid found, nothing to stop." -ForegroundColor DarkGray
}

Write-Host "=== [3/4] Copying plugin jar to server/mods ===" -ForegroundColor Cyan
$jar = Get-ChildItem (Join-Path $pluginDir "build\libs") -File |
  Where-Object {
    $_.Name -like "ScrubyCompanion-v*.jar" -and
    $_.Name -notlike "*-sources.jar" -and
    $_.Name -notlike "*-javadoc.jar"
  } |
  Sort-Object LastWriteTime -Descending |
  Select-Object -First 1

if (-not $jar) { throw "No ScrubyCompanion jar found in build\libs" }

if (!(Test-Path $modsDir)) { throw "Mods dir not found: $modsDir" }

# Remove old Scruby jars so only the new version remains.
# Glob matches both new (ScrubyCompanion-v*.jar) and legacy
# (ScrubyCompanionPlugin-*.jar) names so previous deploys are cleaned up.
Get-ChildItem $modsDir -File -Filter "ScrubyCompanion*.jar" | Remove-Item -Force
Copy-Item $jar.FullName $modsDir -Force
Write-Host ("Copied: " + $jar.Name) -ForegroundColor Green

Write-Host "=== [4/4] Starting Dedicated Server (JDK 25) ===" -ForegroundColor Cyan
if (!(Test-Path $serverDir)) { throw "Server dir not found: $serverDir" }

$serverCmd = "& '$java25' -jar HytaleServer.jar --assets ..\Assets.zip --bind 0.0.0.0:5520"

# Start in a new PowerShell window so logs stay visible
$p = Start-Process powershell -ArgumentList "-NoExit", "-Command", "Set-Location '$serverDir'; $serverCmd" -PassThru
Set-Content -Path $pidFile -Value $p.Id
Write-Host ("Started server in PowerShell PID: " + $p.Id) -ForegroundColor Green
Write-Host ("PID saved to: " + $pidFile) -ForegroundColor DarkGray

Write-Host "DONE. Now connect via: 127.0.0.1:5520" -ForegroundColor Cyan
