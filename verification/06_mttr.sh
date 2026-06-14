#!/usr/bin/env bash
# Measures mean-time-to-recovery: after the running pod is frozen (docker pause), how long until
# a DIFFERENT pod resumes the work? Vigil's orphan detector recovers actively; ShedLock only
# recovers on its next scheduled tick after the TTL lock expires.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
require_cluster

measure(){ # $1 cluster -> echo seconds until another pod charges (or -1)
  local cl="$1" h0 svc t0 p elapsed=-1
  for _ in $(seq 1 60); do h0=$(active_pod "$cl"); [ -n "$h0" ] && break; sleep 1; done
  [ -z "$h0" ] && { echo -1; return; }
  svc=$(pod_service "$h0")
  t0=$(xpsql "$cl" "SELECT now()::timestamp(0)")
  info "$cl: froze $h0 - measuring time until another pod resumes…"
  dc pause "$svc" >/dev/null
  for _ in $(seq 1 90); do
    p=$(xpsql "$cl" "SELECT pod_id FROM billing_audit WHERE charged_at > timestamp '$t0' AND pod_id <> '$h0' ORDER BY charged_at DESC LIMIT 1" | tr -d '[:space:]')
    if [ -n "$p" ]; then
      elapsed=$(xpsql "$cl" "SELECT round(extract(epoch from (now()::timestamp - timestamp '$t0')))")
      break
    fi
    sleep 1
  done
  dc unpause "$svc" >/dev/null
  echo "$elapsed"
}

banner "6 · MEAN TIME TO RECOVERY (MTTR)"
info "Lower is better. Vigil TTL=10s + orphan scan; ShedLock lockAtMostFor=30s + next cron tick."
truncate_audit
V=$(measure vigil);    info "Vigil MTTR    = ${V}s"
S=$(measure shedlock); info "ShedLock MTTR = ${S}s"

[ "${V:-0}" -ge 0 ] 2>/dev/null && [ "${S:-0}" -ge 0 ] 2>/dev/null && ST=PASS || ST=FAIL
record_result mttr "Recovery time after a crash (MTTR)" "$ST" "\"vigil_seconds\":${V:--1},\"shedlock_seconds\":${S:--1}"
echo
echo "  Vigil    ~${V}s   (orphan detector)"
echo "  ShedLock ~${S}s   (TTL + next cron tick)"
[ "$ST" = PASS ] && { [ "${V:-99}" -le "${S:-0}" ] 2>/dev/null && pass "Vigil recovers faster (${V}s vs ${S}s)" || pass "measured (Vigil ${V}s, ShedLock ${S}s)"; } \
                 || fail "could not measure recovery (vigil=$V shedlock=$S)"
