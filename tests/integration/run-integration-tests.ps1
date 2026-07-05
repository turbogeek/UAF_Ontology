# run-integration-tests.ps1
# ==============================================================================
# Drives the Semantic Alignment plugin GUI integration suite through the Cameo
# REST test harness (localhost:8765). For each test script it: POSTs /run,
# polls /status until the run finishes, captures /log, POSTs /stop (mandatory
# between runs - fresh classloader, window/session cleanup), then reads the
# script's dedicated log for the grep-able "RESULT: PASS|FAIL|SKIP" line.
#
# Preconditions (user actions):
#   1. CATIA Magic (E:\Magic SW\CMSoS26xR1pr) is running with a project open.
#   2. The plugin is deployed (gradle deployPlugin) BEFORE Cameo was started.
#   3. The test harness macro "Test Harness - Start" has been run in Cameo.
#
# Usage:  powershell -File run-integration-tests.ps1 [-SkipCleanup]
# ==============================================================================
param(
    [string]$HarnessUrl = 'http://127.0.0.1:8765',
    [switch]$SkipCleanup
)

$ErrorActionPreference = 'Stop'
$testDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$logDir = Join-Path (Split-Path -Parent (Split-Path -Parent $testDir)) 'UAF_Ontology\logs'
if (-not (Test-Path $logDir)) { $logDir = 'E:\_Documents\git\UAF_Ontology\logs' }

$tests = @('IT0_PluginLoaded', 'IT1_ProfileAndFixture', 'IT2_MappingUndo',
           'IT3_GuiSelection', 'IT4_GuiAudit', 'IT5_AuditViolations',
           'IT6_SuggestAlign', 'IT8_QueryView')
if (-not $SkipCleanup) { $tests += 'IT9_CleanupFixture' }

# --- Harness reachability -----------------------------------------------------
try {
    $health = Invoke-RestMethod -Uri "$HarnessUrl/health" -TimeoutSec 5
    Write-Host "Harness healthy: $($health | ConvertTo-Json -Compress)"
} catch {
    Write-Host 'ERROR: Test harness is not reachable. In CATIA Magic run Tools > Macros > "Test Harness - Start" first.' -ForegroundColor Red
    exit 2
}

$results = @()
foreach ($test in $tests) {
    $script = Join-Path $testDir "$test.groovy"
    Write-Host "`n=== $test ===" -ForegroundColor Cyan

    $body = @{ scriptPath = $script; waitMs = 0 } | ConvertTo-Json
    Invoke-RestMethod -Uri "$HarnessUrl/run" -Method Post -Body $body -ContentType 'application/json' | Out-Null

    # Poll until the run leaves the running state (GUI tests can take a while).
    $state = 'running'
    $deadline = (Get-Date).AddSeconds(120)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 750
        $status = Invoke-RestMethod -Uri "$HarnessUrl/status" -TimeoutSec 10
        $state = $status.state
        if ($state -in @('done', 'failed', 'stopped', 'idle')) { break }
    }

    $runLog = ''
    try { $runLog = Invoke-RestMethod -Uri "$HarnessUrl/log" -TimeoutSec 10 } catch {}
    $runLog | Out-File -FilePath (Join-Path $logDir "$test.harness.log") -Encoding utf8

    # Mandatory stop between tests: disposes leaked windows, cancels sessions,
    # drops the Groovy classloader so the next run starts clean.
    try { Invoke-RestMethod -Uri "$HarnessUrl/stop" -Method Post -Body '{}' -ContentType 'application/json' | Out-Null } catch {}

    # Verdict comes from the script's own diagnostic log.
    $verdict = 'NO-RESULT'
    $scriptLog = Join-Path $logDir "$test.log"
    if (Test-Path $scriptLog) {
        $line = Select-String -Path $scriptLog -Pattern 'RESULT: (PASS|FAIL|SKIP)' | Select-Object -Last 1
        if ($line) { $verdict = $line.Matches[0].Groups[1].Value }
    }
    if ($state -eq 'failed' -and $verdict -eq 'NO-RESULT') { $verdict = 'HARNESS-FAILED' }

    $color = switch ($verdict) { 'PASS' { 'Green' } 'SKIP' { 'Yellow' } default { 'Red' } }
    Write-Host "$test -> $verdict (harness state: $state)" -ForegroundColor $color
    $results += [pscustomobject]@{ Test = $test; Verdict = $verdict; HarnessState = $state }
}

Write-Host "`n=== SUMMARY ===" -ForegroundColor Cyan
$results | Format-Table -AutoSize | Out-String | Write-Host
$failed = @($results | Where-Object { $_.Verdict -notin @('PASS', 'SKIP') })
if ($failed.Count -gt 0) {
    Write-Host "$($failed.Count) test(s) not passing." -ForegroundColor Red
    exit 1
}
Write-Host 'All integration tests passed.' -ForegroundColor Green
exit 0
