#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Local Smoke Test — Operability Quick Wins
#
# Prerequisites:
#   - Docker daemon running (docker info must succeed)
#   - .env file in the project root with required secrets
#
# Usage:
#   ./scripts/smoke-test.sh
#   ADMIN_API_TOKEN="my-secret-token" ./scripts/smoke-test.sh
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

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ADMIN_TOKEN="${ADMIN_API_TOKEN:-}"
MAX_WAIT=120

FAILED=0

step()  { printf '\n\033[36m==> %s\033[0m\n' "$1"; }
pass()  { printf '  \033[32m[PASS]\033[0m %s\n' "$1"; }
fail()  { printf '  \033[31m[FAIL]\033[0m %s\n' "$1"; FAILED=$((FAILED + 1)); }
skip()  { printf '  \033[33m[SKIP]\033[0m %s\n' "$1"; }

# ---------------------------------------------------------------------------
# 0. Pre-flight: Docker must be running
# ---------------------------------------------------------------------------
step "Pre-flight: checking Docker daemon"
if ! docker info >/dev/null 2>&1; then
    fail "Docker daemon is not running. Start Docker Desktop and retry."
    exit 1
fi
pass "Docker daemon is running"

# ---------------------------------------------------------------------------
# 1. Build and start the stack
# ---------------------------------------------------------------------------
step "Building and starting Docker Compose stack"
docker compose up --build -d
pass "Stack started in detached mode"

# ---------------------------------------------------------------------------
# 2. Wait for app to become healthy
# ---------------------------------------------------------------------------
step "Waiting for app to become healthy (max ${MAX_WAIT}s)"
elapsed=0
healthy=false
while [ "$elapsed" -lt "$MAX_WAIT" ]; do
    status=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/actuator/health" 2>/dev/null || true)
    if [ "$status" = "200" ]; then
        healthy=true
        break
    fi
    sleep 5
    elapsed=$((elapsed + 5))
    printf '  ... waiting (%ds)\n' "$elapsed"
done

if [ "$healthy" = false ]; then
    fail "App did not become healthy within ${MAX_WAIT}s"
    echo "  Dumping app logs:"
    docker compose logs app --tail 50
    docker compose down
    exit 1
fi
pass "App is healthy after ${elapsed}s"

# ---------------------------------------------------------------------------
# 3. Scrape /actuator/prometheus
# ---------------------------------------------------------------------------
step "Scraping /actuator/prometheus"
HTTP_CODE=$(curl -s -o /tmp/prom_body -w "%{http_code}" "$BASE_URL/actuator/prometheus")
CT=$(curl -sI "$BASE_URL/actuator/prometheus" | grep -i "content-type" | tr -d '\r')
if [ "$HTTP_CODE" = "200" ]; then
    if echo "$CT" | grep -qi "text/plain"; then
        pass "HTTP 200, $CT"
    else
        fail "Unexpected Content-Type: $CT (expected text/plain)"
    fi
else
    fail "HTTP $HTTP_CODE (expected 200)"
fi

# ---------------------------------------------------------------------------
# 4. Hit /api/v1/admin/providers with admin token
# ---------------------------------------------------------------------------
step "Hitting /api/v1/admin/providers"
if [ -z "$ADMIN_TOKEN" ]; then
    skip "No ADMIN_API_TOKEN set; admin endpoints will return 401 (expected when token is empty)"
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/api/v1/admin/providers")
    if [ "$HTTP_CODE" = "401" ]; then
        pass "HTTP 401 (correct — admin token is empty/disabled)"
    else
        fail "Expected 401 but got HTTP $HTTP_CODE"
    fi
else
    HTTP_CODE=$(curl -s -o /tmp/admin_body -w "%{http_code}" -H "X-Admin-Token: $ADMIN_TOKEN" "$BASE_URL/api/v1/admin/providers")
    if [ "$HTTP_CODE" = "200" ]; then
        CT=$(curl -sI -H "X-Admin-Token: $ADMIN_TOKEN" "$BASE_URL/api/v1/admin/providers" | grep -i "content-type" | tr -d '\r')
        if echo "$CT" | grep -qi "application/json"; then
            pass "HTTP 200, JSON response"
        else
            fail "Unexpected Content-Type: $CT"
        fi
    else
        fail "HTTP $HTTP_CODE (expected 200)"
    fi
fi

# ---------------------------------------------------------------------------
# 5. POST /api/v1/admin/sync
# ---------------------------------------------------------------------------
step "POSTing to /api/v1/admin/sync"
if [ -z "$ADMIN_TOKEN" ]; then
    skip "No ADMIN_API_TOKEN set; expecting 401"
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/admin/sync")
    if [ "$HTTP_CODE" = "401" ]; then
        pass "HTTP 401 (correct — admin token is empty/disabled)"
    else
        fail "Expected 401 but got HTTP $HTTP_CODE"
    fi
else
    HTTP_CODE=$(curl -s -o /tmp/sync_body -w "%{http_code}" -X POST -H "X-Admin-Token: $ADMIN_TOKEN" "$BASE_URL/api/v1/admin/sync")
    if [ "$HTTP_CODE" = "202" ]; then
        pass "HTTP 202 Accepted"
        cat /tmp/sync_body | python3 -m json.tool 2>/dev/null || cat /tmp/sync_body
    else
        fail "HTTP $HTTP_CODE (expected 202)"
    fi
fi

# ---------------------------------------------------------------------------
# 6. Download /api/v1/search.csv?q=docker
# ---------------------------------------------------------------------------
step "Downloading /api/v1/search.csv?q=docker"
# Wait a few seconds for sync to populate data if it was just triggered
sleep 5
HTTP_CODE=$(curl -s -o /tmp/csv_body -w "%{http_code}" "$BASE_URL/api/v1/search.csv?q=docker")
if [ "$HTTP_CODE" = "200" ]; then
    CT=$(curl -sI "$BASE_URL/api/v1/search.csv?q=docker" | grep -i "content-type" | tr -d '\r')
    if echo "$CT" | grep -qi "text/csv"; then
        pass "HTTP 200, $CT"
        echo "  First 3 lines:"
        head -3 /tmp/csv_body | sed 's/^/    /'
    else
        fail "Unexpected Content-Type: $CT (expected text/csv)"
    fi
elif [ "$HTTP_CODE" = "400" ]; then
    pass "HTTP 400 (no results for 'docker' — acceptable if DB is empty)"
else
    fail "HTTP $HTTP_CODE (expected 200 or 400)"
fi

# ---------------------------------------------------------------------------
# 7. Tear down
# ---------------------------------------------------------------------------
step "Tearing down Docker Compose stack"
docker compose down
pass "Stack stopped and removed"

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo ""
echo "========================================"
if [ "$FAILED" -eq 0 ]; then
    printf '\033[32m  SMOKE TEST PASSED\033[0m\n'
else
    printf '\033[31m  SMOKE TEST FAILED (%d check(s))\033[0m\n' "$FAILED"
fi
echo "========================================"
exit "$FAILED"
