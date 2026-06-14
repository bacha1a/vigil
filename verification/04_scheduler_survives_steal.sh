n#!/usr/bin/env bash
# Regression for a real bug found during this work: a stolen lock used to permanently freeze a
# job's scheduler on that pod (the heartbeat interrupted the cron loop). Proves it now survives.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
require_cluster

runs(){ vpsql "SELECT count(*) FROM vigil_job_runs WHERE job_name='monthly-billing';"; }

banner "4 · SCHEDULER SURVIVES A LOCK-STEAL (bug regression)"
info "Bug: a heartbeat-driven lock-steal interrupted the cron scheduling thread → the job never"
info "ran again on that pod. Fix: clear the interrupt flag after each tick. This proves it sticks."

step "Confirming monthly-billing schedules normally (≈35s)…"
for _ in 1 2 3; do echo "  runs so far: $(runs)"; sleep 12; done

step "Forcing a lock-steal: freeze the active pod past its TTL…"
res=$(pause_holder_at_run_start vigil 14)
svc="${res%%:*}"
[ -n "$svc" ] && { sleep 14; dc unpause "$svc" >/dev/null; info "unpaused $svc (its lock was stolen mid-run)"; }

base=$(runs)
step "Did scheduling keep going after the steal? (watch ≈70s)…"
for i in $(seq 1 7); do sleep 10; echo "  +$((i*10))s runs=$(runs)"; done
final=$(runs)

echo
echo "  monthly-billing runs: $base  →  $final  (after the steal)"
[ "${final:-0}" -gt "${base:-0}" ] && ST=PASS || ST=FAIL
record_result scheduler_steal "Scheduler survives a lock-steal" "$ST" "\"runs_before\":${base:-0},\"runs_after\":${final:-0}"
[ "$ST" = PASS ] && pass "scheduling survived the steal - runs kept increasing" \
                 || fail "scheduling froze after the steal (bug regressed)"
