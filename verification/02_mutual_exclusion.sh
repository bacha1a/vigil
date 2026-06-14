#!/usr/bin/env bash
# Proves: with NO failures, one and only one pod runs each tick - 0 double-charges on both
# systems. This also validates that the corruption metric isn't just counting normal re-runs.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
require_cluster

banner "2 · MUTUAL EXCLUSION (no chaos)"
info "Claim: under healthy operation neither system double-charges a customer."
info "Metric: a customer charged by 2 different pods within 8s (genuine concurrent execution)."

step "Clearing billing ledgers and letting both clusters bill for 70s (≈2 cron ticks)…"
truncate_audit
sleep 70

vv=$(victims vigil); sv=$(victims shedlock)
echo "  ${B}Vigil${X}    : $(charges vigil) charges, $(distinct vigil) distinct customers, ${B}$vv${X} split-brain victims"
echo "  ${B}ShedLock${X} : $(charges shedlock) charges, $(distinct shedlock) distinct customers, ${B}$sv${X} split-brain victims"

if [ "${vv:-1}" = "0" ] && [ "${sv:-1}" = "0" ]; then ST=PASS; else ST=FAIL; fi
record_result mutual_exclusion "Mutual exclusion (no chaos)" "$ST" "\"vigil_victims\":${vv:-0},\"shedlock_victims\":${sv:-0},\"customers\":$(distinct vigil)"
[ "$ST" = PASS ] && pass "0 double-charges on both clusters - mutual exclusion holds, metric is clean" \
                 || fail "unexpected concurrency without chaos (vigil=$vv shedlock=$sv)"
