#!/usr/bin/env bash
#
# Runs the full test suite: Gradle module tests, probe-agent unit tests,
# and the end-to-end integration tests.
#
# Usage: ./test-all.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FAILED=0
RESULTS=()

section() {
    echo ""
    echo "========================================"
    echo "  $1"
    echo "========================================"
    echo ""
}

record() {
    local name="$1" status="$2"
    RESULTS+=("$status  $name")
    if [ "$status" = "FAIL" ]; then
        FAILED=1
    fi
}

# ── 1. Gradle module tests (implemented modules only) ──
section "Gradle module tests"
cd "$SCRIPT_DIR"
if ./gradlew :api-gateway:test :probe-scheduler:test; then
    record "Gradle (api-gateway + probe-scheduler)" "PASS"
else
    record "Gradle (api-gateway + probe-scheduler)" "FAIL"
fi

# ── 2. Probe agent Python tests ──
section "Probe agent tests"
AGENT_DIR="$SCRIPT_DIR/../probe-agent"
if [ -d "$AGENT_DIR" ]; then
    cd "$AGENT_DIR"
    PYTHON="python3"
    if [ -f ".venv/bin/python" ]; then
        PYTHON=".venv/bin/python"
    elif [ -f ".venv/Scripts/python.exe" ]; then
        PYTHON=".venv/Scripts/python.exe"
    fi
    # Check if pytest and deps are available before running
    if $PYTHON -c "import pytest, fastapi" 2>/dev/null; then
        if $PYTHON -m pytest tests/ -v --tb=short; then
            record "Probe agent (Python)" "PASS"
        else
            record "Probe agent (Python)" "FAIL"
        fi
    else
        echo "Dependencies not installed (tested in Docker via E2E) — skipping"
        record "Probe agent (Python)" "SKIP"
    fi
else
    echo "Probe agent directory not found at $AGENT_DIR — skipping"
    record "Probe agent (Python)" "SKIP"
fi

# ── 3. E2E integration tests ──
section "E2E integration tests"
cd "$SCRIPT_DIR/e2e"
if bash run.sh; then
    record "E2E integration" "PASS"
else
    record "E2E integration" "FAIL"
fi

# ── Summary ──
section "Summary"
for r in "${RESULTS[@]}"; do
    echo "  $r"
done
echo ""

if [ $FAILED -eq 0 ]; then
    echo "All test suites passed."
else
    echo "One or more test suites FAILED."
fi

exit $FAILED
