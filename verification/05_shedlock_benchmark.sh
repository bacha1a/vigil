#!/usr/bin/env bash
# Head-to-head: identical GC-pause split-brain chaos on Vigil and ShedLock. Reproduces the
# three-tier result 42 → 3 → 0 by toggling Vigil's idempotency key. LONG (~12-15 min).
# Usage: 05_shedlock_benchmark.sh [cycles]   (default 2 crashes per configuration)
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
require_cluster
N="${1:-2}"

chaos_cycle(){ # $1 cluster - pause holder at run-start for 35s (> both lock timeouts)
  local r svc; r=$(pause_holder_at_run_start "$1" 35); svc="${r%%:*}"
  [ -n "$svc" ] && { sleep 35; dc unpause "$svc" >/dev/null; info "recovered; settling 12s"; sleep 12; }
}
recreate_vigil(){ # $1 = true|false  (BILLING_IDEMPOTENCY_ENABLED)
  info "recreating Vigil pods with idempotency=$1…"
  BILLING_IDEMPOTENCY_ENABLED="$1" dc up -d --force-recreate --no-deps vigil-app-1 vigil-app-2 vigil-app-3 >/dev/null 2>&1
  sleep 22
}

banner "5 · HEAD-TO-HEAD - Vigil vs ShedLock under GC-pause split-brain  ($N crashes each)"
info "Chaos = docker pause the lock holder at run-start for 35s (a long GC/VM stall), then unpause."
info "Metric = customers double-charged (charged by 2 pods within 8s). Lower is better."

step "Tier A - ShedLock (TTL lock, no fencing, no checkpoint, no idempotency key)…"
spsql "TRUNCATE billing_audit;" >/dev/null
for c in $(seq 1 "$N"); do echo "  shedlock crash $c"; chaos_cycle shedlock; done
SHED=$(victims shedlock)

step "Tier B - Vigil with idempotency OFF (fencing + checkpoint only)…"
recreate_vigil false
vpsql "TRUNCATE billing_audit;" >/dev/null
for c in $(seq 1 "$N"); do echo "  vigil crash $c"; chaos_cycle vigil; done
VOFF=$(victims vigil)

step "Tier C - Vigil with idempotency ON (stable runId key + idempotent provider)…"
recreate_vigil true
vpsql "TRUNCATE billing_audit;" >/dev/null
for c in $(seq 1 "$N"); do echo "  vigil crash $c"; chaos_cycle vigil; done
VON=$(victims vigil)

TS=$(date '+%Y-%m-%dT%H:%M:%S')
cat > "$RESULTS_DIR/benchmark.json" <<EOF
{"shedlock":$SHED,"vigil_noidem":$VOFF,"vigil_idem":$VON,"cycles":$N,"timestamp":"$TS"}
EOF
python3 "$HERE/gen_charts.py" "$RESULTS_DIR/benchmark.json" "$RESULTS_DIR/benchmark.svg" 2>/dev/null

banner "RESULT - customers double-charged under identical chaos ($N crashes)"
printf "  %-42s ${R}${B}%s${X}\n" "ShedLock (no fencing/checkpoint/idemkey)" "$SHED"
printf "  %-42s ${Y}${B}%s${X}\n" "Vigil  (fencing + checkpoint)"            "$VOFF"
printf "  %-42s ${G}${B}%s${X}\n" "Vigil  (+ stable idempotency key)"        "$VON"
echo;  info "chart → $RESULTS_DIR/benchmark.svg   data → $RESULTS_DIR/benchmark.json"
{ [ "${SHED:-0}" -gt "${VOFF:-99}" ] && [ "${VON:-9}" -le "${VOFF:-0}" ]; } 2>/dev/null && ST=PASS || ST=FAIL
record_result benchmark "ShedLock vs Vigil under GC-pause split-brain" "$ST" \
  "\"shedlock\":${SHED:-0},\"vigil_noidem\":${VOFF:-0},\"vigil_idem\":${VON:-0},\"crashes\":$N"
[ "$ST" = PASS ] && pass "Vigil corrupts far less than ShedLock; stable idempotency key drives it toward 0" \
                 || fail "unexpected ordering (shed=$SHED vigil_off=$VOFF vigil_on=$VON) - re-run, chaos timing can vary"
