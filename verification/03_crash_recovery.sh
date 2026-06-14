#!/usr/bin/env bash
# Proves: when the pod running a job suffers a GC-stall (docker pause) longer than the lock TTL,
# another pod recovers the SAME run and resumes from the last checkpoint (not from zero), the
# fencing token increments, and the woken-up zombie is blocked - 0 duplicate charges.
# State is read from the database (works even while the holder pod is frozen).
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
require_cluster

lockf(){ vpsql "SELECT $1 FROM vigil_job_locks WHERE job_name='monthly-billing';"; }
donec(){ vpsql "SELECT count(distinct customer_id) FROM billing_audit;"; }

banner "3 · CRASH RECOVERY - fencing + checkpoint resume"
info "Vigil cluster. monthly-billing over 45 customers. TTL=10s, orphan scan=2s."

step "Resetting and triggering a billing run…"
truncate_audit
curl -s -m3 -X POST localhost:8081/actuator/vigil-jobs/monthly-billing \
     -H 'Content-Type: application/json' -d '{"action":"trigger"}' -o /dev/null

step "Waiting until it is mid-CHARGE…"
for _ in $(seq 1 50); do
  st=$(lockf status); dn=$(donec)
  [ "$st" = HELD ] && [ "${dn:-0}" -ge 8 ] && [ "${dn:-0}" -le 35 ] && break
  sleep 0.5
done
H1=$(lockf holder); T1=$(lockf token); R1=$(lockf run_id); D1=$(donec)
echo "  ${B}BEFORE crash${X}: holder=$H1  token=$T1  done=$D1/45  run=${R1:0:8}"

svc=$(pod_service "$H1")
step "Freezing $H1 (docker pause = long GC stall)…"
dc pause "$svc" >/dev/null

step "Watching another pod take over (reading the DB; up to 60s)…"
H2="$H1"
for _ in $(seq 1 30); do
  h=$(lockf holder)
  if [ -n "$h" ] && [ "$h" != "$H1" ]; then H2=$h; break; fi
  sleep 2
done
T2=$(lockf token); R2=$(lockf run_id); D2=$(donec)
dc unpause "$svc" >/dev/null
echo "  ${B}AFTER  crash${X}: holder=$H2  token=$T2  done=$D2/45  run=${R2:0:8}"
info "(unpaused $H1; its next checkpoint write is now stale-fenced and the run aborts)"

step "Letting it finish, then checking for double-charges…"
sleep 12
V=$(victims vigil)

ok=1
{ [ -n "$H2" ] && [ "$H2" != "$H1" ]; } || ok=0
[ "${T2:-0}" -gt "${T1:-0}" ] 2>/dev/null || ok=0
[ "$R2" = "$R1" ] && [ -n "$R1" ] || ok=0
[ "${D2:-0}" -ge "${D1:-0}" ] 2>/dev/null || ok=0
[ "${V:-1}" = "0" ] || ok=0

echo
echo "  takeover by a different pod           : $H1 → $H2"
echo "  runId preserved (resume, not restart) : $([ "$R2" = "$R1" ] && echo "${G}yes${X}" || echo "${R}no${X}")  (${R1:0:8} → ${R2:0:8})"
echo "  fencing token incremented             : $T1 → $T2"
echo "  progress continued (not reset to 0)   : $D1 → $D2"
echo "  duplicate charges after recovery      : $V"
[ "$ok" = 1 ] && ST=PASS || ST=FAIL
record_result crash_recovery "Crash recovery (fencing + checkpoint resume)" "$ST" \
  "\"before_holder\":\"$H1\",\"after_holder\":\"$H2\",\"token_before\":${T1:-0},\"token_after\":${T2:-0},\"runid_preserved\":$([ "$R2" = "$R1" ] && echo true || echo false),\"done_before\":${D1:-0},\"done_after\":${D2:-0},\"duplicates\":${V:-0}"
[ "$ST" = PASS ] && pass "another pod resumed the SAME run from checkpoint; zombie fenced; 0 duplicates" \
                 || fail "recovery assertion failed (see values above)"
