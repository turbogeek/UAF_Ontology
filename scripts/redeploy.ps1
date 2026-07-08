# scripts/redeploy.ps1
# ==============================================================================
# SAFE redeploy for the Semantic Alignment plugin.
#
# Why this exists: never HOT-OVERWRITE the deployed jar under a LIVE Cameo. Doing
# so corrupts the running JVM's cached ZipFile offsets, and the next lazily-loaded
# class throws "NoClassDefFoundError: ... invalid LOC header (bad signature)" (the
# 2026-07-08 TransactionWrapper incident). A raw Copy-Item bypasses deployPlugin's
# lock guard entirely. Always: STOP Cameo -> DEPLOY -> RELAUNCH.
#
# Sequence:
#   1. If the plugin's REST harness answers, stop Cameo cleanly (ShutdownCameo.groovy
#      -> closeProjectNoSave + Application.shutdown; NO System.exit) and wait for exit.
#   2. gradle deployPlugin (Sync task; its guard also refuses a live deploy).
#   3. Print relaunch instructions (launching Cameo is a manual, license/project-specific step).
#
# WARNING: step 1 closes the ACTIVE PROJECT WITHOUT SAVING. SAVE real work first.
# Pass -Yes to skip the confirmation (only for throwaway test fixtures).
#
# Usage:
#   powershell -File scripts/redeploy.ps1               # prompts before stopping a live Cameo
#   powershell -File scripts/redeploy.ps1 -Yes          # no prompt (fixtures only)
# ==============================================================================
param(
    [string]$HarnessUrl     = 'http://127.0.0.1:8765',
    [string]$PluginDir      = 'E:\_Documents\git\UAF_Ontology\plugin',
    [string]$ShutdownScript = 'E:\_Documents\git\UAF_Ontology\tests\integration\ShutdownCameo.groovy',
    [switch]$Yes
)
$ErrorActionPreference = 'Stop'

function Test-HarnessUp {
    param($url)
    try { Invoke-RestMethod -Uri "$url/health" -TimeoutSec 3 | Out-Null; return $true } catch { return $false }
}

# --- 1. Stop a live Cameo cleanly (never kill the JVM) ------------------------
if (Test-HarnessUp $HarnessUrl) {
    if (-not $Yes) {
        Write-Host 'A Cameo instance is RUNNING.' -ForegroundColor Yellow
        Write-Host 'Stopping it will CLOSE THE ACTIVE PROJECT WITHOUT SAVING (ShutdownCameo.groovy).' -ForegroundColor Yellow
        $ans = Read-Host "SAVE your work first. Type 'yes' to proceed"
        if ($ans -ne 'yes') { Write-Host 'Aborted (nothing changed).'; exit 3 }
    }
    # Forward slashes in the JSON path (backslashes are JSON escapes; the harness recovers them, but don't rely on it).
    $body = @{ scriptPath = ($ShutdownScript -replace '\\', '/'); waitMs = 0 } | ConvertTo-Json
    try { Invoke-RestMethod -Uri "$HarnessUrl/run" -Method Post -Body $body -ContentType 'application/json' | Out-Null } catch {}
    Write-Host 'Requested clean shutdown; waiting for Cameo to exit...'
    $deadline = (Get-Date).AddSeconds(90)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        if (-not (Test-HarnessUp $HarnessUrl)) { break }
    }
    if (Test-HarnessUp $HarnessUrl) {
        Write-Host 'Cameo still up after 90s; ABORTING to avoid a live (corrupting) deploy.' -ForegroundColor Red
        exit 4
    }
    Write-Host 'Cameo exited (harness down).' -ForegroundColor Green
    Start-Sleep -Seconds 3   # let Windows release the jar file handles
} else {
    Write-Host 'No live harness detected -> safe to deploy.'
}

# --- 2. Deploy with Gradle (never Copy-Item) ---------------------------------
Write-Host 'Running gradle deployPlugin...'
Push-Location $PluginDir
try {
    & gradle deployPlugin --console=plain
    if ($LASTEXITCODE -ne 0) { throw "deployPlugin failed (exit $LASTEXITCODE)" }
} finally { Pop-Location }
Write-Host 'Deploy complete (fresh jar staged; no live JVM was holding it).' -ForegroundColor Green

# --- 3. Relaunch (manual) ----------------------------------------------------
Write-Host ''
Write-Host 'NEXT:' -ForegroundColor Cyan
Write-Host '  1) Launch CATIA Magic (msosa.exe) with your project.'
Write-Host '  2) The integrated harness auto-starts on 8765 (or run the Test Harness macro).'
Write-Host '  3) Verify the Apply path + plugin load:'
Write-Host '       POST tests/integration/IT_ApplyPath.groovy and IT0_PluginLoaded.groovy to the harness,'
Write-Host '       or run:  powershell -File tests/integration/run-integration-tests.ps1'
exit 0
