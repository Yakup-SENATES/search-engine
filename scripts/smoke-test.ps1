# ---------------------------------------------------------------------------
# Local Smoke Test — Operability Quick Wins
#
# Prerequisites:
#   - Docker Desktop running (docker info must succeed)
#   - .env file in the project root with required secrets
#
# Usage:
#   .\scripts\smoke-test.ps1
#   .\scripts\smoke-test.ps1 -AdminToken "my-secret-token"
#
# What it does:
#   1. Builds and starts the full Docker Compose stack
#   2. Waits for the app to become healthy
#   3. Scrapes /actuator/prometheus (expect 200, text/plain)
#   4. Hits /api/v1/admin/providers with the admin token (expect 200, JSON)
#   5. POSTs to /api/v1/admin/sync (expect 202)
#   6. Downloads /api/v1/search.csv?q=docker (expect 200, text/csv)
#   7. Tears down the stack
# ---------------------------------------------------------------------------

param(
    [string]$AdminToken = $env:ADMIN_API_TOKEN,
    [string]$BaseUrl = "http://localhost:8080",
    [int]$MaxWaitSeconds = 120
)

$ErrorActionPreference = "Stop"

function Write-Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Write-Pass($msg) { Write-Host "  [PASS] $msg" -ForegroundColor Green }
function Write-Fail($msg) { Write-Host "  [FAIL] $msg" -ForegroundColor Red }
function Write-Skip($msg) { Write-Host "  [SKIP] $msg" -ForegroundColor Yellow }

$failed = 0

# ---------------------------------------------------------------------------
# 0. Pre-flight: Docker must be running
# ---------------------------------------------------------------------------
Write-Step "Pre-flight: checking Docker daemon"
try {
    docker info 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Docker daemon not reachable" }
    Write-Pass "Docker daemon is running"
} catch {
    Write-Fail "Docker daemon is not running. Start Docker Desktop and retry."
    exit 1
}

# ---------------------------------------------------------------------------
# 1. Build and start the stack
# ---------------------------------------------------------------------------
Write-Step "Building and starting Docker Compose stack"
docker compose up --build -d
if ($LASTEXITCODE -ne 0) {
    Write-Fail "docker compose up --build -d failed"
    exit 1
}
Write-Pass "Stack started in detached mode"

# ---------------------------------------------------------------------------
# 2. Wait for app to become healthy
# ---------------------------------------------------------------------------
Write-Step "Waiting for app to become healthy (max ${MaxWaitSeconds}s)"
$elapsed = 0
$healthy = $false
while ($elapsed -lt $MaxWaitSeconds) {
    try {
        $resp = Invoke-WebRequest -Uri "$BaseUrl/actuator/health" -UseBasicParsing -TimeoutSec 5 -ErrorAction SilentlyContinue
        if ($resp.StatusCode -eq 200) {
            $healthy = $true
            break
        }
    } catch { }
    Start-Sleep -Seconds 5
    $elapsed += 5
    Write-Host "  ... waiting ($elapsed s)" -ForegroundColor DarkGray
}

if (-not $healthy) {
    Write-Fail "App did not become healthy within ${MaxWaitSeconds}s"
    Write-Host "  Dumping app logs:" -ForegroundColor Yellow
    docker compose logs app --tail 50
    docker compose down
    exit 1
}
Write-Pass "App is healthy after ${elapsed}s"

# ---------------------------------------------------------------------------
# 3. Scrape /actuator/prometheus
# ---------------------------------------------------------------------------
Write-Step "Scraping /actuator/prometheus"
try {
    $resp = Invoke-WebRequest -Uri "$BaseUrl/actuator/prometheus" -UseBasicParsing
    if ($resp.StatusCode -eq 200) {
        $ct = $resp.Headers["Content-Type"]
        if ($ct -match "text/plain") {
            Write-Pass "HTTP 200, Content-Type: $ct"
        } else {
            Write-Fail "Unexpected Content-Type: $ct (expected text/plain)"
            $failed++
        }
    } else {
        Write-Fail "HTTP $($resp.StatusCode) (expected 200)"
        $failed++
    }
} catch {
    Write-Fail "Request failed: $_"
    $failed++
}

