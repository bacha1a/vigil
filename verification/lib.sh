#!/usr/bin/env bash
# Shared helpers for the Vigil verification suite. Sourced by every scenario.
set -uo pipefail

export DOCKER_HOST="${DOCKER_HOST:-unix:///var/run/docker.sock}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
COMPOSE="$ROOT/docker-compose.yml"
RESULTS_DIR="${RESULTS_DIR:-$HERE/results}"
mkdir -p "$RESULTS_DIR"

# ── colours ───────────────────────────────────────────────────────────────
if [ -t 1 ]; then B=$'\e[1m'; G=$'\e[32m'; R=$'\e[31m'; Y=$'\e[33m'; C=$'\e[36m'; D=$'\e[2m'; X=$'\e[0m';
else B=""; G=""; R=""; Y=""; C=""; D=""; X=""; fi

banner(){ { echo; echo "${B}${C}══════════════════════════════════════════════════════════════════════${X}";
          echo "${B}${C}  $1${X}"; echo "${B}${C}══════════════════════════════════════════════════════════════════════${X}"; } >&2; }
info(){  echo "${D}  · $*${X}" >&2; }
step(){  echo "${B}▸ $*${X}" >&2; }
pass(){  echo "${G}${B}  ✔ PASS${X} - $*" >&2; }
fail(){  echo "${R}${B}  X FAIL${X} - $*" >&2; }

# ── docker / db helpers ───────────────────────────────────────────────────
record_result(){ # id, title, status(PASS|FAIL), metrics-json-body
  printf '{"id":"%s","title":"%s","status":"%s","metrics":{%s},"ts":"%s"}\n' \
    "$1" "$2" "$3" "${4:-}" "$(date '+%Y-%m-%d %H:%M:%S')" > "$RESULTS_DIR/$1.result.json"
}

dc(){ docker compose -f "$COMPOSE" "$@"; }
ctr(){ dc ps -q "$1" 2>/dev/null; }
vpsql(){ docker exec "$(ctr postgres-vigil)"    psql -U vigil    -d vigildb    -tAc "$1" 2>/dev/null; }
spsql(){ docker exec "$(ctr postgres-shedlock)" psql -U shedlock -d shedlockdb -tAc "$1" 2>/dev/null; }
xpsql(){ if [ "$1" = vigil ]; then vpsql "$2"; else spsql "$2"; fi; }

# pod_id (vigil-pod-2 / shedlock-pod-3)  ->  compose service (vigil-app-2 / shedlock-app-3)
pod_service(){ local p="$1" n="${1##*-}"; case "$p" in vigil-pod-*) echo "vigil-app-$n";; shedlock-pod-*) echo "shedlock-app-$n";; esac; }

charges(){    xpsql "$1" "SELECT count(*) FROM billing_audit;"; }
distinct(){   xpsql "$1" "SELECT count(distinct customer_id) FROM billing_audit;"; }
active_pod(){ xpsql "$1" "SELECT pod_id FROM billing_audit WHERE charged_at>now()-interval '2 seconds' ORDER BY charged_at DESC LIMIT 1" | tr -d '[:space:]'; }
# split-brain victims = customers charged by 2 different pods within 8s (true concurrency)
victims(){    xpsql "$1" "SELECT count(DISTINCT a.customer_id) FROM billing_audit a JOIN billing_audit b ON a.customer_id=b.customer_id AND a.pod_id<>b.pod_id AND b.charged_at>a.charged_at AND b.charged_at-a.charged_at < interval '8 seconds';"; }
truncate_audit(){ vpsql "TRUNCATE billing_audit;" >/dev/null; spsql "TRUNCATE billing_audit;" >/dev/null; }

cluster_up(){ [ -n "$(ctr postgres-vigil)" ] && [ -n "$(ctr vigil-app-1)" ] && [ -n "$(ctr shedlock-app-1)" ]; }

require_cluster(){
  if ! cluster_up; then
    fail "cluster is not running. Start it with:  verification/00_environment.sh --up"
    exit 1
  fi
}

# wait until a fresh monthly-billing run STARTS on a cluster (first charge after idle); pause holder
pause_holder_at_run_start(){ # $1 cluster ; $2 pause seconds
  local cl="$1" secs="$2" last flat=0 c ap svc
  last=$(charges "$cl")
  for _ in $(seq 1 60); do
    sleep 1; c=$(charges "$cl")
    if [ "$c" = "$last" ]; then flat=$((flat+1)); else [ "$flat" -ge 4 ] && { last=$c; break; }; flat=0; fi
    last=$c
  done
  ap=$(active_pod "$cl"); svc=$(pod_service "$ap")
  [ -z "$svc" ] && { echo ""; return 1; }
  info "run started on ${B}$ap${X}${D} (charges=$(charges "$cl")) - freezing it for ${secs}s (docker pause = full JVM stall)"
  dc pause "$svc" >/dev/null
  echo "$svc:$ap"
}
