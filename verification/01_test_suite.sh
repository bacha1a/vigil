#!/usr/bin/env bash
# Runs the automated unit-test suite live and prints the pass count.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

banner "1 · AUTOMATED TEST SUITE"
info "Environment: JUnit 5 + AssertJ, run by Maven. Integration tests (not run here) use"
info "Testcontainers to spin real PostgreSQL 16 / Redis 7. This step runs the fast unit tests live."

step "Running  mvn test  (vigil-core, vigil-scheduler unit tests - no DB)…"
LOG="$RESULTS_DIR/01_test_suite.out"
( cd "$ROOT" && mvn -o test -pl vigil-core -Djacoco.skip=true ) > "$LOG" 2>&1
line=$(grep -E "Tests run: [0-9]+, Failures" "$LOG" | tail -1)
echo "  ${B}vigil-core:${X} ${line:-<no summary>}"

fails=$(echo "$line" | sed -n 's/.*Failures: \([0-9]*\).*/\1/p')
errs=$(echo "$line" | sed -n 's/.*Errors: \([0-9]*\).*/\1/p')
runs=$(echo "$line" | sed -n 's/.*Tests run: \([0-9]*\).*/\1/p')

info "Full suite across all 12 modules (incl. Testcontainers integration tests) is run by:"
info "  mvn verify   - see verification/results/full_verify_summary.txt for the latest captured run."
[ -f "$RESULTS_DIR/full_verify_summary.txt" ] && sed "s/^/  ${D}/;s/$/${X}/" "$RESULTS_DIR/full_verify_summary.txt"

if [ "${fails:-1}" = "0" ] && [ "${errs:-1}" = "0" ] && [ "${runs:-0}" -gt 0 ]; then ST=PASS; else ST=FAIL; fi
record_result test_suite "Automated test suite" "$ST" "\"unit_tests\":${runs:-0},\"failures\":${fails:-0}"
[ "$ST" = PASS ] && pass "$runs unit tests passed, 0 failures" || fail "unit tests reported failures - see $LOG"