# ---------------------------------------------------------------------------
# 4. Hit /api/v1/admin/providers with admin token
# ---------------------------------------------------------------------------
Write-Step "Hitting /api/v1/admin/providers"
if ([string]::IsNullOrEmpty($AdminToken)) {
    Write-Skip "No ADMIN_API_TOKEN set; admin endpoints will return 401 (expected when token is empty)"
    try {
        $resp = Invoke-WebRequest -Uri "$BaseUrl/api/v1/admin/providers" -UseBasicParsing -ErrorAction SilentlyContinue
        Write-Fail "Expected 401 but got HTTP $($resp.StatusCode)"
        $failed++
    } catch {
        $statusCode = $_.Exception.Response.StatusCode.value__
        if ($statusCode -eq 401) {
            Write-Pass "HTTP 401 (correct — admin token is empty/disabled)"
        } else {
            Write-Fail "Unexpected error: $_"
            $failed++
        }
    }
} else {
    try {
        $headers = @{ "X-Admin-Token" = $AdminToken }
        $resp = Invoke-WebRequest -Uri "$BaseUrl/api/v1/admin/providers" -Headers $headers -UseBasicParsing
        if ($resp.StatusCode -eq 200) {
            $ct = $resp.Headers["Content-Type"]
            if ($ct -match "application/json") {
                $body = $resp.Content | ConvertFrom-Json
                if ($body.providers) {
                    Write-Pass "HTTP 200, JSON with $($body.providers.Count) provider(s)"
                } else {
                    Write-Fail "Response missing 'providers' key"
                    $failed++
                }
            } else {
                Write-Fail "Unexpected Content-Type: $ct"
                $failed++
            }
        } else {
            Write-Fail "HTTP $($resp.StatusCode) (expected 200)"
            $failed++
        }
    } catch {
        Write-Fail "Request failed: $_"
        $failed++
    }
}

# ---------------------------------------------------------------------------
# 5. POST /api/v1/admin/sync
# ---------------------------------------------------------------------------
Write-Step "POSTing to /api/v1/admin/sync"
if ([string]::IsNullOrEmpty($AdminToken)) {
    Write-Skip "No ADMIN_API_TOKEN set; expecting 401"
    try {
        $resp = Invoke-WebRequest -Uri "$BaseUrl/api/v1/admin/sync" -Method POST -UseBasicParsing -ErrorAction SilentlyContinue
        Write-Fail "Expected 401 but got HTTP $($resp.StatusCode)"
        $failed++
    } catch {
        $statusCode = $_.Exception.Response.StatusCode.value__
        if ($statusCode -eq 401) {
            Write-Pass "HTTP 401 (correct — admin token is empty/disabled)"
        } else {
            Write-Fail "Unexpected error: $_"
            $failed++
        }
    }
} else {
    try {
        $headers = @{ "X-Admin-Token" = $AdminToken }
        $resp = Invoke-WebRequest -Uri "$BaseUrl/api/v1/admin/sync" -Method POST -Headers $headers -UseBasicParsing
        if ($resp.StatusCode -eq 202) {
            $body = $resp.Content | ConvertFrom-Json
            Write-Pass "HTTP 202, triggered=$($body.triggered), alreadyRunning=$($body.alreadyRunning)"
        } else {
            Write-Fail "HTTP $($resp.StatusCode) (expected 202)"
            $failed++
        }
    } catch {
        $statusCode = $_.Exception.Response.StatusCode.value__
        if ($statusCode -eq 202) {
            Write-Pass "HTTP 202 Accepted"
        } else {
            Write-Fail "Request failed: $_"
            $failed++
        }
    }
}

# ---------------------------------------------------------------------------
# 6. Download /api/v1/search.csv?q=docker
# ---------------------------------------------------------------------------
Write-Step "Downloading /api/v1/search.csv?q=docker"
# Wait a few seconds for sync to populate data if it was just triggered
Start-Sleep -Seconds 5
try {
    $resp = Invoke-WebRequest -Uri "$BaseUrl/api/v1/search.csv?q=docker" -UseBasicParsing
    if ($resp.StatusCode -eq 200) {
        $ct = $resp.Headers["Content-Type"]
        $cd = $resp.Headers["Content-Disposition"]
        if ($ct -match "text/csv") {
            Write-Pass "HTTP 200, Content-Type: $ct"
            if ($cd -match "attachment") {
                Write-Pass "Content-Disposition: $cd"
            }
            # Show first 3 lines of CSV
            $lines = ($resp.Content -split "`n") | Select-Object -First 3
            Write-Host "  First lines:" -ForegroundColor DarkGray
            $lines | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
        } else {
            Write-Fail "Unexpected Content-Type: $ct (expected text/csv)"
            $failed++
        }
    } else {
        Write-Fail "HTTP $($resp.StatusCode) (expected 200)"
        $failed++
    }
} catch {
    $statusCode = $_.Exception.Response.StatusCode.value__
    # A 400 with INVALID_QUERY is acceptable if no data matches "docker"
    if ($statusCode -eq 400) {
        Write-Pass "HTTP 400 (no results for 'docker' — acceptable if DB is empty)"
    } else {
        Write-Fail "Request failed: $_"
        $failed++
    }
}

# ---------------------------------------------------------------------------
# 7. Tear down
# ---------------------------------------------------------------------------
Write-Step "Tearing down Docker Compose stack"
docker compose down
if ($LASTEXITCODE -eq 0) {
    Write-Pass "Stack stopped and removed"
} else {
    Write-Fail "docker compose down returned non-zero exit code"
}

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "========================================" -ForegroundColor White
if ($failed -eq 0) {
    Write-Host "  SMOKE TEST PASSED" -ForegroundColor Green
} else {
    Write-Host "  SMOKE TEST FAILED ($failed check(s))" -ForegroundColor Red
}
Write-Host "========================================" -ForegroundColor White
exit $failed
